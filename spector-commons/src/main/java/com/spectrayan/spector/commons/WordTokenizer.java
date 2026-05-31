/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.spectrayan.spector.commons;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Word-boundary tokenizer for accurate token counting and token-level chunking.
 *
 * <p>Uses ICU/Java {@link BreakIterator} for locale-aware word segmentation,
 * filtering out whitespace and punctuation-only tokens.</p>
 *
 * <h3>Token estimation vs. actual tokenization</h3>
 * <ul>
 *   <li>{@link TextUtils#estimateTokens(String)} — fast approximation (chars/4)</li>
 *   <li>{@link WordTokenizer#tokenize(String)} — accurate word-level tokenization</li>
 * </ul>
 */
public final class WordTokenizer {

    private WordTokenizer() {}

    /**
     * A single token with its position in the source text.
     *
     * @param text       the token text
     * @param startChar  start offset in original text (inclusive)
     * @param endChar    end offset in original text (exclusive)
     * @param index      zero-based token index
     */
    public record Token(String text, int startChar, int endChar, int index) {
        /** Returns the character length of this token. */
        public int length() { return text.length(); }
    }

    /**
     * Tokenizes text into words using locale-aware word boundaries.
     * Filters out whitespace-only and punctuation-only tokens.
     *
     * @param text the input text
     * @return list of word tokens with positions
     */
    public static List<Token> tokenize(String text) {
        return tokenize(text, Locale.ENGLISH);
    }

    /**
     * Tokenizes text using the specified locale.
     *
     * @param text   the input text
     * @param locale the locale for word boundary rules
     * @return list of word tokens with positions
     */
    public static List<Token> tokenize(String text, Locale locale) {
        if (text == null || text.isEmpty()) return List.of();

        List<Token> tokens = new ArrayList<>();
        BreakIterator iter = BreakIterator.getWordInstance(locale);
        iter.setText(text);

        int start = iter.first();
        int end = iter.next();
        int index = 0;

        while (end != BreakIterator.DONE) {
            String word = text.substring(start, end);
            // Keep only tokens with at least one letter or digit
            if (isWord(word)) {
                tokens.add(new Token(word, start, end, index++));
            }
            start = end;
            end = iter.next();
        }
        return tokens;
    }

    /**
     * Counts the number of word tokens in the text.
     *
     * @param text the input text
     * @return token count
     */
    public static int countTokens(String text) {
        if (text == null || text.isEmpty()) return 0;

        BreakIterator iter = BreakIterator.getWordInstance(Locale.ENGLISH);
        iter.setText(text);
        int count = 0;

        int start = iter.first();
        int end = iter.next();
        while (end != BreakIterator.DONE) {
            if (isWord(text.substring(start, end))) {
                count++;
            }
            start = end;
            end = iter.next();
        }
        return count;
    }

    /**
     * Returns the character offset of the Nth token.
     * Useful for finding where to split text at a token boundary.
     *
     * @param text      the input text
     * @param tokenIndex the target token index (0-based)
     * @return the character start offset of the token, or text.length() if past end
     */
    public static int charOffsetOfToken(String text, int tokenIndex) {
        if (text == null || text.isEmpty() || tokenIndex <= 0) return 0;

        BreakIterator iter = BreakIterator.getWordInstance(Locale.ENGLISH);
        iter.setText(text);
        int wordCount = 0;

        int start = iter.first();
        int end = iter.next();
        while (end != BreakIterator.DONE) {
            if (isWord(text.substring(start, end))) {
                if (wordCount == tokenIndex) return start;
                wordCount++;
            }
            start = end;
            end = iter.next();
        }
        return text.length();
    }

    /**
     * Returns the character end offset after the Nth token.
     *
     * @param text      the input text
     * @param tokenCount number of tokens from the start
     * @return the character end offset after the last included token
     */
    public static int charEndAfterTokens(String text, int tokenCount) {
        if (text == null || text.isEmpty() || tokenCount <= 0) return 0;

        BreakIterator iter = BreakIterator.getWordInstance(Locale.ENGLISH);
        iter.setText(text);
        int wordCount = 0;

        int start = iter.first();
        int end = iter.next();
        while (end != BreakIterator.DONE) {
            if (isWord(text.substring(start, end))) {
                wordCount++;
                if (wordCount == tokenCount) return end;
            }
            start = end;
            end = iter.next();
        }
        return text.length();
    }

    private static boolean isWord(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isLetterOrDigit(c)) return true;
        }
        return false;
    }
}
