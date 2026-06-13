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
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.SpectorGpuException;
import com.spectrayan.spector.storage.error.SpectorSegmentClosedException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * CUDA-accelerated distance computation for HNSW candidate evaluation.
 *
 * <p>Optimized for the HNSW access pattern: small batches of 10–200 candidate
 * neighbors, repeated many times during graph traversal. Provides both cosine
 * similarity and L2 squared distance kernels.</p>
 *
 * <h3>When to Use GPU vs CPU SIMD</h3>
 * <ul>
 *   <li>GPU: candidate batch ≥ 32 vectors AND high-dimensional (≥256)</li>
 *   <li>CPU SIMD: small batches or low dimensionality</li>
 * </ul>
 *
 * <h3>Fallback Behavior</h3>
 * <p>If GPU is unavailable or a CUDA error occurs, transparently falls back to
 * CPU SIMD using the Java Vector API. Never throws to callers.</p>
 *
 * @see SimilarityKernel
 * @see GpuCapability
 */
public final class CudaHnswKernel implements SimilarityKernel, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CudaHnswKernel.class);
    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    /** Minimum candidates to route to GPU (below this, CPU SIMD is faster). */
    private static final int GPU_MIN_CANDIDATES = 32;

    /** CUDA threads per block. */
    private static final int THREADS_PER_BLOCK = 256;

    /** Minimum supported dimensions. */
    private static final int MIN_DIMENSIONS = 32;

    /** Maximum supported dimensions. */
    private static final int MAX_DIMENSIONS = 2048;

    private final boolean gpuAvailable;
    private Arena arena;
    private SymbolLookup cudaLib;
    private Linker linker;
    private MemorySegment cuModule;
    private MemorySegment cosineFunction;
    private MemorySegment l2Function;

    // Cached method handles
    private MethodHandle cuMemAlloc;
    private MethodHandle cuMemcpyHtoD;
    private MethodHandle cuMemcpyDtoH;
    private MethodHandle cuMemFree;
    private MethodHandle cuLaunchKernel;
    private MethodHandle cuCtxSynchronize;

    private volatile boolean closed;

    /**
     * Creates a CUDA HNSW kernel.
     *
     * <p>Loads the {@code hnsw_candidates.ptx} resource and initializes
     * both cosine and L2 kernel functions. Falls back to CPU SIMD if
     * GPU is unavailable.</p>
     */
    public CudaHnswKernel() {
        this(true);
    }

    /**
     * Creates a CUDA HNSW kernel with optional GPU usage.
     *
     * @param useGpu if false, forces CPU SIMD fallback
     */
    public CudaHnswKernel(boolean useGpu) {
        this.closed = false;
        this.gpuAvailable = useGpu && initGpu();

        if (gpuAvailable) {
            log.info("CudaHnswKernel initialized with GPU acceleration: {}",
                    GpuCapability.detect().report());
        } else {
            log.info("CudaHnswKernel initialized in CPU SIMD fallback mode");
        }
    }

    /**
     * Computes cosine similarity between query and candidate vectors.
     * This is the default metric used by {@link #compute}.
     */
    @Override
    public float[] compute(float[] query, float[] database, int numVectors, int dimensions) {
        return computeCosine(query, database, numVectors, dimensions);
    }

    /**
     * Computes cosine similarity between a query and HNSW candidate vectors.
     *
     * @param query      the query vector (length D)
     * @param candidates the candidate vectors as a flat array (K × D)
     * @param k          number of candidates
     * @param dimensions vector dimensionality
     * @return array of K cosine similarity scores
     */
    public float[] computeCosine(float[] query, float[] candidates, int k, int dimensions) {
        ensureOpen();
        validateInputs(query, candidates, k, dimensions);
        if (k == 0) return new float[0];

        if (gpuAvailable && k >= GPU_MIN_CANDIDATES) {
            try {
                return computeGpu(query, candidates, k, dimensions, true);
            } catch (Exception e) {
                log.debug("GPU HNSW cosine failed, CPU fallback: {}", e.getMessage());
            }
        }
        return computeCosineCpu(query, candidates, k, dimensions);
    }

    /**
     * Computes L2 squared distance between a query and HNSW candidate vectors.
     *
     * @param query      the query vector (length D)
     * @param candidates the candidate vectors as a flat array (K × D)
     * @param k          number of candidates
     * @param dimensions vector dimensionality
     * @return array of K L2 squared distances
     */
    public float[] computeL2(float[] query, float[] candidates, int k, int dimensions) {
        ensureOpen();
        validateInputs(query, candidates, k, dimensions);
        if (k == 0) return new float[0];

        if (gpuAvailable && k >= GPU_MIN_CANDIDATES) {
            try {
                return computeGpu(query, candidates, k, dimensions, false);
            } catch (Exception e) {
                log.debug("GPU HNSW L2 failed, CPU fallback: {}", e.getMessage());
            }
        }
        return computeL2Cpu(query, candidates, k, dimensions);
    }

    @Override
    public String name() {
        return "hnsw-candidates";
    }

    @Override
    public boolean isGpuActive() {
        return gpuAvailable && !closed;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            if (gpuAvailable && arena != null) {
                try {
                    if (cuModule != null) {
                        MethodHandle cuModuleUnload = linker.downcallHandle(
                                cudaLib.find("cuModuleUnload").orElseThrow(),
                                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                        cuModuleUnload.invoke(cuModule);
                    }
                } catch (Throwable e) {
                    log.warn("Error unloading HNSW CUDA module", e);
                }
                arena.close();
                log.info("CudaHnswKernel closed");
            }
        }
    }

    // ── GPU Initialization ──────────────────────────────────────────────────────

    private boolean initGpu() {
        if (!GpuCapability.isAvailable()) {
            return false;
        }

        try {
            this.arena = Arena.ofShared();
            this.linker = Linker.nativeLinker();

            String libName = System.getProperty("os.name").toLowerCase().contains("win")
                    ? "nvcuda" : "cuda";
            this.cudaLib = SymbolLookup.libraryLookup(libName, arena);

            // Load PTX module
            String ptx = loadPtxResource();
            if (ptx == null) {
                log.warn("HNSW PTX resource not found, falling back to CPU");
                arena.close();
                return false;
            }

            MemorySegment modulePtr = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment ptxData = arena.allocateFrom(ptx);
            MethodHandle cuModuleLoadData = linker.downcallHandle(
                    cudaLib.find("cuModuleLoadData").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            int result = (int) cuModuleLoadData.invoke(modulePtr, ptxData);
            if (result != 0) {
                log.warn("cuModuleLoadData failed (HNSW): {}, falling back to CPU", result);
                arena.close();
                return false;
            }
            this.cuModule = modulePtr.get(ValueLayout.ADDRESS, 0);

            // Get cosine function
            this.cosineFunction = getFunction("hnsw_cosine_candidates");
            if (this.cosineFunction == null) {
                // Fallback: try batch_cosine from batch_similarity module
                this.cosineFunction = getFunction("batch_cosine");
            }

            // Get L2 function
            this.l2Function = getFunction("hnsw_l2_candidates");
            if (this.l2Function == null) {
                this.l2Function = getFunction("batch_l2");
            }

            if (this.cosineFunction == null && this.l2Function == null) {
                log.warn("No HNSW kernel functions found, falling back to CPU");
                arena.close();
                return false;
            }

            // Cache method handles
            cacheMethodHandles();
            return true;

        } catch (Throwable e) {
            log.warn("HNSW GPU init failed: {}, falling back to CPU", e.getMessage());
            if (arena != null) {
                try { arena.close(); } catch (Exception ignored) {}
            }
            return false;
        }
    }

    private MemorySegment getFunction(String name) {
        try {
            MemorySegment funcPtr = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment nameStr = arena.allocateFrom(name);
            MethodHandle cuModuleGetFunction = linker.downcallHandle(
                    cudaLib.find("cuModuleGetFunction").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            int result = (int) cuModuleGetFunction.invoke(funcPtr, cuModule, nameStr);
            if (result != 0) return null;
            return funcPtr.get(ValueLayout.ADDRESS, 0);
        } catch (Throwable e) {
            return null;
        }
    }

    private void cacheMethodHandles() throws Throwable {
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
        this.cuLaunchKernel = linker.downcallHandle(
                cudaLib.find("cuLaunchKernel").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS));
        this.cuCtxSynchronize = linker.downcallHandle(
                cudaLib.find("cuCtxSynchronize").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
    }

    private String loadPtxResource() {
        try (var is = getClass().getResourceAsStream("/cuda/hnsw_candidates.ptx")) {
            if (is != null) {
                return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("Failed to load hnsw_candidates.ptx: {}", e.getMessage());
        }
        // Fallback to batch_similarity.ptx (has batch_cosine and batch_l2)
        try (var is = getClass().getResourceAsStream("/cuda/batch_similarity.ptx")) {
            if (is != null) {
                return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("Failed to load batch_similarity.ptx: {}", e.getMessage());
        }
        return null;
    }

    // ── GPU Execution ───────────────────────────────────────────────────────────

    private float[] computeGpu(float[] query, float[] candidates, int k, int dimensions,
                                boolean cosine) throws Exception {
        MemorySegment function = cosine ? cosineFunction : l2Function;
        if (function == null) {
            throw new SpectorGpuException(ErrorCode.GPU_DEVICE_ERROR,
                    (cosine ? "cosine" : "l2") + " function not loaded", 0);
        }

        long queryBytes = (long) dimensions * Float.BYTES;
        long candidateBytes = (long) k * dimensions * Float.BYTES;
        long resultBytes = (long) k * Float.BYTES;
        int sharedMemBytes = THREADS_PER_BLOCK * Float.BYTES * (cosine ? 3 : 1);

        List<Long> devicePtrs = new ArrayList<>(3);

        try (var localArena = Arena.ofConfined()) {
            long dQuery = deviceAlloc(queryBytes, localArena);
            devicePtrs.add(dQuery);
            long dCandidates = deviceAlloc(candidateBytes, localArena);
            devicePtrs.add(dCandidates);
            long dResults = deviceAlloc(resultBytes, localArena);
            devicePtrs.add(dResults);

            // Upload query and candidates
            MemorySegment querySegment = localArena.allocateFrom(ValueLayout.JAVA_FLOAT, query);
            checkCuda((int) cuMemcpyHtoD.invoke(dQuery, querySegment, queryBytes), "query HtoD");

            MemorySegment candidateSegment = localArena.allocateFrom(ValueLayout.JAVA_FLOAT, candidates);
            checkCuda((int) cuMemcpyHtoD.invoke(dCandidates, candidateSegment, candidateBytes), "candidates HtoD");

            // Set up kernel params
            MemorySegment pQuery = localArena.allocate(ValueLayout.JAVA_LONG);
            pQuery.set(ValueLayout.JAVA_LONG, 0, dQuery);
            MemorySegment pCandidates = localArena.allocate(ValueLayout.JAVA_LONG);
            pCandidates.set(ValueLayout.JAVA_LONG, 0, dCandidates);
            MemorySegment pResults = localArena.allocate(ValueLayout.JAVA_LONG);
            pResults.set(ValueLayout.JAVA_LONG, 0, dResults);
            MemorySegment pK = localArena.allocate(ValueLayout.JAVA_INT);
            pK.set(ValueLayout.JAVA_INT, 0, k);
            MemorySegment pD = localArena.allocate(ValueLayout.JAVA_INT);
            pD.set(ValueLayout.JAVA_INT, 0, dimensions);

            MemorySegment kernelParams = localArena.allocate(ValueLayout.ADDRESS, 5);
            kernelParams.setAtIndex(ValueLayout.ADDRESS, 0, pQuery);
            kernelParams.setAtIndex(ValueLayout.ADDRESS, 1, pCandidates);
            kernelParams.setAtIndex(ValueLayout.ADDRESS, 2, pResults);
            kernelParams.setAtIndex(ValueLayout.ADDRESS, 3, pK);
            kernelParams.setAtIndex(ValueLayout.ADDRESS, 4, pD);

            int blockDim = Math.min(dimensions, THREADS_PER_BLOCK);
            checkCuda((int) cuLaunchKernel.invoke(
                    function,
                    k, 1, 1,            // grid: one block per candidate
                    blockDim, 1, 1,     // block
                    sharedMemBytes,
                    MemorySegment.NULL,
                    kernelParams,
                    MemorySegment.NULL), "kernel launch");

            checkCuda((int) cuCtxSynchronize.invoke(), "sync");

            // Download results
            MemorySegment resultSegment = localArena.allocate(ValueLayout.JAVA_FLOAT, k);
            checkCuda((int) cuMemcpyDtoH.invoke(resultSegment, dResults, resultBytes), "DtoH");

            float[] results = new float[k];
            MemorySegment.copy(resultSegment, ValueLayout.JAVA_FLOAT, 0, results, 0, k);

            freeDeviceMemory(devicePtrs);
            return results;

        } catch (Throwable e) {
            freeDeviceMemory(devicePtrs);
            if (e instanceof Exception ex) throw ex;
            throw new SpectorGpuException(ErrorCode.GPU_DEVICE_ERROR, e, "HNSW kernel failed", 0);
        }
    }

    private long deviceAlloc(long bytes, Arena localArena) throws Throwable {
        MemorySegment ptrHolder = localArena.allocate(ValueLayout.JAVA_LONG);
        int result = (int) cuMemAlloc.invoke(ptrHolder, bytes);
        if (result != 0) {
            throw new SpectorGpuException(ErrorCode.GPU_DEVICE_ERROR, "cuMemAlloc failed", result);
        }
        return ptrHolder.get(ValueLayout.JAVA_LONG, 0);
    }

    private void checkCuda(int result, String operation) {
        if (result != 0) {
            throw new SpectorGpuException(ErrorCode.GPU_DEVICE_ERROR, operation + " failed", result);
        }
    }

    private void freeDeviceMemory(List<Long> ptrs) {
        for (Long ptr : ptrs) {
            if (ptr != null && ptr != 0) {
                try { cuMemFree.invoke(ptr); } catch (Throwable ignored) {}
            }
        }
        ptrs.clear();
    }

    // ── CPU SIMD Fallback ───────────────────────────────────────────────────────

    private float[] computeCosineCpu(float[] query, float[] candidates, int k, int dims) {
        float[] results = new float[k];
        int laneCount = SPECIES.length();
        int simdBound = SPECIES.loopBound(dims);

        // Precompute query norm
        FloatVector qNormAcc = FloatVector.zero(SPECIES);
        int d = 0;
        for (; d < simdBound; d += laneCount) {
            FloatVector qv = FloatVector.fromArray(SPECIES, query, d);
            qNormAcc = qv.fma(qv, qNormAcc);
        }
        float queryNormSq = qNormAcc.reduceLanes(VectorOperators.ADD);
        for (; d < dims; d++) queryNormSq += query[d] * query[d];
        float queryNorm = (float) Math.sqrt(queryNormSq);
        if (queryNorm == 0) return results;

        for (int i = 0; i < k; i++) {
            int offset = i * dims;
            FloatVector dotAcc = FloatVector.zero(SPECIES);
            FloatVector normAcc = FloatVector.zero(SPECIES);
            d = 0;
            for (; d < simdBound; d += laneCount) {
                FloatVector qv = FloatVector.fromArray(SPECIES, query, d);
                FloatVector cv = FloatVector.fromArray(SPECIES, candidates, offset + d);
                dotAcc = qv.fma(cv, dotAcc);
                normAcc = cv.fma(cv, normAcc);
            }
            float dot = dotAcc.reduceLanes(VectorOperators.ADD);
            float candNormSq = normAcc.reduceLanes(VectorOperators.ADD);
            for (; d < dims; d++) {
                dot += query[d] * candidates[offset + d];
                candNormSq += candidates[offset + d] * candidates[offset + d];
            }
            float candNorm = (float) Math.sqrt(candNormSq);
            results[i] = candNorm > 0 ? dot / (queryNorm * candNorm) : 0;
        }
        return results;
    }

    private float[] computeL2Cpu(float[] query, float[] candidates, int k, int dims) {
        float[] results = new float[k];
        int laneCount = SPECIES.length();
        int simdBound = SPECIES.loopBound(dims);

        for (int i = 0; i < k; i++) {
            int offset = i * dims;
            FloatVector acc = FloatVector.zero(SPECIES);
            int d = 0;
            for (; d < simdBound; d += laneCount) {
                FloatVector qv = FloatVector.fromArray(SPECIES, query, d);
                FloatVector cv = FloatVector.fromArray(SPECIES, candidates, offset + d);
                FloatVector diff = qv.sub(cv);
                acc = diff.fma(diff, acc);
            }
            float dist = acc.reduceLanes(VectorOperators.ADD);
            for (; d < dims; d++) {
                float diff = query[d] - candidates[offset + d];
                dist += diff * diff;
            }
            results[i] = dist;
        }
        return results;
    }

    // ── Validation ──────────────────────────────────────────────────────────────

    private void validateInputs(float[] query, float[] candidates, int k, int dimensions) {
        if (dimensions < MIN_DIMENSIONS || dimensions > MAX_DIMENSIONS) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE,
                    "dimensions", MIN_DIMENSIONS, MAX_DIMENSIONS, dimensions);
        }
        if (query == null) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "Query vector");
        }
        if (candidates == null) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "Candidates array");
        }
        if (query.length < dimensions) {
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH,
                    "Query length (%d) < dimensions (%d)".formatted(query.length, dimensions));
        }
        if (k > 0 && candidates.length < (long) k * dimensions) {
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH,
                    "Candidates length (%d) < required (%d)".formatted(candidates.length, (long) k * dimensions));
        }
    }

    private void ensureOpen() {
        if (closed) throw new SpectorSegmentClosedException();
    }
}
