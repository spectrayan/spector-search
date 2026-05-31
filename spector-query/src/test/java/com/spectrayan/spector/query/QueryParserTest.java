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
package com.spectrayan.spector.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link QueryParser}.
 */
class QueryParserTest {

    @Test
    void parseSimpleKeywordQuery() {
        SearchQuery q = QueryParser.parse("java virtual machine");
        assertThat(q.mode()).isEqualTo(SearchQuery.SearchMode.KEYWORD);
        assertThat(q.text()).isEqualTo("java virtual machine");
        assertThat(q.topK()).isEqualTo(10); // default
    }

    @Test
    void parseModeDirective() {
        SearchQuery q = QueryParser.parse("mode:keyword search engine");
        assertThat(q.mode()).isEqualTo(SearchQuery.SearchMode.KEYWORD);
        assertThat(q.text()).isEqualTo("search engine");
    }

    @Test
    void parseTopKDirective() {
        SearchQuery q = QueryParser.parse("k:20 vector similarity");
        assertThat(q.topK()).isEqualTo(20);
        assertThat(q.text()).isEqualTo("vector similarity");
    }

    @Test
    void parseMultipleDirectives() {
        SearchQuery q = QueryParser.parse("mode:keyword k:5 hello world");
        assertThat(q.mode()).isEqualTo(SearchQuery.SearchMode.KEYWORD);
        assertThat(q.topK()).isEqualTo(5);
        assertThat(q.text()).isEqualTo("hello world");
    }

    @Test
    void parseWithVector() {
        float[] vec = {0.1f, 0.2f, 0.3f};
        SearchQuery q = QueryParser.parse("mode:hybrid k:10 test query", vec);
        assertThat(q.mode()).isEqualTo(SearchQuery.SearchMode.HYBRID);
        assertThat(q.vector()).isEqualTo(vec);
        assertThat(q.text()).isEqualTo("test query");
    }

    @Test
    void autoDetectsHybridMode() {
        float[] vec = {0.1f, 0.2f};
        SearchQuery q = QueryParser.parse("search text", vec);
        assertThat(q.mode()).isEqualTo(SearchQuery.SearchMode.HYBRID);
    }

    @Test
    void autoDetectsVectorMode() {
        float[] vec = {0.1f, 0.2f};
        SearchQuery q = QueryParser.parse("  ", vec);
        assertThat(q.mode()).isEqualTo(SearchQuery.SearchMode.VECTOR);
    }

    @Test
    void invalidTopKUsesDefault() {
        SearchQuery q = QueryParser.parse("k:abc hello");
        assertThat(q.topK()).isEqualTo(10);
    }

    @Test
    void negativeTopKUsesDefault() {
        SearchQuery q = QueryParser.parse("k:-5 hello");
        assertThat(q.topK()).isEqualTo(10);
    }

    @Test
    void emptyInputReturnsDefault() {
        SearchQuery q = QueryParser.parse("");
        assertThat(q.mode()).isEqualTo(SearchQuery.SearchMode.KEYWORD);
        assertThat(q.topK()).isEqualTo(10);
    }

    @Test
    void nullInputReturnsDefault() {
        SearchQuery q = QueryParser.parse(null);
        assertThat(q.mode()).isEqualTo(SearchQuery.SearchMode.KEYWORD);
    }

    @Test
    void invalidModeDirectiveFallsBack() {
        SearchQuery q = QueryParser.parse("mode:invalid hello");
        assertThat(q.mode()).isEqualTo(SearchQuery.SearchMode.KEYWORD);
    }
}
