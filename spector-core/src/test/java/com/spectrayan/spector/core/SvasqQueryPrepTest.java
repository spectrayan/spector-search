/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.spectrayan.spector.core;

import com.spectrayan.spector.commons.error.SpectorValidationException;

import com.spectrayan.spector.core.quantization.svasq.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SvasqQueryPrep} — query state correctness.
 */
class SvasqQueryPrepTest {

    private static final long SEED = 42L;
    private static final int  DIM  = 64;

    private SvasqParams calibrate(int n, int dim, long rngSeed) {
        Random rng = new Random(rngSeed);
        List<float[]> samples = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            float[] v = new float[dim];
            for (int d = 0; d < dim; d++) v[d] = (float) rng.nextGaussian();
            samples.add(v);
        }
        return SvasqCalibrator.calibrate(samples, dim, SEED);
    }

    // ── qTilde ────────────────────────────────────────────────────────────────

    @Test
    void qTilde_length_is_paddedDim() {
        SvasqParams params = calibrate(200, DIM, 1L);
        SvasqQueryPrep prep = new SvasqQueryPrep(params);
        float[] query = new float[DIM];
        SvasqQueryState qs = prep.prepare(query);
        assertEquals(params.paddedDim(), qs.qTilde().length);
    }

    @Test
    void qTilde_is_qRot_times_scale() {
        SvasqParams params = calibrate(200, DIM, 2L);
        SvasqQueryPrep prep = new SvasqQueryPrep(params);

        Random rng = new Random(3L);
        float[] query = new float[DIM];
        for (int i = 0; i < DIM; i++) query[i] = (float) rng.nextGaussian();

        SvasqQueryState qs = prep.prepare(query);

        // Manually compute q_rot
        float[] qRot = params.fwht().rotate(query);
        float[] scales = params.scales();

        for (int i = 0; i < params.paddedDim(); i++) {
            assertEquals(qRot[i] * scales[i], qs.qTilde()[i], 1e-5f,
                    "qTilde[" + i + "] mismatch");
        }
    }

    @Test
    void zero_query_gives_zero_qTilde_and_zero_dotOffset() {
        SvasqParams params = calibrate(200, DIM, 4L);
        SvasqQueryPrep prep = new SvasqQueryPrep(params);
        float[] zero = new float[DIM];
        SvasqQueryState qs = prep.prepare(zero);

        for (int i = 0; i < params.paddedDim(); i++) {
            assertEquals(0f, qs.qTilde()[i], 1e-6f, "qTilde must be zero for zero query");
        }
        assertEquals(0f, qs.qNormSq(), 1e-9f);
    }

    // ── constL2Q sign ─────────────────────────────────────────────────────────

    @Test
    void constL2Q_equals_qNormSq_minus_2_times_dotOffset() {
        SvasqParams params = calibrate(200, DIM, 5L);
        SvasqQueryPrep prep = new SvasqQueryPrep(params);

        Random rng = new Random(6L);
        float[] query = new float[DIM];
        for (int i = 0; i < DIM; i++) query[i] = (float) rng.nextGaussian();

        SvasqQueryState qs = prep.prepare(query);

        float expected = qs.qNormSq() - 2f * qs.dotOffset();
        assertEquals(expected, qs.constL2Q(), 1e-4f,
                "constL2Q must equal qNormSq - 2*C(q). "
                + "Got constL2Q=" + qs.constL2Q() + " expected=" + expected);
    }

    @Test
    void zero_query_constL2Q_is_zero() {
        SvasqParams params = calibrate(200, DIM, 7L);
        SvasqQueryPrep prep = new SvasqQueryPrep(params);
        SvasqQueryState qs = prep.prepare(new float[DIM]);
        assertEquals(0f, qs.constL2Q(), 1e-6f);
    }

    // ── qNormSq ───────────────────────────────────────────────────────────────

    @Test
    void qNormSq_matches_manual_calculation() {
        SvasqParams params = calibrate(200, DIM, 8L);
        SvasqQueryPrep prep = new SvasqQueryPrep(params);

        Random rng = new Random(9L);
        float[] query = new float[DIM];
        double expected = 0;
        for (int i = 0; i < DIM; i++) {
            query[i] = (float) rng.nextGaussian();
            expected += (double) query[i] * query[i];
        }

        SvasqQueryState qs = prep.prepare(query);
        assertEquals((float) expected, qs.qNormSq(), 1e-3f * (float) expected,
                "qNormSq mismatch");
    }

    // ── Error cases ───────────────────────────────────────────────────────────

    @Test
    void wrongDimThrows() {
        SvasqParams params = calibrate(100, DIM, 10L);
        SvasqQueryPrep prep = new SvasqQueryPrep(params);
        assertThrows(SpectorValidationException.class,
                () -> prep.prepare(new float[DIM + 1]));
    }

    @Test
    void nullParamsThrows() {
        assertThrows(SpectorValidationException.class, () -> new SvasqQueryPrep(null));
    }
}
