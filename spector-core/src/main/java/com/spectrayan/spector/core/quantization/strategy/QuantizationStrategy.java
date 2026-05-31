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
package com.spectrayan.spector.core.quantization.strategy;

import java.lang.foreign.MemorySegment;

import com.spectrayan.spector.commons.error.SpectorException;

/**
 * Strategy interface for vector quantization operations.
 *
 * <p>This is the core SPI (Service Provider Interface) of the Strategy + Abstract Factory
 * design pattern refactor. Each implementation encapsulates a complete quantization
 * scheme: encoding, decoding, and asymmetric distance computation.</p>
 *
 * <h3>Design</h3>
 * <ul>
 *   <li><b>Open/Closed:</b> Adding a new quantization type (INT16, FP8, BFloat16) requires
 *       only a new implementation of this interface. {@code QuantizedVectorStore} and
 *       {@code QuantizedHnswIndex} never need to change.</li>
 *   <li><b>Asymmetric Distance:</b> {@link #prepareQueryContext(float[])} is called
 *       <em>once per search</em>. The returned {@link DistanceContext} is passed into
 *       {@link #distance} for every candidate, amortizing per-query computation
 *       (e.g., FWHT rotation, scale pre-multiplication) across all comparisons.</li>
 *   <li><b>Off-heap:</b> {@link #encode} and {@link #distance} operate directly on
 *       {@link MemorySegment}, enabling zero-copy hot paths without GC pressure.</li>
 * </ul>
 *
 * <h3>Implementations</h3>
 * <ul>
 *   <li>{@link Int8Strategy} — linear INT8 quantization via {@code ScalarQuantizer}</li>
 *   <li>{@link Int4Strategy} — nibble-packed INT4 via {@code NonUniformQuantizer}</li>
 *   <li>{@link Int2Strategy} — crumb-packed INT2 via {@code NonUniformQuantizer}</li>
 *   <li>{@link TurboQuantStrategy} — turbo quantization via {@code TurboQuantizer}</li>
 *   <li>{@link SvasqStrategy} — FWHT-rotated INT8 with Panama SIMD kernel</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>All implementations are immutable after construction and safe for concurrent
 * {@link #encode} and {@link #distance} calls. {@link #prepareQueryContext} returns
 * a new per-call object; callers are responsible for thread-local usage.</p>
 */
public interface QuantizationStrategy {

    /**
     * Encodes a float32 vector and writes the result directly into an off-heap segment.
     *
     * <p>The segment must have at least {@code offset + bytesPerVector()} bytes available
     * starting at {@code offset}.</p>
     *
     * @param vector  float32 input vector (length must equal the strategy's dimension)
     * @param segment off-heap target memory segment
     * @param offset  byte offset within the segment for this vector
     */
    void encode(float[] vector, MemorySegment segment, long offset);

    /**
     * Decodes an approximation of the original float32 vector from an off-heap segment.
     *
     * @param segment    off-heap segment containing the encoded vector
     * @param offset     byte offset of the encoded vector
     * @param dimensions original vector dimensionality
     * @return approximate float32 reconstruction
     */
    float[] decode(MemorySegment segment, long offset, int dimensions);

    /**
     * Computes the approximate distance between a stored (quantized) vector and
     * a pre-prepared query context.
     *
     * <p>{@code ctx} must be the result of {@link #prepareQueryContext(float[])}
     * called with the same query for this search traversal.</p>
     *
     * @param segment  off-heap segment containing the encoded candidate vector
     * @param offset   byte offset of the candidate vector within the segment
     * @param ctx      pre-computed query context from {@link #prepareQueryContext}
     * @return approximate distance (interpretation depends on similarity function)
     */
    float distance(MemorySegment segment, long offset, DistanceContext ctx);

    /**
     * Prepares a per-query distance context to be reused for all candidates in a
     * single search traversal.
     *
     * <p>This is the "prepare-once, evaluate-N-times" step. For SVASQ, this performs
     * the O(D log D) FWHT rotation and scale pre-multiplication. For INT8, it
     * captures the query vector reference. For packed strategies, it resolves the
     * global centroid table.</p>
     *
     * <p>The returned context must not be shared across concurrent searches.</p>
     *
     * @param query float32 query vector
     * @return an immutable per-query context for this strategy type
     */
    DistanceContext prepareQueryContext(float[] query);

    /**
     * Returns the number of bytes this strategy uses per stored vector.
     *
     * @return bytes per vector
     */
    int bytesPerVector();

    /**
     * Returns the approximate compression factor relative to float32 storage
     * (for logging). A value of 4 means the strategy uses 4× less memory than float32.
     *
     * @param dimensions vector dimensionality
     * @return compression factor (≥ 1)
     */
    int compressionFactor(int dimensions);
}
