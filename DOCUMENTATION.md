# BookMatch — How It Works

BookMatch is a command-line full-text search engine for PDF books. You drop any
number of PDFs into a folder, the app reads the text out of each one, builds a
searchable index, and then lets you type a word or phrase and instantly see
where that topic appears across all your books — together with a reconstructed
passage of surrounding context.

It is built on three core ideas:

1. **Apache Tika** — pulls plain text out of PDF files.
2. **Apache Lucene** — builds an *inverted index* over that text and runs fast searches against it.
3. **Chunking + context stitching** — custom logic that splits each book into small pieces so results can be located precisely and then reassembled into a readable passage.

---

## 1. The Big Picture

```
 ┌──────────┐    Tika      ┌────────────┐   chunk    ┌───────────────┐
 │  PDF     │ ───────────► │ raw text   │ ─────────► │ 150-word      │
 │  books   │  extract     │ (a String) │  split     │ chunks        │
 └──────────┘              └────────────┘            └───────┬───────┘
                                                             │ Lucene
                                                             │ index
                                                             ▼
                                                   ┌───────────────────┐
   user types  ──── query ───────────────────────►│  Inverted Index   │
   "machine learning"                              │ (on disk)         │
                                                   └─────────┬─────────┘
                                                             │ search
                                                             ▼
                                              best chunk + neighbouring chunks
                                                = "Complete topic" passage
```

The whole flow happens in two files:

| File | Responsibility |
|------|----------------|
| [BookmatchApplication.java](src/main/java/com/bookmatch/app/bookmatch/BookmatchApplication.java) | Spring Boot entry point. Wires up folders, kicks off indexing, runs the interactive search loop. |
| [SearchEngineService.java](src/main/java/com/bookmatch/app/bookmatch/SearchEngineService.java) | All the real work: text extraction, chunking, indexing, and searching. |

---

## 2. Apache Tika — Getting Text Out of PDFs

A PDF is **not** a plain text file. It's a binary format describing glyph
positions, fonts, images, and layout. You cannot just read it as a string and
search it. **Apache Tika** is a content-extraction toolkit that detects a file's
type and parses out its text for you.

