package com.bookmatch.app.bookmatch;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;
import org.apache.tika.Tika;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// .\mvnw spring-boot:run

public class SearchEngineService {

    // How many words go into a single searchable chunk/document.
    private static final int WORDS_PER_CHUNK = 150;

    // How many neighbouring chunks to include on each side of the best match
    // when reconstructing the "complete topic" passage.
    private static final int CONTEXT_CHUNKS = 3;

    // Keeps each book's chunks in order (title -> ordered chunks) so we can
    // stitch neighbouring chunks back together into a full passage after a search.
    private final Map<String, List<String>> bookChunks = new LinkedHashMap<>();

    // 1. Extract raw text from a PDF file using Apache Tika
    public String extractTextFromPdf(String pdfPath) throws Exception {
        Tika tika = new Tika();
        // Allow large books (Tika caps extracted text at 100k chars by default).
        tika.setMaxStringLength(50 * 1024 * 1024);
        File file = new File(pdfPath);
        return tika.parseToString(file);
    }

    // 2. Index every PDF found in the given folder into a single Lucene index.
    //    Each PDF is split into many small chunks; each chunk is its own Lucene
    //    document tagged with its book title, so that searches can report which
    //    books/chunks contain a term and show a snippet of context for each one.
    public void indexBooksFolder(String booksFolderPath, String indexPath) throws Exception {
        File booksFolder = new File(booksFolderPath);
        File[] pdfFiles = booksFolder.listFiles(
                (d, name) -> name.toLowerCase().endsWith(".pdf"));

        if (pdfFiles == null || pdfFiles.length == 0) {
            System.out.println("No PDF files found in '" + booksFolderPath
                    + "'. Add some .pdf files there and run again.");
            return;
        }

        FSDirectory dir = FSDirectory.open(Paths.get(indexPath));
        StandardAnalyzer analyzer = new StandardAnalyzer();
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        // Recreate the index every run so repeated runs don't pile up duplicate copies.
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

        IndexWriter writer = new IndexWriter(dir, config);

        for (File pdf : pdfFiles) {
            // Use the file name (without ".pdf") as the book title.
            String title = pdf.getName().replaceFirst("(?i)\\.pdf$", "");
            try {
                System.out.println("Extracting text from: " + pdf.getName());
                String content = extractTextFromPdf(pdf.getAbsolutePath());
                int chunkCount = addBookToIndex(writer, title, content);
                System.out.println("   Indexed '" + title + "' (" + chunkCount + " chunks)");
            } catch (Exception e) {
                System.err.println("   Skipped '" + pdf.getName() + "': " + e.getMessage());
            }
        }

        writer.close(); // Saves changes safely to disk
        System.out.println("Finished indexing " + pdfFiles.length + " PDF file(s).");
    }

    // Split one book's text into chunks and add each chunk as a document.
    private int addBookToIndex(IndexWriter writer, String title, String rawContent) throws Exception {
        List<String> chunks = chunkText(rawContent);
        // Remember the ordered chunks so we can rebuild full passages during search.
        bookChunks.put(title, chunks);
        int chunkNumber = 0;
        for (String chunk : chunks) {
            Document doc = new Document();
            doc.add(new StringField("title", title, Field.Store.YES));
            doc.add(new StoredField("chunk", chunkNumber));
            // Store the content (Store.YES) so we can show snippets in search results.
            doc.add(new TextField("content", chunk, Field.Store.YES));
            writer.addDocument(doc);
            chunkNumber++;
        }
        return chunkNumber;
    }

    // 3. Search the Lucene index
    public void searchIndex(String textQuery, String indexPath) throws Exception {
        FSDirectory dir = FSDirectory.open(Paths.get(indexPath));
        DirectoryReader reader = DirectoryReader.open(dir);
        IndexSearcher searcher = new IndexSearcher(reader);

        StandardAnalyzer analyzer = new StandardAnalyzer();
        QueryParser parser = new QueryParser("content", analyzer);
        Query query = parser.parse(textQuery);

        // Ask for up to 50 matching chunks.
        TopDocs results = searcher.search(query, 50);
        System.out.println("\n--- Search Results for '" + textQuery + "' ---");
        System.out.println("Chunks containing a match: " + results.totalHits.value);

        if (results.scoreDocs.length == 0) {
            System.out.println("No matches found.");
            reader.close();
            return;
        }

        // The best-scoring chunk is the most relevant location for this topic.
        ScoreDoc best = results.scoreDocs[0];
        Document bestDoc = searcher.doc(best.doc);
        String bestTitle = bestDoc.get("title");
        int bestChunk = Integer.parseInt(bestDoc.get("chunk"));

        // Reconstruct the COMPLETE passage: the best chunk plus its neighbours.
        String passage = getCompletePassage(bestTitle, bestChunk);
        System.out.println("\n===== Complete topic from '" + bestTitle + "' "
                + "(around chunk " + bestChunk + ") =====\n");
        System.out.println(passage);
        System.out.println("\n=========================================================");

        // Also list other places the topic appears, so the user can explore further.
        if (results.scoreDocs.length > 1) {
            System.out.println("\nOther matching locations:");
            for (int i = 1; i < results.scoreDocs.length; i++) {
                Document doc = searcher.doc(results.scoreDocs[i].doc);
                String snippet = buildSnippet(doc.get("content"), textQuery);
                System.out.println("  - [" + doc.get("title") + " | chunk " + doc.get("chunk")
                        + "] ..." + snippet + "...");
            }
        }

        reader.close();
    }

    // Stitch the best-matching chunk together with its neighbouring chunks to
    // rebuild the full, continuous passage covering the searched topic.
    private String getCompletePassage(String title, int centerChunk) {
        List<String> chunks = bookChunks.get(title);
        if (chunks == null || chunks.isEmpty()) {
            return "(content unavailable)";
        }
        int start = Math.max(0, centerChunk - CONTEXT_CHUNKS);
        int end = Math.min(chunks.size() - 1, centerChunk + CONTEXT_CHUNKS);

        StringBuilder passage = new StringBuilder();
        for (int i = start; i <= end; i++) {
            passage.append(chunks.get(i)).append(' ');
        }
        return passage.toString().trim();
    }

    // Split text into chunks of roughly WORDS_PER_CHUNK words each.
    private List<String> chunkText(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null) {
            return chunks;
        }
        String[] words = text.trim().split("\\s+");
        StringBuilder current = new StringBuilder();
        int count = 0;
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            current.append(word).append(' ');
            count++;
            if (count >= WORDS_PER_CHUNK) {
                chunks.add(current.toString().trim());
                current.setLength(0);
                count = 0;
            }
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    // Build a short snippet of text around the first occurrence of any query word.
    private String buildSnippet(String content, String textQuery) {
        if (content == null || content.isEmpty()) {
            return "(no stored content)";
        }
        // Try each word in the query and snippet around the first one we find.
        for (String term : textQuery.toLowerCase().split("\\s+")) {
            if (term.isEmpty()) {
                continue;
            }
            Matcher m = Pattern.compile(Pattern.quote(term), Pattern.CASE_INSENSITIVE).matcher(content);
            if (m.find()) {
                int start = Math.max(0, m.start() - 60);
                int end = Math.min(content.length(), m.end() + 60);
                return content.substring(start, end).replaceAll("\\s+", " ");
            }
        }
        // Fallback: first 120 chars.
        return content.substring(0, Math.min(120, content.length())).replaceAll("\\s+", " ");
    }
}
