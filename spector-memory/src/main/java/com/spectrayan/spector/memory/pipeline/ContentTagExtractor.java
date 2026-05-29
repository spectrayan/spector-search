package com.spectrayan.spector.memory.pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Default tag extractor: derives synaptic tags from document path and content.
 *
 * <h3>Extraction Strategy</h3>
 * <ol>
 *   <li><b>Path-based tags</b>: Splits the document ID by path separators,
 *       dots, hyphens, and underscores. Each segment longer than 2 characters
 *       (and not a stop word) becomes a tag.</li>
 *   <li><b>Content-based tags</b>: Extracts significant words from the first
 *       N characters of the text. Words must be longer than 4 characters,
 *       not a stop word, and appear at least once. Top words by length are
 *       selected (longer words are typically more specific).</li>
 * </ol>
 *
 * <p>Total tags per record are capped at {@link #MAX_TAGS} (default: 10)
 * to keep the Bloom filter FPR under 0.2% (per analysis doc §19).</p>
 *
 * @see TagExtractor
 */
public final class ContentTagExtractor implements TagExtractor {

    /** Maximum tags per record (Bloom filter sweet spot). */
    private static final int MAX_TAGS = 10;

    /** Maximum content prefix to scan for keyword extraction. */
    private static final int CONTENT_SCAN_CHARS = 500;

    /** Maximum content-derived tags (path tags get priority). */
    private static final int MAX_CONTENT_TAGS = 5;

    private static final Pattern SPLIT_PATTERN = Pattern.compile("[/\\\\._\\-\\s]+");
    private static final Pattern ALPHA_ONLY = Pattern.compile("[^a-z0-9]");

    /** Common English stop words to exclude from tags. */
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "are", "but", "not", "you", "all",
            "can", "had", "her", "was", "one", "our", "out", "has",
            "his", "how", "its", "may", "new", "now", "old", "see",
            "way", "who", "did", "get", "let", "say", "she", "too",
            "use", "with", "that", "this", "have", "from", "they",
            "been", "said", "each", "which", "their", "will", "other",
            "about", "many", "then", "them", "these", "some", "would",
            "make", "like", "into", "could", "time", "very", "when",
            "come", "made", "after", "back", "only", "just", "being",
            "over", "also", "than", "much", "down", "should", "were",
            "what", "your", "more", "there", "first", "where", "those",
            "still", "here", "through", "while", "before", "between",
            "under", "never", "every", "because", "another",
            // File-related stop words
            "txt", "file", "doc", "docs", "test", "tests", "src", "main",
            "java", "class", "chunk", "part"
    );

    @Override
    public String[] extract(String id, String text) {
        Set<String> tags = new LinkedHashSet<>(); // preserve insertion order, deduplicate

        // Phase 1: Path-based tags from document ID
        extractPathTags(id, tags);

        // Phase 2: Content-based significant words
        if (tags.size() < MAX_TAGS && text != null && !text.isBlank()) {
            extractContentTags(text, tags);
        }

        // Cap at MAX_TAGS
        return tags.stream().limit(MAX_TAGS).toArray(String[]::new);
    }

    /**
     * Extracts tags from document ID path segments.
     * E.g., "stories/auth/login-flow.txt" → ["stories", "auth", "login", "flow"]
     */
    private void extractPathTags(String id, Set<String> tags) {
        if (id == null) return;

        String[] parts = SPLIT_PATTERN.split(id);
        for (String part : parts) {
            String clean = ALPHA_ONLY.matcher(part.toLowerCase(Locale.ROOT)).replaceAll("");
            if (clean.length() > 2 && !STOP_WORDS.contains(clean)) {
                tags.add(clean);
            }
        }
    }

    /**
     * Extracts significant keywords from the beginning of the text content.
     * Selects longer words first (typically more specific/meaningful).
     */
    private void extractContentTags(String text, Set<String> tags) {
        String prefix = text.length() > CONTENT_SCAN_CHARS
                ? text.substring(0, CONTENT_SCAN_CHARS) : text;

        String[] words = SPLIT_PATTERN.split(prefix.toLowerCase(Locale.ROOT));
        List<String> candidates = new ArrayList<>();

        for (String word : words) {
            String clean = ALPHA_ONLY.matcher(word).replaceAll("");
            if (clean.length() > 4 && !STOP_WORDS.contains(clean) && !tags.contains(clean)) {
                candidates.add(clean);
            }
        }

        // Sort by length descending — longer words are more specific
        candidates.sort((a, b) -> Integer.compare(b.length(), a.length()));

        // Deduplicate and take top N
        int added = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (String c : candidates) {
            if (seen.add(c) && tags.add(c)) {
                added++;
                if (added >= MAX_CONTENT_TAGS) break;
            }
        }
    }
}
