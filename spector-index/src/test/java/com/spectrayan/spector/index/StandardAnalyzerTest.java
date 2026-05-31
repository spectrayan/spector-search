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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Tests for {@link StandardAnalyzer}.
 */
class StandardAnalyzerTest {

    private final StandardAnalyzer analyzer = new StandardAnalyzer();

    @Test
    void lowercasesTokens() {
        List<String> tokens = analyzer.analyze("Hello WORLD");
        assertThat(tokens).contains("hello", "world");
    }

    @Test
    void removesStopWords() {
        List<String> tokens = analyzer.analyze("the quick brown fox is in the box");
        assertThat(tokens).doesNotContain("the", "is", "in");
        assertThat(tokens).contains("quick", "brown", "fox", "box");
    }

    @Test
    void removesShortTokens() {
        List<String> tokens = analyzer.analyze("I am a test");
        // "I", "a" are 1 char → removed. "am" is 2 chars → kept if not stop word
        assertThat(tokens).doesNotContain("i", "a");
    }

    @Test
    void splitsOnPunctuation() {
        List<String> tokens = analyzer.analyze("hello-world, foo.bar");
        assertThat(tokens).contains("hello", "world", "foo", "bar");
    }

    @Test
    void handlesEmptyInput() {
        assertThat(analyzer.analyze("")).isEmpty();
        assertThat(analyzer.analyze(null)).isEmpty();
    }

    @Test
    void handlesNumbers() {
        List<String> tokens = analyzer.analyze("version 2.0 release 42");
        assertThat(tokens).contains("version", "release", "42");
    }

    @Test
    void preservesDuplicatesForTfCounting() {
        List<String> tokens = analyzer.analyze("java java java");
        assertThat(tokens).hasSize(3);
        assertThat(tokens).containsOnly("java");
    }
}
