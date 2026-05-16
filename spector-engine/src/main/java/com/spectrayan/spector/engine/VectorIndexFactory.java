package com.spectrayan.spector.engine;

import com.spectrayan.spector.core.QuantizationType;
import com.spectrayan.spector.index.HnswIndex;
import com.spectrayan.spector.index.QuantizedHnswIndex;
import com.spectrayan.spector.index.VectorIndex;
import com.spectrayan.spector.index.ivf.IvfPqIndex;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
     * @param config the engine configuration
     * @return a new, empty vector index
     */
    public VectorIndex create(SpectorConfig config) {
        return switch (config.indexType()) {
            case HNSW -> createHnsw(config);
            case IVF_PQ -> createIvfPq(config);
        };
    }

    /**
     * Creates an HNSW-based index, optionally with scalar quantization.
     */
    private VectorIndex createHnsw(SpectorConfig config) {
        if (config.quantization() == QuantizationType.SCALAR_INT8) {
            log.info("Creating QuantizedHnswIndex (SQ8): dims={}, capacity={}",
                    config.dimensions(), config.capacity());
            return new QuantizedHnswIndex(
                    config.dimensions(), config.capacity(),
                    config.similarityFunction(), config.hnswParams());
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
