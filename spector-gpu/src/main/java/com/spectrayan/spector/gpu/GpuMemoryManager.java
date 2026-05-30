package com.spectrayan.spector.gpu;

import com.spectrayan.spector.gpu.error.SpectorGpuMemoryException;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.SpectorServerException;
import com.spectrayan.spector.commons.error.SpectorGpuException;
import com.spectrayan.spector.storage.error.SpectorSegmentClosedException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Manages GPU device memory allocation and lifecycle via Panama FFM.
 *
 * <p>Provides explicit memory management for CUDA device memory using
 * Panama FFM {@link MemorySegment}s bound to {@link Arena} scopes.
 * When a segment's arena is closed, the corresponding device memory
 * is released within 100ms.</p>
 *
 * <h3>Features</h3>
 * <ul>
 *   <li>Device memory allocation bound to Arena lifecycle</li>
 *   <li>Pinned host memory for zero-copy host-device transfers</li>
 *   <li>Configurable memory budget enforcement (256MB to available GPU memory)</li>
 *   <li>Real-time metrics reporting (total bytes, active segments, per-segment sizes)</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * try (var manager = new GpuMemoryManager(512 * 1024 * 1024L)) {
 *     Arena arena = Arena.ofConfined();
 *     MemorySegment deviceMem = manager.allocateDevice(1024 * 1024, arena);
 *     // ... use deviceMem ...
 *     arena.close(); // triggers device memory release
 * }
 * }</pre>
 *
 * @see GpuCapability
 * @see GpuMemoryMetrics
 * @see GpuAllocation
 */
