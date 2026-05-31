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
package com.spectrayan.spector.index;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Standard text analyzer: lowercase → Unicode-aware tokenize → stop word removal.
 *
 * <p>Splits on non-alphanumeric boundaries, lowercases all tokens, and removes
 * common English stop words. Tokens shorter than 2 characters are discarded.</p>
 */
public class StandardAnalyzer implements Analyzer {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final int MIN_TOKEN_LENGTH = 2;

    /** Common English stop words. */
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
                tokens.add(token);
            }
        }

        return tokens;
    }
}
