package com.spectrayan.spector.index;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Enhanced analyzer with Porter stemming support.
 *
 * <p>Pipeline: tokenize → lowercase → stop word removal → stemming.</p>
 */
public class StemmingAnalyzer implements Analyzer {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final int MIN_TOKEN_LENGTH = 2;

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "but", "by",
            "for", "if", "in", "into", "is", "it", "its", "no", "not",
            "of", "on", "or", "such", "that", "the", "their", "then",
            "there", "these", "they", "this", "to", "was", "will", "with"
    );

    @Override
    public List<String> analyze(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        List<String> tokens = new ArrayList<>();
        var matcher = TOKEN_PATTERN.matcher(text.toLowerCase());

        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() >= MIN_TOKEN_LENGTH && !STOP_WORDS.contains(token)) {
                tokens.add(stem(token));
            }
        }
        return tokens;
    }

    /**
     * Simplified Porter stemmer — handles the most common English suffixes.
     * For production, replace with a full Porter/Snowball implementation.
     */
    static String stem(String word) {
        if (word.length() <= 3) return word;

        // Step 1: plurals and past tenses
        if (word.endsWith("sses")) return word.substring(0, word.length() - 2);
        if (word.endsWith("ies")) return word.substring(0, word.length() - 2);
        if (word.endsWith("ied")) return word.substring(0, word.length() - 2);

        // Step 2: longer suffixes (check BEFORE short ones like -ss, -s)
        if (word.endsWith("edness") && word.length() > 8) return dedupConsonant(word.substring(0, word.length() - 6));
        if (word.endsWith("ingly") && word.length() > 7) return dedupConsonant(word.substring(0, word.length() - 5));
        if (word.endsWith("edly") && word.length() > 6) return dedupConsonant(word.substring(0, word.length() - 4));
        if (word.endsWith("ness") && word.length() > 5) return word.substring(0, word.length() - 4);
        if (word.endsWith("ment") && word.length() > 5) return word.substring(0, word.length() - 4);
        if (word.endsWith("tion") && word.length() > 5) return word.substring(0, word.length() - 4);
        if (word.endsWith("able") && word.length() > 5) return word.substring(0, word.length() - 4);
        if (word.endsWith("ible") && word.length() > 5) return word.substring(0, word.length() - 4);
        if (word.endsWith("ing") && word.length() > 5) return dedupConsonant(word.substring(0, word.length() - 3));
        if (word.endsWith("ful") && word.length() > 4) return word.substring(0, word.length() - 3);
        if (word.endsWith("ous") && word.length() > 4) return word.substring(0, word.length() - 3);
        if (word.endsWith("ive") && word.length() > 4) return word.substring(0, word.length() - 3);
        if (word.endsWith("ly") && word.length() > 4) return word.substring(0, word.length() - 2);
        if (word.endsWith("ed") && word.length() > 4) return dedupConsonant(word.substring(0, word.length() - 2));
        if (word.endsWith("er") && word.length() > 4) return dedupConsonant(word.substring(0, word.length() - 2));

        // Step 3: simple plural (after checking longer suffixes)
        if (word.endsWith("ss")) return word;
        if (word.endsWith("s") && word.length() > 3) return word.substring(0, word.length() - 1);

        return word;
    }

    /**
     * Removes trailing duplicate consonants (e.g., "runn" → "run", "stopp" → "stop").
     */
    private static String dedupConsonant(String stem) {
        int len = stem.length();
        if (len >= 2) {
            char last = stem.charAt(len - 1);
            char prev = stem.charAt(len - 2);
            if (last == prev && !isVowel(last)) {
                return stem.substring(0, len - 1);
            }
        }
        return stem;
    }

    private static boolean isVowel(char c) {
        return "aeiou".indexOf(c) >= 0;
    }
}
