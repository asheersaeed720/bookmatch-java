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
        // Drop any number of PDF books into this folder; they all get indexed.
        String booksFolderPath = "./books";

        try {
            // Step A + B: Extract text from every PDF in the books folder and index it
            System.out.println("Indexing all PDFs in '" + booksFolderPath + "'...");
            searchService.indexBooksFolder(booksFolderPath, indexDirectoryPath);

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