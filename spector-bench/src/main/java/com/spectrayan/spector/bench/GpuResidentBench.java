package com.spectrayan.spector.bench;

import java.util.Random;

import com.spectrayan.spector.gpu.GpuCapability;
import com.spectrayan.spector.gpu.GpuVectorIndex;

/**
 * Benchmark for GPU-resident vector search (persistent device memory model).
 * Database is uploaded to VRAM once, then queries only transfer the query vector.
 */
public class GpuResidentBench {

    private static final int DIMS = 384;
    private static final int WARMUP = 10;
    private static final int MEASURE = 50;

    public static void main(String[] args) {
        System.out.println("GPU: " + GpuCapability.detect().report());
        System.out.println("Dimensions: " + DIMS);
        System.out.println();

        int[] sizes = {10_000, 100_000, 500_000, 1_000_000};

        for (int n : sizes) {
            long memMB = (long) n * DIMS * 4 / (1024 * 1024);
            System.out.printf("▶ %,d vectors (%d MB)%n", n, memMB);

            Random rng = new Random(42);
            float[] database = new float[n * DIMS];
            for (int i = 0; i < database.length; i++) {
                database[i] = rng.nextFloat() * 2f - 1f;
            }
            float[] query = new float[DIMS];
            for (int i = 0; i < DIMS; i++) query[i] = rng.nextFloat() * 2f - 1f;

            // Create GPU index (uploads to VRAM)
            long uploadStart = System.nanoTime();
            GpuVectorIndex gpuIndex = GpuVectorIndex.create(database, n, DIMS, true);
            long uploadMs = (System.nanoTime() - uploadStart) / 1_000_000;
            System.out.printf("  Upload: %dms | GPU active: %s%n", uploadMs, gpuIndex.isGpuActive());

            // Create CPU-only index for comparison
            GpuVectorIndex cpuIndex = GpuVectorIndex.create(database, n, DIMS, false);

            // Warmup
            for (int i = 0; i < WARMUP; i++) {
                gpuIndex.search(query);
                cpuIndex.search(query);
            }

            // Measure GPU
            long gpuTotal = 0;
            for (int i = 0; i < MEASURE; i++) {
                long t0 = System.nanoTime();
                gpuIndex.search(query);
                gpuTotal += System.nanoTime() - t0;
            }
            double gpuMs = (gpuTotal / (double) MEASURE) / 1e6;

            // Measure CPU
            long cpuTotal = 0;
            for (int i = 0; i < MEASURE; i++) {
                long t0 = System.nanoTime();
                cpuIndex.search(query);
                cpuTotal += System.nanoTime() - t0;
            }
            double cpuMs = (cpuTotal / (double) MEASURE) / 1e6;

            double speedup = cpuMs / gpuMs;
            System.out.printf("  CPU SIMD: %.2f ms | GPU: %.2f ms | Speedup: %.1f×%n%n",
                    cpuMs, gpuMs, speedup);

            gpuIndex.close();
            cpuIndex.close();
        }
    }
}
