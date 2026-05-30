package com.spectrayan.spector.config;

import java.nio.file.Path;

import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.core.quantization.QuantizationType;
import com.spectrayan.spector.core.similarity.SimilarityFunction;

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
 * @param indexType          vector index type (HNSW, IVF_PQ, or SPECTRUM)
 * @param ivfNlist           IVF cluster count (only for IVF_PQ)
 * @param ivfNprobe          IVF probe count during search (only for IVF_PQ)
 * @param pqSubspaces        PQ subspace count M (only for IVF_PQ, must divide dimensions)
 * @param gpuEnabled         whether to attempt GPU acceleration (auto-detects availability)
 * @param rerankerEnabled    whether to enable LLM re-ranking
 * @param rerankerOllamaUrl  Ollama server URL for re-ranking (e.g., "http://localhost:11434")
 * @param rerankerModel      Ollama model name for re-ranking (e.g., "llama3.2")
 * @param rerankerMaxCandidates max candidates to send to the LLM re-ranker
 * @param oversamplingFactor   rescore oversampling factor (0 = use default based on quantization type)
 * @param spectrumNCentroids number of IVF centroids for SPECTRUM index (0 = auto: 4×√capacity)
 * @param spectrumNProbe     number of centroids to probe at query time for SPECTRUM (0 = auto: 16)
 * @param spectrumShardThreshold shard size at which flat scan promotes to HNSW (0 = auto: 20000)
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
        int oversamplingFactor,
        int spectrumNCentroids,
        int spectrumNProbe,
        int spectrumShardThreshold
) {
    /** Default: 384-dim embeddings, 100K capacity, cosine similarity, HNSW, no quantization, in-memory. */
    public static final SpectorConfig DEFAULT =
            new SpectorConfig(384, 100_000, SimilarityFunction.COSINE, HnswParams.DEFAULT,
                    QuantizationType.NONE, PersistenceMode.IN_MEMORY, null,
                    IndexType.HNSW, 0, 0, 0,
                    false, false, null, null, 20, 0,
                    0, 0, 0);

    /**
     * Creates a {@link SpectorConfig} from hierarchical properties.
     *
     * <p>Reads all configuration values from the given {@link SpectorProperties},
     * falling back to defaults defined in {@code spector-defaults.yml}.</p>
     *
     * @param props the hierarchical properties
     * @return a fully configured SpectorConfig
     */
    public static SpectorConfig from(SpectorProperties props) {
        var engine = SpectorConfigFactory.engineDefaults(props);
        var hnsw = SpectorConfigFactory.hnswDefaults(props);
        var ivf = SpectorConfigFactory.ivfDefaults(props);
        var spectrum = SpectorConfigFactory.spectrumDefaults(props);
        var reranker = SpectorConfigFactory.rerankerDefaults(props);

        return new SpectorConfig(
                engine.dimensions(),
                engine.capacity(),
                SimilarityFunction.valueOf(engine.similarity()),
                new HnswParams(hnsw.m(), hnsw.efConstruction(), hnsw.efSearch()),
                QuantizationType.valueOf(engine.quantization()),
                PersistenceMode.valueOf(engine.persistenceMode()),
                "IN_MEMORY".equals(engine.persistenceMode()) ? null : engine.dataDirectory(),
                IndexType.valueOf(engine.indexType()),
                ivf.nlist(),
                ivf.nprobe(),
                ivf.pqSubspaces(),
                engine.gpuEnabled(),
                reranker.enabled(),
                reranker.ollamaUrl(),
                reranker.model(),
                reranker.maxCandidates(),
                engine.oversamplingFactor(),
                spectrum.nCentroids(),
                spectrum.nProbe(),
                spectrum.shardThreshold()
        );
    }

    /** Backward-compatible constructor (HNSW, no quantization, in-memory). */
    public SpectorConfig(int dimensions, int capacity,
                          SimilarityFunction similarityFunction, HnswParams hnswParams) {
        this(dimensions, capacity, similarityFunction, hnswParams,
                QuantizationType.NONE, PersistenceMode.IN_MEMORY, null,
                IndexType.HNSW, 0, 0, 0,
                false, false, null, null, 20, 0,
                0, 0, 0);
    }

    /** Pre-quantization constructor (HNSW, in-memory). */
    public SpectorConfig(int dimensions, int capacity,
                          SimilarityFunction similarityFunction, HnswParams hnswParams,
                          QuantizationType quantization, PersistenceMode persistenceMode,
                          Path dataDirectory) {
        this(dimensions, capacity, similarityFunction, hnswParams,
                quantization, persistenceMode, dataDirectory,
                IndexType.HNSW, 0, 0, 0,
                false, false, null, null, 20, 0,
                0, 0, 0);
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
                false, false, null, null, 20, 0,
                0, 0, 0);
    }

    public SpectorConfig {
        if (dimensions <= 0) throw new SpectorConfigException(ErrorCode.CONFIG_VALUE_INVALID, "dimensions", dimensions + " (must be positive)");
        if (capacity <= 0) throw new SpectorConfigException(ErrorCode.CONFIG_VALUE_INVALID, "capacity", capacity + " (must be positive)");
        if (persistenceMode == PersistenceMode.DISK && dataDirectory == null) {
            throw new SpectorConfigException(ErrorCode.CONFIG_REQUIRED_MISSING, "dataDirectory (required for DISK persistence)");
        }
        if (indexType == IndexType.IVF_PQ && pqSubspaces > 0 && dimensions % pqSubspaces != 0) {
            throw new SpectorConfigException(ErrorCode.CONFIG_VALUE_INVALID,
                    "pqSubspaces", pqSubspaces + " (must divide dimensions=" + dimensions + ")");
        }
        if (rerankerEnabled && (rerankerOllamaUrl == null || rerankerOllamaUrl.isBlank())) {
            throw new SpectorConfigException(ErrorCode.CONFIG_REQUIRED_MISSING, "rerankerOllamaUrl (required when reranker is enabled)");
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
                oversamplingFactor, spectrumNCentroids, spectrumNProbe, spectrumShardThreshold);
    }

    /** Builder-style with custom capacity. */
    public SpectorConfig withCapacity(int cap) {
        return new SpectorConfig(dimensions, cap, similarityFunction, hnswParams,
                quantization, persistenceMode, dataDirectory,
                indexType, ivfNlist, ivfNprobe, pqSubspaces,
                gpuEnabled, rerankerEnabled, rerankerOllamaUrl, rerankerModel, rerankerMaxCandidates,
                oversamplingFactor, spectrumNCentroids, spectrumNProbe, spectrumShardThreshold);
    }

    /** Builder-style with custom similarity function. */
    public SpectorConfig withSimilarityFunction(SimilarityFunction sf) {
        return new SpectorConfig(dimensions, capacity, sf, hnswParams,
                quantization, persistenceMode, dataDirectory,
                indexType, ivfNlist, ivfNprobe, pqSubspaces,
                gpuEnabled, rerankerEnabled, rerankerOllamaUrl, rerankerModel, rerankerMaxCandidates,
                oversamplingFactor, spectrumNCentroids, spectrumNProbe, spectrumShardThreshold);
    }

    /** Builder-style with quantization type. */
    public SpectorConfig withQuantization(QuantizationType qt) {
        return new SpectorConfig(dimensions, capacity, similarityFunction, hnswParams,
                qt, persistenceMode, dataDirectory,
                indexType, ivfNlist, ivfNprobe, pqSubspaces,
                gpuEnabled, rerankerEnabled, rerankerOllamaUrl, rerankerModel, rerankerMaxCandidates,
                oversamplingFactor, spectrumNCentroids, spectrumNProbe, spectrumShardThreshold);
    }

    /** Builder-style with persistence mode and data directory. */
    public SpectorConfig withPersistence(PersistenceMode mode, Path directory) {
        return new SpectorConfig(dimensions, capacity, similarityFunction, hnswParams,
                quantization, mode, directory,
                indexType, ivfNlist, ivfNprobe, pqSubspaces,
                gpuEnabled, rerankerEnabled, rerankerOllamaUrl, rerankerModel, rerankerMaxCandidates,
                oversamplingFactor, spectrumNCentroids, spectrumNProbe, spectrumShardThreshold);
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
                oversamplingFactor, 0, 0, 0);
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
                oversamplingFactor, spectrumNCentroids, spectrumNProbe, spectrumShardThreshold);
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
                oversamplingFactor, spectrumNCentroids, spectrumNProbe, spectrumShardThreshold);
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
     * Builder-style to enable VASQ (FWHT-rotated INT8) quantization.
     *
     * <p>VASQ applies a random Walsh-Hadamard Transform before INT8 quantization to
     * isotropize the per-dimension variance distribution, reducing quantization error.
     * The oversampling factor controls how many extra candidates are retrieved before
     * exact-float rescoring (3 is a good default for ≥ 90% recall@10).</p>
     *
     * @param oversamplingFactor rescore oversampling factor (≥ 1; 3 recommended)
     */
    public SpectorConfig withVasq(int oversamplingFactor) {
        return new SpectorConfig(dimensions, capacity, similarityFunction, hnswParams,
                QuantizationType.VASQ, persistenceMode, dataDirectory,
                indexType, ivfNlist, ivfNprobe, pqSubspaces,
                gpuEnabled, rerankerEnabled, rerankerOllamaUrl, rerankerModel, rerankerMaxCandidates,
                Math.max(1, oversamplingFactor),
                spectrumNCentroids, spectrumNProbe, spectrumShardThreshold);
    }

    /** Builder-style to enable VASQ with the default oversampling factor (3). */
    public SpectorConfig withVasq() {
        return withVasq(3);
    }

    /**
     * Builder-style to enable VASQ-4 (FWHT-rotated INT4, nibble-packed) quantization.
     *
     * <p>VASQ-4 provides ~2× additional compression over VASQ-8 at the cost of slightly
     * lower fidelity. With oversampling rescore, recall@10 is typically 97–99%.</p>
     *
     * @param oversamplingFactor rescore oversampling factor (≥ 1; 3 recommended)
     */
    public SpectorConfig withVasq4(int oversamplingFactor) {
        return new SpectorConfig(dimensions, capacity, similarityFunction, hnswParams,
                QuantizationType.VASQ_4, persistenceMode, dataDirectory,
                indexType, ivfNlist, ivfNprobe, pqSubspaces,
                gpuEnabled, rerankerEnabled, rerankerOllamaUrl, rerankerModel, rerankerMaxCandidates,
                Math.max(1, oversamplingFactor),
                spectrumNCentroids, spectrumNProbe, spectrumShardThreshold);
    }

    /** Builder-style to enable VASQ-4 with the default oversampling factor (3). */
    public SpectorConfig withVasq4() {
        return withVasq4(3);
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
            throw new SpectorConfigException(ErrorCode.CONFIG_VALUE_INVALID,
                    "oversamplingFactor", oversamplingFactor + " (must be >= 1)");
        }
        return new SpectorConfig(dimensions, capacity, similarityFunction, hnswParams,
                quantization, persistenceMode, dataDirectory,
                indexType, ivfNlist, ivfNprobe, pqSubspaces,
                gpuEnabled, rerankerEnabled, rerankerOllamaUrl, rerankerModel, rerankerMaxCandidates,
                oversamplingFactor, spectrumNCentroids, spectrumNProbe, spectrumShardThreshold);
    }

    /**
     * Builder-style to switch to SPECTRUM index.
     *
     * <p>Spectrum combines IVF coarse routing, adaptive flat→HNSW per-shard search,
     * and VASQ residual INT8 quantization. Parameters control the IVF structure and
     * the promotion threshold for flat→HNSW transition.</p>
     *
     * @param nCentroids     number of IVF centroids (0 = auto: 4×√capacity)
     * @param nProbe         centroids to probe at query time (0 = auto: 16)
     * @param shardThreshold shard size to trigger HNSW promotion (0 = auto: 20000)
     */
    public SpectorConfig withSpectrum(int nCentroids, int nProbe, int shardThreshold) {
        return new SpectorConfig(dimensions, capacity, similarityFunction, hnswParams,
                quantization, persistenceMode, dataDirectory,
                IndexType.SPECTRUM, ivfNlist, ivfNprobe, pqSubspaces,
                gpuEnabled, rerankerEnabled, rerankerOllamaUrl, rerankerModel, rerankerMaxCandidates,
                oversamplingFactor, nCentroids, nProbe, shardThreshold);
    }

    /** Builder-style to switch to SPECTRUM index with auto parameters. */
    public SpectorConfig withSpectrum() {
        return withSpectrum(0, 0, 0);
    }

    /**
     * Returns the effective oversampling factor, applying defaults based on quantization type
     * when no explicit value has been set.
     *
     * <p>Defaults: INT4 → 3, INT2 → 5, VASQ → 3 (FWHT rotation improves recall enough
     * that moderate oversampling achieves ≥ 90% recall@10), all others → 1 (no oversampling).</p>
     */
    public int effectiveOversamplingFactor() {
        if (oversamplingFactor > 0) return oversamplingFactor;
        return switch (quantization) {
            case SCALAR_INT4, TURBO_QUANT, VASQ, VASQ_4 -> 3;
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

    // ─────────────── Spectrum computed defaults ───────────────

    /** Effective Spectrum nCentroids (auto = 4×√capacity, clamped to [16, capacity/10]). */
    public int effectiveSpectrumNCentroids() {
        if (spectrumNCentroids > 0) return spectrumNCentroids;
        int auto = (int) (4 * Math.sqrt(capacity));
        return Math.max(16, Math.min(auto, capacity / 10));
    }

    /** Effective Spectrum nProbe (auto = 16). */
    public int effectiveSpectrumNProbe() {
        return spectrumNProbe > 0 ? spectrumNProbe : 16;
    }

    /** Effective Spectrum shard threshold (auto = 20000). */
    public int effectiveSpectrumShardThreshold() {
        return spectrumShardThreshold > 0 ? spectrumShardThreshold : 20_000;
    }

    // ─────────────── Index/Vector sharding defaults ───────────────

    /**
     * Default number of nodes per shard for index and vector file sharding.
     * At 384 dimensions × 4 bytes, this yields ~30 MB vector data per shard.
     */
    public static final int DEFAULT_NODES_PER_SHARD = 50_000;

    /**
     * Returns the effective nodes-per-shard for index and vector file sharding.
     *
     * <p>Currently returns the constant default ({@value #DEFAULT_NODES_PER_SHARD}).
     * This can be extended to read from configuration properties if needed.</p>
     *
     * @return nodes per shard
     */
    public int effectiveNodesPerShard() {
        return DEFAULT_NODES_PER_SHARD;
    }
}
