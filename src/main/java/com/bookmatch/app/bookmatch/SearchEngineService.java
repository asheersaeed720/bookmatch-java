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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// .\mvnw spring-boot:run

public class SearchEngineService {

    // How many words go into a single searchable chunk/document.
    private static final int WORDS_PER_CHUNK = 150;

    // 1. Extract raw text from a PDF file using Apache Tika
    public String extractTextFromPdf(String pdfPath) throws Exception {
        Tika tika = new Tika();
        // Allow large books (Tika caps extracted text at 100k chars by default).
        tika.setMaxStringLength(50 * 1024 * 1024);
        File file = new File(pdfPath);
        return tika.parseToString(file);
    }

    // 2. Index the extracted text using Apache Lucene.
    //    The book is split into many small chunks; each chunk is its own Lucene
    //    document so that searches can report HOW MANY chunks contain a term and
    //    show a snippet of context for each one.
    public void indexBook(String title, String rawContent, String indexPath) throws Exception {
        FSDirectory dir = FSDirectory.open(Paths.get(indexPath));
        StandardAnalyzer analyzer = new StandardAnalyzer();
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        // Recreate the index every run so repeated runs don't pile up duplicate copies.
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

        IndexWriter writer = new IndexWriter(dir, config);

        List<String> chunks = chunkText(rawContent);
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

        writer.close(); // Saves changes safely to disk
        System.out.println("Successfully indexed: " + title + " (" + chunkNumber + " chunks)");
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

        int shown = 0;
        for (ScoreDoc scoreDoc : results.scoreDocs) {
            Document doc = searcher.doc(scoreDoc.doc);
            String content = doc.get("content");
            String snippet = buildSnippet(content, textQuery);
            System.out.println("\n[" + doc.get("title") + " | chunk " + doc.get("chunk")
                    + " | score " + String.format("%.2f", scoreDoc.score) + "]");
            System.out.println("   ..." + snippet + "...");
            shown++;
        }

        if (shown == 0) {
            System.out.println("No matches found.");
        }

        reader.close();
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
