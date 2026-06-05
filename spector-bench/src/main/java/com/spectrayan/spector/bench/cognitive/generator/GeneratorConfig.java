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
package com.spectrayan.spector.bench.cognitive.generator;

import java.nio.file.Path;

/**
 * Configuration for the cognitive benchmark dataset generator pipeline.
 *
 * <p>Encapsulates all tunable parameters for LLM-based corpus generation,
 * including Ollama connection settings, persona file paths, output directories,
 * and generation size parameters.</p>
 *
 * <h3>CLI Parsing</h3>
 * <p>Use {@link #fromArgs(String[])} to parse command-line arguments in
 * {@code --key=value} format. Unrecognized arguments are ignored with a warning.</p>
 *
 * @param ollamaUrl              Ollama server base URL (default: http://localhost:11434)
 * @param modelName              LLM model to use for generation (default: llama3.1)
 * @param maxRetries             maximum retry attempts for failed LLM calls (default: 3)
 * @param personaPath            path to persona.json file (required)
 * @param seedPath               path to seed corpus directory (nullable — omit for fresh generation)
 * @param approvedPath           path to approved corpus for incremental generation (nullable)
 * @param outputDir              directory where generated dataset files are written
 * @param totalCorpusSize        target number of corpus records to generate (default: 2000)
 * @param numDays                number of simulated days to generate (default: 30)
 * @param conversationsPerDay    number of conversations per simulated day (default: 8)
 * @param biographicalDepthYears how many years into the past to generate biographical memories (default: 10)
 */
public record GeneratorConfig(
        String ollamaUrl,
        String modelName,
        int maxRetries,
        Path personaPath,
        Path seedPath,
        Path approvedPath,
        Path outputDir,
        int totalCorpusSize,
        int numDays,
        int conversationsPerDay,
        int biographicalDepthYears
) {

    /** Default Ollama server URL. */
    private static final String DEFAULT_OLLAMA_URL = "http://localhost:11434";

    /** Default LLM model name. */
    private static final String DEFAULT_MODEL = "llama3.1";

    /** Default maximum retries for LLM calls. */
    private static final int DEFAULT_MAX_RETRIES = 3;

    /** Default total corpus size target. */
    private static final int DEFAULT_TOTAL_CORPUS_SIZE = 2000;

    /** Default number of simulated days. */
    private static final int DEFAULT_NUM_DAYS = 30;

    /** Default conversations per day. */
    private static final int DEFAULT_CONVERSATIONS_PER_DAY = 8;

    /** Default biographical depth in years. */
    private static final int DEFAULT_BIOGRAPHICAL_DEPTH_YEARS = 10;

    /**
     * Parses command-line arguments into a {@code GeneratorConfig}.
     *
     * <p>Supported arguments (all use {@code --key=value} format):</p>
     * <ul>
     *   <li>{@code --ollama-url} — Ollama server URL</li>
     *   <li>{@code --model} — LLM model name</li>
     *   <li>{@code --max-retries} — max retry count</li>
     *   <li>{@code --persona} — path to persona.json (required)</li>
     *   <li>{@code --seed} — path to seed corpus directory</li>
     *   <li>{@code --approved} — path to approved corpus directory</li>
     *   <li>{@code --output} — output directory (required)</li>
     *   <li>{@code --corpus-size} — total corpus size target</li>
     *   <li>{@code --num-days} — number of simulated days</li>
     *   <li>{@code --conversations-per-day} — conversations per day</li>
     *   <li>{@code --biographical-depth} — biographical depth in years</li>
     * </ul>
     *
     * @param args command-line arguments
     * @return parsed configuration
     * @throws IllegalArgumentException if required arguments are missing or values are invalid
     */
    public static GeneratorConfig fromArgs(String[] args) {
        String ollamaUrl = DEFAULT_OLLAMA_URL;
        String modelName = DEFAULT_MODEL;
        int maxRetries = DEFAULT_MAX_RETRIES;
        Path personaPath = null;
        Path seedPath = null;
        Path approvedPath = null;
        Path outputDir = null;
        int totalCorpusSize = DEFAULT_TOTAL_CORPUS_SIZE;
        int numDays = DEFAULT_NUM_DAYS;
        int conversationsPerDay = DEFAULT_CONVERSATIONS_PER_DAY;
        int biographicalDepthYears = DEFAULT_BIOGRAPHICAL_DEPTH_YEARS;

        for (String arg : args) {
            if (!arg.startsWith("--")) {
                continue;
            }
            int eqIdx = arg.indexOf('=');
            if (eqIdx < 0) {
                continue;
            }
            String key = arg.substring(2, eqIdx);
            String value = arg.substring(eqIdx + 1);

            switch (key) {
                case "ollama-url" -> ollamaUrl = value;
                case "model" -> modelName = value;
                case "max-retries" -> maxRetries = parsePositiveInt(key, value);
                case "persona" -> personaPath = Path.of(value);
                case "seed" -> seedPath = value.isEmpty() ? null : Path.of(value);
                case "approved" -> approvedPath = value.isEmpty() ? null : Path.of(value);
                case "output" -> outputDir = Path.of(value);
                case "corpus-size" -> totalCorpusSize = parsePositiveInt(key, value);
                case "num-days" -> numDays = parsePositiveInt(key, value);
                case "conversations-per-day" -> conversationsPerDay = parsePositiveInt(key, value);
                case "biographical-depth" -> biographicalDepthYears = parsePositiveInt(key, value);
                default -> System.err.println("WARNING: Unknown argument: --" + key);
            }
        }

        if (personaPath == null) {
            throw new IllegalArgumentException("Required argument --persona is missing");
        }
        if (outputDir == null) {
            throw new IllegalArgumentException("Required argument --output is missing");
        }

        return new GeneratorConfig(
                ollamaUrl, modelName, maxRetries,
                personaPath, seedPath, approvedPath, outputDir,
                totalCorpusSize, numDays, conversationsPerDay, biographicalDepthYears
        );
    }

    private static int parsePositiveInt(String key, String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException("--" + key + " must be positive, got: " + value);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + key + " must be an integer, got: " + value);
        }
    }
}
