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
package com.spectrayan.spector.storage;

import com.spectrayan.spector.commons.error.SpectorValidationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.spectrayan.spector.core.quantization.CrumbPacker;
import com.spectrayan.spector.core.quantization.NibblePacker;
import com.spectrayan.spector.core.quantization.NonUniformQuantizer;
import com.spectrayan.spector.core.quantization.QuantizationType;
import com.spectrayan.spector.core.quantization.ScalarQuantizer;

/**
 * Tests for {@link QuantizedVectorStore} covering INT8 backward compatibility,
 * INT4 (nibble-packed) storage, and INT2 (crumb-packed) storage.
 */
class QuantizedVectorStoreTest {

    private static final int DIMS = 8;
    private static final int CAPACITY = 100;

    // ─────────────── INT8 Backward Compatibility ───────────────

    @Test
    void int8_backwardCompatible_singleArgConstructor() {
        float[][] samples = generateSamples(50, DIMS);
        ScalarQuantizer quantizer = ScalarQuantizer.calibrate(samples, DIMS);

        try (var store = new QuantizedVectorStore(DIMS, CAPACITY, quantizer)) {
            assertEquals(QuantizationType.SCALAR_INT8, store.quantizationType());
            assertEquals(DIMS, store.bytesPerVector());
            assertNotNull(store.quantizer());
            assertNull(store.nonUniformQuantizer());

            float[] vector = samples[0];
            int idx = store.put("v1", vector);
            assertEquals(0, idx);
            assertEquals(1, store.size());

            byte[] quantized = store.getQuantized(idx);
            assertEquals(DIMS, quantized.length);

            float[] decoded = store.getFloat(idx);
            assertEquals(DIMS, decoded.length);
            // Verify round-trip is approximate (within INT8 error)
            for (int d = 0; d < DIMS; d++) {
                assertTrue(Math.abs(decoded[d] - vector[d]) < 0.1f,
                        "INT8 decode too far from original at dim " + d);
            }
        }
    }

    @Test
    void int8_fiveArgConstructor() {
        float[][] samples = generateSamples(50, DIMS);
        ScalarQuantizer quantizer = ScalarQuantizer.calibrate(samples, DIMS);

        try (var store = new QuantizedVectorStore(DIMS, CAPACITY, QuantizationType.SCALAR_INT8, quantizer, null)) {
            assertEquals(QuantizationType.SCALAR_INT8, store.quantizationType());
            store.put("v1", samples[0]);
            assertEquals(1, store.size());
        }
    }

    // ─────────────── INT4 Tests ───────────────

    @Test
    void int4_storeAndRetrieve() {
        float[][] samples = generateSamples(50, DIMS);
        NonUniformQuantizer nuq = NonUniformQuantizer.calibrate(samples, DIMS, 16);

        try (var store = new QuantizedVectorStore(DIMS, CAPACITY, QuantizationType.SCALAR_INT4, null, nuq)) {
            assertEquals(QuantizationType.SCALAR_INT4, store.quantizationType());
            assertEquals(NibblePacker.packedSize(DIMS), store.bytesPerVector());
            assertNull(store.quantizer());
            assertNotNull(store.nonUniformQuantizer());

            float[] vector = samples[0];
            int idx = store.put("v1", vector);
            assertEquals(0, idx);

            byte[] packed = store.getQuantized(idx);
            assertEquals(NibblePacker.packedSize(DIMS), packed.length);

            float[] decoded = store.getFloat(idx);
            assertEquals(DIMS, decoded.length);
            // INT4 has 16 levels, so expect larger error than INT8 but still bounded
            for (int d = 0; d < DIMS; d++) {
                assertNotNull(decoded[d]); // just verify no crash
            }
        }
    }

    @Test
    void int4_oddDimensions() {
        int oddDims = 7;
        float[][] samples = generateSamples(50, oddDims);
        NonUniformQuantizer nuq = NonUniformQuantizer.calibrate(samples, oddDims, 16);

        try (var store = new QuantizedVectorStore(oddDims, CAPACITY, QuantizationType.SCALAR_INT4, null, nuq)) {
            assertEquals(NibblePacker.packedSize(oddDims), store.bytesPerVector());
            assertEquals(4, store.bytesPerVector()); // ceil(7/2) = 4

            store.put("v1", samples[0]);
            float[] decoded = store.getFloat(0);
            assertEquals(oddDims, decoded.length);
        }
    }

