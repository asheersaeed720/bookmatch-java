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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// .\mvnw spring-boot:run

public class SearchEngineService {

    private final TextChunker chunker = new TextChunker();

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
        List<String> chunks = chunker.chunkAndStore(title, rawContent);
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

        // Reconstruct the COMPLETE passage: the best chunk plus its neighbours,
        // then tidy it into a clean, readable paragraph for the user.
        String passage = chunker.getCompletePassage(bestTitle, bestChunk);
        String readable = toReadableParagraph(passage, textQuery);
        System.out.println("\n===== Complete topic from '" + bestTitle + "' "
                + "(around chunk " + bestChunk + ") =====\n");
        System.out.println(wrapForTerminal(readable, 100));
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

    // Turn a raw extracted passage into a clean paragraph a human can read.
    // PDF text comes out with hard line breaks mid-sentence, words hyphenated
    // across lines, page numbers/headers, and ragged spacing; we fix those and
    // trim the result to whole sentences so it starts and ends cleanly.
    private String toReadableParagraph(String raw, String textQuery) {
        if (raw == null || raw.isEmpty()) {
            return "(content unavailable)";
        }

        String text = raw;
        // Re-join words that were hyphenated across a line break: "exam-\nple" -> "example".
        text = text.replaceAll("(?<=\\p{L})-\\s*\\n\\s*(?=\\p{L})", "");
        // Collapse every run of whitespace (including newlines) into a single space.
        text = text.replaceAll("\\s+", " ");
        // Drop stray standalone numbers left behind by page numbers/headers.
        text = text.replaceAll("\\s+\\d{1,4}\\s+", " ");
        // Tidy spacing around punctuation: no space before, exactly one after.
        text = text.replaceAll("\\s+([,.;:!?])", "$1");
        text = text.replaceAll("([,.;:!?])(?=\\p{L})", "$1 ");
        text = text.trim();

        // Trim to whole sentences so the paragraph doesn't start or end mid-thought,
        // while making sure the sentence(s) mentioning the search term are kept.
        String trimmed = trimToSentences(text, textQuery);
        return trimmed.isEmpty() ? text : trimmed;
    }

    // Keep the passage from the start of the sentence before the first query
    // match to the end of the sentence after the last match, so it reads as a
    // complete thought around the topic the user searched for.
    private String trimToSentences(String text, String textQuery) {
        int firstHit = -1;
        int lastHit = -1;
        for (String term : textQuery.toLowerCase().split("\\s+")) {
            if (term.isEmpty()) {
                continue;
            }
            Matcher m = Pattern.compile(Pattern.quote(term), Pattern.CASE_INSENSITIVE).matcher(text);
            while (m.find()) {
                if (firstHit < 0 || m.start() < firstHit) {
                    firstHit = m.start();
                }
                if (m.end() > lastHit) {
                    lastHit = m.end();
                }
            }
        }
        if (firstHit < 0) {
            return text; // No match in the cleaned text; return it as-is.
        }

        // Walk back to the start of the sentence containing the first match.
        int start = 0;
        for (int i = firstHit - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?') {
                start = i + 1;
                break;
            }
        }
        // Walk forward to the end of the sentence containing the last match.
        int end = text.length();
        for (int i = lastHit; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?') {
                end = i + 1;
                break;
            }
        }
        return text.substring(start, end).trim();
    }

    // Soft-wrap a paragraph at word boundaries so long lines stay readable in a
    // terminal, without splitting words across lines.
    private String wrapForTerminal(String text, int maxWidth) {
        StringBuilder out = new StringBuilder();
        int lineLength = 0;
        for (String word : text.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            if (lineLength > 0 && lineLength + 1 + word.length() > maxWidth) {
                out.append('\n');
                lineLength = 0;
            } else if (lineLength > 0) {
                out.append(' ');
                lineLength++;
            }
            out.append(word);
            lineLength += word.length();
        }
        return out.toString();
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
