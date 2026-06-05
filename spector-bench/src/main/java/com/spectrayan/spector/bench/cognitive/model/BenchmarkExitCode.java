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
package com.spectrayan.spector.bench.cognitive.model;

/**
 * Process exit codes for the cognitive benchmark harness.
 *
 * <p>Used by {@code CognitiveBenchmarkHarness} to communicate the benchmark outcome
 * to CI pipelines and orchestration scripts via {@link System#exit(int)}. Each code
 * represents a distinct terminal state of the benchmark run.</p>
 *
 * <h3>Exit Code Summary</h3>
 * <table>
 *   <tr><th>Code</th><th>Constant</th><th>Meaning</th></tr>
 *   <tr><td>0</td><td>{@link #SUCCESS}</td><td>All criteria passed</td></tr>
 *   <tr><td>1</td><td>{@link #EFFECT_SIZE_INSUFFICIENT}</td><td>Cohen's d &lt; 0.5</td></tr>
 *   <tr><td>2</td><td>{@link #NDCG_REGRESSION}</td><td>nDCG below regression threshold</td></tr>
 *   <tr><td>3</td><td>{@link #DATASET_VALIDATION_FAILED}</td><td>Dataset referential integrity failed</td></tr>
 *   <tr><td>4</td><td>{@link #SETUP_FAILED}</td><td>Memory instance setup failed</td></tr>
 *   <tr><td>5</td><td>{@link #PARTIAL_EXECUTION}</td><td>Some queries were skipped or timed out</td></tr>
 * </table>
 *
 * @see com.spectrayan.spector.bench.cognitive.CognitiveBenchmarkHarness
 */
public enum BenchmarkExitCode {

    /** Benchmark passed all criteria — cognitive retrieval demonstrates sufficient uplift. */
    SUCCESS(0),

    /** Cohen's d effect size is below the medium threshold (d &lt; 0.5). */
    EFFECT_SIZE_INSUFFICIENT(1),

    /** Cognitive nDCG@10 fell below the configured regression threshold. */
    NDCG_REGRESSION(2),

    /** Dataset failed referential integrity validation during loading. */
    DATASET_VALIDATION_FAILED(3),

    /** SpectorMemory instance setup failed (ingestion, graph loading, or embedding errors). */
    SETUP_FAILED(4),

    /** One or more queries were skipped due to timeout or runtime errors. */
    PARTIAL_EXECUTION(5);

    private final int code;

    BenchmarkExitCode(int code) {
        this.code = code;
    }

    /**
     * Returns the numeric exit code for use with {@link System#exit(int)}.
     *
     * @return the integer exit code (0–5)
     */
    public int code() {
        return code;
    }
}
