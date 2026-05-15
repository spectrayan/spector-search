package com.spectrayan.spector.gpu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CUDA kernel loader and executor via Panama FFM.
 *
 * <p>Loads PTX (CUDA compiled) kernels at runtime and provides methods to
 * launch them with typed arguments. This is the low-level bridge between
 * Java and custom GPU code.</p>
 *
 * <h3>Kernel Lifecycle</h3>
 * <ol>
 *   <li>Load PTX from file or resource</li>
 *   <li>Create a CUDA module from the PTX</li>
 *   <li>Get function handles from the module</li>
 *   <li>Launch kernels with grid/block dimensions</li>
 *   <li>Close to free GPU resources</li>
 * </ol>
 *
 * <h3>Bundled Kernels</h3>
 * <ul>
 *   <li><b>batch_cosine</b>: Computes N cosine similarities in parallel</li>
 *   <li><b>batch_dot</b>: Computes N dot products in parallel</li>
 *   <li><b>batch_l2</b>: Computes N L2 distances in parallel</li>
 * </ul>
 *
 * @see GpuBatchSimilarity
 * @see GpuCapability
 */
public class CudaKernelLauncher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CudaKernelLauncher.class);

    private final Arena arena;
    private final SymbolLookup cudaLib;
    private final Linker linker;

    private MemorySegment cuModule;
    private volatile boolean closed;

    /**
     * Creates a CUDA kernel launcher.
     *
     * @throws IllegalStateException if CUDA is not available
     */
    public CudaKernelLauncher() {
        if (!GpuCapability.isAvailable()) {
            throw new IllegalStateException("CUDA GPU not available");
        }

        this.arena = Arena.ofShared();
        this.linker = Linker.nativeLinker();
        this.closed = false;

        String libName = System.getProperty("os.name").toLowerCase().contains("win")
                ? "nvcuda" : "cuda";
        this.cudaLib = SymbolLookup.libraryLookup(libName, arena);

        log.info("CudaKernelLauncher initialized");
    }

    /**
     * Loads a PTX kernel module from a file.
     *
     * @param ptxPath path to the .ptx file
     * @return this launcher for chaining
     * @throws RuntimeException if loading fails
     */
    public CudaKernelLauncher loadModule(Path ptxPath) {
        ensureOpen();
        try {
            String ptxSource = Files.readString(ptxPath);
            return loadModuleFromSource(ptxSource);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load PTX from: " + ptxPath, e);
        }
    }

    /**
     * Loads a PTX kernel module from a source string.
     *
     * @param ptxSource PTX source code
     * @return this launcher for chaining
     */
    public CudaKernelLauncher loadModuleFromSource(String ptxSource) {
        ensureOpen();
        try {
            MemorySegment modulePtr = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment ptxData = arena.allocateFrom(ptxSource);

            MethodHandle cuModuleLoadData = linker.downcallHandle(
                    cudaLib.find("cuModuleLoadData").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            int result = (int) cuModuleLoadData.invoke(modulePtr, ptxData);
            if (result != 0) {
                throw new RuntimeException("cuModuleLoadData failed: " + result);
            }

            this.cuModule = modulePtr.get(ValueLayout.ADDRESS, 0);
            log.info("CUDA module loaded ({} bytes PTX)", ptxSource.length());
            return this;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to load CUDA module", e);
        }
    }

    /**
     * Gets a function handle from the loaded module.
     *
     * @param functionName name of the kernel function
     * @return device function pointer
     */
    public MemorySegment getFunction(String functionName) {
        ensureOpen();
        if (cuModule == null) {
            throw new IllegalStateException("No module loaded. Call loadModule() first.");
        }

        try {
            MemorySegment funcPtr = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment nameStr = arena.allocateFrom(functionName);

            MethodHandle cuModuleGetFunction = linker.downcallHandle(
                    cudaLib.find("cuModuleGetFunction").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            int result = (int) cuModuleGetFunction.invoke(funcPtr, cuModule, nameStr);
            if (result != 0) {
                throw new RuntimeException("cuModuleGetFunction('" + functionName + "') failed: " + result);
            }

            return funcPtr.get(ValueLayout.ADDRESS, 0);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to get function: " + functionName, e);
        }
    }

    /**
     * Launches a kernel with the specified grid and block dimensions.
     *
     * @param function      function handle from {@link #getFunction}
     * @param gridDimX      grid dimension X (number of blocks)
     * @param gridDimY      grid dimension Y
     * @param gridDimZ      grid dimension Z
     * @param blockDimX     block dimension X (threads per block)
     * @param blockDimY     block dimension Y
     * @param blockDimZ     block dimension Z
     * @param sharedMemBytes shared memory per block
     * @param kernelParams  pointer to kernel parameter array
     */
    public void launchKernel(MemorySegment function,
                             int gridDimX, int gridDimY, int gridDimZ,
                             int blockDimX, int blockDimY, int blockDimZ,
                             int sharedMemBytes,
                             MemorySegment kernelParams) {
        ensureOpen();
        try {
            MethodHandle cuLaunchKernel = linker.downcallHandle(
                    cudaLib.find("cuLaunchKernel").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,  // stream (0 = default)
                            ValueLayout.ADDRESS,  // kernelParams
                            ValueLayout.ADDRESS   // extra (null)
                    ));

            int result = (int) cuLaunchKernel.invoke(function,
                    gridDimX, gridDimY, gridDimZ,
                    blockDimX, blockDimY, blockDimZ,
                    sharedMemBytes,
                    MemorySegment.NULL,   // default stream
                    kernelParams,
                    MemorySegment.NULL);  // no extra

            if (result != 0) {
                throw new RuntimeException("cuLaunchKernel failed: " + result);
            }

            // Synchronize
            MethodHandle cuCtxSync = linker.downcallHandle(
                    cudaLib.find("cuCtxSynchronize").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));
            cuCtxSync.invoke();

        } catch (Throwable e) {
            throw new RuntimeException("Kernel launch failed", e);
        }
    }

    /** Returns whether a module is loaded. */
    public boolean isModuleLoaded() { return cuModule != null; }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            if (cuModule != null) {
                try {
                    MethodHandle cuModuleUnload = linker.downcallHandle(
                            cudaLib.find("cuModuleUnload").orElseThrow(),
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                    cuModuleUnload.invoke(cuModule);
                } catch (Throwable e) {
                    log.warn("cuModuleUnload failed", e);
                }
            }
            arena.close();
            log.info("CudaKernelLauncher closed");
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("CudaKernelLauncher is closed");
    }
}
