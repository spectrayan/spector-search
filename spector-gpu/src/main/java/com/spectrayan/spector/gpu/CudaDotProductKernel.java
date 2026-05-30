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

import com.spectrayan.spector.core.similarity.DotProduct;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.SpectorServerException;
import com.spectrayan.spector.commons.error.SpectorGpuException;
import com.spectrayan.spector.commons.error.SpectorSegmentClosedException;
import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorInternalException;

/**
 * CUDA-accelerated dot-product similarity kernel via Panama FFM.
 *
 * <p>Loads the {@code batch_dot} CUDA PTX kernel at construction time and
 * dispatches batch dot-product computations to the GPU. When the GPU is
 * unavailable or a CUDA error occurs during execution, transparently falls
 * back to a CPU SIMD implementation using the Java Vector API.</p>
 *
 * <h3>Constraints</h3>
 * <ul>
 *   <li>Dimensions must be multiples of 32, range [32, 2048]</li>
 *   <li>Batch sizes from 1 to 1,000,000</li>
 *   <li>Falls back to CPU SIMD when GPU unavailable or on CUDA error</li>
 *   <li>Releases device memory on failure</li>
 * </ul>
 *
 * @see SimilarityKernel
 * @see GpuCapability
 */
public final class CudaDotProductKernel implements SimilarityKernel, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CudaDotProductKernel.class);

    /** Minimum supported dimensions. */
    private static final int MIN_DIMENSIONS = 32;

    /** Maximum supported dimensions. */
    private static final int MAX_DIMENSIONS = 2048;

    /** Maximum supported batch size. */
    private static final int MAX_BATCH_SIZE = 1_000_000;

    /** CUDA threads per block for the dot-product kernel. */
    private static final int THREADS_PER_BLOCK = 256;

    // GPU state (null when falling back to CPU)
    private final boolean gpuAvailable;
    private Arena arena;
    private SymbolLookup cudaLib;
    private Linker linker;
    private MemorySegment cuModule;
    private MemorySegment dotFunction;

    // Cached method handles for CUDA driver API
    private MethodHandle cuMemAlloc;
    private MethodHandle cuMemcpyHtoD;
    private MethodHandle cuMemcpyDtoH;
    private MethodHandle cuMemFree;
    private MethodHandle cuLaunchKernel;
    private MethodHandle cuCtxSynchronize;

    private volatile boolean closed;

    /**
     * Creates a CUDA dot-product kernel.
     *
     * <p>If the GPU is unavailable, the kernel will operate in CPU-only mode
     * using SIMD acceleration (Java Vector API) without throwing an exception.</p>
     */
    public CudaDotProductKernel() {
        this(true);
    }

    /**
     * Creates a CUDA dot-product kernel with optional GPU usage.
     *
     * @param useGpu if false, forces CPU SIMD fallback regardless of GPU availability
     */
    public CudaDotProductKernel(boolean useGpu) {
        this.closed = false;
        this.gpuAvailable = useGpu && initGpu();

        if (gpuAvailable) {
            log.info("CudaDotProductKernel initialized with GPU acceleration: {}",
                    GpuCapability.detect().report());
        } else {
            log.info("CudaDotProductKernel initialized in CPU SIMD fallback mode");
        }
    }

    @Override
    public float[] compute(float[] query, float[] database, int numVectors, int dimensions) {
        ensureOpen();
        validateInputs(query, database, numVectors, dimensions);

        if (numVectors == 0) {
            return new float[0];
        }

        if (gpuAvailable) {
            try {
                return computeGpu(query, database, numVectors, dimensions);
            } catch (Exception e) {
                log.warn("CUDA kernel execution failed, falling back to CPU SIMD: {}", e.getMessage());
                return computeCpuSimd(query, database, numVectors, dimensions);
            }
        }

        return computeCpuSimd(query, database, numVectors, dimensions);
    }

    @Override
    public String name() {
        return "dot-product";
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
                    log.warn("Error unloading CUDA module", e);
                }
                arena.close();
                log.info("CudaDotProductKernel closed");
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

            // Load the PTX module from resources
            String ptxSource = loadPtxResource();
            if (ptxSource == null) {
                log.warn("PTX resource not found, falling back to CPU");
                arena.close();
                return false;
            }

            // Load CUDA module
            MemorySegment modulePtr = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment ptxData = arena.allocateFrom(ptxSource);

            MethodHandle cuModuleLoadData = linker.downcallHandle(
                    cudaLib.find("cuModuleLoadData").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            int result = (int) cuModuleLoadData.invoke(modulePtr, ptxData);
            if (result != 0) {
                log.warn("cuModuleLoadData failed with error {}, falling back to CPU", result);
                arena.close();
                return false;
            }
            this.cuModule = modulePtr.get(ValueLayout.ADDRESS, 0);

            // Get batch_dot function
            MemorySegment funcPtr = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment nameStr = arena.allocateFrom("batch_dot");
            MethodHandle cuModuleGetFunction = linker.downcallHandle(
                    cudaLib.find("cuModuleGetFunction").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            result = (int) cuModuleGetFunction.invoke(funcPtr, cuModule, nameStr);
            if (result != 0) {
                log.warn("cuModuleGetFunction('batch_dot') failed: {}, falling back to CPU", result);
                arena.close();
                return false;
            }
            this.dotFunction = funcPtr.get(ValueLayout.ADDRESS, 0);

            // Cache method handles
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

            return true;

        } catch (Throwable e) {
            log.warn("GPU initialization failed: {}, falling back to CPU", e.getMessage());
            if (arena != null) {
                try { arena.close(); } catch (Exception ignored) {}
            }
            return false;
        }
    }

    private String loadPtxResource() {
        try (var is = getClass().getResourceAsStream("/cuda/batch_similarity.ptx")) {
            if (is == null) {
                // Try .cu source as fallback (would need nvcc compilation in production)
                try (var cuIs = getClass().getResourceAsStream("/cuda/batch_similarity.cu")) {
                    if (cuIs == null) return null;
                    // In production, PTX would be pre-compiled. For now, return null
                    // to trigger CPU fallback when only .cu source is available.
                    return null;
                }
            }
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Failed to load PTX resource: {}", e.getMessage());
            return null;
        }
    }

    // ── GPU Execution ───────────────────────────────────────────────────────────

    private float[] computeGpu(float[] query, float[] database, int numVectors, int dimensions)
            throws Exception {

        long queryBytes = (long) dimensions * Float.BYTES;
        long dbBytes = (long) numVectors * dimensions * Float.BYTES;
        long resultBytes = (long) numVectors * Float.BYTES;
        int sharedMemBytes = THREADS_PER_BLOCK * Float.BYTES;

        // Track device allocations for cleanup on failure
        List<Long> devicePtrs = new ArrayList<>(3);

        try (var localArena = Arena.ofConfined()) {
            // Allocate device memory
            long dQuery = deviceAlloc(queryBytes, localArena);
            devicePtrs.add(dQuery);

            long dDatabase = deviceAlloc(dbBytes, localArena);
            devicePtrs.add(dDatabase);

            long dResults = deviceAlloc(resultBytes, localArena);
            devicePtrs.add(dResults);

            // Copy host data to device
            MemorySegment querySegment = localArena.allocateFrom(ValueLayout.JAVA_FLOAT, query);
            int htodResult = (int) cuMemcpyHtoD.invoke(dQuery, querySegment, queryBytes);
            if (htodResult != 0) {
                throw new SpectorGpuException(ErrorCode.GPU_DEVICE_ERROR, "cuMemcpyHtoD (query) failed", htodResult);
            }

            MemorySegment dbSegment = localArena.allocateFrom(ValueLayout.JAVA_FLOAT, database);
            htodResult = (int) cuMemcpyHtoD.invoke(dDatabase, dbSegment, dbBytes);
            if (htodResult != 0) {
                throw new SpectorGpuException(ErrorCode.GPU_DEVICE_ERROR, "cuMemcpyHtoD (database) failed", htodResult);
            }

            // Set up kernel parameters:
            // batch_dot(const float* query, const float* database, float* results, int N, int D)
            MemorySegment pQuery = localArena.allocate(ValueLayout.JAVA_LONG);
            pQuery.set(ValueLayout.JAVA_LONG, 0, dQuery);

            MemorySegment pDatabase = localArena.allocate(ValueLayout.JAVA_LONG);
            pDatabase.set(ValueLayout.JAVA_LONG, 0, dDatabase);

            MemorySegment pResults = localArena.allocate(ValueLayout.JAVA_LONG);
            pResults.set(ValueLayout.JAVA_LONG, 0, dResults);

            MemorySegment pN = localArena.allocate(ValueLayout.JAVA_INT);
            pN.set(ValueLayout.JAVA_INT, 0, numVectors);

            MemorySegment pD = localArena.allocate(ValueLayout.JAVA_INT);
            pD.set(ValueLayout.JAVA_INT, 0, dimensions);

            // Kernel params array (pointers to each parameter)
            MemorySegment kernelParams = localArena.allocate(ValueLayout.ADDRESS, 5);
            kernelParams.setAtIndex(ValueLayout.ADDRESS, 0, pQuery);
            kernelParams.setAtIndex(ValueLayout.ADDRESS, 1, pDatabase);
            kernelParams.setAtIndex(ValueLayout.ADDRESS, 2, pResults);
            kernelParams.setAtIndex(ValueLayout.ADDRESS, 3, pN);
            kernelParams.setAtIndex(ValueLayout.ADDRESS, 4, pD);

            // Launch kernel: grid = (numVectors, 1, 1), block = (threadsPerBlock, 1, 1)
            int blockDim = Math.min(dimensions, THREADS_PER_BLOCK);
            int launchResult = (int) cuLaunchKernel.invoke(
                    dotFunction,
                    numVectors, 1, 1,   // grid dimensions
                    blockDim, 1, 1,     // block dimensions
                    sharedMemBytes,     // shared memory
                    MemorySegment.NULL, // default stream
                    kernelParams,       // kernel params
                    MemorySegment.NULL  // extra (null)
            );
            if (launchResult != 0) {
                throw new SpectorGpuException(ErrorCode.GPU_DEVICE_ERROR, "cuLaunchKernel failed", launchResult);
            }

            // Synchronize
            int syncResult = (int) cuCtxSynchronize.invoke();
            if (syncResult != 0) {
                throw new SpectorGpuException(ErrorCode.GPU_DEVICE_ERROR, "cuCtxSynchronize failed", syncResult);
            }

            // Copy results back
            MemorySegment resultSegment = localArena.allocate(ValueLayout.JAVA_FLOAT, numVectors);
            int dtohResult = (int) cuMemcpyDtoH.invoke(resultSegment, dResults, resultBytes);
            if (dtohResult != 0) {
                throw new SpectorGpuException(ErrorCode.GPU_DEVICE_ERROR, "cuMemcpyDtoH failed", dtohResult);
            }

            // Extract results
            float[] results = new float[numVectors];
            for (int i = 0; i < numVectors; i++) {
                results[i] = resultSegment.getAtIndex(ValueLayout.JAVA_FLOAT, i);
            }

            // Free device memory (success path)
            freeDeviceMemory(devicePtrs);

            return results;

        } catch (Throwable e) {
            // Release device memory on failure
            freeDeviceMemory(devicePtrs);
            throw new SpectorGpuException(ErrorCode.GPU_DEVICE_ERROR, e, "dot-product failed", 0);
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

    private void freeDeviceMemory(List<Long> devicePtrs) {
        for (Long ptr : devicePtrs) {
            if (ptr != null && ptr != 0) {
                try {
                    cuMemFree.invoke(ptr);
                } catch (Throwable e) {
                    log.warn("cuMemFree failed for pointer {}: {}", ptr, e.getMessage());
                }
            }
        }
        devicePtrs.clear();
    }

    // ── CPU SIMD Fallback ───────────────────────────────────────────────────────

    /**
     * CPU SIMD fallback using Java Vector API.
     * Computes dot products between the query and each database vector.
     */
    private float[] computeCpuSimd(float[] query, float[] database, int numVectors, int dimensions) {
        float[] results = new float[numVectors];
        for (int i = 0; i < numVectors; i++) {
            int offset = i * dimensions;
            results[i] = DotProduct.compute(query, 0, database, offset, dimensions);
        }
        return results;
    }

    // ── Validation ──────────────────────────────────────────────────────────────

    private void validateInputs(float[] query, float[] database, int numVectors, int dimensions) {
        if (dimensions < MIN_DIMENSIONS || dimensions > MAX_DIMENSIONS) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "dimensions", MIN_DIMENSIONS, MAX_DIMENSIONS, dimensions);
        }
        if (dimensions % 32 != 0) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "dimensions (must be multiple of 32)", dimensions);
        }
        if (numVectors < 0 || numVectors > MAX_BATCH_SIZE) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "batchSize", 0, MAX_BATCH_SIZE, numVectors);
        }
        if (query == null) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "Query vector");
        }
        if (database == null) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "Database array");
        }
        if (query.length < dimensions) {
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, "Query vector length (" + query.length + ") is less than dimensions (" + dimensions + ")");
        }
        if (numVectors > 0 && database.length < (long) numVectors * dimensions) {
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, "Database array length (" + database.length + ") is less than required (" + ((long) numVectors * dimensions) + ")");
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new SpectorSegmentClosedException();
        }
    }
}