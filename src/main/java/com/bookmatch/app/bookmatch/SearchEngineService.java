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

// .\mvnw spring-boot:run

public class SearchEngineService {

    // 1. Extract raw text from a PDF file using Apache Tika
    public String extractTextFromPdf(String pdfPath) throws Exception {
        Tika tika = new Tika();
        File file = new File(pdfPath);
        return tika.parseToString(file);
    }

    // 2. Index the extracted text using Apache Lucene
    public void indexBook(String title, String rawContent, String indexPath) throws Exception {
        FSDirectory dir = FSDirectory.open(Paths.get(indexPath));
        StandardAnalyzer analyzer = new StandardAnalyzer();
        IndexWriterConfig config = new IndexWriterConfig(analyzer);

        // Open the index directory to write data
        IndexWriter writer = new IndexWriter(dir, config);

        Document doc = new Document();
        // Store the title so we can retrieve it in search results
        doc.add(new StringField("title", title, Field.Store.YES));
        // Index the content body for search capabilities
        doc.add(new TextField("content", rawContent, Field.Store.NO));

        writer.addDocument(doc);
        writer.close(); // Saves changes safely to disk
        System.out.println("Successfully indexed: " + title);
    }

    // 3. Search the Lucene index
    public void searchIndex(String textQuery, String indexPath) throws Exception {
        FSDirectory dir = FSDirectory.open(Paths.get(indexPath));
        DirectoryReader reader = DirectoryReader.open(dir);
        IndexSearcher searcher = new IndexSearcher(reader);

        StandardAnalyzer analyzer = new StandardAnalyzer();
        QueryParser parser = new QueryParser("content", analyzer);
        Query query = parser.parse(textQuery);

        TopDocs results = searcher.search(query, 10);
        System.out.println("\n--- Search Results for '" + textQuery + "' ---");
        System.out.println("Total matches found: " + results.totalHits.value);

        for (ScoreDoc scoreDoc : results.scoreDocs) {
            Document doc = searcher.doc(scoreDoc.doc);
            System.out.println("-> Match found in book title: " + doc.get("title"));
            System.out.println("-> Content found in book: " + doc.get("content"));
        }

        reader.close();
    }
}