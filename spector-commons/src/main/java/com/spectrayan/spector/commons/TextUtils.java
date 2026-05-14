package com.spectrayan.spector.commons;

/**
 * Common text normalization utilities.
 */
public final class TextUtils {

    private TextUtils() {}

    /**
     * Normalizes whitespace: collapses runs of whitespace to single spaces and trims.
     *
     * @param text the input text
     * @return normalized text
     */
    public static String normalizeWhitespace(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", " ").trim();
    }

    /**
     * Truncates text to a maximum length, appending an ellipsis if truncated.
     *
     * @param text      the input text
     * @param maxLength maximum character length
     * @return truncated text
     */
    public static String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 3) + "...";
    }

    /**
     * Estimates the token count for a text string.
     * Uses the rough approximation of 1 token ≈ 4 characters.
     *
     * @param text the input text
     * @return estimated token count
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (text.length() + 3) / 4; // ceiling division by 4
    }

    /**
     * Checks if a text is likely too long for a single embedding pass.
     *
     * @param text     the input text
     * @param maxTokens maximum token limit (e.g., 512 for many models)
     * @return true if the text likely exceeds the token limit
     */
    public static boolean exceedsTokenLimit(String text, int maxTokens) {
        return estimateTokens(text) > maxTokens;
    }
}
