package com.spectrayan.spector.gpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GpuCapability} — GPU detection.
 *
 * <p>These tests are designed to pass regardless of whether a CUDA GPU
 * is available on the test machine.</p>
 */
class GpuCapabilityTest {

    @Test
    void detect_returnsNonNullResult() {
        GpuCapability.GpuInfo info = GpuCapability.detect();
        assertNotNull(info);
        assertNotNull(info.report());
    }

    @Test
    void detect_isCached() {
        GpuCapability.GpuInfo first = GpuCapability.detect();
        GpuCapability.GpuInfo second = GpuCapability.detect();
        assertSame(first, second, "Detection should be cached");
    }

    @Test
    void gpuInfo_unavailable_hasErrorMessage() {
        var info = GpuCapability.GpuInfo.unavailable("test reason");
        assertFalse(info.available());
        assertEquals(0, info.deviceCount());
        assertEquals("test reason", info.errorMessage());
        assertTrue(info.report().contains("unavailable"));
    }

    @Test
    void gpuInfo_available_hasDeviceInfo() {
        var info = GpuCapability.GpuInfo.available(1, "RTX 4090", 24L * 1024 * 1024 * 1024, 8, 9);
        assertTrue(info.available());
        assertEquals(1, info.deviceCount());
        assertEquals("RTX 4090", info.deviceName());
        assertTrue(info.report().contains("RTX 4090"));
        assertNull(info.errorMessage());
    }
}
