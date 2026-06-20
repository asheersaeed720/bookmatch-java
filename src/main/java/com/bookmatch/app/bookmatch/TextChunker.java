package com.bookmatch.app.bookmatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TextChunker {

    private static final int WORDS_PER_CHUNK = 150;
    private static final int CONTEXT_CHUNKS = 3;

    // Keeps each book's chunks in order (title -> ordered chunks) so we can
    // stitch neighbouring chunks back together into a full passage after a search.
    private final Map<String, List<String>> bookChunks = new LinkedHashMap<>();

    // Split text into chunks of roughly WORDS_PER_CHUNK words and store them
    // under the given title. Returns the ordered list of chunks produced.
    public List<String> chunkAndStore(String title, String text) {
        List<String> chunks = chunkText(text);
        bookChunks.put(title, chunks);
        return chunks;
    }

    // Stitch the best-matching chunk together with its neighbouring chunks to
    // rebuild the full, continuous passage covering the searched topic.
    public String getCompletePassage(String title, int centerChunk) {
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
}
