package com.spectrayan.spector.bench;

import java.util.Random;

import com.spectrayan.spector.core.CosineSimilarity;
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
    private static final int[] BATCH_SIZES = {1, 8, 32, 128, 512, 1024, 4096};

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

            // Measure GPU
            long gpuTotal = 0;
            for (int i = 0; i < MEASURE; i++) {
                long t0 = System.nanoTime();
                gpu.batchCosineSimilarity(query, database, batchSize, DIMENSIONS);
                gpuTotal += System.nanoTime() - t0;
            }
            double gpuAvgMs = (gpuTotal / (double) MEASURE) / 1e6;

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
