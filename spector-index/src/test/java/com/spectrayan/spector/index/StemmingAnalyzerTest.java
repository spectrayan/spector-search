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
