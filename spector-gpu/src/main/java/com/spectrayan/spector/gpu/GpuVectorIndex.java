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

/**
 * GPU-resident vector index for brute-force similarity search.
 *
 * <p>Uploads the entire vector database to GPU VRAM once at construction,
 * then each search only transfers the query vector (tiny) and retrieves
 * the results. This amortizes the PCIe transfer cost over many queries.</p>
 *
 * <h3>Usage Pattern</h3>
 * <pre>{@code
 *   // Upload database to GPU (one-time cost)
 *   var gpuIndex = GpuVectorIndex.create(database, numVectors, dims);
 *
 *   // Each search only transfers query (384 floats = 1.5KB)
 *   float[] scores = gpuIndex.search(queryVector);
 *   // scores[i] = cosine(query, database[i])
 *
 *   // Cleanup
 *   gpuIndex.close();
 * }</pre>
 *
 * <h3>Fallback Behavior</h3>
 * <ul>
 *   <li>If GPU flag is false → never attempts GPU, uses CPU SIMD</li>
 *   <li>If GPU hardware unavailable → logs warning, falls back to CPU SIMD</li>
 *   <li>If GPU allocation fails (OOM) → logs warning, falls back to CPU SIMD</li>
 *   <li>If kernel launch fails → logs warning, falls back to CPU SIMD for that query</li>
 *   <li>Never throws exceptions to the caller — always returns valid results</li>
 * </ul>
 */
