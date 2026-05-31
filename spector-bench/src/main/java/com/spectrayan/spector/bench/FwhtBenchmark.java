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

import com.spectrayan.spector.core.quantization.svasq.SvasqFwht;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks for {@link SvasqFwht} — the FWHT rotation step in the SVASQ pipeline.
 *
 * <p>FWHT is applied once per query preparation ({@code O(N log N)} additions, zero multiplications)
 * and once per indexed vector during encode. This benchmark isolates the rotation cost so it
 * can be tracked separately from the SVASQ quantization overhead.</p>
 *
 * <p>Run via:</p>
 * <pre>
 *   java -jar spector-bench/target/benchmarks.jar FwhtBenchmark
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
        "-Xmx2g"
})
public class FwhtBenchmark {

    /** Vector dimensionality — 128 (small), 768 (BERT), 1024 (padded BERT). */
    @Param({"128", "768", "1024"})
    int dims;

    private SvasqFwht fwht;
    private float[] inputVector;
    private float[] outputBuffer;

    @Setup(Level.Trial)
    public void setup() {
        fwht = new SvasqFwht(dims, 42L);
        int paddedDim = fwht.paddedDim();
        Random rng = new Random(1L);
        inputVector = new float[dims];
        outputBuffer = new float[paddedDim];
        for (int i = 0; i < dims; i++) {
            inputVector[i] = (float) rng.nextGaussian();
        }
    }

    /**
     * Allocating variant — creates a new output buffer each call.
     * Represents the encode path at index time.
     */
    @Benchmark
    public float[] rotate_allocating(Blackhole bh) {
        return fwht.rotate(inputVector);
    }

    /**
     * Zero-copy variant — writes into a pre-allocated buffer.
     * Represents the query preparation path (called once per search).
     */
    @Benchmark
    public void rotate_intoBuffer(Blackhole bh) {
        fwht.rotate(inputVector, outputBuffer);
        bh.consume(outputBuffer);
    }

    /**
     * Raw FWHT butterfly on an already-prepared array.
     * Isolates the O(N log N) butterfly cost without sign-flip or normalization overhead.
     */
    @Benchmark
    public void rawFwht_butterfly(Blackhole bh) {
        System.arraycopy(inputVector, 0, outputBuffer, 0, dims);
        SvasqFwht.applyFwht(outputBuffer);
        bh.consume(outputBuffer);
    }
}
