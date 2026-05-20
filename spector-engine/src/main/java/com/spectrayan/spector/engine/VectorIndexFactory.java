package com.spectrayan.spector.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.core.QuantizationType;
import com.spectrayan.spector.index.HnswIndex;
import com.spectrayan.spector.index.QuantizedHnswIndex;
import com.spectrayan.spector.index.VectorIndex;
import com.spectrayan.spector.index.ivf.IvfPqIndex;

/**
 * Factory Method pattern for creating {@link VectorIndex} instances.
 *
 * <p>Centralizes the index creation logic that was previously inlined
 * in {@link SpectorEngine}'s constructor. New index types can be added
 * by extending this class or adding a case to the factory method —
 * without modifying the engine itself (Open/Closed Principle).</p>
 *
 * <h3>Supported Index Types</h3>
 * <ul>
 *   <li>{@link IndexType#HNSW} — Standard or quantized HNSW graph index</li>
 *   <li>{@link IndexType#IVF_PQ} — Inverted file with product quantization</li>
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
        SpectorConfig effectiveConfig = applyGpuFallbackIfNeeded(config);
        return switch (effectiveConfig.indexType()) {
            case HNSW -> createHnsw(effectiveConfig);
            case IVF_PQ -> createIvfPq(effectiveConfig);
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
    private VectorIndex createHnsw(SpectorConfig config) {
        QuantizationType qt = config.quantization();

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
            // NonUniformQuantizer will be injected after calibration during ingestion;
            // pass null here for lazy calibration (index will require quantizer before search)
            return new QuantizedHnswIndex(
                    config.dimensions(), config.capacity(),
                    config.similarityFunction(), config.hnswParams(),
                    null, qt, null, effectiveOversampling);
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
}
