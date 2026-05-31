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

import com.spectrayan.spector.index.BM25Index;
import com.spectrayan.spector.index.ScoredResult;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks for BM25 keyword index.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class BM25Benchmark {

    @Param({"1000", "10000"})
    int datasetSize;

    BM25Index index;

    private static final String[] WORDS = {
            "java", "search", "vector", "simd", "performance", "engine",
            "query", "index", "document", "semantic", "hybrid", "fusion",
            "kernel", "memory", "thread", "virtual", "panama", "arena"
    };

    @Setup
    public void setup() {
        index = new BM25Index();
        Random rng = new Random(42);

        for (int i = 0; i < datasetSize; i++) {
            StringBuilder sb = new StringBuilder();
            int wordCount = 10 + rng.nextInt(50);
            for (int w = 0; w < wordCount; w++) {
                sb.append(WORDS[rng.nextInt(WORDS.length)]).append(' ');
            }
            index.index("doc-" + i, sb.toString());
        }
    }

    @TearDown
    public void tearDown() {
        index.close();
    }

    @Benchmark
    public void singleTermSearch(Blackhole bh) {
        bh.consume(index.search("java", 10));
    }

    @Benchmark
    public void multiTermSearch(Blackhole bh) {
        bh.consume(index.search("java vector search engine", 10));
    }
}
