package com.spectrayan.spector.gpu;

import com.spectrayan.spector.commons.error.SpectorException;

import com.spectrayan.spector.commons.error.SpectorValidationException;

import com.spectrayan.spector.commons.error.SpectorGpuMemoryException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GpuMemoryManager}.
 *
 * <p>Tests run in simulated mode (no GPU required) to validate budget
 * enforcement, allocation tracking, metrics, and lifecycle management.</p>
 */
class GpuMemoryManagerTest {

    private static final long BUDGET_512MB = 512L * 1024 * 1024;
    private static final long BUDGET_256MB = 256L * 1024 * 1024;

    private GpuMemoryManager manager;

    @BeforeEach
    void setUp() {
        // Use simulated mode (no real GPU required) for unit testing
        manager = new GpuMemoryManager(BUDGET_512MB, true);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    void constructor_rejectsBudgetBelowMinimum() {
        assertThrows(SpectorValidationException.class, () ->
                new GpuMemoryManager(100L * 1024 * 1024)); // 100MB < 256MB minimum
    }

    @Test
    void constructor_acceptsMinimumBudget() {
        try (var mgr = new GpuMemoryManager(BUDGET_256MB, true)) {
            assertEquals(BUDGET_256MB, mgr.getMaxBudgetBytes());
        }
    }

    @Test
    void allocateDevice_returnsNonNullSegment() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = manager.allocateDevice(1024, arena);
            assertNotNull(segment);
        }
    }

    @Test
    void allocateDevice_tracksAllocation() {
        try (Arena arena = Arena.ofConfined()) {
            manager.allocateDevice(4096, arena);
            GpuMemoryMetrics metrics = manager.getMetrics();
            assertEquals(4096, metrics.totalAllocatedBytes());
            assertEquals(1, metrics.activeSegments());
        }
    }

    @Test
    void allocateDevice_multipleAllocationsAccumulate() {
        try (Arena arena = Arena.ofConfined()) {
            manager.allocateDevice(1024, arena);
            manager.allocateDevice(2048, arena);
            manager.allocateDevice(4096, arena);

            GpuMemoryMetrics metrics = manager.getMetrics();
            assertEquals(1024 + 2048 + 4096, metrics.totalAllocatedBytes());
            assertEquals(3, metrics.activeSegments());
        }
    }

    @Test
    void allocateDevice_rejectsZeroSize() {
        try (Arena arena = Arena.ofConfined()) {
            assertThrows(SpectorValidationException.class, () ->
                    manager.allocateDevice(0, arena));
        }
    }

    @Test
    void allocateDevice_rejectsNegativeSize() {
        try (Arena arena = Arena.ofConfined()) {
            assertThrows(SpectorValidationException.class, () ->
                    manager.allocateDevice(-1, arena));
        }
    }

    @Test
    void allocateDevice_enforceBudget() {
        try (Arena arena = Arena.ofConfined()) {
            // Allocate most of budget
            manager.allocateDevice(500L * 1024 * 1024, arena);

            // This should exceed budget
            assertThrows(SpectorGpuMemoryException.class, () ->
                    manager.allocateDevice(50L * 1024 * 1024, arena));
        }
    }

    @Test
    void allocateDevice_budgetExceptionContainsDetails() {
        try (Arena arena = Arena.ofConfined()) {
            manager.allocateDevice(500L * 1024 * 1024, arena);

            SpectorGpuMemoryException ex = assertThrows(SpectorGpuMemoryException.class, () ->
                    manager.allocateDevice(50L * 1024 * 1024, arena));

            assertEquals(50L * 1024 * 1024, ex.getRequestedBytes());
            assertTrue(ex.getAvailableBytes() < 50L * 1024 * 1024);
        }
    }

    @Test
    void allocateDevice_releasedOnArenaClose() throws InterruptedException {
        Arena arena = Arena.ofConfined();
        manager.allocateDevice(8192, arena);
        assertEquals(8192, manager.getMetrics().totalAllocatedBytes());

        // Close the arena — should trigger release within 100ms
        arena.close();
        Thread.sleep(150); // Wait for async cleanup

        assertEquals(0, manager.getMetrics().totalAllocatedBytes());
        assertEquals(0, manager.getActiveAllocationCount());
    }

    @Test
    void allocatePinned_returnsUsableSegment() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pinned = manager.allocatePinned(1024, arena);
            assertNotNull(pinned);
            assertTrue(pinned.byteSize() >= 1024);
        }
    }

    @Test
    void allocatePinned_tracksAllocation() {
        try (Arena arena = Arena.ofConfined()) {
            manager.allocatePinned(2048, arena);
            GpuMemoryMetrics metrics = manager.getMetrics();
            assertEquals(2048, metrics.totalAllocatedBytes());
            assertEquals(1, metrics.activeSegments());
        }
    }

    @Test
    void allocatePinned_enforceBudget() {
        try (Arena arena = Arena.ofConfined()) {
            manager.allocateDevice(500L * 1024 * 1024, arena);

            assertThrows(SpectorGpuMemoryException.class, () ->
                    manager.allocatePinned(50L * 1024 * 1024, arena));
        }
    }

    @Test
    void getMetrics_reflectsCurrentState() {
        try (Arena arena = Arena.ofConfined()) {
            GpuMemoryMetrics empty = manager.getMetrics();
            assertEquals(0, empty.totalAllocatedBytes());
            assertEquals(0, empty.activeSegments());
            assertTrue(empty.segmentSizes().isEmpty());

            manager.allocateDevice(1024, arena);
            manager.allocateDevice(2048, arena);

            GpuMemoryMetrics afterAlloc = manager.getMetrics();
            assertEquals(3072, afterAlloc.totalAllocatedBytes());
            assertEquals(2, afterAlloc.activeSegments());
            assertEquals(2, afterAlloc.segmentSizes().size());
            assertTrue(afterAlloc.segmentSizes().containsValue(1024L));
            assertTrue(afterAlloc.segmentSizes().containsValue(2048L));
        }
    }

    @Test
    void getAvailableBytes_decreasesWithAllocations() {
        assertEquals(BUDGET_512MB, manager.getAvailableBytes());

        try (Arena arena = Arena.ofConfined()) {
            manager.allocateDevice(1024 * 1024, arena);
            assertEquals(BUDGET_512MB - 1024 * 1024, manager.getAvailableBytes());
        }
    }

    @Test
    void close_releasesAllAllocations() {
        Arena arena = Arena.ofShared();
        manager.allocateDevice(1024, arena);
        manager.allocateDevice(2048, arena);
        assertEquals(2, manager.getActiveAllocationCount());

        manager.close();
        assertEquals(0, manager.getActiveAllocationCount());

        arena.close();
    }

    @Test
    void close_rejectsSubsequentAllocations() {
        manager.close();
        try (Arena arena = Arena.ofConfined()) {
            assertThrows(SpectorException.class, () ->
                    manager.allocateDevice(1024, arena));
        }
    }

    @Test
    void mixedAllocations_deviceAndPinned() {
        try (Arena arena = Arena.ofConfined()) {
            manager.allocateDevice(1024, arena);
            manager.allocatePinned(2048, arena);

            GpuMemoryMetrics metrics = manager.getMetrics();
            assertEquals(3072, metrics.totalAllocatedBytes());
            assertEquals(2, metrics.activeSegments());
        }
    }
}
