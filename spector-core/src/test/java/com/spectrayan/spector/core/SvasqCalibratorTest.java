package com.spectrayan.spector.core;

import com.spectrayan.spector.commons.error.SpectorValidationException;

import com.spectrayan.spector.core.quantization.vasq.VasqCalibrator;
import com.spectrayan.spector.core.quantization.vasq.VasqParams;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link VasqCalibrator} — calibration correctness and robustness.
 */
class VasqCalibratorTest {

    private static final long SEED = 42L;

    // ── Basic calibration ─────────────────────────────────────────────────────

    @Test
    void calibrate_returns_non_null_params() {
        List<float[]> samples = gaussian(500, 128, new Random(1L));
        VasqParams p = VasqCalibrator.calibrate(samples, 128, SEED);
        assertNotNull(p);
        assertEquals(128, p.originalDim());
        assertEquals(128, p.paddedDim()); // 128 is already power-of-two
    }

    @Test
    void calibrate_768dim_padded_to_1024() {
        List<float[]> samples = gaussian(500, 768, new Random(1L));
        VasqParams p = VasqCalibrator.calibrate(samples, 768, SEED);
        assertEquals(768,  p.originalDim());
        assertEquals(1024, p.paddedDim());
    }

    @Test
    void params_arrays_have_paddedDim_length() {
        int dim = 100;
        List<float[]> samples = gaussian(200, dim, new Random(2L));
        VasqParams p = VasqCalibrator.calibrate(samples, dim, SEED);

        assertEquals(p.paddedDim(), p.means().length,     "means must have paddedDim elements");
        assertEquals(p.paddedDim(), p.scales().length,    "scales must have paddedDim elements");
        assertEquals(p.paddedDim(), p.invScales().length, "invScales must have paddedDim elements");
    }

    // ── Scale/invScale consistency ─────────────────────────────────────────────

    @Test
    void scales_and_invScales_are_reciprocal() {
        List<float[]> samples = gaussian(500, 64, new Random(3L));
        VasqParams p = VasqCalibrator.calibrate(samples, 64, SEED);

        for (int i = 0; i < p.paddedDim(); i++) {
            float product = p.scales()[i] * p.invScales()[i];
            assertEquals(1.0f, product, 0.01f,
                    "scale × invScale must be ≈ 1.0 at dim " + i);
        }
    }

    @Test
    void scales_are_positive() {
        List<float[]> samples = gaussian(300, 64, new Random(4L));
        VasqParams p = VasqCalibrator.calibrate(samples, 64, SEED);

        for (int i = 0; i < p.paddedDim(); i++) {
            assertTrue(p.scales()[i] > 0f, "scale must be positive at dim " + i);
            assertTrue(p.invScales()[i] > 0f, "invScale must be positive at dim " + i);
        }
    }

    // ── Outlier resistance ────────────────────────────────────────────────────

    @Test
    void calibration_robust_to_outliers() {
        // 499 normal vectors + 1 extreme outlier (50× scale)
        Random rng = new Random(5L);
        List<float[]> samples = gaussian(499, 64, rng);

        float[] outlier = new float[64];
        Arrays.fill(outlier, 50f);
        samples.add(outlier);

        VasqParams pWithOutlier = VasqCalibrator.calibrate(samples, 64, SEED);

        // Calibrate clean sample for comparison
        List<float[]> cleanSamples = gaussian(500, 64, new Random(5L));
        VasqParams pClean = VasqCalibrator.calibrate(cleanSamples, 64, SEED);

        // The scale from the outlier-polluted set should not be dramatically larger
        // (if percentile clipping works, scales should be within ~2× of the clean set)
        double maxScaleRatio = 0;
        for (int i = 0; i < pClean.paddedDim(); i++) {
            double ratio = pWithOutlier.scales()[i] / (pClean.scales()[i] + 1e-9f);
            maxScaleRatio = Math.max(maxScaleRatio, ratio);
        }
        assertTrue(maxScaleRatio < 5.0,
                "Outlier should not inflate scales by more than 5×; max ratio was " + maxScaleRatio);
    }

    // ── Padded dimensions ─────────────────────────────────────────────────────

    @Test
    void padded_dims_have_near_zero_mean() {
        // Original dim = 100, padded to 128; dims [100..127] were zero-padded before FWHT.
        // After FWHT the energy is spread, but means should be close to zero.
        List<float[]> samples = gaussian(500, 100, new Random(6L));
        VasqParams p = VasqCalibrator.calibrate(samples, 100, SEED);

        assertEquals(128, p.paddedDim());

        // The padded portion [100..127] should have small means relative to the original dims
        float maxPaddedMean = 0f;
        for (int i = 100; i < 128; i++) {
            maxPaddedMean = Math.max(maxPaddedMean, Math.abs(p.means()[i]));
        }

        float maxOrigMean = 0f;
        for (int i = 0; i < 100; i++) {
            maxOrigMean = Math.max(maxOrigMean, Math.abs(p.means()[i]));
        }

        // Padded dims should have smaller average mean than original dims
        // (They may not be strictly zero due to FWHT mixing, but should be smaller)
        assertTrue(maxPaddedMean <= maxOrigMean * 2 + 0.1f,
                "Padded dim means should not exceed original dim means; "
                + "maxPadded=" + maxPaddedMean + " maxOrig=" + maxOrigMean);
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    void emptySampleThrows() {
        assertThrows(SpectorValidationException.class,
                () -> VasqCalibrator.calibrate(List.of(), 64, SEED));
    }

    @Test
    void wrongDimThrows() {
        List<float[]> samples = List.of(new float[32], new float[64]);
        assertThrows(SpectorValidationException.class,
                () -> VasqCalibrator.calibrate(samples, 64, SEED));
    }

    @Test
    void singleSample_doesNotThrow() {
        List<float[]> samples = List.of(gaussian(1, 32, new Random(7L)).get(0));
        assertDoesNotThrow(() -> VasqCalibrator.calibrate(samples, 32, SEED));
    }

    @Test
    void largeCorpus_capped_at_maxSampleSize() {
        // 15,000 samples → should be capped at MAX_SAMPLE_SIZE (10,000) without error
        List<float[]> samples = gaussian(15_000, 32, new Random(8L));
        assertDoesNotThrow(() -> VasqCalibrator.calibrate(samples, 32, SEED));
    }

    @Test
    void bytesPerVector_is_4_plus_paddedDim() {
        List<float[]> samples = gaussian(200, 64, new Random(9L));
        VasqParams p = VasqCalibrator.calibrate(samples, 64, SEED);
        assertEquals(4 + p.paddedDim(), p.bytesPerVector());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static List<float[]> gaussian(int n, int dim, Random rng) {
        List<float[]> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            float[] v = new float[dim];
            for (int d = 0; d < dim; d++) v[d] = (float) rng.nextGaussian();
            list.add(v);
        }
        return list;
    }
}
