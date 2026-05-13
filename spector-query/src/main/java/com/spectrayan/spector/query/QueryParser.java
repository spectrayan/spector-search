package com.spectrayan.spector.query;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a text query string into a {@link SearchQuery}.
 *
 * <h3>Syntax</h3>
 * <pre>
 *   mode:hybrid k:10 java virtual machine
 *   mode:keyword k:5 search engine
 *   k:20 vector similarity
 * </pre>
 *
 * <p>Supported directives:</p>
 * <ul>
 *   <li>{@code mode:keyword|vector|hybrid} — search mode (default: keyword)</li>
 *   <li>{@code k:N} — top-K results (default: 10)</li>
 * </ul>
 *
 * <p>Everything not matching a directive is treated as the query text.</p>
 */
public final class QueryParser {

    private static final Pattern DIRECTIVE = Pattern.compile("(mode|k):(\\S+)");
    private static final int DEFAULT_TOP_K = 10;

    private QueryParser() {}

    /**
     * Parses a query string into a SearchQuery.
     *
     * @param input the raw query string
     * @return the parsed SearchQuery
     */
    public static SearchQuery parse(String input) {
        return parse(input, null);
    }

    /**
     * Parses a query string with an optional pre-computed vector.
     *
     * @param input  the raw query string
     * @param vector optional embedding vector (for vector/hybrid mode)
     * @return the parsed SearchQuery
     */
    public static SearchQuery parse(String input, float[] vector) {
        if (input == null || input.isBlank()) {
            if (vector != null && vector.length > 0) {
                return SearchQuery.vector(vector, DEFAULT_TOP_K);
            }
            return SearchQuery.keyword("", DEFAULT_TOP_K);
        }

        Map<String, String> directives = new HashMap<>();
        StringBuilder textBuilder = new StringBuilder();

        Matcher m = DIRECTIVE.matcher(input);
        int lastEnd = 0;

        while (m.find()) {
            // Append text before directive
            if (m.start() > lastEnd) {
                textBuilder.append(input, lastEnd, m.start());
            }
            directives.put(m.group(1).toLowerCase(), m.group(2).toLowerCase());
            lastEnd = m.end();
        }

        // Append remaining text
        if (lastEnd < input.length()) {
            textBuilder.append(input.substring(lastEnd));
        }

        String text = textBuilder.toString().trim();
        int topK = parseTopK(directives.get("k"));
        SearchQuery.SearchMode mode = parseMode(directives.get("mode"), text, vector);

        return switch (mode) {
            case KEYWORD -> SearchQuery.keyword(text, topK);
            case VECTOR -> SearchQuery.vector(vector, topK);
            case HYBRID -> SearchQuery.hybrid(text, vector, topK);
        };
    }

    private static int parseTopK(String value) {
        if (value == null) return DEFAULT_TOP_K;
        try {
            int k = Integer.parseInt(value);
            return k > 0 ? k : DEFAULT_TOP_K;
        } catch (NumberFormatException e) {
            return DEFAULT_TOP_K;
        }
    }

    private static SearchQuery.SearchMode parseMode(String value, String text, float[] vector) {
        if (value != null) {
            try {
                return SearchQuery.SearchMode.valueOf(value.toUpperCase());
            } catch (IllegalArgumentException e) {
                // fall through to auto-detect
            }
        }

        // Auto-detect
        boolean hasText = text != null && !text.isBlank();
        boolean hasVector = vector != null && vector.length > 0;

        if (hasText && hasVector) return SearchQuery.SearchMode.HYBRID;
        if (hasVector) return SearchQuery.SearchMode.VECTOR;
        return SearchQuery.SearchMode.KEYWORD;
    }
}
