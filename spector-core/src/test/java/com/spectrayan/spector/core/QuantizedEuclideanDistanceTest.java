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

import com.spectrayan.spector.core.similarity.QuantizedEuclideanDistance;
import com.spectrayan.spector.core.similarity.SimilarityFunction;

import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.Random;

/**
 * Tests and benchmarks for {@link QuantizedEuclideanDistance} — the SIMD-accelerated
 * INT8 quantized L2 distance kernel (P3 optimization).
 *
 * <p>Verifies correctness against a reference scalar implementation and
 * benchmarks performance at common embedding dimensions (128, 384, 768, 1024).</p>
 */
@DisplayName("QuantizedEuclideanDistance — SIMD L2 Kernel")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QuantizedEuclideanDistanceTest {

    private static final Random RNG = new Random(42);

    // ══════════════════════════════════════════════════════════════
    // Correctness: SIMD matches scalar reference
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("SIMD result matches scalar reference for 128-dim")
    void correctness_128dim() {
        assertSimdMatchesScalar(128);
    }

    @Test
    @Order(2)
    @DisplayName("SIMD result matches scalar reference for 384-dim")
    void correctness_384dim() {
        assertSimdMatchesScalar(384);
    }

    @Test
    @Order(3)
    @DisplayName("SIMD result matches scalar reference for 768-dim (nomic-embed-text)")
    void correctness_768dim() {
        assertSimdMatchesScalar(768);
    }

    @Test
    @Order(4)
    @DisplayName("SIMD result matches scalar reference for 1024-dim")
    void correctness_1024dim() {
        assertSimdMatchesScalar(1024);
    }

    @Test
    @Order(5)
    @DisplayName("SIMD result matches scalar reference for 7-dim (tail-only)")
    void correctness_7dim_tailOnly() {
        assertSimdMatchesScalar(7);
    }

    @Test
    @Order(6)
    @DisplayName("SIMD result matches scalar reference for 1-dim (degenerate)")
    void correctness_1dim() {
        assertSimdMatchesScalar(1);
    }

    @Test
    @Order(7)
    @DisplayName("SIMD result matches scalar reference for 17-dim (1 SIMD + tail)")
    void correctness_17dim() {
        assertSimdMatchesScalar(17);
    }

    @Test
    @Order(8)
    @DisplayName("Zero vector returns sqrt(sum(mins²))")
    void zeroVector() {
        int dims = 32;
        float[] query = new float[dims];
        float[] mins = new float[dims];
        float[] scales = new float[dims];
        Arrays.fill(mins, -1.0f);
        Arrays.fill(scales, 1.0f / 127.5f);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(dims, 32);
            seg.fill((byte) 0);

            float dist = QuantizedEuclideanDistance.compute(query, seg, 0, mins, scales, dims);
            float expected = scalarEuclidean(query, seg, 0, mins, scales, dims);

            assertThat(dist).isCloseTo(expected, within(0.01f));
        }
    }

    @Test
    @Order(9)
    @DisplayName("Identical vectors have zero distance")
    void identicalVectors() {
        int dims = 64;
        float[] mins = new float[dims];
        float[] scales = new float[dims];
        Arrays.fill(mins, -1.0f);
        Arrays.fill(scales, 1.0f / 127.5f);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(dims, 32);
            byte[] quantized = new byte[dims];
            for (int i = 0; i < dims; i++) {
                quantized[i] = (byte) RNG.nextInt(256);
                seg.set(ValueLayout.JAVA_BYTE, i, quantized[i]);
            }

            // Build query = exact dequantization of the stored vector
            float[] query = new float[dims];
            for (int i = 0; i < dims; i++) {
                query[i] = (quantized[i] & 0xFF) * scales[i] + mins[i];
            }

            float dist = QuantizedEuclideanDistance.compute(query, seg, 0, mins, scales, dims);
            assertThat(dist).as("Distance to self should be ~0").isCloseTo(0f, within(0.001f));
        }
    }

    @Test
    @Order(10)
    @DisplayName("SimilarityFunction.EUCLIDEAN delegates to SIMD kernel")
    void similarityFunctionDelegates() {
        int dims = 128;
        float[] mins = new float[dims];
        float[] scales = new float[dims];
        Arrays.fill(mins, -1.0f);
        Arrays.fill(scales, 1.0f / 127.5f);

        float[] query = randomFloatVector(dims);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(dims, 32);
            fillRandomBytes(seg, dims);

            float simd = QuantizedEuclideanDistance.compute(query, seg, 0, mins, scales, dims);
            float via_enum = SimilarityFunction.EUCLIDEAN.computeQuantizedFromSegment(
                    query, seg, 0, mins, scales, dims);

            assertThat(via_enum).as("Enum should delegate to SIMD kernel")
                    .isCloseTo(simd, within(0.0001f));
        }
    }

    @Test
    @Order(11)
    @DisplayName("Byte array overload matches segment overload")
    void byteArrayOverload() {
        int dims = 64;
        float[] mins = new float[dims];
        float[] scales = new float[dims];
        Arrays.fill(mins, -1.0f);
        Arrays.fill(scales, 1.0f / 127.5f);

        float[] query = randomFloatVector(dims);
        byte[] quantized = new byte[dims];
        RNG.nextBytes(quantized);

        @SuppressWarnings("deprecation")
        float fromArray = QuantizedEuclideanDistance.compute(query, quantized, mins, scales, dims);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(dims, 32);
            MemorySegment.copy(MemorySegment.ofArray(quantized), 0, seg, 0, dims);
            float fromSegment = QuantizedEuclideanDistance.compute(query, seg, 0, mins, scales, dims);

            assertThat(fromArray).isCloseTo(fromSegment, within(0.0001f));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Benchmarks: SIMD throughput at various dimensions
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(20)
    @DisplayName("Benchmark: 50K × 128-dim L2 distance")
    void benchmark_128dim() {
        runBenchmark(128, 50_000);
    }

    @Test
    @Order(21)
    @DisplayName("Benchmark: 50K × 384-dim L2 distance")
    void benchmark_384dim() {
        runBenchmark(384, 50_000);
    }

    @Test
    @Order(22)
    @DisplayName("Benchmark: 50K × 768-dim L2 distance")
    void benchmark_768dim() {
        runBenchmark(768, 50_000);
    }

    @Test
    @Order(23)
    @DisplayName("Benchmark: 10K × 1024-dim L2 distance")
    void benchmark_1024dim() {
        runBenchmark(1024, 10_000);
    }

    // ══════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════

    private void assertSimdMatchesScalar(int dims) {
        float[] mins = new float[dims];
        float[] scales = new float[dims];
        Arrays.fill(mins, -1.0f);
        Arrays.fill(scales, 1.0f / 127.5f);

        float[] query = randomFloatVector(dims);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(dims, 32);
            fillRandomBytes(seg, dims);

            float simd = QuantizedEuclideanDistance.compute(query, seg, 0, mins, scales, dims);
            float scalar = scalarEuclidean(query, seg, 0, mins, scales, dims);

            // Allow small floating-point divergence from FMA reordering
            assertThat(simd).isCloseTo(scalar, within(Math.max(0.01f, scalar * 0.001f)));
        }
    }

    private void runBenchmark(int dims, int count) {
        float[] mins = new float[dims];
        float[] scales = new float[dims];
        Arrays.fill(mins, -1.0f);
        Arrays.fill(scales, 1.0f / 127.5f);
        float[] query = randomFloatVector(dims);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate((long) count * dims, 32);
            for (int i = 0; i < count * dims; i++) {
                seg.set(ValueLayout.JAVA_BYTE, i, (byte) RNG.nextInt(256));
            }

            // Warm up (5 iterations)
            for (int i = 0; i < Math.min(5, count); i++) {
                QuantizedEuclideanDistance.compute(query, seg, (long) i * dims, mins, scales, dims);
            }

            // Benchmark
            long start = System.nanoTime();
            float checksum = 0;
            for (int i = 0; i < count; i++) {
                checksum += QuantizedEuclideanDistance.compute(query, seg, (long) i * dims, mins, scales, dims);
            }
            long elapsed = System.nanoTime() - start;

            double totalMs = elapsed / 1e6;
            double avgUs = elapsed / 1e3 / count;
            double throughput = count / (totalMs / 1000);

            System.out.printf("  SIMD L2 %d-dim × %,d: %.1f ms total (%.1f µs/vec, %.0f vec/s, checksum=%.2f)%n",
                    dims, count, totalMs, avgUs, throughput, checksum);

            // Throughput assertions (conservative for CI)
            assertThat(totalMs).as("Total time should be reasonable").isLessThan(5_000);
        }
    }

    /** Reference scalar implementation for correctness verification. */
    private static float scalarEuclidean(float[] query, MemorySegment segment, long offset,
                                          float[] mins, float[] scales, int length) {
        float sum = 0;
        for (int i = 0; i < length; i++) {
            int unsigned = segment.get(ValueLayout.JAVA_BYTE, offset + i) & 0xFF;
            float d = unsigned * scales[i] + mins[i];
            float diff = query[i] - d;
            sum += diff * diff;
        }
        return (float) Math.sqrt(sum);
    }

    private float[] randomFloatVector(int dims) {
        float[] v = new float[dims];
        for (int i = 0; i < dims; i++) v[i] = RNG.nextFloat() * 2 - 1;
        return v;
    }

    private void fillRandomBytes(MemorySegment seg, int length) {
        for (int i = 0; i < length; i++) {
            seg.set(ValueLayout.JAVA_BYTE, i, (byte) RNG.nextInt(256));
        }
    }
}
