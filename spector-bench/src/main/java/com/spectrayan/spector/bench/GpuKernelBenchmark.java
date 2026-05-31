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
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import com.spectrayan.spector.gpu.CudaCosineKernel;
import com.spectrayan.spector.gpu.CudaDotProductKernel;
import com.spectrayan.spector.gpu.GpuCapability;

/**
 * JMH benchmarks for GPU similarity kernels.
 *
 * <p>These benchmarks are conditionally included when a CUDA GPU is detected.
 * When no GPU is available, the benchmarks exercise the CPU SIMD fallback path,
 * allowing performance comparison between GPU and CPU execution paths.</p>
 *
 * <p>Validates Requirements 19.4, 24.5</p>
 *
 * <p>Run via:</p>
 * <pre>
 *   java -jar spector-bench/target/benchmarks.jar GpuKernelBenchmark
 * </pre>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(value = 1, jvmArgsAppend = {
        "--add-modules", "jdk.incubator.vector",
        "--enable-native-access=ALL-UNNAMED",
        "-Xmx4g", "-Xms2g"
})
public class GpuKernelBenchmark {

    @Param({"32", "128", "384", "768", "1536"})
    int dimensions;

    @Param({"1000", "10000"})
    int batchSize;

    private CudaDotProductKernel dotKernel;
    private CudaCosineKernel cosineKernel;
    private float[] queryVector;
    private float[] database;
    private boolean gpuAvailable;

    @Setup(Level.Trial)
    public void setup() {
        gpuAvailable = GpuCapability.isAvailable();

        // Initialize kernels — they fall back to CPU SIMD if GPU unavailable
        dotKernel = new CudaDotProductKernel();
        cosineKernel = new CudaCosineKernel();

        Random rng = new Random(42);
        queryVector = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            queryVector[i] = rng.nextFloat() * 2f - 1f;
        }

        database = new float[batchSize * dimensions];
        for (int i = 0; i < database.length; i++) {
            database[i] = rng.nextFloat() * 2f - 1f;
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (dotKernel != null) dotKernel.close();
    }

    // ─────────────── Dot Product GPU/Fallback ───────────────

    @Benchmark
    public void gpuDotProduct(Blackhole bh) {
        bh.consume(dotKernel.compute(queryVector, database, batchSize, dimensions));
    }

    // ─────────────── Cosine Similarity GPU/Fallback ───────────────

    @Benchmark
    public void gpuCosineSimilarity(Blackhole bh) {
        bh.consume(cosineKernel.compute(queryVector, database, batchSize, dimensions));
    }

    /**
     * Returns whether GPU acceleration is active for this benchmark run.
     * Useful for interpreting results.
     */
    public boolean isGpuActive() {
        return gpuAvailable;
    }
}
