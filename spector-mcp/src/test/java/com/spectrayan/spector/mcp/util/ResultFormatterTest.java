/*
 * Copyright 2026 Spectrayan
 */
package com.spectrayan.spector.mcp.util;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ResultFormatter} — timing footer, static helper methods.
 */
@DisplayName("ResultFormatter")
class ResultFormatterTest {

    @Nested
    @DisplayName("withTimingFooter")
    class TimingFooterTests {

        @Test @DisplayName("appends timing footer to result")
        void appendsTimingFooter() {
            var result = ResultFormatter.withTimingFooter("Found 5 results", "SIMD search", 42);
            assertThat(result).contains("Found 5 results");
            assertThat(result).contains("[SIMD search completed in 42ms]");
        }

        @Test @DisplayName("works with empty text")
        void emptyText() {
            var result = ResultFormatter.withTimingFooter("", "search", 0);
            assertThat(result).contains("[search completed in 0ms]");
        }

        @Test @DisplayName("preserves newline before footer")
        void newlineBeforeFooter() {
            var result = ResultFormatter.withTimingFooter("text", "op", 100);
            assertThat(result).startsWith("text\n[");
        }
    }
}
