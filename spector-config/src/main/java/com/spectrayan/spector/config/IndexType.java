package com.spectrayan.spector.config;

/**
 * Selects the vector index implementation.
 *
 * <ul>
 *   <li>{@link #HNSW} — Default graph-based ANN index. Best for datasets up to ~5M vectors.</li>
 *   <li>{@link #IVF_PQ} — Inverted file with product quantization. Best for 1M+ vectors
 *       where memory is constrained. Requires a training step.</li>
 *   <li>{@link #SPECTRUM} — Adaptive IVF + VASQ-HNSW hybrid index ({@code SpectorIndex}).
 *       Combines IVF coarse routing, per-shard adaptive flat/HNSW search, and VASQ
 *       residual INT8 quantization. Best overall recall/throughput tradeoff for 100K–10M
 *       vectors. Requires a training step.</li>
 * </ul>
 */
public enum IndexType {

    /** HNSW (Hierarchical Navigable Small World) graph index. Default. */
    HNSW,

    /** IVF-PQ (Inverted File with Product Quantization) index. High compression. */
    IVF_PQ,

    /** Spectrum — Adaptive IVF + VASQ-HNSW hybrid index. Best recall/throughput. */
    SPECTRUM
}
