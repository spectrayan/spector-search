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

import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.SpectorGpuException;
import com.spectrayan.spector.storage.error.SpectorSegmentClosedException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * CUDA-accelerated SVASQ quantized distance computation via Panama FFM.
 *
 * <p>Implements the asymmetric distance formula from {@code SvasqSimdKernel}
 * on GPU, operating on INT8 quantized codes with a float32 query. This is the
 * highest-impact GPU kernel since SVASQ is Spector's primary storage format.</p>
 *
 * <h3>Supported Operations</h3>
 * <ul>
 *   <li>{@link #computeL2} — L2 ≈ exactNormSq + constL2Q - 2 × dot(qTilde, z)</li>
 *   <li>{@link #computeDot} — IP ≈ dot(qTilde, z) + dotOffset</li>
 * </ul>
 *
 * <h3>Memory Layout</h3>
 * <p>Expects separated arrays (not interleaved norm+codes) for GPU efficiency:
 * <ul>
 *   <li>{@code qTilde} — pre-scaled query vector (float32[D])</li>
 *   <li>{@code codes} — INT8 quantized codes (byte[N × D], signed)</li>
 *   <li>{@code norms} — float16 norms (short[N], for L2 only)</li>
 * </ul>
 *
 * <h3>GPU Routing</h3>
 * <p>Routes to GPU for batches ≥ 1024 vectors (below this, CPU SIMD wins
 * due to PCIe transfer overhead for the INT8 codes).</p>
 *
 * @see com.spectrayan.spector.core.quantization.svasq.SvasqSimdKernel
 */
public final class CudaSvasqKernel implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CudaSvasqKernel.class);

    /** Minimum batch size to route to GPU (INT8 codes have low transfer cost). */
    private static final int GPU_MIN_VECTORS = 1024;

    /** CUDA threads per block. */
    private static final int THREADS_PER_BLOCK = 256;

    private final boolean gpuAvailable;
    private Arena arena;
    private SymbolLookup cudaLib;
    private Linker linker;
    private MemorySegment cuModule;
    private MemorySegment l2Function;
    private MemorySegment dotFunction;

    // Cached method handles
    private MethodHandle cuMemAlloc;
    private MethodHandle cuMemcpyHtoD;
    private MethodHandle cuMemcpyDtoH;
    private MethodHandle cuMemFree;
    private MethodHandle cuLaunchKernel;
    private MethodHandle cuCtxSynchronize;

    private volatile boolean closed;

    /**
     * Creates a CUDA SVASQ kernel.
     *
     * <p>Loads the {@code svasq_distance.ptx} resource. Falls back to CPU
     * if GPU is unavailable or PTX loading fails.</p>
     */
    public CudaSvasqKernel() {
        this(true);
    }

    /**
     * Creates a CUDA SVASQ kernel with optional GPU usage.
     *
     * @param useGpu if false, forces CPU fallback
     */
    public CudaSvasqKernel(boolean useGpu) {
        this.closed = false;
        this.gpuAvailable = useGpu && initGpu();

        if (gpuAvailable) {
            log.info("CudaSvasqKernel initialized with GPU acceleration");
        } else {
            log.info("CudaSvasqKernel initialized in CPU fallback mode");
        }
    }

    /**
     * Computes SVASQ asymmetric L2 distances on GPU.
     *
     * <p>Formula: {@code L2[i] ≈ norms[i] + constL2Q - 2 × dot(qTilde, codes[i])}</p>
     *
     * @param qTilde   pre-scaled query vector (float32[D])
     * @param codes    INT8 quantized codes (byte[N × D], signed)
     * @param norms    float16 norms as shorts (short[N])
     * @param constL2Q query-side L2 constant (‖q‖² - 2·C(q))
     * @param n        number of database vectors
     * @param dims     padded vector dimensionality
     * @return array of N approximate L2 squared distances
     */
    public float[] computeL2(float[] qTilde, byte[] codes, short[] norms,
                              float constL2Q, int n, int dims) {
        ensureOpen();
        if (n == 0) return new float[0];

        if (gpuAvailable && n >= GPU_MIN_VECTORS && l2Function != null) {
            try {
                return computeL2Gpu(qTilde, codes, norms, constL2Q, n, dims);
            } catch (Exception e) {
                log.debug("GPU SVASQ L2 failed, CPU fallback: {}", e.getMessage());
            }
        }
        return computeL2Cpu(qTilde, codes, norms, constL2Q, n, dims);
    }

    /**
     * Computes SVASQ asymmetric dot products on GPU.
     *
     * <p>Formula: {@code IP[i] ≈ dot(qTilde, codes[i]) + dotOffset}</p>
     *
     * @param qTilde    pre-scaled query vector (float32[D])
     * @param codes     INT8 quantized codes (byte[N × D], signed)
     * @param dotOffset query-side mean correction constant
     * @param n         number of database vectors
     * @param dims      padded vector dimensionality
     * @return array of N approximate inner product scores
     */
    public float[] computeDot(float[] qTilde, byte[] codes,
                               float dotOffset, int n, int dims) {
        ensureOpen();
        if (n == 0) return new float[0];

        if (gpuAvailable && n >= GPU_MIN_VECTORS && dotFunction != null) {
            try {
                return computeDotGpu(qTilde, codes, dotOffset, n, dims);
            } catch (Exception e) {
                log.debug("GPU SVASQ dot failed, CPU fallback: {}", e.getMessage());
            }
        }
        return computeDotCpu(qTilde, codes, dotOffset, n, dims);
    }

    /** Returns whether GPU is active for this kernel. */
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
                    log.warn("Error unloading SVASQ CUDA module", e);
                }
                arena.close();
                log.info("CudaSvasqKernel closed");
            }
        }
    }

    // ── GPU Initialization ──────────────────────────────────────────────────────

    private boolean initGpu() {
        if (!GpuCapability.isAvailable()) return false;

        try {
            this.arena = Arena.ofShared();
            this.linker = Linker.nativeLinker();
            String libName = System.getProperty("os.name").toLowerCase().contains("win")
                    ? "nvcuda" : "cuda";
            this.cudaLib = SymbolLookup.libraryLookup(libName, arena);

            String ptx = loadPtxResource();
            if (ptx == null) {
                log.warn("SVASQ PTX not found, falling back to CPU");
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
                log.warn("cuModuleLoadData failed (SVASQ): {}", result);
                arena.close();
                return false;
            }
            this.cuModule = modulePtr.get(ValueLayout.ADDRESS, 0);

            this.l2Function = getFunction("svasq8_l2_distance");
            this.dotFunction = getFunction("svasq8_dot_product");

            if (l2Function == null && dotFunction == null) {
                log.warn("No SVASQ kernel functions found");
                arena.close();
                return false;
            }

            cacheMethodHandles();
            return true;

        } catch (Throwable e) {
            log.warn("SVASQ GPU init failed: {}", e.getMessage());
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
        try (var is = getClass().getResourceAsStream("/cuda/svasq_distance.ptx")) {
            if (is != null) {
                return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("Failed to load svasq_distance.ptx: {}", e.getMessage());
        }
        return null;
    }

    // ── GPU Execution ───────────────────────────────────────────────────────────

    private float[] computeL2Gpu(float[] qTilde, byte[] codes, short[] norms,
                                  float constL2Q, int n, int dims) throws Exception {
        long qTildeBytes = (long) dims * Float.BYTES;
        long codeBytes = (long) n * dims;               // INT8: 1 byte per element
        long normBytes = (long) n * Short.BYTES;         // float16 as short
        long resultBytes = (long) n * Float.BYTES;
        int sharedMemBytes = THREADS_PER_BLOCK * Float.BYTES;

        List<Long> devicePtrs = new ArrayList<>(4);

        try (var localArena = Arena.ofConfined()) {
            long dQTilde = deviceAlloc(qTildeBytes, localArena);
            devicePtrs.add(dQTilde);
            long dCodes = deviceAlloc(codeBytes, localArena);
            devicePtrs.add(dCodes);
            long dNorms = deviceAlloc(normBytes, localArena);
            devicePtrs.add(dNorms);
            long dResults = deviceAlloc(resultBytes, localArena);
            devicePtrs.add(dResults);

            // Upload qTilde
            MemorySegment qSeg = localArena.allocateFrom(ValueLayout.JAVA_FLOAT, qTilde);
            checkCuda((int) cuMemcpyHtoD.invoke(dQTilde, qSeg, qTildeBytes), "qTilde HtoD");

            // Upload INT8 codes
            MemorySegment codeSeg = localArena.allocate(codeBytes);
            for (int i = 0; i < codes.length; i++) {
                codeSeg.set(ValueLayout.JAVA_BYTE, i, codes[i]);
            }
            checkCuda((int) cuMemcpyHtoD.invoke(dCodes, codeSeg, codeBytes), "codes HtoD");

            // Upload float16 norms
            MemorySegment normSeg = localArena.allocateFrom(ValueLayout.JAVA_SHORT, norms);
            checkCuda((int) cuMemcpyHtoD.invoke(dNorms, normSeg, normBytes), "norms HtoD");

            // Kernel params: (qTilde, codes, norms, results, constL2Q, N, D)
            MemorySegment pQTilde = localArena.allocate(ValueLayout.JAVA_LONG);
            pQTilde.set(ValueLayout.JAVA_LONG, 0, dQTilde);
            MemorySegment pCodes = localArena.allocate(ValueLayout.JAVA_LONG);
            pCodes.set(ValueLayout.JAVA_LONG, 0, dCodes);
            MemorySegment pNorms = localArena.allocate(ValueLayout.JAVA_LONG);
            pNorms.set(ValueLayout.JAVA_LONG, 0, dNorms);
            MemorySegment pResults = localArena.allocate(ValueLayout.JAVA_LONG);
            pResults.set(ValueLayout.JAVA_LONG, 0, dResults);
            MemorySegment pConstL2Q = localArena.allocate(ValueLayout.JAVA_FLOAT);
            pConstL2Q.set(ValueLayout.JAVA_FLOAT, 0, constL2Q);
            MemorySegment pN = localArena.allocate(ValueLayout.JAVA_INT);
            pN.set(ValueLayout.JAVA_INT, 0, n);
            MemorySegment pD = localArena.allocate(ValueLayout.JAVA_INT);
            pD.set(ValueLayout.JAVA_INT, 0, dims);

            MemorySegment kernelParams = localArena.allocate(ValueLayout.ADDRESS, 7);
            kernelParams.setAtIndex(ValueLayout.ADDRESS, 0, pQTilde);
            kernelParams.setAtIndex(ValueLayout.ADDRESS, 1, pCodes);
            kernelParams.setAtIndex(ValueLayout.ADDRESS, 2, pNorms);
            kernelParams.setAtIndex(ValueLayout.ADDRESS, 3, pResults);
            kernelParams.setAtIndex(ValueLayout.ADDRESS, 4, pConstL2Q);
            kernelParams.setAtIndex(ValueLayout.ADDRESS, 5, pN);
            kernelParams.setAtIndex(ValueLayout.ADDRESS, 6, pD);

            int blockDim = Math.min(dims, THREADS_PER_BLOCK);
            checkCuda((int) cuLaunchKernel.invoke(
                    l2Function, n, 1, 1, blockDim, 1, 1,
                    sharedMemBytes, MemorySegment.NULL,
                    kernelParams, MemorySegment.NULL), "SVASQ L2 launch");

            checkCuda((int) cuCtxSynchronize.invoke(), "sync");

            MemorySegment resSeg = localArena.allocate(ValueLayout.JAVA_FLOAT, n);
            checkCuda((int) cuMemcpyDtoH.invoke(resSeg, dResults, resultBytes), "DtoH");

            float[] results = new float[n];
            MemorySegment.copy(resSeg, ValueLayout.JAVA_FLOAT, 0, results, 0, n);
            freeDeviceMemory(devicePtrs);
            return results;

        } catch (Throwable e) {
            freeDeviceMemory(devicePtrs);
            if (e instanceof Exception ex) throw ex;
            throw new SpectorGpuException(ErrorCode.GPU_DEVICE_ERROR, e, "SVASQ L2 failed", 0);
        }
    }

    private float[] computeDotGpu(float[] qTilde, byte[] codes,
                                   float dotOffset, int n, int dims) throws Exception {
        long qTildeBytes = (long) dims * Float.BYTES;
        long codeBytes = (long) n * dims;
        long resultBytes = (long) n * Float.BYTES;
        int sharedMemBytes = THREADS_PER_BLOCK * Float.BYTES;

        List<Long> devicePtrs = new ArrayList<>(3);

        try (var localArena = Arena.ofConfined()) {
            long dQTilde = deviceAlloc(qTildeBytes, localArena);
            devicePtrs.add(dQTilde);
            long dCodes = deviceAlloc(codeBytes, localArena);
            devicePtrs.add(dCodes);
            long dResults = deviceAlloc(resultBytes, localArena);
            devicePtrs.add(dResults);

            MemorySegment qSeg = localArena.allocateFrom(ValueLayout.JAVA_FLOAT, qTilde);
            checkCuda((int) cuMemcpyHtoD.invoke(dQTilde, qSeg, qTildeBytes), "qTilde HtoD");

            MemorySegment codeSeg = localArena.allocate(codeBytes);
            for (int i = 0; i < codes.length; i++) {
                codeSeg.set(ValueLayout.JAVA_BYTE, i, codes[i]);
            }
            checkCuda((int) cuMemcpyHtoD.invoke(dCodes, codeSeg, codeBytes), "codes HtoD");

            // Kernel params: (qTilde, codes, results, dotOffset, N, D)
            MemorySegment pQTilde = localArena.allocate(ValueLayout.JAVA_LONG);
            pQTilde.set(ValueLayout.JAVA_LONG, 0, dQTilde);
            MemorySegment pCodes = localArena.allocate(ValueLayout.JAVA_LONG);
            pCodes.set(ValueLayout.JAVA_LONG, 0, dCodes);
            MemorySegment pResults = localArena.allocate(ValueLayout.JAVA_LONG);
            pResults.set(ValueLayout.JAVA_LONG, 0, dResults);
            MemorySegment pDotOffset = localArena.allocate(ValueLayout.JAVA_FLOAT);
            pDotOffset.set(ValueLayout.JAVA_FLOAT, 0, dotOffset);
            MemorySegment pN = localArena.allocate(ValueLayout.JAVA_INT);
            pN.set(ValueLayout.JAVA_INT, 0, n);
            MemorySegment pD = localArena.allocate(ValueLayout.JAVA_INT);
            pD.set(ValueLayout.JAVA_INT, 0, dims);

            MemorySegment kernelParams = localArena.allocate(ValueLayout.ADDRESS, 6);
            kernelParams.setAtIndex(ValueLayout.ADDRESS, 0, pQTilde);
            kernelParams.setAtIndex(ValueLayout.ADDRESS, 1, pCodes);
            kernelParams.setAtIndex(ValueLayout.ADDRESS, 2, pResults);
            kernelParams.setAtIndex(ValueLayout.ADDRESS, 3, pDotOffset);
            kernelParams.setAtIndex(ValueLayout.ADDRESS, 4, pN);
            kernelParams.setAtIndex(ValueLayout.ADDRESS, 5, pD);

            int blockDim = Math.min(dims, THREADS_PER_BLOCK);
            checkCuda((int) cuLaunchKernel.invoke(
                    dotFunction, n, 1, 1, blockDim, 1, 1,
                    sharedMemBytes, MemorySegment.NULL,
                    kernelParams, MemorySegment.NULL), "SVASQ dot launch");

            checkCuda((int) cuCtxSynchronize.invoke(), "sync");

            MemorySegment resSeg = localArena.allocate(ValueLayout.JAVA_FLOAT, n);
            checkCuda((int) cuMemcpyDtoH.invoke(resSeg, dResults, resultBytes), "DtoH");

            float[] results = new float[n];
            MemorySegment.copy(resSeg, ValueLayout.JAVA_FLOAT, 0, results, 0, n);
            freeDeviceMemory(devicePtrs);
            return results;

        } catch (Throwable e) {
            freeDeviceMemory(devicePtrs);
            if (e instanceof Exception ex) throw ex;
            throw new SpectorGpuException(ErrorCode.GPU_DEVICE_ERROR, e, "SVASQ dot failed", 0);
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

    private void checkCuda(int result, String op) {
        if (result != 0) {
            throw new SpectorGpuException(ErrorCode.GPU_DEVICE_ERROR, op + " failed", result);
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

    // ── CPU Fallback ────────────────────────────────────────────────────────────

    /**
     * CPU fallback for SVASQ L2 distance.
     * L2[i] = exactNormSq + constL2Q - 2 × dot(qTilde, codes[i])
     */
    private float[] computeL2Cpu(float[] qTilde, byte[] codes, short[] norms,
                                  float constL2Q, int n, int dims) {
        float[] results = new float[n];
        for (int i = 0; i < n; i++) {
            float exactNormSq = Float.float16ToFloat(norms[i]);
            int offset = i * dims;
            float dot = 0;
            for (int d = 0; d < dims; d++) {
                dot += qTilde[d] * codes[offset + d];
            }
            results[i] = Math.max(0, exactNormSq + constL2Q - 2f * dot);
        }
        return results;
    }

    /**
     * CPU fallback for SVASQ dot product.
     * IP[i] = dot(qTilde, codes[i]) + dotOffset
     */
    private float[] computeDotCpu(float[] qTilde, byte[] codes,
                                   float dotOffset, int n, int dims) {
        float[] results = new float[n];
        for (int i = 0; i < n; i++) {
            int offset = i * dims;
            float dot = 0;
            for (int d = 0; d < dims; d++) {
                dot += qTilde[d] * codes[offset + d];
            }
            results[i] = dot + dotOffset;
        }
        return results;
    }

    private void ensureOpen() {
        if (closed) throw new SpectorSegmentClosedException();
    }
}
