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

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks for index operations (HNSW insert, HNSW search, BM25 search)
 * parameterized across dataset sizes (10k, 50k, 100k).
 *
 * <p>Validates Requirements 19.2, 24.2, 24.4</p>
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
public class IndexOperationBenchmark {

    @Param({"10000", "50000", "100000"})
    int datasetSize;

    @Param({"128", "384"})
    int dimensions;

    SpectorEngine engine;
    float[] queryVector;
    int insertCounter;
    Random insertRng;

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
        var config = new SpectorConfig(dimensions, datasetSize + 10_000,
                SimilarityFunction.COSINE, hnswParams);
        engine = new DefaultSpectorEngine(config);

        Random rng = new Random(42);
        for (int i = 0; i < datasetSize; i++) {
            String content = generateText(20 + rng.nextInt(60), rng);
            float[] vector = randomVector(dimensions, rng);
            engine.ingest("doc-" + i, content, vector);
        }

        Random qrng = new Random(999);
        queryVector = randomVector(dimensions, qrng);
        insertCounter = datasetSize;
        insertRng = new Random(123);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (engine != null) engine.close();
    }

    // ─────────────── HNSW Search ───────────────

    @Benchmark
    public void hnswSearch_top10(Blackhole bh) {
        bh.consume(engine.vectorSearch(queryVector, 10));
    }

    @Benchmark
    public void hnswSearch_top50(Blackhole bh) {
        bh.consume(engine.vectorSearch(queryVector, 50));
    }

    // ─────────────── BM25 Search ───────────────

    @Benchmark
    public void bm25Search_top10(Blackhole bh) {
        bh.consume(engine.keywordSearch("java vector search engine performance", 10));
    }

    // ─────────────── Hybrid Search ───────────────

    @Benchmark
    public void hybridSearch_top10(Blackhole bh) {
        bh.consume(engine.hybridSearch("java vector search", queryVector, 10));
    }

    // ─────────────── HNSW Insert ───────────────

    @Benchmark
    public void hnswInsert(Blackhole bh) {
        String id = "insert-" + insertCounter++;
        String content = generateText(30, insertRng);
        float[] vector = randomVector(dimensions, insertRng);
        engine.ingest(id, content, vector);
        bh.consume(id);
    }

    // ─────────────── Helpers ───────────────

    private float[] randomVector(int dim, Random rng) {
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) v[i] = rng.nextFloat() * 2f - 1f;
        return v;
    }

    private String generateText(int wordCount, Random rng) {
        StringBuilder sb = new StringBuilder(wordCount * 8);
        for (int w = 0; w < wordCount; w++) {
            sb.append(WORDS[rng.nextInt(WORDS.length)]).append(' ');
        }
        return sb.toString();
    }
}
