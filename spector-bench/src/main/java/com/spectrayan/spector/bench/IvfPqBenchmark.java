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
import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.index.ivf.IvfPqIndex;
import com.spectrayan.spector.index.pq.ProductQuantizer;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks for IVF-PQ index, Product Quantization, and batch similarity.
 *
 * <p>Measures:</p>
 * <ul>
 *   <li>IVF-PQ search latency at various scales (10K, 50K, 100K vectors)</li>
 *   <li>PQ encode/decode throughput</li>
 *   <li>ADC distance table computation</li>
 *   <li>Batch cosine similarity (SIMD-optimized)</li>
 *   <li>IVF-PQ vs HNSW search comparison</li>
 * </ul>
 *
 * <p>Run via:</p>
 * <pre>
 *   java -jar spector-bench/target/benchmarks.jar IvfPqBenchmark
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
public class IvfPqBenchmark {

    @Param({"10000", "50000"})
    int datasetSize;

    @Param({"128", "384"})
    int dimensions;

    IvfPqIndex ivfPqIndex;
    ProductQuantizer pq;
    float[][] vectors;
    float[] queryVector;
    float[] flatDatabase; // N*D flat array for batch similarity

    @Setup(Level.Trial)
    public void setup() {
        Random rng = new Random(42);
        int M = dimensions / 8;  // PQ subspaces
        int nlist = Math.max(16, (int) Math.sqrt(datasetSize));

        // Generate random vectors
        vectors = new float[datasetSize][dimensions];
        for (int i = 0; i < datasetSize; i++) {
            for (int d = 0; d < dimensions; d++) {
                vectors[i][d] = rng.nextFloat() * 2f - 1f;
            }
        }

        // Train PQ on a sample
        int sampleSize = Math.min(datasetSize, 5000);
        float[][] sample = new float[sampleSize][];
        System.arraycopy(vectors, 0, sample, 0, sampleSize);
        pq = ProductQuantizer.train(sample, dimensions, M);

        // Create and train IVF-PQ index
        ivfPqIndex = new IvfPqIndex(dimensions, nlist, 10, M, SimilarityFunction.COSINE);
        ivfPqIndex.train(vectors);

        // Index all vectors
        for (int i = 0; i < datasetSize; i++) {
            ivfPqIndex.add("doc-" + i, i, vectors[i]);
        }

        // Flatten database for batch similarity benchmark
        flatDatabase = new float[datasetSize * dimensions];
        for (int i = 0; i < datasetSize; i++) {
            System.arraycopy(vectors[i], 0, flatDatabase, i * dimensions, dimensions);
        }

        // Query vector
        queryVector = new float[dimensions];
        Random qrng = new Random(999);
        for (int d = 0; d < dimensions; d++) {
            queryVector[d] = qrng.nextFloat() * 2f - 1f;
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        ivfPqIndex.close();
    }

    // ─────────────── IVF-PQ Search ───────────────

    @Benchmark
    public void ivfPqSearch_top10(Blackhole bh) {
        bh.consume(ivfPqIndex.search(queryVector, 10));
    }

    @Benchmark
    public void ivfPqSearch_top50(Blackhole bh) {
        bh.consume(ivfPqIndex.search(queryVector, 50));
    }

    @Benchmark
    public void ivfPqSearch_top100(Blackhole bh) {
        bh.consume(ivfPqIndex.search(queryVector, 100));
    }

    // ─────────────── PQ Operations ───────────────

    @Benchmark
    public void pqEncode(Blackhole bh) {
        bh.consume(pq.encode(queryVector));
    }

    @Benchmark
    public void pqDecode(Blackhole bh) {
        byte[] code = pq.encode(queryVector);
        bh.consume(pq.decode(code));
    }

    @Benchmark
    public void pqDistanceTable(Blackhole bh) {
        bh.consume(pq.computeDistanceTable(queryVector));
    }

    @Benchmark
    public void pqAdcDistance_1000vectors(Blackhole bh) {
        float[][] table = pq.computeDistanceTable(queryVector);
        int count = Math.min(1000, datasetSize);
        for (int i = 0; i < count; i++) {
            byte[] code = pq.encode(vectors[i]);
            bh.consume(ProductQuantizer.adcDistance(table, code));
        }
    }

    // ─────────────── Batch Similarity (SIMD) ───────────────

    @Benchmark
    public void batchCosineSimilarity_1000vectors(Blackhole bh) {
        int n = Math.min(1000, datasetSize);
        float[] results = new float[n];

        // SIMD-friendly single-pass
        float queryNorm = 0;
        for (int d = 0; d < dimensions; d++) queryNorm += queryVector[d] * queryVector[d];
        queryNorm = (float) Math.sqrt(queryNorm);

        for (int i = 0; i < n; i++) {
            float dot = 0, docNorm = 0;
            int offset = i * dimensions;
            for (int d = 0; d < dimensions; d++) {
                dot += queryVector[d] * flatDatabase[offset + d];
                docNorm += flatDatabase[offset + d] * flatDatabase[offset + d];
            }
            docNorm = (float) Math.sqrt(docNorm);
            results[i] = queryNorm > 0 && docNorm > 0 ? dot / (queryNorm * docNorm) : 0;
        }
        bh.consume(results);
    }
}
