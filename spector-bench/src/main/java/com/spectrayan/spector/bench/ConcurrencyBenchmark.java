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
import com.spectrayan.spector.config.SpectorConfig;
import com.spectrayan.spector.engine.DefaultSpectorEngine;
import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.config.HnswParams;
import com.spectrayan.spector.query.SearchQuery;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Concurrency stress benchmarks for SpectorEngine.
 *
 * <p>Simulates multiple threads performing concurrent searches against a
 * pre-loaded 50K document corpus. Measures throughput degradation under
 * contention to validate thread-safety and scalability.</p>
 *
 * <p>Each thread uses its own query vector (seeded by thread ID) to avoid
 * cache-friendly patterns that would inflate throughput numbers.</p>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(value = 1, jvmArgsAppend = {
        "--add-modules", "jdk.incubator.vector",
        "-Xmx4g", "-Xms2g",
        "-XX:+UseZGC"
})
public class ConcurrencyBenchmark {

    private static final int DATASET_SIZE = 50_000;
    private static final int DIMENSIONS = 128;

    @Param({"4", "8", "16"})
    int threadCount;

    SpectorEngine engine;

    private static final String[] WORDS = {
            "java", "search", "vector", "simd", "performance", "engine",
            "query", "index", "document", "semantic", "hybrid", "fusion",
            "kernel", "memory", "thread", "virtual", "panama", "arena"
    };

    @Setup(Level.Trial)
    public void setup() {
        var hnswParams = new HnswParams(16, 200, 64);
        var config = new SpectorConfig(DIMENSIONS, DATASET_SIZE + 1000,
                SimilarityFunction.COSINE, hnswParams);
        engine = new DefaultSpectorEngine(config);

        Random rng = new Random(42);
        for (int i = 0; i < DATASET_SIZE; i++) {
            StringBuilder sb = new StringBuilder();
            int wordCount = 15 + rng.nextInt(50);
            for (int w = 0; w < wordCount; w++) {
                sb.append(WORDS[rng.nextInt(WORDS.length)]).append(' ');
            }
            float[] vector = new float[DIMENSIONS];
            for (int j = 0; j < DIMENSIONS; j++) {
                vector[j] = rng.nextFloat() * 2f - 1f;
            }
            engine.ingest("doc-" + i, sb.toString(), vector);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (engine != null) engine.close();
    }

    /**
     * Per-thread state: each thread gets its own unique query vector
     * to avoid cache-friendly access patterns.
     */
    @State(Scope.Thread)
    public static class ThreadState {
        float[] queryVector;
        String queryText;
        int queryIndex;

        private static final String[] QUERIES = {
                "java vector search",
                "semantic similarity engine",
                "hybrid fusion ranking",
                "performance optimization thread",
                "memory kernel virtual panama",
                "document index query simd",
                "search engine performance",
                "vector similarity index"
        };

        @Setup(Level.Trial)
        public void setup() {
            long threadSeed = java.lang.Thread.currentThread().threadId();
            Random rng = new Random(threadSeed);
            queryVector = new float[DIMENSIONS];
            for (int i = 0; i < DIMENSIONS; i++) {
                queryVector[i] = rng.nextFloat() * 2f - 1f;
            }
            queryIndex = (int) (threadSeed % QUERIES.length);
            queryText = QUERIES[queryIndex];
        }
    }

    @Benchmark
    @Threads(4)
    @Group("concurrent_keyword_4t")
    public void keywordSearch_4threads(ThreadState ts, Blackhole bh) {
        bh.consume(engine.keywordSearch(ts.queryText, 10));
    }

    @Benchmark
    @Threads(8)
    @Group("concurrent_keyword_8t")
    public void keywordSearch_8threads(ThreadState ts, Blackhole bh) {
        bh.consume(engine.keywordSearch(ts.queryText, 10));
    }

    @Benchmark
    @Threads(16)
    @Group("concurrent_keyword_16t")
    public void keywordSearch_16threads(ThreadState ts, Blackhole bh) {
        bh.consume(engine.keywordSearch(ts.queryText, 10));
    }

    @Benchmark
    @Threads(4)
    @Group("concurrent_vector_4t")
    public void vectorSearch_4threads(ThreadState ts, Blackhole bh) {
        bh.consume(engine.vectorSearch(ts.queryVector, 10));
    }

    @Benchmark
    @Threads(8)
    @Group("concurrent_vector_8t")
    public void vectorSearch_8threads(ThreadState ts, Blackhole bh) {
        bh.consume(engine.vectorSearch(ts.queryVector, 10));
    }

    @Benchmark
    @Threads(16)
    @Group("concurrent_vector_16t")
    public void vectorSearch_16threads(ThreadState ts, Blackhole bh) {
        bh.consume(engine.vectorSearch(ts.queryVector, 10));
    }

    @Benchmark
    @Threads(4)
    @Group("concurrent_hybrid_4t")
    public void hybridSearch_4threads(ThreadState ts, Blackhole bh) {
        bh.consume(engine.hybridSearch(ts.queryText, ts.queryVector, 10));
    }

    @Benchmark
    @Threads(8)
    @Group("concurrent_hybrid_8t")
    public void hybridSearch_8threads(ThreadState ts, Blackhole bh) {
        bh.consume(engine.hybridSearch(ts.queryText, ts.queryVector, 10));
    }

    @Benchmark
    @Threads(16)
    @Group("concurrent_hybrid_16t")
    public void hybridSearch_16threads(ThreadState ts, Blackhole bh) {
        bh.consume(engine.hybridSearch(ts.queryText, ts.queryVector, 10));
    }
}
