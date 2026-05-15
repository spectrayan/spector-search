package com.spectrayan.spector.engine;

import com.spectrayan.spector.core.QuantizationType;
import com.spectrayan.spector.core.SimilarityFunction;
import com.spectrayan.spector.index.HnswParams;
import com.spectrayan.spector.storage.PersistenceMode;

import java.nio.file.Path;

/**
 * Immutable configuration for a Spector Search engine instance.
 *
 * @param dimensions         vector dimensionality
 * @param capacity           max number of documents
 * @param similarityFunction distance/similarity metric for vectors
 * @param hnswParams         HNSW index tuning parameters
 * @param quantization       vector quantization strategy
 * @param persistenceMode    storage persistence mode
 * @param dataDirectory      directory for persistent index files (null for in-memory)
 * @param indexType          vector index type (HNSW or IVF_PQ)
 * @param ivfNlist           IVF cluster count (only for IVF_PQ)
 * @param ivfNprobe          IVF probe count during search (only for IVF_PQ)
 * @param pqSubspaces        PQ subspace count M (only for IVF_PQ, must divide dimensions)
 */
public record SpectorConfig(
        int dimensions,
        int capacity,
        SimilarityFunction similarityFunction,
        HnswParams hnswParams,
        QuantizationType quantization,
        PersistenceMode persistenceMode,
        Path dataDirectory,
        IndexType indexType,
        int ivfNlist,
        int ivfNprobe,
        int pqSubspaces
) {
    /** Default: 384-dim embeddings, 100K capacity, cosine similarity, HNSW, no quantization, in-memory. */
    public static final SpectorConfig DEFAULT =
            new SpectorConfig(384, 100_000, SimilarityFunction.COSINE, HnswParams.DEFAULT,
                    QuantizationType.NONE, PersistenceMode.IN_MEMORY, null,
                    IndexType.HNSW, 0, 0, 0);

    /** Backward-compatible constructor (HNSW, no quantization, in-memory). */
    public SpectorConfig(int dimensions, int capacity,
                          SimilarityFunction similarityFunction, HnswParams hnswParams) {
        this(dimensions, capacity, similarityFunction, hnswParams,
                QuantizationType.NONE, PersistenceMode.IN_MEMORY, null,
                IndexType.HNSW, 0, 0, 0);
    }

    /** Pre-quantization constructor (HNSW, in-memory). */
    public SpectorConfig(int dimensions, int capacity,
                          SimilarityFunction similarityFunction, HnswParams hnswParams,
                          QuantizationType quantization, PersistenceMode persistenceMode,
                          Path dataDirectory) {
        this(dimensions, capacity, similarityFunction, hnswParams,
                quantization, persistenceMode, dataDirectory,
                IndexType.HNSW, 0, 0, 0);
    }

    public SpectorConfig {
        if (dimensions <= 0) throw new IllegalArgumentException("dimensions must be positive");
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        if (persistenceMode == PersistenceMode.DISK && dataDirectory == null) {
            throw new IllegalArgumentException("dataDirectory required for DISK persistence");
        }
        if (indexType == IndexType.IVF_PQ && pqSubspaces > 0 && dimensions % pqSubspaces != 0) {
            throw new IllegalArgumentException(
                    "dimensions (" + dimensions + ") must be divisible by pqSubspaces (" + pqSubspaces + ")");
        }
    }

    /** Builder-style with custom dimensions. */
    public SpectorConfig withDimensions(int dims) {
        return new SpectorConfig(dims, capacity, similarityFunction, hnswParams,
                quantization, persistenceMode, dataDirectory,
                indexType, ivfNlist, ivfNprobe, pqSubspaces);
    }

    /** Builder-style with custom capacity. */
    public SpectorConfig withCapacity(int cap) {
        return new SpectorConfig(dimensions, cap, similarityFunction, hnswParams,
                quantization, persistenceMode, dataDirectory,
                indexType, ivfNlist, ivfNprobe, pqSubspaces);
    }

    /** Builder-style with custom similarity function. */
    public SpectorConfig withSimilarityFunction(SimilarityFunction sf) {
        return new SpectorConfig(dimensions, capacity, sf, hnswParams,
                quantization, persistenceMode, dataDirectory,
                indexType, ivfNlist, ivfNprobe, pqSubspaces);
    }

    /** Builder-style with quantization type. */
    public SpectorConfig withQuantization(QuantizationType qt) {
        return new SpectorConfig(dimensions, capacity, similarityFunction, hnswParams,
                qt, persistenceMode, dataDirectory,
                indexType, ivfNlist, ivfNprobe, pqSubspaces);
    }

    /** Builder-style with persistence mode and data directory. */
    public SpectorConfig withPersistence(PersistenceMode mode, Path directory) {
        return new SpectorConfig(dimensions, capacity, similarityFunction, hnswParams,
                quantization, mode, directory,
                indexType, ivfNlist, ivfNprobe, pqSubspaces);
    }

    /**
     * Builder-style to switch to IVF-PQ index.
     *
     * @param nlist       number of IVF clusters (0 = auto: √capacity)
     * @param nprobe      clusters to search (0 = auto: 10)
     * @param subspaces   PQ subspaces M (0 = auto: dims/8)
     */
    public SpectorConfig withIvfPq(int nlist, int nprobe, int subspaces) {
        return new SpectorConfig(dimensions, capacity, similarityFunction, hnswParams,
                quantization, persistenceMode, dataDirectory,
                IndexType.IVF_PQ, nlist, nprobe, subspaces);
    }

    /** Builder-style to switch to IVF-PQ index with auto parameters. */
    public SpectorConfig withIvfPq() {
        return withIvfPq(0, 0, 0);
    }

    // ─────────────── IVF-PQ computed defaults ───────────────

    /** Effective nlist (auto = √capacity). */
    public int effectiveNlist() {
        if (ivfNlist > 0) return ivfNlist;
        return Math.max(16, (int) Math.sqrt(capacity));
    }

    /** Effective nprobe (auto = 10). */
    public int effectiveNprobe() {
        return ivfNprobe > 0 ? ivfNprobe : 10;
    }

    /** Effective PQ subspaces (auto = dims/8, min 4). */
    public int effectivePqSubspaces() {
        if (pqSubspaces > 0) return pqSubspaces;
        return Math.max(4, dimensions / 8);
    }
}