public final class GpuVectorIndex implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GpuVectorIndex.class);
    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    private final int numVectors;
    private final int dimensions;
    private final float[] cpuDatabase; // kept for CPU fallback
    private final boolean gpuActive;

    // GPU state (null if GPU unavailable or failed)
    private final Arena arena;
    private final long dDatabase;      // device pointer to database vectors
    private final long dResults;       // device pointer to results buffer
    private final long dQuery;         // device pointer to query buffer
    private final MethodHandle cuMemcpyHtoD;
    private final MethodHandle cuMemcpyDtoH;
    private final MethodHandle cuLaunchKernel;
    private final MethodHandle cuCtxSynchronize;
    private final long cuFunction;     // kernel function handle

    private volatile boolean closed;

    private GpuVectorIndex(int numVectors, int dimensions, float[] cpuDatabase,
                           boolean gpuActive, Arena arena, long dDatabase,
                           long dResults, long dQuery,
                           MethodHandle cuMemcpyHtoD, MethodHandle cuMemcpyDtoH,
                           MethodHandle cuLaunchKernel, MethodHandle cuCtxSynchronize,
                           long cuFunction) {
        this.numVectors = numVectors;
        this.dimensions = dimensions;
        this.cpuDatabase = cpuDatabase;
        this.gpuActive = gpuActive;
        this.arena = arena;
        this.dDatabase = dDatabase;
        this.dResults = dResults;
        this.dQuery = dQuery;
        this.cuMemcpyHtoD = cuMemcpyHtoD;
        this.cuMemcpyDtoH = cuMemcpyDtoH;
        this.cuLaunchKernel = cuLaunchKernel;
        this.cuCtxSynchronize = cuCtxSynchronize;
        this.cuFunction = cuFunction;
        this.closed = false;
    }

    /**
     * Creates a GPU vector index. Uploads database to VRAM if GPU is available.
     *
     * @param database   flat database vectors (numVectors × dims)
     * @param numVectors number of vectors
     * @param dims       vector dimensionality
     * @param gpuEnabled whether to attempt GPU acceleration
     * @return a GpuVectorIndex (always succeeds — falls back to CPU if needed)
     */
    public static GpuVectorIndex create(float[] database, int numVectors, int dims, boolean gpuEnabled) {
        if (!gpuEnabled) {
            log.info("GPU disabled by config — using CPU SIMD for brute-force search");
            return cpuOnly(database, numVectors, dims);
        }

        if (!GpuCapability.isAvailable()) {
            log.warn("GPU enabled but hardware not available — falling back to CPU SIMD. {}",
                    GpuCapability.detect().report());
            return cpuOnly(database, numVectors, dims);
        }

        try {
            return createGpu(database, numVectors, dims);
        } catch (Throwable e) {
            log.warn("GPU initialization failed — falling back to CPU SIMD: {}", e.getMessage());
            return cpuOnly(database, numVectors, dims);
        }
    }

    private static GpuVectorIndex cpuOnly(float[] database, int numVectors, int dims) {
        return new GpuVectorIndex(numVectors, dims, database, false,
                null, 0, 0, 0, null, null, null, null, 0);
    }

    private static GpuVectorIndex createGpu(float[] database, int numVectors, int dims) throws Throwable {
        Arena arena = Arena.ofShared();
        Linker linker = Linker.nativeLinker();
        String libName = System.getProperty("os.name").toLowerCase().contains("win") ? "nvcuda" : "cuda";
        SymbolLookup cudaLib = SymbolLookup.libraryLookup(libName, arena);

        // Create CUDA context first
        MethodHandle cuCtxCreate = linker.downcallHandle(
                cudaLib.find("cuCtxCreate_v2").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        MemorySegment ctxPtr = arena.allocate(ValueLayout.ADDRESS);
        int result = (int) cuCtxCreate.invoke(ctxPtr, 0, 0);
        if (result != 0) throw new RuntimeException("cuCtxCreate failed: " + result);

        long dbBytes = (long) numVectors * dims * Float.BYTES;
        long resultBytes = (long) numVectors * Float.BYTES;
        long queryBytes = (long) dims * Float.BYTES;

        log.info("Uploading {} vectors ({} MB) to GPU VRAM...",
                numVectors, dbBytes / (1024 * 1024));

        // Allocate device memory
        MethodHandle cuMemAlloc = linker.downcallHandle(
                cudaLib.find("cuMemAlloc_v2").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

        long dDatabase = deviceAlloc(arena, cuMemAlloc, dbBytes);
        long dResults = deviceAlloc(arena, cuMemAlloc, resultBytes);
        long dQuery = deviceAlloc(arena, cuMemAlloc, queryBytes);

        // Upload database to GPU (one-time)
        MethodHandle cuMemcpyHtoD = linker.downcallHandle(
                cudaLib.find("cuMemcpyHtoD_v2").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

        MemorySegment dbSegment = arena.allocateFrom(ValueLayout.JAVA_FLOAT, database);
        int r = (int) cuMemcpyHtoD.invoke(dDatabase, dbSegment, dbBytes);
        if (r != 0) throw new RuntimeException("cuMemcpyHtoD failed: " + r);

        log.info("Database uploaded to GPU successfully");

        // Load PTX kernel
        try (var ptxStream = GpuVectorIndex.class.getResourceAsStream("/kernels/batch_cosine.ptx")) {
            if (ptxStream == null) throw new RuntimeException("batch_cosine.ptx not found");
            String ptx = new String(ptxStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            MemorySegment ptxBytes = arena.allocateFrom(ptx);

            MemorySegment modulePtr = arena.allocate(ValueLayout.ADDRESS);
            MethodHandle cuModuleLoadData = linker.downcallHandle(
                    cudaLib.find("cuModuleLoadData").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            result = (int) cuModuleLoadData.invoke(modulePtr, ptxBytes);
            if (result != 0) throw new RuntimeException("cuModuleLoadData failed: " + result);

            long module = modulePtr.get(ValueLayout.ADDRESS, 0).address();

            MemorySegment funcPtr = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment funcName = arena.allocateFrom("batch_cosine");
            MethodHandle cuModuleGetFunction = linker.downcallHandle(
                    cudaLib.find("cuModuleGetFunction").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            result = (int) cuModuleGetFunction.invoke(funcPtr, MemorySegment.ofAddress(module), funcName);
            if (result != 0) throw new RuntimeException("cuModuleGetFunction failed: " + result);

            long cuFunction = funcPtr.get(ValueLayout.ADDRESS, 0).address();

            MethodHandle cuMemcpyDtoH = linker.downcallHandle(
                    cudaLib.find("cuMemcpyDtoH_v2").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));

            MethodHandle cuLaunchKernel = linker.downcallHandle(
                    cudaLib.find("cuLaunchKernel").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            MethodHandle cuCtxSynchronize = linker.downcallHandle(
                    cudaLib.find("cuCtxSynchronize").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));

            log.info("GPU kernel loaded — ready for search ({} vectors resident in VRAM)", numVectors);

            return new GpuVectorIndex(numVectors, dims, database, true, arena,
                    dDatabase, dResults, dQuery,
                    cuMemcpyHtoD, cuMemcpyDtoH, cuLaunchKernel, cuCtxSynchronize, cuFunction);
        }
    }

    private static long deviceAlloc(Arena arena, MethodHandle cuMemAlloc, long bytes) throws Throwable {
        MemorySegment ptr = arena.allocate(ValueLayout.JAVA_LONG);
        int result = (int) cuMemAlloc.invoke(ptr, bytes);
        if (result != 0) throw new RuntimeException("cuMemAlloc failed: " + result + " (bytes=" + bytes + ")");
        return ptr.get(ValueLayout.JAVA_LONG, 0);
    }

    /**
     * Computes cosine similarity between the query and ALL database vectors.
     * GPU path: only transfers query (tiny) since database is already resident.
     * Falls back to CPU SIMD on any GPU error.
     *
     * @param query query vector (length = dimensions)
     * @return array of numVectors similarity scores
     */
    public float[] search(float[] query) {
        if (closed) throw new IllegalStateException(com.spectrayan.spector.commons.error.ErrorCode.SEGMENT_CLOSED.format());

        if (gpuActive) {
            try {
                return searchGpu(query);
            } catch (Throwable e) {
                log.warn("GPU search failed, falling back to CPU SIMD: {}", e.getMessage());
            }
        }
        return searchCpu(query);
    }

    private float[] searchGpu(float[] query) throws Throwable {
        long queryBytes = (long) dimensions * Float.BYTES;
        long resultBytes = (long) numVectors * Float.BYTES;

        // Upload query to device (only 1.5KB for 384-dim)
        try (Arena local = Arena.ofConfined()) {
            MemorySegment querySegment = local.allocateFrom(ValueLayout.JAVA_FLOAT, query);
            int r = (int) cuMemcpyHtoD.invoke(dQuery, querySegment, queryBytes);
            if (r != 0) throw new RuntimeException("Query upload failed: " + r);

            // Set up kernel params
            MemorySegment paramsArray = local.allocate(ValueLayout.ADDRESS, 5);
            MemorySegment pQuery = local.allocate(ValueLayout.JAVA_LONG);
            pQuery.set(ValueLayout.JAVA_LONG, 0, dQuery);
            MemorySegment pDb = local.allocate(ValueLayout.JAVA_LONG);
            pDb.set(ValueLayout.JAVA_LONG, 0, dDatabase);
            MemorySegment pRes = local.allocate(ValueLayout.JAVA_LONG);
            pRes.set(ValueLayout.JAVA_LONG, 0, dResults);
            MemorySegment pN = local.allocate(ValueLayout.JAVA_INT);
            pN.set(ValueLayout.JAVA_INT, 0, numVectors);
            MemorySegment pDims = local.allocate(ValueLayout.JAVA_INT);
            pDims.set(ValueLayout.JAVA_INT, 0, dimensions);

            paramsArray.setAtIndex(ValueLayout.ADDRESS, 0, pQuery);
            paramsArray.setAtIndex(ValueLayout.ADDRESS, 1, pDb);
            paramsArray.setAtIndex(ValueLayout.ADDRESS, 2, pRes);
            paramsArray.setAtIndex(ValueLayout.ADDRESS, 3, pN);
            paramsArray.setAtIndex(ValueLayout.ADDRESS, 4, pDims);

            // Launch kernel
            int blockSize = 256;
            int gridSize = (numVectors + blockSize - 1) / blockSize;

            r = (int) cuLaunchKernel.invoke(
                    MemorySegment.ofAddress(cuFunction),
                    gridSize, 1, 1, blockSize, 1, 1,
                    0, MemorySegment.NULL, paramsArray, MemorySegment.NULL);
            if (r != 0) throw new RuntimeException("Kernel launch failed: " + r);

            r = (int) cuCtxSynchronize.invoke();
            if (r != 0) throw new RuntimeException("Sync failed: " + r);

            // Download results
            MemorySegment resultSegment = local.allocate(ValueLayout.JAVA_FLOAT, numVectors);
            r = (int) cuMemcpyDtoH.invoke(resultSegment, dResults, resultBytes);
            if (r != 0) throw new RuntimeException("Result download failed: " + r);

            float[] results = new float[numVectors];
            MemorySegment.copy(resultSegment, ValueLayout.JAVA_FLOAT, 0, results, 0, numVectors);
            return results;
        }
    }

    /** CPU SIMD brute-force fallback. */
    private float[] searchCpu(float[] query) {
        float[] results = new float[numVectors];
        int laneCount = SPECIES.length();
        int simdBound = SPECIES.loopBound(dimensions);

        // Precompute query norm
        FloatVector qNormAcc = FloatVector.zero(SPECIES);
        int d = 0;
        for (; d < simdBound; d += laneCount) {
            FloatVector qv = FloatVector.fromArray(SPECIES, query, d);
            qNormAcc = qv.fma(qv, qNormAcc);
        }
        float queryNormSq = qNormAcc.reduceLanes(VectorOperators.ADD);
        for (; d < dimensions; d++) queryNormSq += query[d] * query[d];
        float queryNorm = (float) Math.sqrt(queryNormSq);
        if (queryNorm == 0) return results;

        for (int i = 0; i < numVectors; i++) {
            int offset = i * dimensions;
            FloatVector dotAcc = FloatVector.zero(SPECIES);
            FloatVector normAcc = FloatVector.zero(SPECIES);
            d = 0;
            for (; d < simdBound; d += laneCount) {
                FloatVector qv = FloatVector.fromArray(SPECIES, query, d);
                FloatVector dv = FloatVector.fromArray(SPECIES, cpuDatabase, offset + d);
                dotAcc = qv.fma(dv, dotAcc);
                normAcc = dv.fma(dv, normAcc);
            }
            float dot = dotAcc.reduceLanes(VectorOperators.ADD);
            float docNormSq = normAcc.reduceLanes(VectorOperators.ADD);
            for (; d < dimensions; d++) {
                dot += query[d] * cpuDatabase[offset + d];
                docNormSq += cpuDatabase[offset + d] * cpuDatabase[offset + d];
            }
            float docNorm = (float) Math.sqrt(docNormSq);
            results[i] = docNorm > 0 ? dot / (queryNorm * docNorm) : 0;
        }
        return results;
    }

    /** Returns true if GPU is active for this index. */
    public boolean isGpuActive() { return gpuActive; }

    /** Returns the number of vectors stored. */
    public int size() { return numVectors; }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            if (gpuActive && arena != null) {
                try {
                    // Free device memory via cuMemFree
                    Linker linker = Linker.nativeLinker();
                    String libName = System.getProperty("os.name").toLowerCase().contains("win") ? "nvcuda" : "cuda";
                    try (Arena localArena = Arena.ofConfined()) {
                        SymbolLookup lib = SymbolLookup.libraryLookup(libName, localArena);
                        MethodHandle cuMemFree = linker.downcallHandle(
                                lib.find("cuMemFree_v2").orElseThrow(),
                                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
                        cuMemFree.invoke(dDatabase);
                        cuMemFree.invoke(dResults);
                        cuMemFree.invoke(dQuery);
                    }
                } catch (Throwable e) {
                    log.warn("Error freeing GPU memory: {}", e.getMessage());
                }
                arena.close();
                log.info("GpuVectorIndex closed — {} vectors freed from VRAM", numVectors);
            }
        }
    }
}
