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
package com.spectrayan.spector.gpu;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CudaSvasqKernel}.
 *
 * <p>Validates the CPU fallback path and verifies numerical equivalence with
 * the scalar SVASQ distance formula:
 * <ul>
 *   <li>L2 ≈ exactNormSq + constL2Q - 2 × dot(qTilde, z_int8)</li>
 *   <li>IP ≈ dot(qTilde, z_int8) + dotOffset</li>
 * </ul>
 */
class CudaSvasqKernelTest {

    private CudaSvasqKernel kernel;

    @BeforeEach
    void setUp() {
        kernel = new CudaSvasqKernel(false); // CPU fallback
    }

    @AfterEach
    void tearDown() {
        kernel.close();
    }

    // ── L2 Distance ─────────────────────────────────────────────────────────────

    @Test
    void computeL2_zeroCodesAndQuery_returnsNormOnly() {
        int dims = 64;
        float[] qTilde = new float[dims]; // all zeros
        byte[] codes = new byte[dims];    // all zeros
        short[] norms = {Float.floatToFloat16(4.0f)}; // exactNormSq = 4.0
        float constL2Q = 1.0f;

        float[] results = kernel.computeL2(qTilde, codes, norms, constL2Q, 1, dims);

        // L2 = 4.0 + 1.0 - 2*0 = 5.0
        assertEquals(1, results.length);
        assertEquals(5.0f, results[0], 1e-3f);
    }

    @Test
    void computeL2_knownValues_correctResult() {
        int dims = 32;
        float[] qTilde = new float[dims];
        byte[] codes = new byte[dims];
        for (int i = 0; i < dims; i++) {
            qTilde[i] = 2.0f;   // pre-scaled query
            codes[i] = 3;       // INT8 code = 3
        }
        short[] norms = {Float.floatToFloat16(10.0f)}; // exactNormSq = 10
        float constL2Q = 5.0f;

        float[] results = kernel.computeL2(qTilde, codes, norms, constL2Q, 1, dims);

        // dot = Σ(2.0 * 3) = 32 * 6 = 192
        // L2 = 10 + 5 - 2*192 = 15 - 384 = -369 → clamped to 0
        assertEquals(1, results.length);
        assertEquals(0.0f, results[0], 1e-3f);
    }

    @Test
    void computeL2_multipleVectors_correctResults() {
        int dims = 32;
        int n = 3;
        float[] qTilde = createUniformFloats(dims, 1.0f);
        byte[] codes = new byte[n * dims];
        short[] norms = new short[n];

        // Vector 0: codes all 1, norm = 2.0
        for (int d = 0; d < dims; d++) codes[d] = 1;
        norms[0] = Float.floatToFloat16(2.0f);

        // Vector 1: codes all 0, norm = 5.0
        norms[1] = Float.floatToFloat16(5.0f);

        // Vector 2: codes all -1, norm = 3.0
        for (int d = 0; d < dims; d++) codes[2 * dims + d] = -1;
        norms[2] = Float.floatToFloat16(3.0f);

        float constL2Q = 1.0f;
        float[] results = kernel.computeL2(qTilde, codes, norms, constL2Q, n, dims);

        assertEquals(n, results.length);
        // Vector 0: dot=32, L2 = 2 + 1 - 64 → 0 (clamped)
        assertEquals(0.0f, results[0], 1e-3f);
        // Vector 1: dot=0, L2 = 5 + 1 - 0 = 6
        assertEquals(6.0f, results[1], 1e-3f);
        // Vector 2: dot=-32, L2 = 3 + 1 + 64 = 68
        assertEquals(68.0f, results[2], 1e-3f);
    }

    @Test
    void computeL2_matchesScalarFormula() {
        int dims = 64;
        int n = 10;
        java.util.Random rng = new java.util.Random(42);
        float[] qTilde = createRandomFloats(dims, rng);
        byte[] codes = createRandomBytes(n * dims, rng);
        short[] norms = new short[n];
        for (int i = 0; i < n; i++) {
            norms[i] = Float.floatToFloat16(rng.nextFloat() * 10);
        }
        float constL2Q = 3.5f;

        float[] results = kernel.computeL2(qTilde, codes, norms, constL2Q, n, dims);

        for (int i = 0; i < n; i++) {
            float expected = scalarL2(qTilde, codes, norms, constL2Q, i, dims);
            assertEquals(expected, results[i], 1e-2f, "L2 mismatch at vector " + i);
        }
    }

    @Test
    void computeL2_emptyBatch_returnsEmpty() {
        float[] results = kernel.computeL2(new float[32], new byte[0], new short[0], 0, 0, 32);
        assertEquals(0, results.length);
    }

    @Test
    void computeL2_resultNonNegative() {
        int dims = 64;
        int n = 100;
        java.util.Random rng = new java.util.Random(42);
        float[] qTilde = createRandomFloats(dims, rng);
        byte[] codes = createRandomBytes(n * dims, rng);
        short[] norms = new short[n];
        for (int i = 0; i < n; i++) norms[i] = Float.floatToFloat16(rng.nextFloat());

        float[] results = kernel.computeL2(qTilde, codes, norms, 0.5f, n, dims);

        for (float r : results) {
            assertTrue(r >= 0, "L2 should be non-negative, got: " + r);
        }
    }

