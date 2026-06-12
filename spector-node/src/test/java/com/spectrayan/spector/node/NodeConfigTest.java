/*
 * Copyright 2026 Spectrayan
 */
package com.spectrayan.spector.node;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link NodeConfig} — construction, defaults, factory methods, and mode detection.
 */
@DisplayName("NodeConfig")
class NodeConfigTest {

    // ══════════════════════════════════════════════════════════════
    // Defaults
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("default constants have expected values")
    void defaultConstants() {
        assertThat(NodeConfig.DEFAULT_PORT).isEqualTo(7070);
        assertThat(NodeConfig.DEFAULT_DIMENSIONS).isEqualTo(384);
        assertThat(NodeConfig.DEFAULT_MAX_CONNECTIONS).isEqualTo(10_000);
        assertThat(NodeConfig.DEFAULT_REQUEST_TIMEOUT).isEqualTo(Duration.ofSeconds(30));
        assertThat(NodeConfig.DEFAULT_IDLE_TIMEOUT).isEqualTo(Duration.ofSeconds(60));
    }

    // ══════════════════════════════════════════════════════════════
    // standalone() factory
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("standalone() factory")
    class StandaloneFactory {

        @Test
        @DisplayName("creates STANDALONE mode config")
        void standaloneMode() {
            var cfg = NodeConfig.standalone(8080, 128);
            assertThat(cfg.mode()).isEqualTo(NodeConfig.NodeMode.STANDALONE);
            assertThat(cfg.isClustered()).isFalse();
        }

        @Test
        @DisplayName("sets port and dimensions")
        void setsPortAndDims() {
            var cfg = NodeConfig.standalone(9999, 768);
            assertThat(cfg.port()).isEqualTo(9999);
            assertThat(cfg.dimensions()).isEqualTo(768);
        }

        @Test
        @DisplayName("defaults MCP enabled")
        void defaultsMcpEnabled() {
            var cfg = NodeConfig.standalone(7070, 384);
            assertThat(cfg.mcpEnabled()).isTrue();
        }

        @Test
        @DisplayName("seed nodes empty in standalone")
        void emptySeedNodes() {
            var cfg = NodeConfig.standalone(7070, 384);
            assertThat(cfg.seedNodes()).isEmpty();
        }

        @Test
        @DisplayName("compression enabled by default")
        void compressionEnabled() {
            var cfg = NodeConfig.standalone(7070, 384);
            assertThat(cfg.compressionEnabled()).isTrue();
        }

        @Test
        @DisplayName("no API key by default")
        void noApiKey() {
            var cfg = NodeConfig.standalone(7070, 384);
            assertThat(cfg.apiKey()).isNull();
        }

        @Test
        @DisplayName("uses host-derived node ID")
        void hostDerivedNodeId() {
            var cfg = NodeConfig.standalone(7070, 384);
            assertThat(cfg.nodeId()).isNotNull().isNotBlank();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Manual construction
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("manual construction")
    class ManualConstruction {

        @Test
        @DisplayName("CLUSTERED mode with seed nodes")
        void clusteredMode() {
            var cfg = new NodeConfig(7070, NodeConfig.NodeMode.CLUSTERED, "node-1",
                    List.of("host1:7070", "host2:7070"), "my-key", true, 384,
                    5000, Duration.ofSeconds(60), true, Duration.ofSeconds(120));
            assertThat(cfg.isClustered()).isTrue();
            assertThat(cfg.seedNodes()).hasSize(2);
            assertThat(cfg.apiKey()).isEqualTo("my-key");
        }

        @Test
        @DisplayName("custom timeouts work")
        void customTimeouts() {
            var cfg = new NodeConfig(7070, NodeConfig.NodeMode.STANDALONE, "test",
                    List.of(), null, false, 256,
                    100, Duration.ofSeconds(5), false, Duration.ofSeconds(10));
            assertThat(cfg.requestTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(cfg.idleTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(cfg.mcpEnabled()).isFalse();
            assertThat(cfg.compressionEnabled()).isFalse();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // NodeMode enum
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("NodeMode has STANDALONE and CLUSTERED")
    void nodeModeValues() {
        assertThat(NodeConfig.NodeMode.values()).containsExactlyInAnyOrder(
                NodeConfig.NodeMode.STANDALONE, NodeConfig.NodeMode.CLUSTERED);
    }
}
