package com.spectrayan.spector.index;

import com.spectrayan.spector.config.HnswParams;
import com.spectrayan.spector.config.IndexType;
import com.spectrayan.spector.config.SpectorConfig;
import com.spectrayan.spector.core.quantization.QuantizationType;
import com.spectrayan.spector.index.ivf.IvfPqIndex;
import com.spectrayan.spector.index.spectrum.SpectorIndex;
import com.spectrayan.spector.storage.VectorStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory Method pattern for creating {@link VectorIndex} instances.
 *
 * <p>Centralizes the index creation logic. New index types can be added
 * by extending this class or adding a case to the factory method —
 * without modifying the engine itself (Open/Closed Principle).</p>
 *
 * <h3>Supported Index Types</h3>
 * <ul>
 *   <li>{@link IndexType#HNSW} — Standard or quantized HNSW graph index</li>
 *   <li>{@link IndexType#IVF_PQ} — Inverted file with product quantization</li>
 *   <li>{@link IndexType#SPECTRUM} — Adaptive IVF + VASQ-HNSW hybrid index</li>
 * </ul>
 */
public class VectorIndexFactory {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexFactory.class);

    /**
     * Creates a {@link VectorIndex} based on the engine configuration.
     *
     * <p>If GPU is enabled with INT4 or INT2 quantization but the vector dimensions
     * are not a multiple of 32, GPU acceleration is disabled for this index and a
     * warning is logged. The index will fall back to CPU/SIMD computation.</p>
     *
     * @param config the engine configuration
     * @return a new, empty vector index
     */
    public VectorIndex create(SpectorConfig config) {
        return create(config, null);
    }

    /**
     * Creates a {@link VectorIndex} with an optional {@link VectorStore} backing.
     *
     * <p>When a VectorStore is provided, HNSW indexes will read vectors from it
     * during graph traversal instead of keeping heap-resident copies. This
     * eliminates O(capacity × dims × 4) bytes of heap overhead.</p>
     *
     * @param config      the engine configuration
     * @param vectorStore optional off-heap vector store (null = inline mode)
     * @return a new, empty vector index
     */
    public VectorIndex create(SpectorConfig config, VectorStore vectorStore) {
        SpectorConfig effectiveConfig = applyGpuFallbackIfNeeded(config);
        return switch (effectiveConfig.indexType()) {
            case HNSW -> createHnsw(effectiveConfig, vectorStore);
            case IVF_PQ -> createIvfPq(effectiveConfig);
            case SPECTRUM -> createSpectrum(effectiveConfig);
        };
    }

    /**
     * Checks whether GPU must be disabled due to non-aligned dimensions for INT4/INT2.
     *
     * <p>GPU-accelerated distance computation for INT4 and INT2 packed formats requires
     * vector dimensions to be a multiple of 32. When this alignment requirement is not met,
     * this method disables GPU and returns a modified config that falls back to CPU/SIMD.</p>
     *
     * @param config the original engine configuration
     * @return the config with GPU disabled if fallback is needed, otherwise the original config
     */
    SpectorConfig applyGpuFallbackIfNeeded(SpectorConfig config) {
        if (!config.gpuEnabled()) {
            return config;
        }

        QuantizationType quantization = config.quantization();
        if (quantization != QuantizationType.SCALAR_INT4 && quantization != QuantizationType.SCALAR_INT2) {
            return config;
        }

        if (config.dimensions() % 32 != 0) {
            log.warn("GPU acceleration disabled for {} quantization: vector dimensions {} "
                            + "are not a multiple of 32. Falling back to CPU/SIMD computation.",
                    quantization, config.dimensions());
            return config.withGpu(false);
        }

        return config;
    }

    /**
     * Creates an HNSW-based index, optionally with scalar quantization.
     */
    private VectorIndex createHnsw(SpectorConfig config, VectorStore vectorStore) {
        QuantizationType qt = config.quantization();

        if (qt == QuantizationType.VASQ) {
            int oversampling = config.effectiveOversamplingFactor();
            log.info("Creating QuantizedHnswIndex (VASQ): dims={}, capacity={}, oversampling={}",
                    config.dimensions(), config.capacity(), oversampling);
            return QuantizedHnswIndex.vasq(
                    config.dimensions(), config.capacity(),
                    config.similarityFunction(), config.hnswParams(), oversampling);
        }

        if (qt == QuantizationType.VASQ_4) {
            int oversampling = config.effectiveOversamplingFactor();
            log.info("Creating QuantizedHnswIndex (VASQ-4): dims={}, capacity={}, oversampling={}",
                    config.dimensions(), config.capacity(), oversampling);
            return QuantizedHnswIndex.vasq4(
                    config.dimensions(), config.capacity(),
                    config.similarityFunction(), config.hnswParams(), oversampling);
        }

        if (qt == QuantizationType.SCALAR_INT8) {
            log.info("Creating QuantizedHnswIndex (SQ8): dims={}, capacity={}",
                    config.dimensions(), config.capacity());
            return new QuantizedHnswIndex(
                    config.dimensions(), config.capacity(),
                    config.similarityFunction(), config.hnswParams());
        }

        if (qt == QuantizationType.SCALAR_INT4 || qt == QuantizationType.SCALAR_INT2) {
            int effectiveOversampling = config.effectiveOversamplingFactor();
            log.info("Creating QuantizedHnswIndex ({}): dims={}, capacity={}, oversampling={}",
                    qt, config.dimensions(), config.capacity(), effectiveOversampling);
            return new QuantizedHnswIndex(
                    config.dimensions(), config.capacity(),
                    config.similarityFunction(), config.hnswParams(),
                    null, qt, null, effectiveOversampling);
        }

        if (vectorStore != null) {
            log.info("Creating HnswIndex (store-backed): dims={}, capacity={}",
                    config.dimensions(), config.capacity());
            return new HnswIndex(
                    config.dimensions(), config.capacity(),
                    config.similarityFunction(), config.hnswParams(), vectorStore);
        }

        log.info("Creating HnswIndex: dims={}, capacity={}", config.dimensions(), config.capacity());
        return new HnswIndex(
                config.dimensions(), config.capacity(),
                config.similarityFunction(), config.hnswParams());
    }

    /**
     * Creates an IVF-PQ index (untrained — training happens during ingestion).
     */
    private VectorIndex createIvfPq(SpectorConfig config) {
        log.info("Creating IvfPqIndex: dims={}, nlist={}, nprobe={}, M={}",
                config.dimensions(), config.effectiveNlist(),
                config.effectiveNprobe(), config.effectivePqSubspaces());
        return new IvfPqIndex(
                config.dimensions(),
                config.effectiveNlist(),
                config.effectiveNprobe(),
                config.effectivePqSubspaces(),
                config.similarityFunction());
    }

    /**
     * Creates a Spectrum index (untrained — training happens during ingestion).
     *
     * <p>Spectrum is the adaptive IVF + VASQ-HNSW hybrid. It requires a training step
     * with representative vectors before use (like IVF-PQ). The engine's ingestion
     * pipeline should call {@link SpectorIndex#train(float[][])} before adding vectors.</p>
     */
    private VectorIndex createSpectrum(SpectorConfig config) {
        int nCentroids = config.effectiveSpectrumNCentroids();
        int nProbe = config.effectiveSpectrumNProbe();
        int shardThreshold = config.effectiveSpectrumShardThreshold();
        int oversampling = config.effectiveOversamplingFactor();

        log.info("Creating SpectorIndex (Spectrum): dims={}, nCentroids={}, nProbe={}, "
                        + "shardThreshold={}, oversampling={}",
                config.dimensions(), nCentroids, nProbe, shardThreshold, oversampling);

        return SpectorIndex.builder()
                .dimensions(config.dimensions())
                .nCentroids(nCentroids)
                .nProbe(nProbe)
                .shardThreshold(shardThreshold)
                .oversamplingFactor(oversampling)
                .similarityFunction(config.similarityFunction())
                .hnswParams(config.hnswParams())
                .build();
    }
}
