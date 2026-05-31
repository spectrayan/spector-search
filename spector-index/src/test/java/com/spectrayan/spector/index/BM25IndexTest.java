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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link BM25Index}.
 */
class BM25IndexTest {

    private BM25Index index;

    @BeforeEach
    void setUp() {
        index = new BM25Index();
    }

    @Test
    void emptyIndexReturnsNoResults() {
        ScoredResult[] results = index.search("hello", 10);
        assertThat(results).isEmpty();
    }

    @Test
    void singleDocumentExactMatch() {
        index.index("d1", "the quick brown fox jumps over the lazy dog");
        ScoredResult[] results = index.search("quick fox", 10);

        assertThat(results).hasSize(1);
        assertThat(results[0].id()).isEqualTo("d1");
        assertThat(results[0].score()).isGreaterThan(0);
    }

    @Test
    void ranksExactMatchHigher() {
        index.index("d1", "java programming language");
        index.index("d2", "python programming language");
        index.index("d3", "java virtual machine performance");

        ScoredResult[] results = index.search("java", 10);

        // Both d1 and d3 contain "java" but not d2
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        for (ScoredResult r : results) {
            assertThat(r.id()).isNotEqualTo("d2");
        }
    }

    @Test
    void multiTermQueryCombinesScores() {
        index.index("d1", "java virtual machine");
        index.index("d2", "java programming");
        index.index("d3", "virtual reality headset");

        ScoredResult[] results = index.search("java virtual", 10);

        // d1 matches both terms → should score highest
        assertThat(results[0].id()).isEqualTo("d1");
    }

    @Test
    void termFrequencyBoostsScore() {
        index.index("d1", "java");
        index.index("d2", "java java java java java");

        ScoredResult[] results = index.search("java", 10);

        // Both match, but d2 has higher TF
        assertThat(results).hasSize(2);
        // d2 should score higher due to TF (though BM25 saturates)
        assertThat(results[0].id()).isEqualTo("d2");
    }

    @Test
    void idfDownranksCommonTerms() {
        // Index 10 docs containing "common", but only 1 containing "rare"
        for (int i = 0; i < 10; i++) {
            index.index("common-" + i, "common word document number " + i);
        }
        index.index("rare-doc", "rare unique special word");

        ScoredResult[] results = index.search("rare", 10);
        assertThat(results).hasSize(1);
        assertThat(results[0].id()).isEqualTo("rare-doc");

        // "common" appears in all docs → lower IDF
        ScoredResult[] commonResults = index.search("common", 10);
        assertThat(commonResults).hasSize(10);
        // Each score should be positive but lower than rare term score
        assertThat(commonResults[0].score()).isLessThan(results[0].score());
    }

    @Test
    void noMatchReturnsEmpty() {
        index.index("d1", "hello world");
        ScoredResult[] results = index.search("xyzzy", 10);
        assertThat(results).isEmpty();
    }

    @Test
    void sizeTracking() {
        assertThat(index.size()).isEqualTo(0);
        index.index("d1", "hello");
        assertThat(index.size()).isEqualTo(1);
        index.index("d2", "world");
        assertThat(index.size()).isEqualTo(2);
    }

    @Test
    void resultsLimitedToK() {
        for (int i = 0; i < 20; i++) {
            index.index("doc-" + i, "search engine optimization performance " + i);
        }
        ScoredResult[] results = index.search("search engine", 5);
        assertThat(results).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    void resultsSortedByScoreDescending() {
        for (int i = 0; i < 10; i++) {
            index.index("doc-" + i, "search " + "engine ".repeat(i + 1));
        }
        ScoredResult[] results = index.search("engine", 10);
        for (int i = 1; i < results.length; i++) {
            assertThat(results[i - 1].score())
                    .isGreaterThanOrEqualTo(results[i].score());
        }
    }

    @Test
    void closeClearsIndex() {
        index.index("d1", "hello");
        index.close();
        assertThat(index.size()).isEqualTo(0);
        assertThat(index.search("hello", 10)).isEmpty();
    }

    @Test
    void stopWordsOnlyQueryReturnsEmpty() {
        index.index("d1", "the quick brown fox");
        // "the" and "is" are stop words
        ScoredResult[] results = index.search("the is", 10);
        assertThat(results).isEmpty();
    }
}