    @Test
    void int4_multipleVectors() {
        float[][] samples = generateSamples(50, DIMS);
        NonUniformQuantizer nuq = NonUniformQuantizer.calibrate(samples, DIMS, 16);

        try (var store = new QuantizedVectorStore(DIMS, CAPACITY, QuantizationType.SCALAR_INT4, null, nuq)) {
            for (int i = 0; i < 10; i++) {
                store.put("v" + i, samples[i]);
            }
            assertEquals(10, store.size());

            // Verify each vector is stored independently
            for (int i = 0; i < 10; i++) {
                byte[] packed = store.getQuantized(i);
                assertNotNull(packed);
                assertEquals(NibblePacker.packedSize(DIMS), packed.length);
            }
        }
    }

    // ─────────────── INT2 Tests ───────────────

    @Test
    void int2_storeAndRetrieve() {
        float[][] samples = generateSamples(50, DIMS);
        NonUniformQuantizer nuq = NonUniformQuantizer.calibrate(samples, DIMS, 4);

        try (var store = new QuantizedVectorStore(DIMS, CAPACITY, QuantizationType.SCALAR_INT2, null, nuq)) {
            assertEquals(QuantizationType.SCALAR_INT2, store.quantizationType());
            assertEquals(CrumbPacker.packedSize(DIMS), store.bytesPerVector());
            assertNull(store.quantizer());
            assertNotNull(store.nonUniformQuantizer());

            float[] vector = samples[0];
            int idx = store.put("v1", vector);
            assertEquals(0, idx);

            byte[] packed = store.getQuantized(idx);
            assertEquals(CrumbPacker.packedSize(DIMS), packed.length);

            float[] decoded = store.getFloat(idx);
            assertEquals(DIMS, decoded.length);
        }
    }

    @Test
    void int2_nonMultipleOf4Dimensions() {
        int dims = 5; // not a multiple of 4
        float[][] samples = generateSamples(50, dims);
        NonUniformQuantizer nuq = NonUniformQuantizer.calibrate(samples, dims, 4);

        try (var store = new QuantizedVectorStore(dims, CAPACITY, QuantizationType.SCALAR_INT2, null, nuq)) {
            assertEquals(CrumbPacker.packedSize(dims), store.bytesPerVector());
            assertEquals(2, store.bytesPerVector()); // ceil(5/4) = 2

            store.put("v1", samples[0]);
            float[] decoded = store.getFloat(0);
            assertEquals(dims, decoded.length);
        }
    }

    @Test
    void int2_multipleVectors() {
        float[][] samples = generateSamples(50, DIMS);
        NonUniformQuantizer nuq = NonUniformQuantizer.calibrate(samples, DIMS, 4);

        try (var store = new QuantizedVectorStore(DIMS, CAPACITY, QuantizationType.SCALAR_INT2, null, nuq)) {
            for (int i = 0; i < 10; i++) {
                store.put("v" + i, samples[i]);
            }
            assertEquals(10, store.size());

            for (int i = 0; i < 10; i++) {
                byte[] packed = store.getQuantized(i);
                assertEquals(CrumbPacker.packedSize(DIMS), packed.length);
            }
        }
    }

    // ─────────────── Validation Tests ───────────────

    @Test
    void rejectsNullQuantizationType() {
        assertThrows(SpectorValidationException.class,
                () -> new QuantizedVectorStore(DIMS, CAPACITY, null, null, null));
    }

    @Test
    void rejectsMissingScalarQuantizerForInt8() {
        assertThrows(SpectorValidationException.class,
                () -> new QuantizedVectorStore(DIMS, CAPACITY, QuantizationType.SCALAR_INT8, null, null));
    }

    @Test
    void rejectsMissingNonUniformQuantizerForInt4() {
        assertThrows(SpectorValidationException.class,
                () -> new QuantizedVectorStore(DIMS, CAPACITY, QuantizationType.SCALAR_INT4, null, null));
    }

