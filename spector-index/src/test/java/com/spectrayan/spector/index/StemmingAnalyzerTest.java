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
 * Tests for {@link StemmingAnalyzer}.
 */
class StemmingAnalyzerTest {

    private final StemmingAnalyzer analyzer = new StemmingAnalyzer();

    @Test
    void stemsPlurals() {
        List<String> tokens = analyzer.analyze("running dogs and cats");
        assertThat(tokens).contains("run", "dog", "cat");
    }

    @Test
    void stemsIngSuffix() {
        assertThat(StemmingAnalyzer.stem("running")).isEqualTo("run");
        assertThat(StemmingAnalyzer.stem("searching")).isEqualTo("search");
    }

    @Test
    void stemsTionSuffix() {
        assertThat(StemmingAnalyzer.stem("optimization")).isEqualTo("optimiza");
        assertThat(StemmingAnalyzer.stem("computation")).isEqualTo("computa");
    }

    @Test
    void stemsNessSuffix() {
        assertThat(StemmingAnalyzer.stem("darkness")).isEqualTo("dark");
        assertThat(StemmingAnalyzer.stem("happiness")).isEqualTo("happi");
    }

    @Test
    void stemsAbleSuffix() {
        assertThat(StemmingAnalyzer.stem("searchable")).isEqualTo("search");
        assertThat(StemmingAnalyzer.stem("readable")).isEqualTo("read");
    }

    @Test
    void stemsLySuffix() {
        assertThat(StemmingAnalyzer.stem("quickly")).isEqualTo("quick");
        assertThat(StemmingAnalyzer.stem("nearly")).isEqualTo("near");
    }

    @Test
    void shortWordsUnchanged() {
        assertThat(StemmingAnalyzer.stem("run")).isEqualTo("run");
        assertThat(StemmingAnalyzer.stem("the")).isEqualTo("the");
    }

    @Test
    void removesStopWords() {
        List<String> tokens = analyzer.analyze("the quick brown fox is in the box");
        assertThat(tokens).doesNotContain("the", "is", "in");
    }

    @Test
    void handlesEmptyInput() {
        assertThat(analyzer.analyze("")).isEmpty();
        assertThat(analyzer.analyze(null)).isEmpty();
    }
}
