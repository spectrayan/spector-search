package com.spectrayan.spector.gpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CudaKernelLauncher}.
 *
 * <p>Tests run regardless of CUDA availability —
 * they validate the API contract and error handling.</p>
 */
class CudaKernelLauncherTest {

    @Test
    void constructor_throwsWhenCudaUnavailable() {
        if (GpuCapability.isAvailable()) {
            // CUDA available — constructor should succeed
            try (var launcher = new CudaKernelLauncher()) {
                assertFalse(launcher.isModuleLoaded());
            }
        } else {
            // CUDA unavailable — constructor should throw
            assertThrows(IllegalStateException.class, CudaKernelLauncher::new);
        }
    }

    @Test
    void moduleLoaded_falseByDefault() {
        if (!GpuCapability.isAvailable()) return; // skip if no CUDA

        try (var launcher = new CudaKernelLauncher()) {
            assertFalse(launcher.isModuleLoaded());
        }
    }

    @Test
    void getFunction_throwsWithoutModule() {
        if (!GpuCapability.isAvailable()) return; // skip if no CUDA

        try (var launcher = new CudaKernelLauncher()) {
            assertThrows(IllegalStateException.class,
                    () -> launcher.getFunction("nonexistent"));
        }
    }
}
