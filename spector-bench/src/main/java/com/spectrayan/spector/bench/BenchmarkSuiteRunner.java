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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import com.spectrayan.spector.gpu.GpuCapability;

/**
 * JMH benchmark suite runner that executes all benchmarks with JSON output
 * and performs baseline regression detection.
 *
 * <p>Produces JSON results in {@code target/jmh-results/} and compares against
 * a stored baseline (if present) to detect performance regressions exceeding
 * the 10% threshold.</p>
 *
 * <p>Validates Requirements 19.1, 19.2, 19.3, 19.4, 19.5, 19.6, 24.1, 24.2, 24.3, 24.4, 24.5, 24.6</p>
 *
 * <p>Usage:</p>
 * <pre>
 *   java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED \
 *     -cp spector-bench/target/classes:... \
 *     com.spectrayan.spector.bench.BenchmarkSuiteRunner [--include pattern] [--baseline path]
 * </pre>
 */
public class BenchmarkSuiteRunner {

    private static final String OUTPUT_DIR = "target/jmh-results";
    private static final String BASELINE_FILE = "target/jmh-results/baseline.json";

    public static void main(String[] args) throws RunnerException, IOException {
        String includePattern = "com.spectrayan.spector.bench.*";
        String baselinePath = BASELINE_FILE;
        boolean skipGpu = false;

        // Parse arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--include" -> includePattern = args[++i];
                case "--baseline" -> baselinePath = args[++i];
                case "--skip-gpu" -> skipGpu = true;
            }
        }

        // Detect GPU availability
        boolean gpuAvailable = !skipGpu && GpuCapability.isAvailable();
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║          SPECTOR SEARCH — JMH BENCHMARK SUITE           ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.printf("  GPU: %s%n", gpuAvailable ? GpuCapability.detect().report() : "not available (GPU benchmarks skipped)");
        System.out.printf("  Include: %s%n", includePattern);
        System.out.printf("  Baseline: %s%n", baselinePath);
        System.out.println();

        // Ensure output directory exists
        Path outputDir = Path.of(OUTPUT_DIR);
        Files.createDirectories(outputDir);

        // Generate timestamped output filename
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String resultFile = OUTPUT_DIR + "/jmh-result-" + timestamp + ".json";

        // Build JMH options
        ChainedOptionsBuilder builder = new OptionsBuilder()
                .include(includePattern)
                .warmupIterations(3)
                .measurementIterations(5)
                .forks(1)
                .jvmArgsAppend("--add-modules", "jdk.incubator.vector",
                        "--enable-native-access=ALL-UNNAMED")
                .resultFormat(ResultFormatType.JSON)
                .result(resultFile);

        // Conditionally exclude GPU benchmarks if GPU not available
        if (!gpuAvailable) {
            builder = builder.exclude(".*GpuKernelBenchmark.*");
        }

        Options opts = builder.build();

        System.out.printf("  Output: %s%n%n", resultFile);

        // Run benchmarks
        new Runner(opts).run();

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.printf("  Results written to: %s%n", resultFile);

        // Run baseline regression detection
        Path baseline = Path.of(baselinePath);
        if (Files.exists(baseline)) {
            System.out.println("  Checking for regressions against baseline...");
            BaselineRegressionDetector detector = new BaselineRegressionDetector();
            BaselineRegressionDetector.RegressionReport report =
                    detector.compare(baseline, Path.of(resultFile));

            if (report.hasRegressions()) {
                System.out.println();
                System.out.println("  ⚠️  REGRESSIONS DETECTED (>10% threshold):");
                for (var regression : report.regressions()) {
                    System.out.printf("    ✗ %s: %.2f → %.2f (%+.1f%%)%n",
                            regression.benchmark(), regression.baselineScore(),
                            regression.currentScore(), regression.percentChange());
                }
                System.out.println();
                System.out.println("  Run with updated baseline? Save current results:");
                System.out.printf("    cp %s %s%n", resultFile, baselinePath);
                System.exit(1);
            } else {
                System.out.println("  ✓ No regressions detected.");
            }
        } else {
            System.out.println("  No baseline found. Saving current results as baseline.");
            Files.copy(Path.of(resultFile), baseline);
        }

        System.out.println("═══════════════════════════════════════════════════════════");
    }
}
