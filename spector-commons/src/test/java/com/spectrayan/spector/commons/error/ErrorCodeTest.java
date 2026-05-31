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
package com.spectrayan.spector.commons.error;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the ErrorCode registry, ErrorCategory ranges, and SpectorException hierarchy.
 */
class ErrorCodeTest {

    // ─────────────── Uniqueness ───────────────

    @Test
    void allCodesAreUnique() {
        Map<Integer, ErrorCode> seen = new HashMap<>();
        for (ErrorCode ec : ErrorCode.values()) {
            ErrorCode prev = seen.put(ec.code(), ec);
            assertThat(prev)
                    .describedAs("Duplicate code %d: %s and %s", ec.code(), ec.name(), prev)
                    .isNull();
        }
    }

    @Test
    void allIdsAreUnique() {
        Set<String> seen = new HashSet<>();
        for (ErrorCode ec : ErrorCode.values()) {
            boolean added = seen.add(ec.id());
            assertThat(added)
                    .describedAs("Duplicate id: %s (%s)", ec.id(), ec.name())
                    .isTrue();
        }
    }

    // ─────────────── Category Range ───────────────

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void everyCodeFallsWithinItsCategoryRange(ErrorCode ec) {
        ErrorCategory cat = ec.category();
        assertThat(cat.contains(ec.code()))
                .describedAs("%s (code=%d) should be in %s range [%d000–%d999]",
                        ec.name(), ec.code(), cat.name(), cat.rangeStart(), cat.rangeEnd())
                .isTrue();
    }

    // ─────────────── ID Formatting ───────────────

    @Test
    void idFormatMatchesSpeXxxYyy() {
        for (ErrorCode ec : ErrorCode.values()) {
            assertThat(ec.id())
                    .matches("SPE-\\d{3}-\\d{3}")
                    .describedAs("ID for %s should match SPE-XXX-YYY", ec.name());
        }
    }

    @Test
    void specificIdValues() {
        assertThat(ErrorCode.DIMENSIONS_INVALID.id()).isEqualTo("SPE-100-001");
        assertThat(ErrorCode.CONFIG_FILE_NOT_FOUND.id()).isEqualTo("SPE-110-001");
        assertThat(ErrorCode.HNSW_BUILD_FAILED.id()).isEqualTo("SPE-200-001");
        assertThat(ErrorCode.SEGMENT_CLOSED.id()).isEqualTo("SPE-210-001");
        assertThat(ErrorCode.EMBEDDING_UNAVAILABLE.id()).isEqualTo("SPE-300-001");
        assertThat(ErrorCode.MEMORY_TIER_FULL.id()).isEqualTo("SPE-310-001");
        assertThat(ErrorCode.CUDA_DRIVER_NOT_FOUND.id()).isEqualTo("SPE-400-001");
        assertThat(ErrorCode.API_BAD_REQUEST.id()).isEqualTo("SPE-500-001");
        assertThat(ErrorCode.CLIENT_CONNECTION_FAILED.id()).isEqualTo("SPE-510-001");
        assertThat(ErrorCode.INGESTION_FORMAT_UNSUPPORTED.id()).isEqualTo("SPE-600-001");
        assertThat(ErrorCode.SHARD_UNAVAILABLE.id()).isEqualTo("SPE-700-001");
        assertThat(ErrorCode.INTERNAL_ERROR.id()).isEqualTo("SPE-900-001");
    }

