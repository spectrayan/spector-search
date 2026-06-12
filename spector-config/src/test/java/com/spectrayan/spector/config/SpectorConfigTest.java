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
import com.spectrayan.spector.core.quantization.QuantizationType;
import com.spectrayan.spector.core.similarity.SimilarityFunction;

/**
 * Tests for {@link SpectorConfig}, {@link HnswParams}, {@link SpectorMode},
 * {@link IndexType}, and {@link PersistenceMode}.
 */
@DisplayName("SpectorConfig")
class SpectorConfigTest {

    // ══════════════════════════════════════════════════════════════
    // DEFAULT
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DEFAULT config")
    class DefaultConfig {

        @Test
        @DisplayName("has sensible defaults")
        void hasSensibleDefaults() {
            var cfg = SpectorConfig.DEFAULT;
            assertThat(cfg.dimensions()).isEqualTo(384);
            assertThat(cfg.capacity()).isEqualTo(100_000);
            assertThat(cfg.similarityFunction()).isEqualTo(SimilarityFunction.COSINE);
            assertThat(cfg.quantization()).isEqualTo(QuantizationType.NONE);
            assertThat(cfg.persistenceMode()).isEqualTo(PersistenceMode.IN_MEMORY);
            assertThat(cfg.indexType()).isEqualTo(IndexType.HNSW);
            assertThat(cfg.gpuEnabled()).isFalse();
            assertThat(cfg.rerankerEnabled()).isFalse();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Validation
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("rejects zero dimensions")
        void rejectsZeroDimensions() {
            assertThatThrownBy(() -> SpectorConfig.DEFAULT.withDimensions(0))
                    .isInstanceOf(SpectorConfigValueException.class);
        }

        @Test
        @DisplayName("rejects negative dimensions")
        void rejectsNegativeDimensions() {
            assertThatThrownBy(() -> SpectorConfig.DEFAULT.withDimensions(-1))
                    .isInstanceOf(SpectorConfigValueException.class);
        }

        @Test
        @DisplayName("rejects zero capacity")
        void rejectsZeroCapacity() {
            assertThatThrownBy(() -> SpectorConfig.DEFAULT.withCapacity(0))
                    .isInstanceOf(SpectorConfigValueException.class);
        }

        @Test
        @DisplayName("rejects DISK persistence without data directory")
        void rejectsDiskWithoutDirectory() {
            assertThatThrownBy(() -> SpectorConfig.DEFAULT.withPersistence(PersistenceMode.DISK, null))
                    .isInstanceOf(SpectorConfigValueException.class);
        }

        @Test
        @DisplayName("accepts DISK persistence with data directory")
        void acceptsDiskWithDirectory() {
            var cfg = SpectorConfig.DEFAULT.withPersistence(PersistenceMode.DISK, Path.of("/tmp/data"));
            assertThat(cfg.persistenceMode()).isEqualTo(PersistenceMode.DISK);
            assertThat(cfg.dataDirectory()).isEqualTo(Path.of("/tmp/data"));
        }

        @Test
        @DisplayName("rejects reranker without URL")
        void rejectsRerankerWithoutUrl() {
            assertThatThrownBy(() -> SpectorConfig.DEFAULT.withReranker(null, "model", 20))
                    .isInstanceOf(SpectorConfigValueException.class);
        }

        @Test
        @DisplayName("rejects IVF_PQ with invalid pqSubspaces")
        void rejectsInvalidPqSubspaces() {
            // 384 % 7 != 0
            assertThatThrownBy(() -> SpectorConfig.DEFAULT.withIvfPq(100, 10, 7))
                    .isInstanceOf(SpectorConfigValueException.class);
        }

        @Test
        @DisplayName("accepts valid IVF_PQ subspaces")
        void acceptsValidPqSubspaces() {
            // 384 % 48 == 0
            var cfg = SpectorConfig.DEFAULT.withIvfPq(100, 10, 48);
            assertThat(cfg.indexType()).isEqualTo(IndexType.IVF_PQ);
            assertThat(cfg.pqSubspaces()).isEqualTo(48);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Builder-style methods
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("builder-style methods")
    class BuilderStyle {

        @Test
        @DisplayName("withDimensions creates copy with new dims")
        void withDimensions() {
            var cfg = SpectorConfig.DEFAULT.withDimensions(768);
            assertThat(cfg.dimensions()).isEqualTo(768);
            assertThat(cfg.capacity()).isEqualTo(SpectorConfig.DEFAULT.capacity());
        }

        @Test
        @DisplayName("withCapacity creates copy with new capacity")
        void withCapacity() {
            var cfg = SpectorConfig.DEFAULT.withCapacity(1_000_000);
            assertThat(cfg.capacity()).isEqualTo(1_000_000);
        }

        @Test
        @DisplayName("withSimilarityFunction changes metric")
        void withSimilarityFunction() {
            var cfg = SpectorConfig.DEFAULT.withSimilarityFunction(SimilarityFunction.DOT_PRODUCT);
            assertThat(cfg.similarityFunction()).isEqualTo(SimilarityFunction.DOT_PRODUCT);
        }

        @Test
        @DisplayName("withQuantization changes quantization type")
        void withQuantization() {
            var cfg = SpectorConfig.DEFAULT.withQuantization(QuantizationType.SCALAR_INT8);
            assertThat(cfg.quantization()).isEqualTo(QuantizationType.SCALAR_INT8);
        }

        @Test
        @DisplayName("withGpu enables GPU")
        void withGpu() {
            var cfg = SpectorConfig.DEFAULT.withGpu(true);
            assertThat(cfg.gpuEnabled()).isTrue();
        }

        @Test
        @DisplayName("withSvasq enables SVASQ quantization")
        void withSvasq() {
            var cfg = SpectorConfig.DEFAULT.withSvasq();
            assertThat(cfg.quantization()).isEqualTo(QuantizationType.SVASQ);
            assertThat(cfg.oversamplingFactor()).isEqualTo(3);
        }

        @Test
        @DisplayName("withSvasq4 enables SVASQ_4 quantization")
        void withSvasq4() {
            var cfg = SpectorConfig.DEFAULT.withSvasq4(5);
            assertThat(cfg.quantization()).isEqualTo(QuantizationType.SVASQ_4);
            assertThat(cfg.oversamplingFactor()).isEqualTo(5);
        }

        @Test
        @DisplayName("withSpectrum enables SPECTRUM index")
        void withSpectrum() {
            var cfg = SpectorConfig.DEFAULT.withSpectrum(64, 8, 10000);
            assertThat(cfg.indexType()).isEqualTo(IndexType.SPECTRUM);
            assertThat(cfg.spectrumNCentroids()).isEqualTo(64);
        }

        @Test
        @DisplayName("withReranker enables reranker")
        void withReranker() {
            var cfg = SpectorConfig.DEFAULT.withReranker("http://localhost:11434", "llama3.2");
            assertThat(cfg.rerankerEnabled()).isTrue();
            assertThat(cfg.rerankerModel()).isEqualTo("llama3.2");
        }

        @Test
        @DisplayName("withRescore sets oversampling factor")
        void withRescore() {
            var cfg = SpectorConfig.DEFAULT.withRescore(5);
            assertThat(cfg.oversamplingFactor()).isEqualTo(5);
        }

        @Test
        @DisplayName("withRescore rejects factor < 1")
        void withRescoreRejectsInvalid() {
            assertThatThrownBy(() -> SpectorConfig.DEFAULT.withRescore(0))
                    .isInstanceOf(SpectorConfigValueException.class);
        }

        @Test
        @DisplayName("withNodesPerShard sets custom shard size")
        void withNodesPerShard() {
            var cfg = SpectorConfig.DEFAULT.withNodesPerShard(5000);
            assertThat(cfg.effectiveNodesPerShard()).isEqualTo(5000);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Computed defaults
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("computed defaults")
    class ComputedDefaults {

        @Test
        @DisplayName("effectiveOversamplingFactor returns explicit when set")
        void explicitOversampling() {
            var cfg = SpectorConfig.DEFAULT.withRescore(7);
            assertThat(cfg.effectiveOversamplingFactor()).isEqualTo(7);
        }

        @Test
        @DisplayName("effectiveOversamplingFactor returns 1 for NONE quantization")
        void noneQuantizationOversampling() {
            assertThat(SpectorConfig.DEFAULT.effectiveOversamplingFactor()).isEqualTo(1);
        }

        @Test
        @DisplayName("effectiveNlist returns auto √capacity")
        void effectiveNlistAuto() {
            int expected = Math.max(16, (int) Math.sqrt(100_000));
            assertThat(SpectorConfig.DEFAULT.effectiveNlist()).isEqualTo(expected);
        }

        @Test
        @DisplayName("effectiveNprobe returns 10 when not set")
        void effectiveNprobeDefault() {
            assertThat(SpectorConfig.DEFAULT.effectiveNprobe()).isEqualTo(10);
        }

        @Test
        @DisplayName("effectivePqSubspaces returns dims/8 when not set")
        void effectivePqSubspacesAuto() {
            assertThat(SpectorConfig.DEFAULT.effectivePqSubspaces()).isEqualTo(384 / 8);
        }

        @Test
        @DisplayName("effectiveNodesPerShard returns DEFAULT when not set")
        void effectiveNodesPerShardDefault() {
            assertThat(SpectorConfig.DEFAULT.effectiveNodesPerShard()).isEqualTo(SpectorConfig.DEFAULT_NODES_PER_SHARD);
        }

        @Test
        @DisplayName("effectiveSpectrumNCentroids auto-computes")
        void effectiveSpectrumNCentroidsAuto() {
            assertThat(SpectorConfig.DEFAULT.effectiveSpectrumNCentroids()).isGreaterThanOrEqualTo(16);
        }

        @Test
        @DisplayName("effectiveSpectrumNProbe defaults to 16")
        void effectiveSpectrumNProbeDefault() {
            assertThat(SpectorConfig.DEFAULT.effectiveSpectrumNProbe()).isEqualTo(16);
        }

        @Test
        @DisplayName("effectiveSpectrumShardThreshold defaults to 20000")
        void effectiveSpectrumShardThresholdDefault() {
            assertThat(SpectorConfig.DEFAULT.effectiveSpectrumShardThreshold()).isEqualTo(20_000);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // HnswParams
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("HnswParams")
    class HnswParamsTests {

        @Test
        @DisplayName("DEFAULT has sensible values")
        void defaultValues() {
            assertThat(HnswParams.DEFAULT.m()).isEqualTo(16);
            assertThat(HnswParams.DEFAULT.efConstruction()).isEqualTo(200);
            assertThat(HnswParams.DEFAULT.efSearch()).isEqualTo(50);
            assertThat(HnswParams.DEFAULT.maxLevel0Connections()).isEqualTo(32); // 2 × m
            assertThat(HnswParams.DEFAULT.levelMultiplier()).isCloseTo(1.0 / Math.log(16), within(0.001));
        }

        @Test
        @DisplayName("compact constructor computes level-0 and multiplier")
        void compactConstructor() {
            var p = new HnswParams(8, 100, 25);
            assertThat(p.maxLevel0Connections()).isEqualTo(16); // 2 × 8
            assertThat(p.levelMultiplier()).isCloseTo(1.0 / Math.log(8), within(0.001));
        }

        @Test
        @DisplayName("rejects m < 2")
        void rejectsTooSmallM() {
            assertThatThrownBy(() -> new HnswParams(1, 100, 50))
                    .isInstanceOf(SpectorConfigValueException.class);
        }

        @Test
        @DisplayName("rejects efConstruction < 1")
        void rejectsInvalidEfConstruction() {
            assertThatThrownBy(() -> new HnswParams(16, 0, 50))
                    .isInstanceOf(SpectorConfigValueException.class);
        }

        @Test
        @DisplayName("rejects efSearch < 1")
        void rejectsInvalidEfSearch() {
            assertThatThrownBy(() -> new HnswParams(16, 200, 0))
                    .isInstanceOf(SpectorConfigValueException.class);
        }

        @Test
        @DisplayName("withEfSearch creates copy")
        void withEfSearch() {
            var p = HnswParams.DEFAULT.withEfSearch(200);
            assertThat(p.efSearch()).isEqualTo(200);
            assertThat(p.m()).isEqualTo(HnswParams.DEFAULT.m()); // unchanged
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Enums
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SpectorMode")
    class SpectorModeTests {

        @Test
        @DisplayName("SEARCH enables engine only")
        void searchMode() {
            assertThat(SpectorMode.SEARCH.engineEnabled()).isTrue();
            assertThat(SpectorMode.SEARCH.memoryEnabled()).isFalse();
        }

        @Test
        @DisplayName("MEMORY enables memory only")
        void memoryMode() {
            assertThat(SpectorMode.MEMORY.engineEnabled()).isFalse();
            assertThat(SpectorMode.MEMORY.memoryEnabled()).isTrue();
        }

        @Test
        @DisplayName("HYBRID enables both")
        void hybridMode() {
            assertThat(SpectorMode.HYBRID.engineEnabled()).isTrue();
            assertThat(SpectorMode.HYBRID.memoryEnabled()).isTrue();
        }

        @Test
        @DisplayName("all enum values exist")
        void allValues() {
            assertThat(SpectorMode.values()).hasSize(3);
        }
    }

    @Nested
    @DisplayName("IndexType")
    class IndexTypeTests {
        @Test
        @DisplayName("has all expected values")
        void allValues() {
            assertThat(IndexType.values()).containsExactlyInAnyOrder(
                    IndexType.HNSW, IndexType.IVF_PQ, IndexType.SPECTRUM);
        }
    }

    @Nested
    @DisplayName("PersistenceMode")
    class PersistenceModeTests {
        @Test
        @DisplayName("has IN_MEMORY and DISK")
        void allValues() {
            assertThat(PersistenceMode.values()).containsExactlyInAnyOrder(
                    PersistenceMode.IN_MEMORY, PersistenceMode.DISK);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Convenience constructors
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("convenience constructors")
    class ConvenienceConstructors {

        @Test
        @DisplayName("4-arg constructor sets HNSW + NONE + IN_MEMORY")
        void fourArgConstructor() {
            var cfg = new SpectorConfig(128, 10_000, SimilarityFunction.EUCLIDEAN, HnswParams.DEFAULT);
            assertThat(cfg.dimensions()).isEqualTo(128);
            assertThat(cfg.quantization()).isEqualTo(QuantizationType.NONE);
            assertThat(cfg.persistenceMode()).isEqualTo(PersistenceMode.IN_MEMORY);
            assertThat(cfg.indexType()).isEqualTo(IndexType.HNSW);
        }

        @Test
        @DisplayName("7-arg constructor sets HNSW + custom persistence")
        void sevenArgConstructor() {
            var cfg = new SpectorConfig(256, 50_000, SimilarityFunction.COSINE, HnswParams.DEFAULT,
                    QuantizationType.SCALAR_INT8, PersistenceMode.DISK, Path.of("/data"));
            assertThat(cfg.dimensions()).isEqualTo(256);
            assertThat(cfg.quantization()).isEqualTo(QuantizationType.SCALAR_INT8);
            assertThat(cfg.persistenceMode()).isEqualTo(PersistenceMode.DISK);
        }
    }
}
