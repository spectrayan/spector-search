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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.bench.cognitive.model.BenchmarkCorpusRecord;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Centralized error handling for the dataset generation pipeline.
 *
 * <p>Implements the error recovery strategy specified in Req 18.12:</p>
 * <ul>
 *   <li><b>Ollama unreachable</b> — retry 3× with exponential backoff → save partial + exit non-zero</li>
 *   <li><b>Invalid LLM JSON</b> — retry with rephrased prompt → skip + log</li>
 *   <li><b>Schema violations</b> — auto-repair where possible, skip + log otherwise</li>
 * </ul>
 *
 * <h3>Partial Save Behavior</h3>
 * <p>When generation fails mid-pipeline, saves whatever has been successfully
 * generated so far to a {@code partial/} subdirectory under the output path.
 * This enables resumption via incremental generation on the next run.</p>
 */
public final class GenerationErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(GenerationErrorHandler.class);

    private final Path outputDir;
    private final ObjectMapper mapper;
    private final List<String> errorLog;
    private final List<String> skippedRecords;

    /**
     * Creates an error handler for the given output directory.
     *
     * @param outputDir the base output directory for the generation run
     */
    public GenerationErrorHandler(Path outputDir) {
        this.outputDir = outputDir;
        this.mapper = JsonMapper.builder().build();
        this.errorLog = new ArrayList<>();
        this.skippedRecords = new ArrayList<>();
    }

    /**
     * Handles an Ollama connectivity failure.
     *
     * <p>Called when all retry attempts have been exhausted. Saves partial output
     * and records the error for the final report.</p>
     *
     * @param e             the exception that caused the failure
     * @param partialCorpus whatever corpus has been generated so far
     */
    public void handleOllamaUnreachable(OllamaCompletionException e,
                                         List<BenchmarkCorpusRecord> partialCorpus) {
        String error = "Ollama unreachable after max retries: " + e.getMessage();
        errorLog.add(error);
        log.error(error);

        if (!partialCorpus.isEmpty()) {
            savePartialCorpus(partialCorpus);
        }
    }

    /**
     * Handles invalid JSON returned by the LLM.
     *
     * <p>Logs the parse failure and records it as a skipped item. The caller
     * should have already attempted rephrased prompts via {@link OllamaCompletionClient}
     * retry logic — this method is called when all parse attempts are exhausted.</p>
     *
     * @param context    description of what was being generated
     * @param rawOutput  the invalid JSON response (for debugging)
     * @param e          the parse exception
     */
    public void handleInvalidJson(String context, String rawOutput, Exception e) {
        String error = "Invalid LLM JSON for " + context + ": " + e.getMessage();
        errorLog.add(error);
        skippedRecords.add(context);
        log.warn("{} — raw output: {}", error, truncate(rawOutput, 200));
    }

    /**
     * Handles a schema violation in a generated record and attempts auto-repair.
     *
     * <p>Auto-repair strategies:</p>
     * <ul>
     *   <li>Importance out of range → clamp to [0.05, 10.0]</li>
     *   <li>Arousal out of range → clamp to [0, 255]</li>
     *   <li>Too many tags → truncate to 10</li>
     *   <li>Empty text → skip record</li>
     *   <li>Null required fields → skip record</li>
     * </ul>
     *
     * @param record the record with a schema violation
     * @return repaired record, or null if repair is not possible (skip)
     */
    public BenchmarkCorpusRecord autoRepairOrSkip(BenchmarkCorpusRecord record) {
        if (record.text() == null || record.text().isBlank()) {
            skippedRecords.add("Empty text in " + record.id());
            log.debug("Skipping record {} — empty text", record.id());
            return null;
        }
        if (record.id() == null || record.id().isBlank()) {
            skippedRecords.add("Null ID in record");
            log.debug("Skipping record with null ID");
            return null;
        }

        // Auto-repair
        boolean repaired = false;
        float importance = record.importance();
        int arousal = record.arousal();
        List<String> tags = record.synapticTags();
        String text = record.text();
        String title = record.title();

        if (importance < 0.05f || importance > 10.0f) {
            importance = Math.max(0.05f, Math.min(10.0f, importance));
            repaired = true;
        }
        if (arousal < 0 || arousal > 255) {
            arousal = Math.max(0, Math.min(255, arousal));
            repaired = true;
        }
        if (tags != null && tags.size() > 10) {
            tags = tags.subList(0, 10);
            repaired = true;
        }
        if (tags == null) {
            tags = List.of("general");
            repaired = true;
        }
        if (text.length() > 4096) {
            text = text.substring(0, 4096);
            repaired = true;
        }
        if (title == null || title.isBlank()) {
            title = "Untitled";
            repaired = true;
        } else if (title.length() > 256) {
            title = title.substring(0, 256);
            repaired = true;
        }

        if (repaired) {
            log.debug("Auto-repaired record {}", record.id());
            return new BenchmarkCorpusRecord(
                    record.id(), text, title, tags,
                    record.valence(), importance, arousal,
                    record.sessionId(), record.timestampMs(),
                    record.entityMentions(), record.memoryType(),
                    record.agentRecallCount()
            );
        }
        return record;
    }

    /**
     * Saves partial corpus to a recovery directory for future incremental generation.
     *
     * @param partialCorpus the records generated so far
     */
    public void savePartialCorpus(List<BenchmarkCorpusRecord> partialCorpus) {
        try {
            Path partialDir = outputDir.resolve("partial");
            Files.createDirectories(partialDir);

            Path corpusFile = partialDir.resolve("corpus.jsonl");
            StringBuilder sb = new StringBuilder();
            for (BenchmarkCorpusRecord record : partialCorpus) {
                sb.append(mapper.writeValueAsString(record)).append('\n');
            }
            Files.writeString(corpusFile, sb.toString());

            log.info("Saved {} partial records to {}", partialCorpus.size(), corpusFile);
        } catch (IOException e) {
            log.error("Failed to save partial corpus: {}", e.getMessage());
        }
    }

    /**
     * Writes the error log summary to the output directory.
     *
     * @throws IOException if writing fails
     */
    public void writeErrorLog() throws IOException {
        if (errorLog.isEmpty() && skippedRecords.isEmpty()) {
            return;
        }

        Path logFile = outputDir.resolve("generation-errors.log");
        Files.createDirectories(outputDir);

        StringBuilder sb = new StringBuilder();
        sb.append("=== Generation Error Log ===\n");
        sb.append("Timestamp: ").append(Instant.now()).append("\n\n");

        if (!errorLog.isEmpty()) {
            sb.append("--- Errors (").append(errorLog.size()).append(") ---\n");
            for (String error : errorLog) {
                sb.append("  • ").append(error).append("\n");
            }
            sb.append("\n");
        }

        if (!skippedRecords.isEmpty()) {
            sb.append("--- Skipped Records (").append(skippedRecords.size()).append(") ---\n");
            for (String skipped : skippedRecords) {
                sb.append("  • ").append(skipped).append("\n");
            }
        }

        Files.writeString(logFile, sb.toString());
        log.info("Error log written to: {}", logFile);
    }

    /**
     * Returns the number of errors encountered.
     *
     * @return error count
     */
    public int errorCount() {
        return errorLog.size();
    }

    /**
     * Returns the number of skipped records.
     *
     * @return skip count
     */
    public int skipCount() {
        return skippedRecords.size();
    }

    /**
     * Returns whether any errors occurred during generation.
     *
     * @return true if at least one error was recorded
     */
    public boolean hasErrors() {
        return !errorLog.isEmpty();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "<null>";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