public class GpuMemoryManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GpuMemoryManager.class);

    /** Minimum configurable budget: 256 MB */
    private static final long MIN_BUDGET_BYTES = 256L * 1024 * 1024;

    /** ID generator for allocation tracking */
    private static final AtomicLong ID_GENERATOR = new AtomicLong(0);

    private final long maxBudgetBytes;
    private final AtomicLong totalAllocatedBytes;
    private final ConcurrentHashMap<Long, GpuAllocation> allocations;

    // Panama FFM handles
    private final Arena managerArena;
    private final Linker linker;
    private final SymbolLookup cudaLib;
    private final MemorySegment cuContext;
    private final MethodHandle cuMemAlloc;
    private final MethodHandle cuMemFree;
    private final MethodHandle cuMemAllocHost;
    private final MethodHandle cuMemFreeHost;

    /** Whether real GPU operations are active (vs simulated mode). */
    private final boolean gpuActive;

    private volatile boolean closed;

    /**
     * Creates a GpuMemoryManager with the specified maximum memory budget.
     *
     * <p>If CUDA is not available, the manager operates in a simulated mode
     * that tracks allocations without actual GPU memory (useful for testing
     * and CPU-fallback scenarios).</p>
     *
     * @param maxBudgetBytes maximum device memory budget in bytes (minimum 256MB)
     * @throws SpectorValidationException if budget is below 256MB
     */
    public GpuMemoryManager(long maxBudgetBytes) {
        this(maxBudgetBytes, !GpuCapability.isAvailable());
    }

    /**
     * Creates a GpuMemoryManager with the specified maximum memory budget and mode.
     *
     * @param maxBudgetBytes maximum device memory budget in bytes (minimum 256MB)
     * @param simulatedMode  if true, operates without real GPU memory (for testing)
     * @throws SpectorValidationException if budget is below 256MB
     */
    public GpuMemoryManager(long maxBudgetBytes, boolean simulatedMode) {
        if (maxBudgetBytes < MIN_BUDGET_BYTES) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "Memory budget must be at least 256MB, got: %d bytes (%d MB)" .formatted(maxBudgetBytes, maxBudgetBytes / (1024 * 1024)));
        }

        this.maxBudgetBytes = maxBudgetBytes;
        this.totalAllocatedBytes = new AtomicLong(0);
        this.allocations = new ConcurrentHashMap<>();
        this.closed = false;
        this.managerArena = Arena.ofShared();
        this.linker = Linker.nativeLinker();

        if (!simulatedMode && GpuCapability.isAvailable()) {
            try {
                String libName = System.getProperty("os.name").toLowerCase().contains("win")
                        ? "nvcuda" : "cuda";
                this.cudaLib = SymbolLookup.libraryLookup(libName, managerArena);

                // Create a CUDA context on device 0 so that allocations succeed
                MemorySegment ctxPtr = managerArena.allocate(ValueLayout.ADDRESS);
                MethodHandle cuCtxCreate = linker.downcallHandle(
                        cudaLib.find("cuCtxCreate_v2").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
                int ctxResult = (int) cuCtxCreate.invoke(ctxPtr, 0, 0);
                if (ctxResult != 0) {
                    throw new SpectorGpuException(ErrorCode.GPU_DEVICE_ERROR, "cuCtxCreate failed", ctxResult);
                }
                this.cuContext = ctxPtr.get(ValueLayout.ADDRESS, 0);

                this.cuMemAlloc = linker.downcallHandle(
                        cudaLib.find("cuMemAlloc_v2").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

                this.cuMemFree = linker.downcallHandle(
                        cudaLib.find("cuMemFree_v2").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));

                this.cuMemAllocHost = linker.downcallHandle(
                        cudaLib.find("cuMemAllocHost_v2").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

                this.cuMemFreeHost = linker.downcallHandle(
                        cudaLib.find("cuMemFreeHost").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

                this.gpuActive = true;
                log.info("GpuMemoryManager initialized: budget={}MB, GPU={}",
                        maxBudgetBytes / (1024 * 1024), GpuCapability.detect().deviceName());
            } catch (Throwable e) {
                throw new SpectorServerException(ErrorCode.INTERNAL_ERROR, e, "Failed to initialize CUDA memory handles");
            }
        } else {
            // Simulated mode — no actual GPU, but track allocations for testing
            this.cudaLib = null;
            this.cuContext = null;
            this.cuMemAlloc = null;
            this.cuMemFree = null;
            this.cuMemAllocHost = null;
            this.cuMemFreeHost = null;
            this.gpuActive = false;
            log.info("GpuMemoryManager initialized in simulated mode: budget={}MB",
                    maxBudgetBytes / (1024 * 1024));
        }
    }

    /**
     * Allocates device memory bound to the given Arena's lifecycle.
     *
     * <p>When the provided Arena is closed, the device memory will be
     * released automatically within 100ms. The returned MemorySegment
     * represents the host-side handle for the allocation.</p>
     *
     * @param size  number of bytes to allocate on the device
     * @param arena the Arena scope that determines the allocation's lifetime
     * @return a MemorySegment representing the device allocation
     * @throws SpectorGpuMemoryException if allocation fails or would exceed budget
     * @throws SpectorGpuException if the manager is closed
     */
    public MemorySegment allocateDevice(long size, Arena arena) {
        ensureOpen();
        validateSize(size);
        enforceBudget(size);

        long allocationId = ID_GENERATOR.incrementAndGet();
        long devicePointer;

        if (gpuActive) {
            // Real GPU allocation
            devicePointer = cudaAllocDevice(size);
        } else {
            // Simulated allocation — use a synthetic pointer
            devicePointer = allocationId * 0x10000L;
        }

        // Track the allocation
        GpuAllocation allocation = new GpuAllocation(devicePointer, size, arena, Instant.now());
        allocations.put(allocationId, allocation);
        totalAllocatedBytes.addAndGet(size);

        // Create a host-side MemorySegment within the caller's arena
        MemorySegment segment = arena.allocate(ValueLayout.JAVA_LONG, devicePointer);

        // Monitor segment accessibility to detect arena closure
        final long monitorId = allocationId;
        Thread.startVirtualThread(() -> monitorSegmentClose(monitorId, allocation, segment));

        log.debug("Allocated device memory: id={}, size={} bytes, devicePtr=0x{}",
                allocationId, size, Long.toHexString(devicePointer));

        return segment;
    }

    /**
     * Allocates pinned (page-locked) host memory for zero-copy transfers.
     *
     * <p>Pinned memory avoids intermediate buffer copies during host-to-device
     * and device-to-host transfers, improving transfer throughput for large
     * data blocks.</p>
     *
     * @param size  number of bytes to allocate as pinned host memory
     * @param arena the Arena scope that determines the allocation's lifetime
     * @return a MemorySegment backed by pinned host memory
     * @throws SpectorGpuMemoryException if allocation fails or would exceed budget
     * @throws SpectorGpuException if the manager is closed
     */
    public MemorySegment allocatePinned(long size, Arena arena) {
        ensureOpen();
        validateSize(size);
        enforceBudget(size);

        long allocationId = ID_GENERATOR.incrementAndGet();
        MemorySegment pinnedSegment;

        if (gpuActive) {
            // Real pinned allocation via CUDA
            pinnedSegment = cudaAllocPinned(size, arena);
        } else {
            // Simulated — allocate regular host memory as stand-in
            pinnedSegment = arena.allocate(size);
        }

        long devicePointer = pinnedSegment.address();
        GpuAllocation allocation = new GpuAllocation(devicePointer, size, arena, Instant.now());
        allocations.put(allocationId, allocation);
        totalAllocatedBytes.addAndGet(size);

        // Monitor for arena close to clean up tracking
        Thread.startVirtualThread(() -> monitorSegmentPinnedClose(allocationId, allocation, pinnedSegment));

        log.debug("Allocated pinned memory: id={}, size={} bytes", allocationId, size);

        return pinnedSegment;
    }

    /**
     * Returns current memory usage metrics.
     *
     * @return metrics snapshot with total bytes, active segments, and per-segment sizes
     */
    public GpuMemoryMetrics getMetrics() {
        Map<Long, Long> segmentSizes = new HashMap<>();
        for (var entry : allocations.entrySet()) {
            segmentSizes.put(entry.getKey(), entry.getValue().sizeBytes());
        }
        return new GpuMemoryMetrics(
                totalAllocatedBytes.get(),
                allocations.size(),
                segmentSizes
        );
    }

    /**
     * Returns the configured maximum memory budget in bytes.
     *
     * @return max budget in bytes
     */
    public long getMaxBudgetBytes() {
        return maxBudgetBytes;
    }

    /**
     * Returns the remaining available budget in bytes.
     *
     * @return available bytes before budget is exhausted
     */
    public long getAvailableBytes() {
        return maxBudgetBytes - totalAllocatedBytes.get();
    }

    /**
     * Returns the number of currently active allocations.
     *
     * @return active allocation count
     */
    public int getActiveAllocationCount() {
        return allocations.size();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;

            // Release all tracked allocations
            for (var entry : allocations.entrySet()) {
                releaseAllocation(entry.getKey(), entry.getValue());
            }
            allocations.clear();
            totalAllocatedBytes.set(0);

            // Destroy CUDA context if we created one
            if (gpuActive && cuContext != null) {
                try {
                    MethodHandle cuCtxDestroy = linker.downcallHandle(
                            cudaLib.find("cuCtxDestroy_v2").orElseThrow(),
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                    cuCtxDestroy.invoke(cuContext);
                } catch (Throwable e) {
                    log.warn("Error destroying CUDA context", e);
                }
            }

            managerArena.close();
            log.info("GpuMemoryManager closed, all allocations released");
        }
    }

    // ──── Internal methods ────────────────────────────────────────────────

    private void ensureOpen() {
        if (closed) {
            throw new SpectorSegmentClosedException();
        }
    }

    private void validateSize(long size) {
        if (size <= 0) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "size", 1, Integer.MAX_VALUE, size);
        }
    }

    private void enforceBudget(long requestedSize) {
        long currentUsage = totalAllocatedBytes.get();
        long available = maxBudgetBytes - currentUsage;

        if (requestedSize > available) {
            throw new SpectorGpuMemoryException(
                    "Allocation of %d bytes would exceed budget. Budget: %d bytes, Used: %d bytes, Available: %d bytes"
                            .formatted(requestedSize, maxBudgetBytes, currentUsage, available),
                    requestedSize,
                    available
            );
        }
    }

    private long cudaAllocDevice(long size) {
        try (var localArena = Arena.ofConfined()) {
            MemorySegment ptrHolder = localArena.allocate(ValueLayout.JAVA_LONG);
            int result = (int) cuMemAlloc.invoke(ptrHolder, size);
            if (result != 0) {
                long available = queryAvailableDeviceMemory();
                throw new SpectorGpuMemoryException(
                        "cuMemAlloc failed (error %d) for %d bytes. Available device memory: %d bytes"
                                .formatted(result, size, available),
                        size,
                        available
                );
            }
            return ptrHolder.get(ValueLayout.JAVA_LONG, 0);
        } catch (SpectorGpuMemoryException e) {
            throw e;
        } catch (Throwable e) {
            throw new SpectorGpuMemoryException(
                    "Device memory allocation failed: " + e.getMessage(),
                    e, size, -1
            );
        }
    }

    private MemorySegment cudaAllocPinned(long size, Arena arena) {
        try (var localArena = Arena.ofConfined()) {
            MemorySegment ptrHolder = localArena.allocate(ValueLayout.ADDRESS);
            int result = (int) cuMemAllocHost.invoke(ptrHolder, size);
            if (result != 0) {
                throw new SpectorGpuMemoryException(
                        "cuMemAllocHost failed (error %d) for %d bytes".formatted(result, size),
                        size,
                        getAvailableBytes()
                );
            }
            MemorySegment hostPtr = ptrHolder.get(ValueLayout.ADDRESS, 0);
            // Reinterpret with the caller's arena scope and desired size
            return hostPtr.reinterpret(size, arena, null);
        } catch (SpectorGpuMemoryException e) {
            throw e;
        } catch (Throwable e) {
            throw new SpectorGpuMemoryException(
                    "Pinned memory allocation failed: " + e.getMessage(),
                    e, size, -1
            );
        }
    }

    private void monitorSegmentClose(long allocationId, GpuAllocation allocation, MemorySegment segment) {
        // Poll the segment's scope liveness. scope().isAlive() is safe to call from any thread.
        while (!closed && allocations.containsKey(allocationId)) {
            try {
                if (!segment.scope().isAlive()) {
                    // Arena/segment is closed — release device memory
                    releaseAllocation(allocationId, allocation);
                    return;
                }
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void monitorSegmentPinnedClose(long allocationId, GpuAllocation allocation,
                                            MemorySegment pinnedSegment) {
        while (!closed && allocations.containsKey(allocationId)) {
            try {
                if (!pinnedSegment.scope().isAlive()) {
                    // Arena closed — free pinned memory and remove from tracking
                    if (gpuActive) {
                        try {
                            cuMemFreeHost.invoke(pinnedSegment);
                        } catch (Throwable t) {
                            log.warn("cuMemFreeHost failed for allocation {}", allocationId, t);
                        }
                    }
                    removeAllocation(allocationId, allocation);
                    return;
                }
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void releaseAllocation(long allocationId, GpuAllocation allocation) {
        if (gpuActive) {
            try {
                int result = (int) cuMemFree.invoke(allocation.devicePointer());
                if (result != 0) {
                    log.warn("cuMemFree failed for allocation {} (error {})", allocationId, result);
                }
            } catch (Throwable e) {
                log.warn("Failed to free device memory for allocation {}", allocationId, e);
            }
        }
        removeAllocation(allocationId, allocation);
    }

    private void removeAllocation(long allocationId, GpuAllocation allocation) {
        if (allocations.remove(allocationId) != null) {
            totalAllocatedBytes.addAndGet(-allocation.sizeBytes());
            log.debug("Released allocation: id={}, size={} bytes", allocationId, allocation.sizeBytes());
        }
    }

    private long queryAvailableDeviceMemory() {
        // Best-effort query of free device memory via cuMemGetInfo
        try {
            var cuMemGetInfo = linker.downcallHandle(
                    cudaLib.find("cuMemGetInfo_v2").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            try (var localArena = Arena.ofConfined()) {
                MemorySegment freePtr = localArena.allocate(ValueLayout.JAVA_LONG);
                MemorySegment totalPtr = localArena.allocate(ValueLayout.JAVA_LONG);
                int result = (int) cuMemGetInfo.invoke(freePtr, totalPtr);
                if (result == 0) {
                    return freePtr.get(ValueLayout.JAVA_LONG, 0);
                }
            }
        } catch (Throwable ignored) {
            // Fall through
        }
        return -1;
    }
}