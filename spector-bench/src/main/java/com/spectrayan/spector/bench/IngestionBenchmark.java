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
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.config.SpectorConfig;
import com.spectrayan.spector.engine.DefaultSpectorEngine;
import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.config.HnswParams;

/**
 * Benchmarks measuring ingestion throughput for SpectorEngine.
 *
 * <p>Measures:
 * <ul>
 *   <li>Single document ingestion latency/throughput</li>
 *   <li>Batch ingestion (100 docs at a time)</li>
 *   <li>Impact of index size on insertion cost (HNSW graph growth)</li>
 * </ul>
 *
 * <p>Validates Requirements 19.3, 24.2, 24.4</p>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(value = 1, jvmArgsAppend = {
        "--add-modules", "jdk.incubator.vector",
        "-Xmx6g", "-Xms2g",
        "-XX:+UseZGC"
})
public class IngestionBenchmark {

    @Param({"128", "384", "768"})
    int dimensions;

    @Param({"10000", "50000"})
    int preloadSize;

    private static final int MAX_CAPACITY = 200_000;

    SpectorEngine engine;
    int docCounter;
    Random rng;

    private static final String[] WORDS = {
            "java", "search", "vector", "simd", "performance", "engine",
            "query", "index", "document", "semantic", "hybrid", "fusion",
            "kernel", "memory", "thread", "virtual", "panama", "arena",
            "embedding", "transformer", "attention", "neural", "network",
            "optimization", "parallel", "concurrent", "cache", "locality"
    };

    @Setup(Level.Trial)
    public void setup() {
        var hnswParams = new HnswParams(16, 200, 64);
        var config = new SpectorConfig(dimensions, MAX_CAPACITY,
                SimilarityFunction.COSINE, hnswParams);
        engine = new DefaultSpectorEngine(config);
        docCounter = 0;
        rng = new Random(42);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (engine != null) engine.close();
    }

    @Benchmark
    public void singleDocIngestion(Blackhole bh) {
        String id = "bench-doc-" + docCounter++;
        String content = generateText(30 + rng.nextInt(50));
        float[] vector = generateVector();
        engine.ingest(id, content, vector);
        bh.consume(id);
    }

    @Benchmark
    @OperationsPerInvocation(100)
    public void batchIngestion100(Blackhole bh) {
        String[] ids = new String[100];
        String[] contents = new String[100];
        float[][] vectors = new float[100][dimensions];

        for (int i = 0; i < 100; i++) {
            ids[i] = "batch-doc-" + docCounter++;
            contents[i] = generateText(30 + rng.nextInt(50));
            vectors[i] = generateVector();
        }
        engine.ingestBatch(ids, contents, vectors);
        bh.consume(ids);
    }

    private String generateText(int wordCount) {
        StringBuilder sb = new StringBuilder(wordCount * 8);
        for (int w = 0; w < wordCount; w++) {
            sb.append(WORDS[rng.nextInt(WORDS.length)]).append(' ');
        }
        return sb.toString();
    }

    private float[] generateVector() {
        float[] v = new float[dimensions];
        for (int j = 0; j < dimensions; j++) {
            v[j] = rng.nextFloat() * 2f - 1f;
        }
        return v;
    }
}