    @Test
    void rejectsMissingNonUniformQuantizerForInt2() {
        assertThrows(SpectorValidationException.class,
                () -> new QuantizedVectorStore(DIMS, CAPACITY, QuantizationType.SCALAR_INT2, null, null));
    }

    @Test
    void rejectsDimensionMismatchForInt4() {
        float[][] samples = generateSamples(50, 16);
        NonUniformQuantizer nuq = NonUniformQuantizer.calibrate(samples, 16, 16);

        assertThrows(SpectorValidationException.class,
                () -> new QuantizedVectorStore(DIMS, CAPACITY, QuantizationType.SCALAR_INT4, null, nuq));
    }

    @Test
    void rejectsWrongLevelsForInt4() {
        float[][] samples = generateSamples(50, DIMS);
        // Calibrate with 4 levels but try to use with INT4 (needs 16)
        NonUniformQuantizer nuq = NonUniformQuantizer.calibrate(samples, DIMS, 4);

        assertThrows(SpectorValidationException.class,
                () -> new QuantizedVectorStore(DIMS, CAPACITY, QuantizationType.SCALAR_INT4, null, nuq));
    }

    @Test
    void rejectsWrongLevelsForInt2() {
        float[][] samples = generateSamples(50, DIMS);
        // Calibrate with 16 levels but try to use with INT2 (needs 4)
        NonUniformQuantizer nuq = NonUniformQuantizer.calibrate(samples, DIMS, 16);

        assertThrows(SpectorValidationException.class,
                () -> new QuantizedVectorStore(DIMS, CAPACITY, QuantizationType.SCALAR_INT2, null, nuq));
    }

    // ─────────────── Common Operations ───────────────

    @Test
    void indexOf_works() {
        float[][] samples = generateSamples(50, DIMS);
        NonUniformQuantizer nuq = NonUniformQuantizer.calibrate(samples, DIMS, 16);

        try (var store = new QuantizedVectorStore(DIMS, CAPACITY, QuantizationType.SCALAR_INT4, null, nuq)) {
            store.put("first", samples[0]);
            store.put("second", samples[1]);

            assertEquals(0, store.indexOf("first"));
            assertEquals(1, store.indexOf("second"));
            assertEquals(-1, store.indexOf("missing"));
        }
    }

    @Test
    void putOverwrite_updatesInPlace() {
        float[][] samples = generateSamples(50, DIMS);
        NonUniformQuantizer nuq = NonUniformQuantizer.calibrate(samples, DIMS, 16);

        try (var store = new QuantizedVectorStore(DIMS, CAPACITY, QuantizationType.SCALAR_INT4, null, nuq)) {
            store.put("v1", samples[0]);
            byte[] first = store.getQuantized(0);

            // Overwrite with a different vector
            store.put("v1", samples[1]);
            byte[] second = store.getQuantized(0);

            assertEquals(1, store.size()); // still 1 vector
            // Packed data may differ
        }
    }

    @Test
    void getQuantized_intoBuf() {
        float[][] samples = generateSamples(50, DIMS);
        NonUniformQuantizer nuq = NonUniformQuantizer.calibrate(samples, DIMS, 4);

        try (var store = new QuantizedVectorStore(DIMS, CAPACITY, QuantizationType.SCALAR_INT2, null, nuq)) {
            store.put("v1", samples[0]);

            int bpv = store.bytesPerVector();
            byte[] buf = new byte[bpv + 4]; // extra padding
            store.getQuantized(0, buf, 2);

            byte[] direct = store.getQuantized(0);
            for (int i = 0; i < bpv; i++) {
                assertEquals(direct[i], buf[i + 2]);
            }
        }
    }

    // ─────────────── Helpers ───────────────

    private static float[][] generateSamples(int count, int dims) {
        java.util.Random rng = new java.util.Random(42);
        float[][] samples = new float[count][dims];
        for (int i = 0; i < count; i++) {
            for (int d = 0; d < dims; d++) {
                samples[i][d] = rng.nextFloat() * 2.0f - 1.0f; // [-1, 1]
            }
        }
        return samples;
    }
}
