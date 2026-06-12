/*
 * Copyright 2026 Spectrayan
 */
package com.spectrayan.spector.mcp;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TransportMode} enum — parsing, defaults, edge cases.
 */
@DisplayName("TransportMode")
class TransportModeTest {

    @Test
    @DisplayName("has STDIO and HTTP values")
    void allValues() {
        assertThat(TransportMode.values()).containsExactlyInAnyOrder(
                TransportMode.STDIO, TransportMode.HTTP);
    }

    @Test
    @DisplayName("fromString parses 'stdio'")
    void parsesStdio() {
        assertThat(TransportMode.fromString("stdio")).isEqualTo(TransportMode.STDIO);
        assertThat(TransportMode.fromString("STDIO")).isEqualTo(TransportMode.STDIO);
    }

    @Test
    @DisplayName("fromString parses 'http'")
    void parsesHttp() {
        assertThat(TransportMode.fromString("http")).isEqualTo(TransportMode.HTTP);
        assertThat(TransportMode.fromString("HTTP")).isEqualTo(TransportMode.HTTP);
    }

    @Test
    @DisplayName("fromString parses 'streamable-http'")
    void parsesStreamableHttp() {
        assertThat(TransportMode.fromString("streamable-http")).isEqualTo(TransportMode.HTTP);
    }

    @Test
    @DisplayName("fromString parses 'sse'")
    void parsesSse() {
        assertThat(TransportMode.fromString("sse")).isEqualTo(TransportMode.HTTP);
    }

    @Test
    @DisplayName("null defaults to STDIO")
    void nullDefaultsToStdio() {
        assertThat(TransportMode.fromString(null)).isEqualTo(TransportMode.STDIO);
    }

    @Test
    @DisplayName("blank defaults to STDIO")
    void blankDefaultsToStdio() {
        assertThat(TransportMode.fromString("  ")).isEqualTo(TransportMode.STDIO);
    }

    @Test
    @DisplayName("unknown value throws IllegalArgumentException")
    void unknownThrows() {
        assertThatThrownBy(() -> TransportMode.fromString("grpc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grpc");
    }
}
