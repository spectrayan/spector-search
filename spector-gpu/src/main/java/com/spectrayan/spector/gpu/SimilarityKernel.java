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
package com.spectrayan.spector.gpu;

/**
 * Interface for batch similarity computation kernels.
 *
 * <p>Implementations may use GPU (CUDA), CPU SIMD (Java Vector API), or other
 * acceleration. The interface provides a uniform contract for computing
 * similarity between a query vector and a batch of database vectors.</p>
 *
 * @see CudaDotProductKernel
 */
public interface SimilarityKernel {

    /**
     * Returns the name of this kernel (e.g., "dot-product", "cosine").
     *
     * @return the kernel name
     */
    String name();

    /**
     * Computes similarity between a query vector and a batch of database vectors.
     *
     * @param query      the query vector of length {@code dimensions}
     * @param database   the database vectors as a flat array of {@code numVectors × dimensions} floats
     * @param numVectors number of database vectors (batch size)
     * @param dimensions vector dimensionality (must be a multiple of 32, range 32–2048)
     * @return array of {@code numVectors} similarity scores
     * @throws SpectorValidationException if dimensions or batch size are invalid
     */
    float[] compute(float[] query, float[] database, int numVectors, int dimensions);

    /**
     * Returns whether this kernel is actively using GPU acceleration.
     *
     * @return true if GPU is being used, false if falling back to CPU SIMD
     */
    boolean isGpuActive();
}
