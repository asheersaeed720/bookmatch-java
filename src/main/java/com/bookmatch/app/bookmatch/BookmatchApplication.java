package com.bookmatch.app.bookmatch;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Scanner;

@SpringBootApplication
public class BookmatchApplication implements CommandLineRunner {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(BookmatchApplication.class);
        app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        app.run(args);
    }

    @Override
    public void run(String... args) throws Exception {
        System.out.println("Initializing BookMatch Search Engine...");

        SearchEngineService searchService = new SearchEngineService();

        String indexDirectoryPath = "./lucene-index";
        String samplePdfPath = "./The Maze Runner.pdf";
        String bookTitle = "The Maze Runner";

        try {
            // Step A: Extract text from PDF
            System.out.println("Extracting text from PDF...");
            String content = searchService.extractTextFromPdf(samplePdfPath);

            // Step B: Index the extracted text
            System.out.println("Writing content to Lucene index...");
            searchService.indexBook(bookTitle, content, indexDirectoryPath);

            // Step C: Interactive search loop
            Scanner scanner = new Scanner(System.in);
            System.out.println("\nBookMatch is ready. Type a word or phrase to search, or 'exit' to quit.");

            while (true) {
                System.out.print("\nEnter search text: ");
                String userInput = scanner.nextLine().trim();

                if (userInput.equalsIgnoreCase("exit") || userInput.equalsIgnoreCase("quit")) {
                    break;
                }

                if (userInput.isEmpty()) {
                    System.out.println("Please enter a search term.");
                    continue;
                }

                searchService.searchIndex(userInput, indexDirectoryPath);
            }

            scanner.close();

        } catch (Exception e) {
            System.err.println("An error occurred during execution: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("Process finished. Shutting down.");
    }
}