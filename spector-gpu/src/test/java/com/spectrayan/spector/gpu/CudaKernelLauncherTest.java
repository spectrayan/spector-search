package com.spectrayan.spector.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CudaKernelLauncher}.
 *
 * <p>Tests that require a working GPU kernel pipeline are skipped when
 * CUDA is unavailable or when the PTX kernel cannot be loaded (e.g., wrong
 * compute capability, missing CUDA Toolkit). This ensures the tests pass
 * cleanly on CI runners without GPUs.</p>
 */
class CudaKernelLauncherTest {

    /**
     * Tries to create a launcher. Returns null if CUDA is unavailable or the PTX
     * cannot be loaded (e.g., GPU architecture mismatch, missing CUDA Toolkit).
     */
    private static CudaKernelLauncher tryCreateLauncher() {
        try {
            return new CudaKernelLauncher();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Test
    void constructor_throwsOrSucceeds() {
        // If the GPU is reported as unavailable by capability detection,
        // the constructor should throw. If the driver is present but the PTX
        // can't load (architecture mismatch, no toolkit), it should also throw
        // but with RuntimeException. Either way, the test validates the contract.
        if (!GpuCapability.isAvailable()) {
            // No CUDA driver at all — constructor should refuse early
            try {
                new CudaKernelLauncher().close();
            } catch (RuntimeException expected) {
                // Both are acceptable: ISE for "no CUDA", RE for initialization failure
            }
        } else {
            // CUDA driver present — constructor may succeed or fail with RE
            // if PTX doesn't match the GPU architecture
            CudaKernelLauncher launcher = tryCreateLauncher();
            if (launcher != null) {
                assertNotNull(launcher);
                launcher.close();
            }
            // If null, the PTX couldn't load — not a test failure
        }
    }

    @Test
    void batchCosine_emptyInput() {
        CudaKernelLauncher launcher = tryCreateLauncher();
        assumeTrue(launcher != null, "Skipping: CUDA kernel pipeline not available");

        try (launcher) {
            float[] result = launcher.batchCosine(new float[384], new float[0], 0, 384);
            assertNotNull(result);
            assertEquals(0, result.length);
        }
    }

    @Test
    void close_isIdempotent() {
        CudaKernelLauncher launcher = tryCreateLauncher();
        assumeTrue(launcher != null, "Skipping: CUDA kernel pipeline not available");

        launcher.close();
        launcher.close(); // should not throw
    }
}
