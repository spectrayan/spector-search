package com.spectrayan.spector.engine;

/**
 * Selects the vector index implementation.
 *
 * <ul>
 *   <li>{@link #HNSW} — Default graph-based ANN index. Best for datasets up to ~5M vectors.</li>
 *   <li>{@link #IVF_PQ} — Inverted file with product quantization. Best for 1M+ vectors
 *       where memory is constrained. Requires a training step.</li>
 * </ul>
 */
public enum IndexType {

    /** HNSW (Hierarchical Navigable Small World) graph index. Default. */
    HNSW,

    /** IVF-PQ (Inverted File with Product Quantization) index. High compression. */
    IVF_PQ
}
