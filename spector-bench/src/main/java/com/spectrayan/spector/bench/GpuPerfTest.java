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
package com.spectrayan.spector.bench;

import java.util.Random;

import com.spectrayan.spector.core.similarity.CosineSimilarity;
import com.spectrayan.spector.gpu.CudaKernelLauncher;
import com.spectrayan.spector.gpu.GpuBatchSimilarity;
import com.spectrayan.spector.gpu.GpuCapability;

/**
 * Quick GPU vs CPU SIMD performance comparison.
 * Tests batch cosine similarity at various batch sizes.
 */
public class GpuPerfTest {

    private static final int DIMENSIONS = 384;
    private static final int WARMUP = 20;
    private static final int MEASURE = 100;
    private static final int[] BATCH_SIZES = {1, 8, 32, 128, 512, 1024, 4096, 10000, 50000, 100000};

    public static void main(String[] args) {
        System.out.println("GPU: " + GpuCapability.detect().report());
        System.out.println("Dimensions: " + DIMENSIONS);
        System.out.println();

        if (!GpuCapability.isAvailable()) {
            System.out.println("ERROR: No GPU available!");
            return;
        }

        Random rng = new Random(42);
        GpuBatchSimilarity gpu = new GpuBatchSimilarity();

        System.out.printf("%-10s %12s %12s %12s%n", "Batch", "CPU SIMD", "GPU", "Speedup");
        System.out.println("-".repeat(52));

        for (int batchSize : BATCH_SIZES) {
            float[] query = randomVec(DIMENSIONS, rng);
            float[] database = new float[batchSize * DIMENSIONS];
            for (int i = 0; i < database.length; i++) {
                database[i] = rng.nextFloat() * 2f - 1f;
            }

            // Warmup both
            for (int i = 0; i < WARMUP; i++) {
                cpuBatchCosine(query, database, batchSize, DIMENSIONS);
                gpu.batchCosineSimilarity(query, database, batchSize, DIMENSIONS);
            }

            // Measure CPU
            long cpuTotal = 0;
            for (int i = 0; i < MEASURE; i++) {
                long t0 = System.nanoTime();
                cpuBatchCosine(query, database, batchSize, DIMENSIONS);
                cpuTotal += System.nanoTime() - t0;
            }
            double cpuAvgMs = (cpuTotal / (double) MEASURE) / 1e6;

            // Measure GPU (direct kernel launch, bypassing threshold)
            long gpuTotal = 0;
            CudaKernelLauncher directLauncher = null;
            try { directLauncher = new CudaKernelLauncher(); } catch (Exception ignored) {}
            if (directLauncher != null) {
                for (int i = 0; i < WARMUP; i++) {
                    directLauncher.batchCosine(query, database, batchSize, DIMENSIONS);
                }
                for (int i = 0; i < MEASURE; i++) {
                    long t0 = System.nanoTime();
                    directLauncher.batchCosine(query, database, batchSize, DIMENSIONS);
                    gpuTotal += System.nanoTime() - t0;
                }
                directLauncher.close();
            }
            double gpuAvgMs = directLauncher != null ? (gpuTotal / (double) MEASURE) / 1e6 : -1;

            double speedup = cpuAvgMs / gpuAvgMs;
            System.out.printf("%-10d %10.3f ms %10.3f ms %10.1f×%n",
                    batchSize, cpuAvgMs, gpuAvgMs, speedup);
        }

        gpu.close();
    }

    private static float[] cpuBatchCosine(float[] query, float[] database,
                                           int n, int dims) {
        float[] results = new float[n];
        for (int i = 0; i < n; i++) {
            results[i] = CosineSimilarity.compute(query, 0, database, i * dims, dims);
        }
        return results;
    }

    private static float[] randomVec(int dims, Random rng) {
        float[] v = new float[dims];
        for (int i = 0; i < dims; i++) v[i] = rng.nextFloat() * 2f - 1f;
        return v;
    }
}
