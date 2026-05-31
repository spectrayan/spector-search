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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Tests for {@link WordTokenizer}.
 */
class WordTokenizerTest {

    @Test
    void tokenizeSimpleSentence() {
        List<WordTokenizer.Token> tokens = WordTokenizer.tokenize("Hello world");
        assertThat(tokens).hasSize(2);
        assertThat(tokens.get(0).text()).isEqualTo("Hello");
        assertThat(tokens.get(1).text()).isEqualTo("world");
    }

    @Test
    void tokenizeWithPunctuation() {
        List<WordTokenizer.Token> tokens = WordTokenizer.tokenize("Hello, world! How are you?");
        // Words only: Hello, world, How, are, you
        assertThat(tokens).hasSize(5);
        assertThat(tokens.stream().map(WordTokenizer.Token::text).toList())
                .containsExactly("Hello", "world", "How", "are", "you");
    }

    @Test
    void tokenizePreservesPositions() {
        List<WordTokenizer.Token> tokens = WordTokenizer.tokenize("ABC DEF");
        assertThat(tokens.get(0).startChar()).isEqualTo(0);
        assertThat(tokens.get(0).endChar()).isEqualTo(3);
        assertThat(tokens.get(1).startChar()).isEqualTo(4);
        assertThat(tokens.get(1).endChar()).isEqualTo(7);
    }

    @Test
    void tokenizeWithNumbers() {
        List<WordTokenizer.Token> tokens = WordTokenizer.tokenize("Java 25 is fast");
        assertThat(tokens).hasSize(4);
        assertThat(tokens.get(1).text()).isEqualTo("25");
    }

    @Test
    void countTokens() {
        assertThat(WordTokenizer.countTokens("one two three four five")).isEqualTo(5);
        assertThat(WordTokenizer.countTokens("")).isEqualTo(0);
        assertThat(WordTokenizer.countTokens(null)).isEqualTo(0);
    }

    @Test
    void charOffsetOfToken() {
        String text = "The quick brown fox";
        // token 0 = "The" @0, token 1 = "quick" @4, token 2 = "brown" @10
        assertThat(WordTokenizer.charOffsetOfToken(text, 0)).isEqualTo(0);
        assertThat(WordTokenizer.charOffsetOfToken(text, 1)).isEqualTo(4);
        assertThat(WordTokenizer.charOffsetOfToken(text, 2)).isEqualTo(10);
    }

    @Test
    void charEndAfterTokens() {
        String text = "The quick brown fox";
        // 1 token = "The" → end at 3
        assertThat(WordTokenizer.charEndAfterTokens(text, 1)).isEqualTo(3);
        // 2 tokens = "The quick" → end at 9
        assertThat(WordTokenizer.charEndAfterTokens(text, 2)).isEqualTo(9);
        // More tokens than exist → text length
        assertThat(WordTokenizer.charEndAfterTokens(text, 100)).isEqualTo(text.length());
    }

    @Test
    void emptyInput() {
        assertThat(WordTokenizer.tokenize("")).isEmpty();
        assertThat(WordTokenizer.tokenize(null)).isEmpty();
    }

    @Test
    void tokenIndex() {
        List<WordTokenizer.Token> tokens = WordTokenizer.tokenize("a b c");
        assertThat(tokens.get(0).index()).isEqualTo(0);
        assertThat(tokens.get(1).index()).isEqualTo(1);
        assertThat(tokens.get(2).index()).isEqualTo(2);
    }

    @Test
    void tokenLength() {
        var token = new WordTokenizer.Token("hello", 0, 5, 0);
        assertThat(token.length()).isEqualTo(5);
    }
}