    // ── Dot Product ─────────────────────────────────────────────────────────────

    @Test
    void computeDot_zeroCodes_returnsDotOffset() {
        int dims = 32;
        float[] qTilde = createUniformFloats(dims, 1.0f);
        byte[] codes = new byte[dims]; // all zeros
        float dotOffset = 7.5f;

        float[] results = kernel.computeDot(qTilde, codes, dotOffset, 1, dims);

        // dot = 0, IP = 0 + 7.5 = 7.5
        assertEquals(1, results.length);
        assertEquals(7.5f, results[0], 1e-6f);
    }

    @Test
    void computeDot_knownValues_correctResult() {
        int dims = 32;
        float[] qTilde = createUniformFloats(dims, 2.0f);
        byte[] codes = new byte[dims];
        for (int i = 0; i < dims; i++) codes[i] = 5;
        float dotOffset = -3.0f;

        float[] results = kernel.computeDot(qTilde, codes, dotOffset, 1, dims);

        // dot = Σ(2.0 * 5) = 32 * 10 = 320
        // IP = 320 + (-3) = 317
        assertEquals(317.0f, results[0], 1e-3f);
    }

    @Test
    void computeDot_multipleVectors_correctResults() {
        int dims = 32;
        int n = 2;
        float[] qTilde = createUniformFloats(dims, 1.0f);
        byte[] codes = new byte[n * dims];
        for (int d = 0; d < dims; d++) codes[d] = 10;         // vector 0
        for (int d = 0; d < dims; d++) codes[dims + d] = -5;  // vector 1
        float dotOffset = 1.0f;

        float[] results = kernel.computeDot(qTilde, codes, dotOffset, n, dims);

        // Vector 0: dot = 32*10 = 320, IP = 321
        assertEquals(321.0f, results[0], 1e-3f);
        // Vector 1: dot = 32*(-5) = -160, IP = -159
        assertEquals(-159.0f, results[1], 1e-3f);
    }

    @Test
    void computeDot_matchesScalarFormula() {
        int dims = 64;
        int n = 10;
        java.util.Random rng = new java.util.Random(42);
        float[] qTilde = createRandomFloats(dims, rng);
        byte[] codes = createRandomBytes(n * dims, rng);
        float dotOffset = 2.0f;

        float[] results = kernel.computeDot(qTilde, codes, dotOffset, n, dims);

        for (int i = 0; i < n; i++) {
            float expected = scalarDot(qTilde, codes, dotOffset, i, dims);
            assertEquals(expected, results[i], 1e-3f, "Dot mismatch at vector " + i);
        }
    }

    @Test
    void computeDot_emptyBatch_returnsEmpty() {
        float[] results = kernel.computeDot(new float[32], new byte[0], 0, 0, 32);
        assertEquals(0, results.length);
    }

    // ── Interface ───────────────────────────────────────────────────────────────

    @Test
    void isGpuActive_returnsFalseInFallbackMode() {
        assertFalse(kernel.isGpuActive());
    }

    @Test
    void defaultConstructor_fallsBackGracefully() {
        try (var defaultKernel = new CudaSvasqKernel()) {
            float[] qTilde = new float[32];
            byte[] codes = new byte[32];
            short[] norms = {Float.floatToFloat16(1.0f)};

            float[] results = defaultKernel.computeL2(qTilde, codes, norms, 0, 1, 32);
            assertEquals(1, results.length);
        }
    }

    @Test
    void signedInt8Codes_handleCorrectly() {
        int dims = 32;
        float[] qTilde = createUniformFloats(dims, 1.0f);
        byte[] codes = new byte[dims];
        // Signed byte: -127 to 127
        for (int i = 0; i < dims; i++) codes[i] = -127;
        float dotOffset = 0;

        float[] results = kernel.computeDot(qTilde, codes, dotOffset, 1, dims);

        // dot = Σ(1.0 * (-127)) = 32 * (-127) = -4064
        assertEquals(-4064.0f, results[0], 1e-3f);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static float[] createUniformFloats(int dims, float value) {
        float[] v = new float[dims];
        java.util.Arrays.fill(v, value);
        return v;
    }

    private static float[] createRandomFloats(int dims, java.util.Random rng) {
        float[] v = new float[dims];
        for (int i = 0; i < dims; i++) v[i] = rng.nextFloat() - 0.5f;
        return v;
    }

    private static byte[] createRandomBytes(int n, java.util.Random rng) {
        byte[] b = new byte[n];
        rng.nextBytes(b);
        return b;
    }

    private static float scalarL2(float[] qTilde, byte[] codes, short[] norms,
                                   float constL2Q, int vecIdx, int dims) {
        float exactNormSq = Float.float16ToFloat(norms[vecIdx]);
        int offset = vecIdx * dims;
        float dot = 0;
        for (int d = 0; d < dims; d++) {
            dot += qTilde[d] * codes[offset + d];
        }
        return Math.max(0, exactNormSq + constL2Q - 2f * dot);
    }

    private static float scalarDot(float[] qTilde, byte[] codes,
                                    float dotOffset, int vecIdx, int dims) {
        int offset = vecIdx * dims;
        float dot = 0;
        for (int d = 0; d < dims; d++) {
            dot += qTilde[d] * codes[offset + d];
        }
        return dot + dotOffset;
    }
}
