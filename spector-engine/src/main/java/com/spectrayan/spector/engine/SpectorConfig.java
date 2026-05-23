package com.spectrayan.spector.engine;

import java.nio.file.Path;

import com.spectrayan.spector.core.QuantizationType;
import com.spectrayan.spector.core.SimilarityFunction;
import com.spectrayan.spector.index.HnswParams;
import com.spectrayan.spector.storage.PersistenceMode;

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
 * @param gpuEnabled         whether to attempt GPU acceleration (auto-detects availability)
 * @param rerankerEnabled    whether to enable LLM re-ranking
 * @param rerankerOllamaUrl  Ollama server URL for re-ranking (e.g., "http://localhost:11434")
 * @param rerankerModel      Ollama model name for re-ranking (e.g., "llama3.2")
 * @param rerankerMaxCandidates max candidates to send to the LLM re-ranker
 * @param oversamplingFactor   rescore oversampling factor (0 = use default based on quantization type)
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
        int pqSubspaces,
        boolean gpuEnabled,
        boolean rerankerEnabled,
        String rerankerOllamaUrl,
        String rerankerModel,
        int rerankerMaxCandidates,
        int oversamplingFactor
) {
    /** Default: 384-dim embeddings, 100K capacity, cosine similarity, HNSW, no quantization, in-memory. */
    public static final SpectorConfig DEFAULT =
            new SpectorConfig(384, 100_000, SimilarityFunction.COSINE, HnswParams.DEFAULT,
                    QuantizationType.NONE, PersistenceMode.IN_MEMORY, null,
                    IndexType.HNSW, 0, 0, 0,
                    false, false, null, null, 20, 0);

    /** Backward-compatible constructor (HNSW, no quantization, in-memory). */
    public SpectorConfig(int dimensions, int capacity,
                          SimilarityFunction similarityFunction, HnswParams hnswParams) {
        this(dimensions, capacity, similarityFunction, hnswParams,
                QuantizationType.NONE, PersistenceMode.IN_MEMORY, null,
                IndexType.HNSW, 0, 0, 0,
                false, false, null, null, 20, 0);
    }

    /** Pre-quantization constructor (HNSW, in-memory). */
    public SpectorConfig(int dimensions, int capacity,
                          SimilarityFunction similarityFunction, HnswParams hnswParams,
                          QuantizationType quantization, PersistenceMode persistenceMode,
                          Path dataDirectory) {
        this(dimensions, capacity, similarityFunction, hnswParams,
                quantization, persistenceMode, dataDirectory,
                IndexType.HNSW, 0, 0, 0,
                false, false, null, null, 20, 0);
    }

    /** Pre-IVF-PQ constructor (no GPU, no reranker). */
    public SpectorConfig(int dimensions, int capacity,
                          SimilarityFunction similarityFunction, HnswParams hnswParams,
                          QuantizationType quantization, PersistenceMode persistenceMode,
                          Path dataDirectory, IndexType indexType,
                          int ivfNlist, int ivfNprobe, int pqSubspaces) {
        this(dimensions, capacity, similarityFunction, hnswParams,
                quantization, persistenceMode, dataDirectory,
                indexType, ivfNlist, ivfNprobe, pqSubspaces,
                false, false, null, null, 20, 0);
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
        if (rerankerEnabled && (rerankerOllamaUrl == null || rerankerOllamaUrl.isBlank())) {
            throw new IllegalArgumentException("rerankerOllamaUrl is required when reranker is enabled");
        }
        if (rerankerMaxCandidates <= 0) {
            rerankerMaxCandidates = 20;
        }
    }

    /** Builder-style with custom dimensions. */
    public SpectorConfig withDimensions(int dims) {
        return new SpectorConfig(dims, capacity, similarityFunction, hnswParams,
                quantization, persistenceMode, dataDirectory,
                indexType, ivfNlist, ivfNprobe, pqSubspaces,
                gpuEnabled, rerankerEnabled, rerankerOllamaUrl, rerankerModel, rerankerMaxCandidates,
                oversamplingFactor);
    }

    /** Builder-style with custom capacity. */
    public SpectorConfig withCapacity(int cap) {
        return new SpectorConfig(dimensions, cap, similarityFunction, hnswParams,
                quantization, persistenceMode, dataDirectory,
                indexType, ivfNlist, ivfNprobe, pqSubspaces,
                gpuEnabled, rerankerEnabled, rerankerOllamaUrl, rerankerModel, rerankerMaxCandidates,
                oversamplingFactor);
    }

    /** Builder-style with custom similarity function. */
    public SpectorConfig withSimilarityFunction(SimilarityFunction sf) {
        return new SpectorConfig(dimensions, capacity, sf, hnswParams,
                quantization, persistenceMode, dataDirectory,
                indexType, ivfNlist, ivfNprobe, pqSubspaces,
                gpuEnabled, rerankerEnabled, rerankerOllamaUrl, rerankerModel, rerankerMaxCandidates,
                oversamplingFactor);
    }

    /** Builder-style with quantization type. */
    public SpectorConfig withQuantization(QuantizationType qt) {
        return new SpectorConfig(dimensions, capacity, similarityFunction, hnswParams,
                qt, persistenceMode, dataDirectory,
                indexType, ivfNlist, ivfNprobe, pqSubspaces,
                gpuEnabled, rerankerEnabled, rerankerOllamaUrl, rerankerModel, rerankerMaxCandidates,
                oversamplingFactor);
    }

    /** Builder-style with persistence mode and data directory. */
    public SpectorConfig withPersistence(PersistenceMode mode, Path directory) {
        return new SpectorConfig(dimensions, capacity, similarityFunction, hnswParams,
                quantization, mode, directory,
                indexType, ivfNlist, ivfNprobe, pqSubspaces,
                gpuEnabled, rerankerEnabled, rerankerOllamaUrl, rerankerModel, rerankerMaxCandidates,
                oversamplingFactor);
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
                IndexType.IVF_PQ, nlist, nprobe, subspaces,
                gpuEnabled, rerankerEnabled, rerankerOllamaUrl, rerankerModel, rerankerMaxCandidates,
                oversamplingFactor);
    }

    /** Builder-style to switch to IVF-PQ index with auto parameters. */
    public SpectorConfig withIvfPq() {
        return withIvfPq(0, 0, 0);
    }

    /**
     * Builder-style to enable GPU acceleration.
     *
     * <p>When enabled, the engine will attempt to use CUDA GPU for batch
     * similarity computations. Automatically falls back to CPU SIMD if
     * no GPU is detected at runtime.</p>
     *
     * @param enabled true to enable GPU acceleration
     */
    public SpectorConfig withGpu(boolean enabled) {
        return new SpectorConfig(dimensions, capacity, similarityFunction, hnswParams,
                quantization, persistenceMode, dataDirectory,
                indexType, ivfNlist, ivfNprobe, pqSubspaces,
                enabled, rerankerEnabled, rerankerOllamaUrl, rerankerModel, rerankerMaxCandidates,
                oversamplingFactor);
    }

    /**
     * Builder-style to enable LLM re-ranking via Ollama.
     *
     * @param ollamaUrl     Ollama server URL (e.g., "http://localhost:11434")
     * @param model         model name (e.g., "llama3.2", "qwen2.5")
     * @param maxCandidates max candidates to send to the LLM (cost control)
     */
    public SpectorConfig withReranker(String ollamaUrl, String model, int maxCandidates) {
        return new SpectorConfig(dimensions, capacity, similarityFunction, hnswParams,
                quantization, persistenceMode, dataDirectory,
                indexType, ivfNlist, ivfNprobe, pqSubspaces,
                gpuEnabled, true, ollamaUrl, model, maxCandidates,
                oversamplingFactor);
    }

    /**
     * Builder-style to enable LLM re-ranking with default max candidates (20).
     *
     * @param ollamaUrl Ollama server URL
     * @param model     model name
     */
    public SpectorConfig withReranker(String ollamaUrl, String model) {
        return withReranker(ollamaUrl, model, 20);
    }

    /**
     * Builder-style to set the rescore oversampling factor.
     *
     * <p>The oversampling factor controls how many extra candidates are retrieved
     * from the quantized index before rescoring with exact distances. A factor of 3
     * means 3×K candidates are retrieved, then the top K are returned after rescoring.</p>
     *
     * @param oversamplingFactor positive integer (≥ 1); factor of 1 skips rescore
     * @throws IllegalArgumentException if oversamplingFactor < 1
     */
    public SpectorConfig withRescore(int oversamplingFactor) {
        if (oversamplingFactor < 1) {
            throw new IllegalArgumentException(
                    "oversamplingFactor must be >= 1, got: " + oversamplingFactor);
        }
        return new SpectorConfig(dimensions, capacity, similarityFunction, hnswParams,
                quantization, persistenceMode, dataDirectory,
                indexType, ivfNlist, ivfNprobe, pqSubspaces,
                gpuEnabled, rerankerEnabled, rerankerOllamaUrl, rerankerModel, rerankerMaxCandidates,
                oversamplingFactor);
    }

    /**
     * Returns the effective oversampling factor, applying defaults based on quantization type
     * when no explicit value has been set.
     *
     * <p>Defaults: INT4 → 3, INT2 → 5, all others → 1 (no oversampling).</p>
     */
    public int effectiveOversamplingFactor() {
        if (oversamplingFactor > 0) return oversamplingFactor;
        return switch (quantization) {
            case SCALAR_INT4, TURBO_QUANT -> 3;
            case SCALAR_INT2 -> 5;
            default -> 1;
        };
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