In this project that's a tiny but crucial method
([SearchEngineService.java:36-42](src/main/java/com/bookmatch/app/bookmatch/SearchEngineService.java#L36-L42)):

```java
public String extractTextFromPdf(String pdfPath) throws Exception {
    Tika tika = new Tika();
    // Allow large books (Tika caps extracted text at 100k chars by default).
    tika.setMaxStringLength(50 * 1024 * 1024);
    File file = new File(pdfPath);
    return tika.parseToString(file);
}
```

What's happening:

- `new Tika()` creates the facade over Tika's parsers.
- By default Tika truncates extracted text at 100,000 characters. A full book is
  far longer, so `setMaxStringLength(50 MB)` raises the cap so an entire book
  comes through.
- `parseToString(file)` auto-detects the PDF, runs the PDF parser (PDFBox under
  the hood), and returns all the readable text as one big `String`.

The two Tika dependencies that make this work are in [pom.xml](pom.xml#L55-L64):
`tika-core` (the API) and `tika-parsers-standard-package` (the actual format
parsers, including PDF).

> Tika supports far more than PDF — Word, HTML, EPUB, PowerPoint, etc. — so
> swapping in other document types later would require very little code change.

---

## 3. The Inverted Index — The Key Concept

This is the heart of any search engine, so it's worth understanding well.

### The naive way (don't do this)

If you wanted to find the word "neuron" in 50 books, the obvious approach is to
read all 50 books start to finish every single search and look for the word.
That's a **forward** scan — slow, and it gets slower the more books you add.

### The inverted way

An **inverted index** flips the relationship around. Instead of mapping
*document → words*, it maps *word → documents*. You build it once, up front, and
then every search is a near-instant dictionary lookup.

Imagine three chunks of text:

```
Chunk 0: "the brain has neurons"
Chunk 1: "a neuron fires"
Chunk 2: "the network learns"
```

The inverted index built from them looks like:

```
TERM        → POSTINGS LIST (which chunks contain it)
─────────────────────────────────────────────
brain       → [0]
fires       → [1]
has         → [0]
learns      → [2]
network     → [2]
neuron      → [1, 2 ...]   (after analysis "neurons"/"neuron" may share a stem)
the         → [0, 2]
```

Now searching for `neuron` is just: look up the word, get back `[1, ...]`
instantly. No scanning. This is why search stays fast whether you have 5 books
or 5,000.

### Where the index lives

Lucene writes this index to disk in the `./lucene-index` folder (set in
[BookmatchApplication.java:24](src/main/java/com/bookmatch/app/bookmatch/BookmatchApplication.java#L24)).
So you only pay the indexing cost once; later runs can search the existing index.

---

## 4. Apache Lucene — Building and Searching the Index

**Apache Lucene** is the industry-standard Java library that implements inverted
indexes, text analysis, scoring, and querying. (Elasticsearch and Apache Solr
are both built on top of Lucene.) This project uses it directly.

### 4a. Documents and Fields

Lucene doesn't index "books" or "files" — it indexes **Documents**, and each
Document is a bag of **Fields**. A field has a name, a value, and rules about
whether it's *indexed* (searchable), *stored* (retrievable), or both.

In this project **one chunk = one Lucene Document**
([SearchEngineService.java:90-98](src/main/java/com/bookmatch/app/bookmatch/SearchEngineService.java#L90-L98)):

```java
Document doc = new Document();
doc.add(new StringField("title", title, Field.Store.YES));   // book name, exact
doc.add(new StoredField("chunk", chunkNumber));              // position in book
doc.add(new TextField("content", chunk, Field.Store.YES));   // the searchable text
writer.addDocument(doc);
```

The three field types are deliberately different:

| Field | Type | Why this type |
|-------|------|---------------|
| `title` | `StringField` | Indexed as a single un-analyzed token. The whole book title is one value — you don't want it broken into words. |
| `chunk` | `StoredField` | Just a number stored for later retrieval; never searched on. It records the chunk's position so neighbours can be found. |
| `content` | `TextField` | **Analyzed** — split into individual terms and fed into the inverted index. This is what searches actually run against. |

`Field.Store.YES` means the original value is kept so it can be shown back to the
user in results (needed here to display snippets and rebuild passages).

### 4b. The Analyzer

The `StandardAnalyzer`
([SearchEngineService.java:60](src/main/java/com/bookmatch/app/bookmatch/SearchEngineService.java#L60))
is what turns a blob of `content` text into clean, searchable terms. It:

- splits text into tokens (tokenization),
- lower-cases everything (so "Neuron" matches "neuron"),
- strips punctuation,
- removes common stop words.

**Critically, the same analyzer is used at index time and at search time.** If
you indexed lower-cased terms but searched with the original case, nothing would
match. Using one analyzer for both keeps them in sync.

### 4c. Writing the index

`indexBooksFolder`
([SearchEngineService.java:48-82](src/main/java/com/bookmatch/app/bookmatch/SearchEngineService.java#L48-L82))
ties it together:

```java
FSDirectory dir = FSDirectory.open(Paths.get(indexPath)); // index on disk
StandardAnalyzer analyzer = new StandardAnalyzer();
IndexWriterConfig config = new IndexWriterConfig(analyzer);
config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);    // fresh index each run
IndexWriter writer = new IndexWriter(dir, config);
```

- `FSDirectory` = the index stored as files on disk (vs. in memory).
- `OpenMode.CREATE` wipes any existing index on each run, so re-running doesn't
  pile up duplicate copies of the same books.
- For each PDF it: derives the title from the filename, extracts text with Tika,
  chunks it, and adds each chunk as a Document.
- `writer.close()` flushes everything safely to disk.

### 4d. Searching the index

`searchIndex`
([SearchEngineService.java:103-148](src/main/java/com/bookmatch/app/bookmatch/SearchEngineService.java#L103-L148)):

```java
DirectoryReader reader = DirectoryReader.open(dir);
IndexSearcher searcher = new IndexSearcher(reader);
QueryParser parser = new QueryParser("content", analyzer);  // search the content field
Query query = parser.parse(textQuery);
TopDocs results = searcher.search(query, 50);               // top 50 chunks
```

- `DirectoryReader` opens the on-disk index for reading.
- `IndexSearcher` runs queries against it.
- `QueryParser` turns the user's typed text into a Lucene `Query`. Because it
  uses the same `StandardAnalyzer`, the query terms are normalised the same way
  the indexed terms were. The parser also understands Lucene query syntax (e.g.
  `brain AND neuron`, `"exact phrase"`, `neuro*`).
- `search(query, 50)` returns the top 50 matching chunks **ranked by relevance**.

### 4e. Relevance ranking (why the "best" chunk is best)

Lucene doesn't just return matches — it *scores* them, by default using **BM25**.
Roughly, a chunk scores higher when:

- the query terms appear **more often** in it (term frequency), and
- the terms are **rarer** across the whole corpus (inverse document frequency —
  a rare, meaningful word counts more than a common one), with a normalisation
  so long chunks don't win just by being long.

That's why `results.scoreDocs[0]` — the top result — is treated as the single
most relevant place for the searched topic
([SearchEngineService.java:124-127](src/main/java/com/bookmatch/app/bookmatch/SearchEngineService.java#L124-L127)).

---

## 5. Chunking — Why the Books Are Cut Into Pieces

If each whole book were one Lucene Document, a search could tell you *which book*
mentions a term but not *where* — and a "snippet" would be useless because the
matched word could be anywhere in 400 pages.

So each book is split into small **chunks** of ~150 words
([SearchEngineService.java:168-192](src/main/java/com/bookmatch/app/bookmatch/SearchEngineService.java#L168-L192)),
controlled by:

```java
private static final int WORDS_PER_CHUNK = 150;
```

`chunkText` simply walks through the words and starts a new chunk every 150
words. Benefits:

- **Precision** — a hit points to a small, specific region of a book.
- **Better snippets** — there's a tight window of text to display.
- **Better ranking** — relevance is judged per passage, not per whole book.

Each book's chunks are also kept **in order** in an in-memory map
([SearchEngineService.java:33](src/main/java/com/bookmatch/app/bookmatch/SearchEngineService.java#L33)):

```java
private final Map<String, List<String>> bookChunks = new LinkedHashMap<>();
```

This ordered list is what makes the next feature possible.

---

## 6. Context Stitching — Rebuilding the "Complete Topic"

A single 150-word chunk often cuts a topic off mid-thought. To give a readable
answer, after finding the best chunk the app **stitches neighbouring chunks back
together** around it
([SearchEngineService.java:152-165](src/main/java/com/bookmatch/app/bookmatch/SearchEngineService.java#L152-L165)):

```java
private static final int CONTEXT_CHUNKS = 3;   // 3 chunks on each side
...
int start = Math.max(0, centerChunk - CONTEXT_CHUNKS);
int end   = Math.min(chunks.size() - 1, centerChunk + CONTEXT_CHUNKS);
```

So if the best match is chunk 12, the app glues chunks 9–15 back into one
continuous passage (~7 × 150 ≈ ~1,000 words) and prints that as the "Complete
topic." The `Math.max`/`Math.min` guards keep it from running off the start or
end of the book.

For the *other* matching locations, it doesn't rebuild a full passage — it just
shows a short snippet around the matched word using `buildSnippet`
([SearchEngineService.java:195-213](src/main/java/com/bookmatch/app/bookmatch/SearchEngineService.java#L195-L213)),
which finds the first query word in the stored content and grabs ~60 characters
on each side.

---

## 7. The Application Flow End-to-End

[BookmatchApplication.java](src/main/java/com/bookmatch/app/bookmatch/BookmatchApplication.java)
is a Spring Boot `CommandLineRunner`. Note it runs as a **plain console app**,
not a web server (`setWebApplicationType(NONE)` on
[line 14](src/main/java/com/bookmatch/app/bookmatch/BookmatchApplication.java#L14)).
On startup it:

1. Creates the `SearchEngineService`.
2. Indexes every PDF in `./books` into `./lucene-index`.
3. Enters an interactive loop: reads a line from the console, searches the index,
   prints the complete passage plus other locations, and repeats until you type
   `exit` or `quit`.

```
Initializing BookMatch Search Engine...
Indexing all PDFs in './books'...
Extracting text from: deep-learning.pdf
   Indexed 'deep-learning' (842 chunks)
Finished indexing 1 PDF file(s).

BookMatch is ready. Type a word or phrase to search, or 'exit' to quit.

Enter search text: backpropagation

--- Search Results for 'backpropagation' ---
Chunks containing a match: 17

===== Complete topic from 'deep-learning' (around chunk 312) =====
... reconstructed ~1000-word passage ...
=========================================================

Other matching locations:
  - [deep-learning | chunk 88] ...the backpropagation algorithm computes gradients...
  - [deep-learning | chunk 401] ...during backpropagation the error is propagated...
```

---

## 8. How to Run It

**Prerequisites:** Java 21 (set in [pom.xml](pom.xml#L31)) and the bundled Maven
wrapper.

1. Create a `books` folder in the project root and drop one or more `.pdf` files in it.
2. Run the app (the command is noted at the top of the service file):

   ```
   ./mvnw spring-boot:run
   ```

   On Windows PowerShell:

   ```
   .\mvnw spring-boot:run
   ```

3. Wait for indexing to finish, then type search terms at the prompt. Type
   `exit` to quit.

The index is written to `./lucene-index`. Because indexing uses
`OpenMode.CREATE`, every run rebuilds the index from whatever PDFs are currently
in `./books`.

---

## 9. Glossary

| Term | Meaning |
|------|---------|
| **Apache Tika** | Library that extracts plain text (and metadata) from documents like PDFs. |
| **Apache Lucene** | Java search library that builds inverted indexes and runs ranked text searches. |
| **Inverted index** | A map from *term → list of documents containing it*, enabling instant lookups. |
| **Document** | Lucene's unit of indexing — here, one ~150-word chunk. |
| **Field** | A named value on a Document (`title`, `chunk`, `content`), each indexed/stored differently. |
| **Analyzer** | Turns raw text into normalised search terms (tokenize, lowercase, remove stop words). Used at both index and search time. |
| **Term / token** | A single normalised word after analysis. |
| **Postings list** | The list of documents (and positions) a term appears in. |
| **QueryParser** | Converts a user's typed text into a Lucene `Query`. |
| **TopDocs / ScoreDoc** | The ranked search results and an individual scored hit. |
| **BM25** | Lucene's default relevance-ranking formula (term frequency × inverse document frequency, length-normalised). |
| **Chunk** | A ~150-word slice of a book; the granularity at which this app indexes and locates matches. |
| **Context stitching** | Rejoining neighbouring chunks around the best match to show a complete, readable passage. |
