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
package com.spectrayan.spector.ingestion;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link IngestionResult} — construction, factory methods, and success tracking.
 */
@DisplayName("IngestionResult")
class IngestionResultTest {

    @Nested
    @DisplayName("single()")
    class SingleResult {

        @Test
        @DisplayName("creates single-document result with 1 chunk and no failures")
        void createsSingleResult() {
            var result = IngestionResult.single("doc-1", 150);
            assertThat(result.documentId()).isEqualTo("doc-1");
            assertThat(result.chunksStored()).isEqualTo(1);
            assertThat(result.failures()).isEmpty();
            assertThat(result.durationMs()).isEqualTo(150);
        }

        @Test
        @DisplayName("single result is always a full success")
        void singleAlwaysFullSuccess() {
            var result = IngestionResult.single("doc-1", 0);
            assertThat(result.isFullSuccess()).isTrue();
        }
    }

    @Nested
    @DisplayName("chunked()")
    class ChunkedResult {

        @Test
        @DisplayName("creates chunked result with correct counts")
        void createsChunkedResult() {
            var result = IngestionResult.chunked("doc-2", 5, List.of(), 300);
            assertThat(result.documentId()).isEqualTo("doc-2");
            assertThat(result.chunksStored()).isEqualTo(5);
            assertThat(result.failures()).isEmpty();
            assertThat(result.durationMs()).isEqualTo(300);
        }

        @Test
        @DisplayName("chunked result with failures is not a full success")
        void chunkedWithFailures() {
            var failures = List.of("doc-2::chunk-2", "doc-2::chunk-4");
            var result = IngestionResult.chunked("doc-2", 3, failures, 500);
            assertThat(result.isFullSuccess()).isFalse();
            assertThat(result.failures()).hasSize(2);
            assertThat(result.chunksStored()).isEqualTo(3);
        }

        @Test
        @DisplayName("chunked result with zero chunks stored")
        void zeroChunksStored() {
            var result = IngestionResult.chunked("doc-3", 0, List.of("doc-3::chunk-0"), 100);
            assertThat(result.chunksStored()).isZero();
            assertThat(result.isFullSuccess()).isFalse();
        }

        @Test
        @DisplayName("chunked result with no failures is full success")
        void noFailuresIsFullSuccess() {
            var result = IngestionResult.chunked("doc-4", 10, List.of(), 1000);
            assertThat(result.isFullSuccess()).isTrue();
        }
    }

    @Nested
    @DisplayName("record equality")
    class RecordEquality {

        @Test
        @DisplayName("equal results have same hashCode")
        void equalResults() {
            var a = IngestionResult.single("doc-1", 100);
            var b = IngestionResult.single("doc-1", 100);
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("different results are not equal")
        void differentResults() {
            var a = IngestionResult.single("doc-1", 100);
            var b = IngestionResult.single("doc-2", 100);
            assertThat(a).isNotEqualTo(b);
        }
    }
}
