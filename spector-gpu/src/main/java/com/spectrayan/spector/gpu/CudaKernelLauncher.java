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

/**
 * CUDA kernel launcher via Panama FFM.
 *
 * <p>Loads a PTX kernel string into a CUDA module and provides methods to
 * launch batch similarity computations on the GPU. Handles device memory
 * allocation, host↔device transfers, and kernel dispatch.</p>
 *
 * <h3>Architecture</h3>
 * <pre>
 *   Host (Java)              Device (GPU)
 *   ─────────────            ────────────────
 *   float[] query    ──HtoD──▶  d_query
 *   float[] database ──HtoD──▶  d_database
 *                               │
 *                         cuLaunchKernel
 *                               │
 *   float[] results  ◀──DtoH──  d_results
 * </pre>
 *
 * <h3>Thread Safety</h3>
 * <p>Each launcher instance owns its CUDA module and is NOT thread-safe.
 * For concurrent use, create one launcher per thread or synchronize externally.</p>
 */
public final class CudaKernelLauncher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CudaKernelLauncher.class);

    /**
     * PTX kernel for batch cosine similarity, loaded from validated resource file.
     * Compiled with ptxas for sm_89 (Ada Lovelace, RTX 40 series).
     */
    private static final String BATCH_COSINE_PTX;

    static {
        try (var is = CudaKernelLauncher.class.getResourceAsStream("/kernels/batch_cosine.ptx")) {
            if (is == null) throw new RuntimeException("batch_cosine.ptx not found in resources");
            BATCH_COSINE_PTX = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to load PTX kernel", e);
        }
    }

    private final Arena arena;
    private final SymbolLookup cudaLib;
    private final Linker linker;
    private final long cuModule;
    private final long cuFunction;

    // Cached method handles
    private final MethodHandle cuMemAlloc;
    private final MethodHandle cuMemcpyHtoD;
    private final MethodHandle cuMemcpyDtoH;
    private final MethodHandle cuMemFree;
    private final MethodHandle cuLaunchKernel;
    private final MethodHandle cuCtxSynchronize;

    private volatile boolean closed;

    /**
     * Creates and initializes the CUDA kernel launcher.
     * Loads the PTX kernel and extracts the function handle.
     *
     * <p>Requires the CUDA Toolkit to be installed (provides the PTX JIT compiler
     * in the driver). Without it, cuModuleLoadData will fail with error 218.</p>
     *
     * @throws RuntimeException if CUDA initialization or PTX loading fails
     */
    public CudaKernelLauncher() {
        if (!GpuCapability.isAvailable()) {
            throw new IllegalStateException("CUDA not available");
        }

        this.arena = Arena.ofShared();
        this.linker = Linker.nativeLinker();
        this.closed = false;

        try {
            String libName = System.getProperty("os.name").toLowerCase().contains("win")
                    ? "nvcuda" : "cuda";
            this.cudaLib = SymbolLookup.libraryLookup(libName, arena);

            // Load PTX module
            MemorySegment ptxBytes = arena.allocateFrom(BATCH_COSINE_PTX);
            MemorySegment modulePtr = arena.allocate(ValueLayout.ADDRESS);

            MethodHandle cuModuleLoadData = linker.downcallHandle(
                    cudaLib.find("cuModuleLoadData").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            int result = (int) cuModuleLoadData.invoke(modulePtr, ptxBytes);
            if (result != 0) {
                throw new RuntimeException("cuModuleLoadData failed: error " + result);
            }
            this.cuModule = modulePtr.get(ValueLayout.ADDRESS, 0).address();

            // Get function handle
            MemorySegment funcPtr = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment funcName = arena.allocateFrom("batch_cosine");
            MethodHandle cuModuleGetFunction = linker.downcallHandle(
                    cudaLib.find("cuModuleGetFunction").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            result = (int) cuModuleGetFunction.invoke(funcPtr,
                    MemorySegment.ofAddress(cuModule), funcName);
            if (result != 0) {
                throw new RuntimeException("cuModuleGetFunction failed: error " + result);
            }
            this.cuFunction = funcPtr.get(ValueLayout.ADDRESS, 0).address();

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
                            ValueLayout.ADDRESS,           // function
                            ValueLayout.JAVA_INT,          // gridDimX
                            ValueLayout.JAVA_INT,          // gridDimY
                            ValueLayout.JAVA_INT,          // gridDimZ
                            ValueLayout.JAVA_INT,          // blockDimX
                            ValueLayout.JAVA_INT,          // blockDimY
                            ValueLayout.JAVA_INT,          // blockDimZ
                            ValueLayout.JAVA_INT,          // sharedMemBytes
                            ValueLayout.ADDRESS,           // stream (null = default)
                            ValueLayout.ADDRESS,           // kernelParams
                            ValueLayout.ADDRESS));         // extra (null)
            this.cuCtxSynchronize = linker.downcallHandle(
                    cudaLib.find("cuCtxSynchronize").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));

            log.info("CudaKernelLauncher initialized: PTX loaded, function 'batch_cosine' ready");

        } catch (Throwable e) {
            throw new RuntimeException("Failed to initialize CUDA kernel launcher", e);
        }
    }

    /**
     * Launches the batch cosine similarity kernel on the GPU.
     *
     * <p>Transfers query and database vectors to device memory, launches the kernel
     * with one thread per database vector, and copies results back to host.</p>
     *
     * @param query    query vector (length = dims)
     * @param database flat database vectors (n × dims)
     * @param n        number of database vectors
     * @param dims     vector dimensionality
     * @return array of n cosine similarity scores
     */
    public float[] batchCosine(float[] query, float[] database, int n, int dims) {
        if (closed) throw new IllegalStateException(com.spectrayan.spector.commons.error.ErrorCode.SEGMENT_CLOSED.format());
        if (n == 0) return new float[0];

        try (Arena local = Arena.ofConfined()) {
            long queryBytes = (long) dims * Float.BYTES;
            long dbBytes = (long) n * dims * Float.BYTES;
            long resultBytes = (long) n * Float.BYTES;

            // Allocate device memory
            long dQuery = deviceAlloc(local, queryBytes);
            long dDatabase = deviceAlloc(local, dbBytes);
            long dResults = deviceAlloc(local, resultBytes);

            try {
                // Copy host → device
                MemorySegment querySegment = local.allocateFrom(ValueLayout.JAVA_FLOAT, query);
                MemorySegment dbSegment = local.allocateFrom(ValueLayout.JAVA_FLOAT, database);

                check((int) cuMemcpyHtoD.invoke(dQuery, querySegment, queryBytes));
                check((int) cuMemcpyHtoD.invoke(dDatabase, dbSegment, dbBytes));

                // Set up kernel parameters
                // params: query_ptr, database_ptr, results_ptr, n, dims
                MemorySegment paramsArray = local.allocate(ValueLayout.ADDRESS, 5);
                MemorySegment pQuery = local.allocate(ValueLayout.JAVA_LONG);
                pQuery.set(ValueLayout.JAVA_LONG, 0, dQuery);
                MemorySegment pDb = local.allocate(ValueLayout.JAVA_LONG);
                pDb.set(ValueLayout.JAVA_LONG, 0, dDatabase);
                MemorySegment pRes = local.allocate(ValueLayout.JAVA_LONG);
                pRes.set(ValueLayout.JAVA_LONG, 0, dResults);
                MemorySegment pN = local.allocate(ValueLayout.JAVA_INT);
                pN.set(ValueLayout.JAVA_INT, 0, n);
                MemorySegment pDims = local.allocate(ValueLayout.JAVA_INT);
                pDims.set(ValueLayout.JAVA_INT, 0, dims);

                paramsArray.setAtIndex(ValueLayout.ADDRESS, 0, pQuery);
                paramsArray.setAtIndex(ValueLayout.ADDRESS, 1, pDb);
                paramsArray.setAtIndex(ValueLayout.ADDRESS, 2, pRes);
                paramsArray.setAtIndex(ValueLayout.ADDRESS, 3, pN);
                paramsArray.setAtIndex(ValueLayout.ADDRESS, 4, pDims);

                // Launch kernel
                int blockSize = 256;
                int gridSize = (n + blockSize - 1) / blockSize;

                int launchResult = (int) cuLaunchKernel.invoke(
                        MemorySegment.ofAddress(cuFunction),
                        gridSize, 1, 1,      // grid dims
                        blockSize, 1, 1,     // block dims
                        0,                   // shared memory
                        MemorySegment.NULL,  // stream (default)
                        paramsArray,         // kernel params
                        MemorySegment.NULL); // extra
                check(launchResult);

                // Synchronize
                check((int) cuCtxSynchronize.invoke());

                // Copy results device → host
                MemorySegment resultSegment = local.allocate(ValueLayout.JAVA_FLOAT, n);
                check((int) cuMemcpyDtoH.invoke(resultSegment, dResults, resultBytes));

                float[] results = new float[n];
                MemorySegment.copy(resultSegment, ValueLayout.JAVA_FLOAT, 0, results, 0, n);
                return results;

            } finally {
                // Free device memory
                try { cuMemFree.invoke(dQuery); } catch (Throwable ignored) {}
                try { cuMemFree.invoke(dDatabase); } catch (Throwable ignored) {}
                try { cuMemFree.invoke(dResults); } catch (Throwable ignored) {}
            }

        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("CUDA kernel launch failed", e);
        }
    }

    private long deviceAlloc(Arena local, long bytes) throws Throwable {
        MemorySegment ptr = local.allocate(ValueLayout.JAVA_LONG);
        check((int) cuMemAlloc.invoke(ptr, bytes));
        return ptr.get(ValueLayout.JAVA_LONG, 0);
    }

    private void check(int cudaResult) {
        if (cudaResult != 0) {
            throw new RuntimeException("CUDA error: " + cudaResult);
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            try {
                MethodHandle cuModuleUnload = linker.downcallHandle(
                        cudaLib.find("cuModuleUnload").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                cuModuleUnload.invoke(MemorySegment.ofAddress(cuModule));
            } catch (Throwable e) {
                log.warn("Error unloading CUDA module", e);
            }
            arena.close();
            log.info("CudaKernelLauncher closed");
        }
    }
}
