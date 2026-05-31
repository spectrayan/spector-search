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
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import com.spectrayan.spector.core.similarity.SimilarityFunction;

/**
 * JMH benchmarks for SIMD similarity kernels.
 *
 * <p>Run via:</p>
 * <pre>
 *   mvn -pl spector-bench compile exec:java \
 *     -Dexec.mainClass=org.openjdk.jmh.Main \
 *     -Dexec.args="SimdKernelBenchmark -f 1 -wi 3 -i 5"
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class SimdKernelBenchmark {

    @Param({"32", "128", "384", "768", "1536"})
    int dimensions;

    float[] vectorA;
    float[] vectorB;

    @Setup
    public void setup() {
        Random rng = new Random(42);
        vectorA = new float[dimensions];
        vectorB = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            vectorA[i] = rng.nextFloat() * 2f - 1f;
            vectorB[i] = rng.nextFloat() * 2f - 1f;
        }
    }

    @Benchmark
    public void dotProduct(Blackhole bh) {
        bh.consume(SimilarityFunction.DOT_PRODUCT.compute(vectorA, vectorB));
    }

    @Benchmark
    public void cosineSimilarity(Blackhole bh) {
        bh.consume(SimilarityFunction.COSINE.compute(vectorA, vectorB));
    }

    @Benchmark
    public void euclideanDistanceSquared(Blackhole bh) {
        bh.consume(SimilarityFunction.EUCLIDEAN.compute(vectorA, vectorB));
    }
}
