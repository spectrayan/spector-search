/*
 * Copyright 2026 Spectrayan
 */
package com.spectrayan.spector.config;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.spectrayan.spector.config.error.SpectorConfigValueException;

/**
 * Additional config module tests to increase coverage:
 * CortexTelemetryConfig, PersistenceFiles, SpectorConfigException, config errors.
 */
@DisplayName("Config Module — Extended Coverage")
class ConfigExtendedTest {

    // ══════════════════════════════════════════════════════════════
    // CortexTelemetryConfig
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("CortexTelemetryConfig")
    class CortexTelemetryTests {

        @Test
        @DisplayName("DEFAULT has all features enabled")
        void defaultConfig() {
            var cfg = CortexTelemetryConfig.DEFAULT;
            assertThat(cfg.enabled()).isTrue();
            assertThat(cfg.intervalMs()).isEqualTo(2000);
            assertThat(cfg.perQueryEnabled()).isTrue();
            assertThat(cfg.querySampleRate()).isEqualTo(1.0);
            assertThat(cfg.simdEnabled()).isTrue();
            assertThat(cfg.graphEnabled()).isTrue();
        }

        @Test
        @DisplayName("fromSystemProperties returns defaults when no props set")
        void fromSystemPropsDefaults() {
            var cfg = CortexTelemetryConfig.fromSystemProperties();
            assertThat(cfg.enabled()).isTrue();
            assertThat(cfg.intervalMs()).isEqualTo(2000);
        }

        @Test
        @DisplayName("shouldSampleQuery returns true when rate is 1.0")
        void sampleQueryFullRate() {
            var cfg = new CortexTelemetryConfig(true, 2000, true, 1.0, true, true);
            assertThat(cfg.shouldSampleQuery()).isTrue();
        }

        @Test
        @DisplayName("shouldSampleQuery returns false when perQuery disabled")
        void sampleQueryDisabled() {
            var cfg = new CortexTelemetryConfig(true, 2000, false, 1.0, true, true);
            assertThat(cfg.shouldSampleQuery()).isFalse();
        }

        @Test
        @DisplayName("shouldSampleQuery returns false when rate is 0.0")
        void sampleQueryZeroRate() {
            var cfg = new CortexTelemetryConfig(true, 2000, true, 0.0, true, true);
            assertThat(cfg.shouldSampleQuery()).isFalse();
        }

        @Test
        @DisplayName("custom config construction")
        void customConfig() {
            var cfg = new CortexTelemetryConfig(false, 5000, true, 0.5, false, false);
            assertThat(cfg.enabled()).isFalse();
            assertThat(cfg.intervalMs()).isEqualTo(5000);
            assertThat(cfg.simdEnabled()).isFalse();
            assertThat(cfg.graphEnabled()).isFalse();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // PersistenceFiles
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PersistenceFiles")
    class PersistenceFilesTests {

        @Test
        @DisplayName("DEFAULTS have expected file names")
        void defaultNames() {
            var pf = PersistenceFiles.DEFAULTS;
            assertThat(pf.indexFile()).isEqualTo("index.spct");
            assertThat(pf.vectorsFile()).isEqualTo("vectors.mmap");
            assertThat(pf.documentsFile()).isEqualTo("documents.dat");
            assertThat(pf.idMappingsFile()).isEqualTo("id-mappings.dat");
            assertThat(pf.shardDirName()).isEqualTo("index_shards");
        }

        @Test
        @DisplayName("4-arg constructor uses default shard dir")
        void fourArgConstructor() {
            var pf = new PersistenceFiles("a.idx", "b.mmap", "c.dat", "d.dat");
            assertThat(pf.shardDirName()).isEqualTo("index_shards");
        }

        @Test
        @DisplayName("resolve methods build correct paths")
        void resolvePaths() {
            var pf = PersistenceFiles.DEFAULTS;
            var dir = Path.of("/data/spector");
            assertThat(pf.resolveIndex(dir)).isEqualTo(dir.resolve("index.spct"));
            assertThat(pf.resolveVectors(dir)).isEqualTo(dir.resolve("vectors.mmap"));
            assertThat(pf.resolveDocuments(dir)).isEqualTo(dir.resolve("documents.dat"));
            assertThat(pf.resolveIdMappings(dir)).isEqualTo(dir.resolve("id-mappings.dat"));
            assertThat(pf.resolveShardDir(dir)).isEqualTo(dir.resolve("index_shards"));
        }

        @Test
        @DisplayName("rejects blank indexFile")
        void rejectsBlankIndex() {
            assertThatThrownBy(() -> new PersistenceFiles("", "v", "d", "i", "s"))
                    .isInstanceOf(SpectorConfigValueException.class);
        }

        @Test
        @DisplayName("rejects null vectorsFile")
        void rejectsNullVectors() {
            assertThatThrownBy(() -> new PersistenceFiles("idx", null, "d", "i", "s"))
                    .isInstanceOf(SpectorConfigValueException.class);
        }

        @Test
        @DisplayName("rejects blank documentsFile")
        void rejectsBlankDocs() {
            assertThatThrownBy(() -> new PersistenceFiles("idx", "v", " ", "i", "s"))
                    .isInstanceOf(SpectorConfigValueException.class);
        }

        @Test
        @DisplayName("rejects blank idMappingsFile")
        void rejectsBlankMappings() {
            assertThatThrownBy(() -> new PersistenceFiles("idx", "v", "d", "", "s"))
                    .isInstanceOf(SpectorConfigValueException.class);
        }

        @Test
        @DisplayName("rejects blank shardDirName")
        void rejectsBlankShard() {
            assertThatThrownBy(() -> new PersistenceFiles("idx", "v", "d", "i", ""))
                    .isInstanceOf(SpectorConfigValueException.class);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // SpectorConfigException (deprecated but needs coverage)
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SpectorConfigException")
    @SuppressWarnings("deprecation")
    class ConfigExceptionTests {

        @Test
        @DisplayName("message-only constructor")
        void messageOnly() {
            var ex = new SpectorConfigException("bad config");
            assertThat(ex.getMessage()).isEqualTo("bad config");
            assertThat(ex.errorCode()).isNull();
        }

        @Test
        @DisplayName("message + cause constructor")
        void messageWithCause() {
            var cause = new RuntimeException("root");
            var ex = new SpectorConfigException("bad config", cause);
            assertThat(ex.getCause()).isEqualTo(cause);
            assertThat(ex.errorCode()).isNull();
        }

        @Test
        @DisplayName("ErrorCode constructor")
        void errorCodeConstructor() {
            var ex = new SpectorConfigException(
                    com.spectrayan.spector.commons.error.ErrorCode.CONFIG_FILE_NOT_FOUND, "test.prop");
            assertThat(ex.errorCode()).isNotNull();
            assertThat(ex.getMessage()).contains("test.prop");
        }

        @Test
        @DisplayName("ErrorCode + cause constructor")
        void errorCodeWithCause() {
            var cause = new RuntimeException("io");
            var ex = new SpectorConfigException(
                    com.spectrayan.spector.commons.error.ErrorCode.CONFIG_FILE_NOT_FOUND, cause, "test.prop");
            assertThat(ex.getCause()).isEqualTo(cause);
            assertThat(ex.errorCode()).isNotNull();
        }
    }
}
