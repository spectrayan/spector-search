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

import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.index.HnswIndex;
import com.spectrayan.spector.config.HnswParams;
import com.spectrayan.spector.index.ScoredResult;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks for HNSW index operations.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class HnswBenchmark {

    @Param({"1000", "10000"})
    int datasetSize;

    @Param({"128"})
    int dimensions;

    HnswIndex index;
    float[] queryVector;

    @Setup
    public void setup() {
        var params = new HnswParams(16, 200, 50);
        index = new HnswIndex(dimensions, datasetSize, SimilarityFunction.COSINE, params);
        Random rng = new Random(42);

        for (int i = 0; i < datasetSize; i++) {
            float[] v = new float[dimensions];
            for (int j = 0; j < dimensions; j++) v[j] = rng.nextFloat() * 2f - 1f;
            index.add("doc-" + i, i, v);
        }

        queryVector = new float[dimensions];
        Random queryRng = new Random(999);
        for (int i = 0; i < dimensions; i++) queryVector[i] = queryRng.nextFloat() * 2f - 1f;
    }

    @TearDown
    public void tearDown() {
        index.close();
    }

    @Benchmark
    public void searchTop10(Blackhole bh) {
        bh.consume(index.search(queryVector, 10));
    }

    @Benchmark
    public void searchTop50(Blackhole bh) {
        bh.consume(index.search(queryVector, 50));
    }
}
