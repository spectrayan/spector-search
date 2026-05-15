package com.spectrayan.spector.bench;

import com.spectrayan.spector.core.SimilarityFunction;
import com.spectrayan.spector.engine.SpectorConfig;
import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.index.HnswParams;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks measuring ingestion throughput for SpectorEngine.
 *
 * <p>Measures:
 * <ul>
 *   <li>Single document ingestion latency/throughput</li>
 *   <li>Batch ingestion (100 docs at a time)</li>
 *   <li>Impact of index size on insertion cost (HNSW graph growth)</li>
 * </ul>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(value = 1, jvmArgsAppend = {
        "--add-modules", "jdk.incubator.vector",
        "-Xmx4g", "-Xms2g",
        "-XX:+UseZGC"
})
public class IngestionBenchmark {

    @Param({"128", "384"})
    int dimensions;

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
        engine = new SpectorEngine(config);
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
