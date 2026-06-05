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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.bench.cognitive.DatasetLoader;
import com.spectrayan.spector.bench.cognitive.model.BenchmarkCorpusRecord;

/**
 * Supports incremental generation by managing approved corpus loading and preservation.
 *
 * <p>Implements the incremental generation contract (Req 18.9):</p>
 * <ul>
 *   <li>Loads approved corpus as read-only context for continuation</li>
 *   <li>Generates only new memories extending the existing timeline</li>
 *   <li>Preserves approved records byte-identical in output</li>
 *   <li>Assigns new memory IDs continuing from the approved corpus</li>
 * </ul>
 *
 * <h3>Usage Pattern</h3>
 * <pre>{@code
 * IncrementalGenerationSupport incremental = new IncrementalGenerationSupport(config);
 * List<BenchmarkCorpusRecord> approved = incremental.loadApprovedRecords();
 * int nextId = incremental.computeNextId(approved);
 * // ... generate new records starting from nextId ...
 * incremental.writeWithPreservation(approved, newRecords, outputDir);
 * }</pre>
 */
public final class IncrementalGenerationSupport {

    private static final Logger log = LoggerFactory.getLogger(IncrementalGenerationSupport.class);

    private final GeneratorConfig config;
    private final DatasetLoader loader;

    /**
     * Creates incremental generation support.
     *
     * @param config the generator configuration
     */
    public IncrementalGenerationSupport(GeneratorConfig config) {
        this.config = config;
        this.loader = new DatasetLoader();
    }

    /**
     * Loads approved corpus records from the approved path.
     *
     * <p>These records are treated as immutable context. They will not be modified,
     * re-annotated, or re-generated — only used as context for new generation and
     * preserved byte-identical in the output.</p>
     *
     * @return list of approved records (empty if no approved path configured)
     */
    public List<BenchmarkCorpusRecord> loadApprovedRecords() {
        Path approvedPath = config.approvedPath();
        if (approvedPath == null || !Files.exists(approvedPath)) {
            log.info("No approved corpus configured — starting fresh generation");
            return List.of();
        }

        Path corpusFile = resolveCorpusFile(approvedPath);
        if (corpusFile == null || !Files.exists(corpusFile)) {
            log.warn("Approved corpus file not found at {}", approvedPath);
            return List.of();
        }

        try {
            List<BenchmarkCorpusRecord> approved = loader.loadCorpus(corpusFile);
            log.info("Loaded {} approved records for incremental generation", approved.size());
            return List.copyOf(approved); // immutable copy
        } catch (Exception e) {
            log.error("Failed to load approved corpus: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Loads seed corpus records for initial context.
     *
     * <p>Seed records provide context for generation but are not necessarily
     * preserved byte-identical (unlike approved records). They serve as the
     * starting point for the first generation run.</p>
     *
     * @return list of seed records (empty if no seed path configured)
     */
    public List<BenchmarkCorpusRecord> loadSeedRecords() {
        Path seedPath = config.seedPath();
        if (seedPath == null || !Files.exists(seedPath)) {
            return List.of();
        }

        Path corpusFile = resolveCorpusFile(seedPath);
        if (corpusFile == null || !Files.exists(corpusFile)) {
            return List.of();
        }

        try {
            List<BenchmarkCorpusRecord> seed = loader.loadCorpus(corpusFile);
            log.info("Loaded {} seed records for context", seed.size());
            return seed;
        } catch (Exception e) {
            log.warn("Failed to load seed corpus: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Computes the next memory ID to assign based on existing records.
     *
     * <p>Parses the numeric suffix from existing IDs (format "mem-NNNN") and returns
     * one greater than the maximum found. Falls back to 1 if no existing records.</p>
     *
     * @param existingRecords approved or seed records
     * @return the next available memory ID number
     */
    public int computeNextId(List<BenchmarkCorpusRecord> existingRecords) {
        int maxId = 0;
        for (BenchmarkCorpusRecord record : existingRecords) {
            String id = record.id();
            if (id != null && id.startsWith("mem-")) {
                try {
                    int num = Integer.parseInt(id.substring(4));
                    maxId = Math.max(maxId, num);
                } catch (NumberFormatException e) {
                    // Non-numeric suffix — skip
                }
            }
        }
        return maxId + 1;
    }

    /**
     * Determines how many new records need to be generated.
     *
     * @param approvedCount number of approved records already present
     * @return number of new records to generate
     */
    public int computeTargetNewRecords(int approvedCount) {
        return Math.max(0, config.totalCorpusSize() - approvedCount);
    }

    /**
     * Writes the final corpus preserving approved records byte-identical.
     *
     * <p>The approved records are written first in their original order, followed by
     * newly generated records. This ensures approved content is never modified.</p>
     *
     * @param approvedRawLines raw JSONL lines from the approved corpus file (for byte preservation)
     * @param newRecords       newly generated records to append
     * @param outputFile       the output corpus.jsonl file
     * @throws IOException if writing fails
     */
    public void writePreservingApproved(List<String> approvedRawLines,
                                         List<BenchmarkCorpusRecord> newRecords,
                                         Path outputFile) throws IOException {
        // Write approved lines byte-identical
        StringBuilder sb = new StringBuilder();
        for (String line : approvedRawLines) {
            sb.append(line).append('\n');
        }
        // Append new records (serialized fresh)
        var jsonMapper = tools.jackson.databind.json.JsonMapper.builder().build();
        for (BenchmarkCorpusRecord record : newRecords) {
            sb.append(jsonMapper.writeValueAsString(record)).append('\n');
        }
        Files.writeString(outputFile, sb.toString());
        log.info("Wrote {} approved + {} new records to {}", approvedRawLines.size(),
                newRecords.size(), outputFile.getFileName());
    }

    /**
     * Loads approved corpus raw lines for byte-identical preservation.
     *
     * @return raw JSONL lines from the approved corpus file
     */
    public List<String> loadApprovedRawLines() {
        Path approvedPath = config.approvedPath();
        if (approvedPath == null) return List.of();

        Path corpusFile = resolveCorpusFile(approvedPath);
        if (corpusFile == null || !Files.exists(corpusFile)) return List.of();

        try {
            return Files.readAllLines(corpusFile).stream()
                    .filter(line -> !line.isBlank())
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to read approved raw lines: {}", e.getMessage());
            return List.of();
        }
    }

    // ─────────────── Private helpers ───────────────

    private Path resolveCorpusFile(Path basePath) {
        if (Files.isRegularFile(basePath)) {
            return basePath;
        }
        // Assume it's a directory containing corpus.jsonl
        return basePath.resolve("corpus.jsonl");
    }
}
