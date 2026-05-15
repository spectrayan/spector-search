package com.spectrayan.spector.bench;

import com.spectrayan.spector.core.SimilarityFunction;
import com.spectrayan.spector.engine.SpectorConfig;
import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.index.HnswParams;
import com.spectrayan.spector.query.SearchQuery;
import com.spectrayan.spector.query.SearchResponse;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Heavy end-to-end performance benchmarks for SpectorEngine.
 *
 * <p>Tests ingestion throughput and search latency at scale (50K / 100K documents)
 * across keyword, vector, and hybrid search modes. Exercises the full pipeline:
 * vector store → HNSW index → BM25 index → hybrid orchestrator → RRF fusion.</p>
 *
 * <p>Run via:</p>
 * <pre>
 *   java -jar spector-bench/target/benchmarks.jar HeavyPerformanceBenchmark
 * </pre>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(value = 1, jvmArgsAppend = {
        "--add-modules", "jdk.incubator.vector",
        "-Xmx4g", "-Xms2g",
        "-XX:+UseZGC"
})
public class HeavyPerformanceBenchmark {

    @Param({"50000", "100000"})
    int datasetSize;

    @Param({"128", "384"})
    int dimensions;

    SpectorEngine engine;
    float[] queryVector;
    String[] queryTexts;

    private static final String[] CORPUS_WORDS = {
            "java", "search", "vector", "simd", "performance", "engine",
            "query", "index", "document", "semantic", "hybrid", "fusion",
            "kernel", "memory", "thread", "virtual", "panama", "arena",
            "embedding", "transformer", "attention", "neural", "network",
            "language", "model", "inference", "batch", "latency", "throughput",
            "optimization", "parallel", "concurrent", "cache", "locality",
            "pipeline", "streaming", "chunking", "tokenize", "normalize",
            "cosine", "euclidean", "dot", "product", "similarity", "distance",
            "approximate", "nearest", "neighbor", "graph", "layer", "hnsw",
            "recall", "precision", "relevance", "ranking", "score", "fusion"
    };

    @Setup(Level.Trial)
    public void setup() {
        var hnswParams = new HnswParams(16, 200, 64);
        var config = new SpectorConfig(dimensions, datasetSize + 1000,
                SimilarityFunction.COSINE, hnswParams);
        engine = new SpectorEngine(config);

        Random rng = new Random(42);

        // Ingest dataset
        for (int i = 0; i < datasetSize; i++) {
            // Generate random text content
            StringBuilder sb = new StringBuilder();
            int wordCount = 20 + rng.nextInt(80);
            for (int w = 0; w < wordCount; w++) {
                sb.append(CORPUS_WORDS[rng.nextInt(CORPUS_WORDS.length)]).append(' ');
            }

            // Generate random vector
            float[] vector = new float[dimensions];
            for (int j = 0; j < dimensions; j++) {
                vector[j] = rng.nextFloat() * 2f - 1f;
            }

            engine.ingest("doc-" + i, sb.toString(), vector);
        }

        // Prepare query vectors and texts
        Random queryRng = new Random(999);
        queryVector = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            queryVector[i] = queryRng.nextFloat() * 2f - 1f;
        }

        queryTexts = new String[]{
                "java vector search engine",
                "semantic similarity neural network",
                "hybrid fusion ranking optimization",
                "hnsw approximate nearest neighbor graph",
                "performance throughput latency pipeline parallel concurrent"
        };
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (engine != null) engine.close();
    }

    // ─────────────── Keyword Search Benchmarks ───────────────

    @Benchmark
    public void keywordSearch_top10(Blackhole bh) {
        bh.consume(engine.keywordSearch("java vector search engine", 10));
    }

    @Benchmark
    public void keywordSearch_top50(Blackhole bh) {
        bh.consume(engine.keywordSearch("semantic similarity neural network", 50));
    }

    @Benchmark
    public void keywordSearch_top100(Blackhole bh) {
        bh.consume(engine.keywordSearch("performance throughput latency pipeline parallel concurrent", 100));
    }

    // ─────────────── Vector Search Benchmarks ───────────────

    @Benchmark
    public void vectorSearch_top10(Blackhole bh) {
        bh.consume(engine.vectorSearch(queryVector, 10));
    }

    @Benchmark
    public void vectorSearch_top50(Blackhole bh) {
        bh.consume(engine.vectorSearch(queryVector, 50));
    }

    @Benchmark
    public void vectorSearch_top100(Blackhole bh) {
        bh.consume(engine.vectorSearch(queryVector, 100));
    }

    // ─────────────── Hybrid Search Benchmarks ───────────────

    @Benchmark
    public void hybridSearch_top10(Blackhole bh) {
        bh.consume(engine.hybridSearch("java vector search", queryVector, 10));
    }

    @Benchmark
    public void hybridSearch_top50(Blackhole bh) {
        bh.consume(engine.hybridSearch("semantic similarity neural", queryVector, 50));
    }

    @Benchmark
    public void hybridSearch_top100(Blackhole bh) {
        bh.consume(engine.hybridSearch("performance throughput latency pipeline", queryVector, 100));
    }

    // ─────────────── Mixed Workload ───────────────

    @Benchmark
    public void mixedWorkload(Blackhole bh) {
        // Simulates realistic mixed usage: keyword → vector → hybrid
        bh.consume(engine.keywordSearch("java search engine", 10));
        bh.consume(engine.vectorSearch(queryVector, 10));
        bh.consume(engine.hybridSearch("vector similarity", queryVector, 20));
    }
}
