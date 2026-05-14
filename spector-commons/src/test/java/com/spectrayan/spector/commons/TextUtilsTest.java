package com.spectrayan.spector.commons;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TextUtils}.
 */
class TextUtilsTest {

    @Test
    void normalizeWhitespace() {
        assertThat(TextUtils.normalizeWhitespace("  hello   world  ")).isEqualTo("hello world");
        assertThat(TextUtils.normalizeWhitespace("tabs\t\ttoo")).isEqualTo("tabs too");
        assertThat(TextUtils.normalizeWhitespace(null)).isEmpty();
    }

    @Test
    void truncate() {
        assertThat(TextUtils.truncate("short", 100)).isEqualTo("short");
        assertThat(TextUtils.truncate("a very long string that should be cut", 20)).hasSize(20);
        assertThat(TextUtils.truncate("a very long string that should be cut", 20)).endsWith("...");
        assertThat(TextUtils.truncate(null, 10)).isEmpty();
    }

    @Test
    void estimateTokens() {
        assertThat(TextUtils.estimateTokens("")).isEqualTo(0);
        assertThat(TextUtils.estimateTokens(null)).isEqualTo(0);
        assertThat(TextUtils.estimateTokens("hello world")).isGreaterThan(0);
        // "hello world" = 11 chars → ceil(11/4) = 3 tokens
        assertThat(TextUtils.estimateTokens("hello world")).isEqualTo(3);
    }

    @Test
    void exceedsTokenLimit() {
        assertThat(TextUtils.exceedsTokenLimit("short", 512)).isFalse();
        String longText = "word ".repeat(1000); // 5000 chars ≈ 1250 tokens
        assertThat(TextUtils.exceedsTokenLimit(longText, 512)).isTrue();
    }
}
