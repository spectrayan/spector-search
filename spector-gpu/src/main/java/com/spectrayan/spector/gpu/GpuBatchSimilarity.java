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

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import com.spectrayan.spector.commons.error.SpectorServerException;
import com.spectrayan.spector.commons.error.SpectorGpuException;
import com.spectrayan.spector.storage.error.SpectorSegmentClosedException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * GPU-accelerated batch similarity computation via CUDA.
 *
 * <p>Provides batch cosine similarity and dot product computation by
 * uploading vectors to GPU device memory and executing CUDA kernels.
 * Falls back to CPU SIMD when CUDA is not available.</p>
 *
 * <h3>When GPU Helps</h3>
 * <ul>
 *   <li>IVF coarse search: brute-force scan over cluster centroids</li>
 *   <li>Re-ranking: computing exact distances for 100s-1000s of candidates</li>
 *   <li>Batch ingestion: parallel distance computation during HNSW construction</li>
 * </ul>
 *
 * <h3>When GPU Does NOT Help</h3>
 * <ul>
 *   <li>HNSW graph traversal: inherently sequential, random-access pattern</li>
 *   <li>Small datasets (&lt;10K vectors): CPU SIMD is fast enough</li>
 * </ul>
 *
 * @see GpuCapability
 */
public final class GpuBatchSimilarity implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GpuBatchSimilarity.class);

    /** Preferred SIMD vector species for this hardware (AVX-512 = 16 floats, AVX2 = 8). */
    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    private final Arena arena;
    private final SymbolLookup cudaLib;
    private final Linker linker;

    // CUDA handles
    private final MemorySegment cuContext;
    private final CudaKernelLauncher kernelLauncher; // actual GPU compute

    // Method handles for CUDA driver API
    private final MethodHandle cuMemAlloc;
    private final MethodHandle cuMemcpyHtoD;
    private final MethodHandle cuMemcpyDtoH;
    private final MethodHandle cuMemFree;

    private volatile boolean closed;

    /**
     * Creates a GPU batch similarity engine.
     *
     * @throws SpectorGpuException if CUDA is not available
     */
    public GpuBatchSimilarity() {
        if (!GpuCapability.isAvailable()) {
            throw new SpectorServerException(ErrorCode.GPU_NOT_AVAILABLE);
        }

        this.arena = Arena.ofShared();
        this.linker = Linker.nativeLinker();
        this.closed = false;

        try {
            String libName = System.getProperty("os.name").toLowerCase().contains("win")
                    ? "nvcuda" : "cuda";
            this.cudaLib = SymbolLookup.libraryLookup(libName, arena);

            // Create CUDA context on device 0
            MemorySegment ctxPtr = arena.allocate(ValueLayout.ADDRESS);
            MethodHandle cuCtxCreate = linker.downcallHandle(
                    cudaLib.find("cuCtxCreate_v2").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            int result = (int) cuCtxCreate.invoke(ctxPtr, 0, 0);
            if (result != 0) {
                throw new SpectorGpuException(ErrorCode.GPU_DEVICE_ERROR, "cuCtxCreate failed", result);
            }
            this.cuContext = ctxPtr.get(ValueLayout.ADDRESS, 0);

            // Cache common method handles
            this.cuMemAlloc = linker.downcallHandle(
                    cudaLib.find("cuMemAlloc_v2").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

            this.cuMemcpyHtoD = linker.downcallHandle(
                    cudaLib.find("cuMemcpyHtoD_v2").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

            this.cuMemcpyDtoH = linker.downcallHandle(
                    cudaLib.find("cuMemcpyDtoH_v2").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));

            this.cuMemFree = linker.downcallHandle(
                    cudaLib.find("cuMemFree_v2").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));

            log.info("GpuBatchSimilarity initialized: {}", GpuCapability.detect().report());

        } catch (Throwable e) {
            throw new SpectorServerException(ErrorCode.INTERNAL_ERROR, e, "Failed to initialize CUDA context");
        }

        // Initialize kernel launcher for actual GPU compute
        CudaKernelLauncher launcher = null;
        try {
            launcher = new CudaKernelLauncher();
        } catch (Exception e) {
            log.warn("CUDA kernel launcher init failed, will use CPU SIMD fallback: {}",
                    e.getMessage());
            log.debug("CUDA kernel launcher failure details", e);
        }
        this.kernelLauncher = launcher;
    }

    /**
     * Computes batch dot products between a query vector and a matrix of database vectors.
     *
     * <p>Uses SIMD (Java Vector API) to process multiple dimensions per clock cycle.
     * Each database vector's dot product is computed in a single pass with FMA operations.</p>
     *
     * @param query    the query vector (length D)
     * @param database the database vectors (N × D), stored as flat array [N*D]
     * @param n        number of database vectors
     * @param dims     vector dimensionality
     * @return array of N dot product scores
     */
    public float[] batchDotProduct(float[] query, float[] database, int n, int dims) {
        ensureOpen();
        if (n == 0) return new float[0];

        float[] results = new float[n];
        int vectorLen = SPECIES.length();
        int simdBound = dims - (dims % vectorLen);

        for (int i = 0; i < n; i++) {
            int offset = i * dims;
            FloatVector sumVec = FloatVector.zero(SPECIES);
            int d = 0;

            // SIMD loop — process vectorLen floats per iteration
            for (; d < simdBound; d += vectorLen) {
                FloatVector qVec = FloatVector.fromArray(SPECIES, query, d);
                FloatVector dbVec = FloatVector.fromArray(SPECIES, database, offset + d);
                sumVec = qVec.fma(dbVec, sumVec); // fused multiply-add
            }
            float dot = sumVec.reduceLanes(VectorOperators.ADD);

            // Scalar tail
            for (; d < dims; d++) {
                dot += query[d] * database[offset + d];
            }
            results[i] = dot;
        }
        return results;
    }

    /**
     * Computes batch cosine similarities between a query and database vectors.
     *
     * <p>For large batches (≥64 vectors), dispatches to the GPU CUDA kernel.
     * For small batches or when the kernel is unavailable, uses CPU SIMD.</p>
     *
     * @param query    the query vector (length D)
     * @param database the database vectors (N × D), stored as flat array [N*D]
     * @param n        number of database vectors
     * @param dims     vector dimensionality
     * @return array of N cosine similarity scores
     */
    public float[] batchCosineSimilarity(float[] query, float[] database, int n, int dims) {
        ensureOpen();
        if (n == 0) return new float[0];

        // Use GPU for very large batches where compute dominates transfer overhead
        // At 384-dim, breakeven is ~10K+ vectors due to PCIe transfer cost
        if (kernelLauncher != null && n >= 10_000) {
            try {
                return kernelLauncher.batchCosine(query, database, n, dims);
            } catch (Exception e) {
                log.debug("GPU kernel failed, falling back to CPU SIMD: {}", e.getMessage());
            }
        }

        // CPU SIMD path (small batches or fallback)
        return batchCosineSimilarityCpu(query, database, n, dims);
    }

    /**
     * CPU SIMD implementation of batch cosine similarity.
     */
    private float[] batchCosineSimilarityCpu(float[] query, float[] database, int n, int dims) {
        ensureOpen();
        if (n == 0) return new float[0];

        int vectorLen = SPECIES.length();
        int simdBound = dims - (dims % vectorLen);

        // ── Pass 1: Precompute query norm (single SIMD pass, amortized over N vectors) ──
        FloatVector qNormVec = FloatVector.zero(SPECIES);
        int d = 0;
        for (; d < simdBound; d += vectorLen) {
            FloatVector qVec = FloatVector.fromArray(SPECIES, query, d);
            qNormVec = qVec.fma(qVec, qNormVec);
        }
        float queryNormSq = qNormVec.reduceLanes(VectorOperators.ADD);
        for (; d < dims; d++) queryNormSq += query[d] * query[d];
        float queryNorm = (float) Math.sqrt(queryNormSq);

        if (queryNorm == 0) return new float[n]; // all zeros

        // ── Pass 2: Fused dot-product + doc-norm per database vector (single SIMD pass each) ──
        float[] results = new float[n];
        for (int i = 0; i < n; i++) {
            int offset = i * dims;
            FloatVector dotVec = FloatVector.zero(SPECIES);
            FloatVector normVec = FloatVector.zero(SPECIES);

            d = 0;
            for (; d < simdBound; d += vectorLen) {
                FloatVector qVec = FloatVector.fromArray(SPECIES, query, d);
                FloatVector dbVec = FloatVector.fromArray(SPECIES, database, offset + d);
                dotVec = qVec.fma(dbVec, dotVec);    // dot += q[d] * db[d]
                normVec = dbVec.fma(dbVec, normVec);  // norm += db[d]²
            }

            float dot = dotVec.reduceLanes(VectorOperators.ADD);
            float docNormSq = normVec.reduceLanes(VectorOperators.ADD);

            // Scalar tail
            for (; d < dims; d++) {
                dot += query[d] * database[offset + d];
                docNormSq += database[offset + d] * database[offset + d];
            }

            float docNorm = (float) Math.sqrt(docNormSq);
            results[i] = docNorm > 0 ? dot / (queryNorm * docNorm) : 0;
        }
        return results;
    }

    /**
     * Allocates device memory.
     *
     * @param bytes number of bytes to allocate
     * @return device pointer (as long)
     */
    public long deviceMalloc(long bytes) {
        ensureOpen();
        try (var localArena = Arena.ofConfined()) {
            MemorySegment ptrHolder = localArena.allocate(ValueLayout.JAVA_LONG);
            int result = (int) cuMemAlloc.invoke(ptrHolder, bytes);
            if (result != 0) {
                throw new SpectorGpuException(ErrorCode.GPU_DEVICE_ERROR, "cuMemAlloc failed", result);
            }
            return ptrHolder.get(ValueLayout.JAVA_LONG, 0);
        } catch (Throwable e) {
            throw new SpectorGpuException(ErrorCode.GPU_MEMORY_ALLOC_FAILED, e, 0);
        }
    }

    /**
     * Frees device memory.
     *
     * @param devicePtr device pointer from {@link #deviceMalloc}
     */
    public void deviceFree(long devicePtr) {
        ensureOpen();
        try {
            cuMemFree.invoke(devicePtr);
        } catch (Throwable e) {
            log.warn("cuMemFree failed", e);
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            try {
                if (kernelLauncher != null) kernelLauncher.close();
                // Destroy CUDA context
                MethodHandle cuCtxDestroy = linker.downcallHandle(
                        cudaLib.find("cuCtxDestroy_v2").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                cuCtxDestroy.invoke(cuContext);
                arena.close();
                log.info("GpuBatchSimilarity closed");
            } catch (Throwable e) {
                log.warn("Error closing GPU context", e);
            }
        }
    }

    private void ensureOpen() {
        if (closed) throw new SpectorSegmentClosedException();
    }
}
