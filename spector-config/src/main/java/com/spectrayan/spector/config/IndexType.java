/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.spectrayan.spector.config;

/**
 * Selects the vector index implementation.
 *
 * <ul>
 *   <li>{@link #HNSW} — Default graph-based ANN index. Best for datasets up to ~5M vectors.</li>
 *   <li>{@link #IVF_PQ} — Inverted file with product quantization. Best for 1M+ vectors
 *       where memory is constrained. Requires a training step.</li>
 *   <li>{@link #SPECTRUM} — Adaptive IVF + SVASQ-HNSW hybrid index ({@code SpectorIndex}).
 *       Combines IVF coarse routing, per-shard adaptive flat/HNSW search, and SVASQ
 *       residual INT8 quantization. Best overall recall/throughput tradeoff for 100K–10M
 *       vectors. Requires a training step.</li>
 * </ul>
 */
public enum IndexType {

    /** HNSW (Hierarchical Navigable Small World) graph index. Default. */
    HNSW,

    /** IVF-PQ (Inverted File with Product Quantization) index. High compression. */
    IVF_PQ,

    /** Spectrum — Adaptive IVF + SVASQ-HNSW hybrid index. Best recall/throughput. */
    SPECTRUM
}