    // ─────────────── Lookup ───────────────

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void fromCodeRoundTrips(ErrorCode ec) {
        assertThat(ErrorCode.fromCode(ec.code())).isSameAs(ec);
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void fromIdRoundTrips(ErrorCode ec) {
        assertThat(ErrorCode.fromId(ec.id())).isSameAs(ec);
    }

    @Test
    void fromCodeReturnsNullForUnknown() {
        assertThat(ErrorCode.fromCode(999_999)).isNull();
    }

    @Test
    void fromIdReturnsNullForMalformed() {
        assertThat(ErrorCode.fromId(null)).isNull();
        assertThat(ErrorCode.fromId("")).isNull();
        assertThat(ErrorCode.fromId("not-a-code")).isNull();
        assertThat(ErrorCode.fromId("SPE-999-999")).isNull();
        assertThat(ErrorCode.fromId("ABC-100-001")).isNull();
    }

    // ─────────────── Message Formatting ───────────────

    @Test
    void formatWithNoArgs() {
        String msg = ErrorCode.VECTOR_NULL.format();
        assertThat(msg).isEqualTo("[SPE-100-003] Vector must not be null");
    }

    @Test
    void formatWithOneArg() {
        String msg = ErrorCode.DIMENSIONS_INVALID.format(0);
        assertThat(msg).isEqualTo("[SPE-100-001] Vector dimensions must be positive, got 0");
    }

    @Test
    void formatWithTwoArgs() {
        String msg = ErrorCode.DIMENSIONS_MISMATCH.format(384, 768);
        assertThat(msg).isEqualTo("[SPE-100-002] Expected 384 dimensions but received 768");
    }

    @Test
    void formatWithFourArgs() {
        String msg = ErrorCode.ARGUMENT_OUT_OF_RANGE.format("topK", 1, 10000, 0);
        assertThat(msg).isEqualTo("[SPE-100-008] topK must be between 1 and 10000, got 0");
    }

    @Test
    void formatWithExtraArgsIgnoresExtras() {
        String msg = ErrorCode.VECTOR_NULL.format("extra", "ignored");
        assertThat(msg).isEqualTo("[SPE-100-003] Vector must not be null");
    }

    @Test
    void formatWithFewerArgsThanPlaceholders() {
        String msg = ErrorCode.DIMENSIONS_MISMATCH.format(384);
        assertThat(msg).isEqualTo("[SPE-100-002] Expected 384 dimensions but received {}");
    }

    // ─────────────── Message Templates ───────────────

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void allMessageTemplatesAreNonEmpty(ErrorCode ec) {
        assertThat(ec.messageTemplate())
                .describedAs("Message template for %s", ec.name())
                .isNotNull()
                .isNotBlank();
    }

    // ─────────────── Exception Hierarchy ───────────────

    @Test
    void validationExceptionCarriesErrorCode() throws SpectorException {
        var ex = new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, 384, 768);

        assertThat(ex).isInstanceOf(SpectorException.class);
        assertThat(ex).isInstanceOf(Exception.class);
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.DIMENSIONS_MISMATCH);
        assertThat(ex.code()).isEqualTo(100_002);
        assertThat(ex.codeId()).isEqualTo("SPE-100-002");
        assertThat(ex.category()).isEqualTo(ErrorCategory.VALIDATION);
        assertThat(ex.getMessage()).isEqualTo("[SPE-100-002] Expected 384 dimensions but received 768");
    }

    @Test
    void internalExceptionCarriesErrorCode() throws SpectorException {
        var ex = new SpectorInternalException(ErrorCode.UNREACHABLE_CODE, "switch on bits=16");

        assertThat(ex).isInstanceOf(SpectorException.class);
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.UNREACHABLE_CODE);
        assertThat(ex.code()).isEqualTo(900_003);
        assertThat(ex.codeId()).isEqualTo("SPE-900-003");
        assertThat(ex.category()).isEqualTo(ErrorCategory.INTERNAL);
        assertThat(ex.getMessage()).contains("SPE-900-003");
        assertThat(ex.getMessage()).contains("switch on bits=16");
    }

    @Test
    void exceptionWithCausePreservesCause() {
        var cause = new RuntimeException("disk full");
        var ex = new SpectorInternalException(ErrorCode.INTERNAL_ERROR, cause, "write failed");

        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getMessage()).contains("SPE-900-001");
        assertThat(ex.getMessage()).contains("write failed");
    }

    @Test
    void spectorExceptionHierarchy() {
        // SpectorException extends RuntimeException
        assertThat(Exception.class).isAssignableFrom(SpectorException.class);
        assertThat(RuntimeException.class.isAssignableFrom(SpectorException.class))
                .describedAs("SpectorException extends RuntimeException")
                .isTrue();
    }

    // ─────────────── ErrorCategory ───────────────

    @Test
    void categoryContainsWorksCorrectly() {
        assertThat(ErrorCategory.VALIDATION.contains(100_001)).isTrue();
        assertThat(ErrorCategory.VALIDATION.contains(100_999)).isTrue();
        assertThat(ErrorCategory.VALIDATION.contains(109_999)).isTrue();
        assertThat(ErrorCategory.VALIDATION.contains(110_001)).isFalse(); // CONFIG range
        assertThat(ErrorCategory.VALIDATION.contains(200_001)).isFalse();

        assertThat(ErrorCategory.INTERNAL.contains(900_001)).isTrue();
        assertThat(ErrorCategory.INTERNAL.contains(909_999)).isTrue();
        assertThat(ErrorCategory.INTERNAL.contains(910_001)).isFalse();
    }

    @Test
    void allCategoriesHaveDistinctRanges() {
        ErrorCategory[] cats = ErrorCategory.values();
        for (int i = 0; i < cats.length; i++) {
            for (int j = i + 1; j < cats.length; j++) {
                boolean overlap = cats[i].rangeStart() <= cats[j].rangeEnd()
                        && cats[j].rangeStart() <= cats[i].rangeEnd();
                assertThat(overlap)
                        .describedAs("%s [%d–%d] overlaps with %s [%d–%d]",
                                cats[i].name(), cats[i].rangeStart(), cats[i].rangeEnd(),
                                cats[j].name(), cats[j].rangeStart(), cats[j].rangeEnd())
                        .isFalse();
            }
        }
    }
}
