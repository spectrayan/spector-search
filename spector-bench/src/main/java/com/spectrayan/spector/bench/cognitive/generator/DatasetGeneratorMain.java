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
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.bench.cognitive.DatasetLoader;
import com.spectrayan.spector.bench.cognitive.model.BenchmarkCorpusRecord;
import com.spectrayan.spector.bench.cognitive.model.BenchmarkQuery;
import com.spectrayan.spector.bench.cognitive.model.EntityRelation;
import com.spectrayan.spector.bench.cognitive.model.HebbianEdgeDef;
import com.spectrayan.spector.bench.cognitive.model.PersonaDef;
import com.spectrayan.spector.bench.cognitive.model.RelevanceJudgment;
import com.spectrayan.spector.bench.cognitive.model.TemporalChainDef;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Main entry point for the cognitive benchmark dataset generator pipeline.
 *
 * <p>Orchestrates the full generation flow:</p>
 * <ol>
 *   <li>Load persona definition</li>
 *   <li>Load seed/approved corpus (if provided)</li>
 *   <li>Generate daily conversations</li>
 *   <li>Generate biographical memories</li>
 *   <li>Annotate with cognitive metadata</li>
 *   <li>Build entity, temporal, and Hebbian graphs</li>
 *   <li>Generate queries with relevance judgments</li>
 *   <li>Validate cross-file consistency</li>
 *   <li>Write output files</li>
 * </ol>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * java -cp spector-bench.jar com.spectrayan.spector.bench.cognitive.generator.DatasetGeneratorMain \
 *   --persona=datasets/cognitive-benchmark/persona.json \
 *   --output=datasets/cognitive-benchmark \
 *   --model=llama3.1 \
 *   --corpus-size=2000
 * }</pre>
 *
 * <h3>Exit Codes</h3>
 * <ul>
 *   <li>0 — success</li>
 *   <li>1 — validation failure</li>
 *   <li>2 — generation error (partial output may be saved)</li>
 *   <li>3 — configuration/setup error</li>
 * </ul>
 */
public final class DatasetGeneratorMain {

    private static final Logger log = LoggerFactory.getLogger(DatasetGeneratorMain.class);

    /** Exit code for successful generation. */
    public static final int EXIT_SUCCESS = 0;

    /** Exit code for validation failure. */
    public static final int EXIT_VALIDATION_FAILURE = 1;

    /** Exit code for generation errors (partial output saved). */
    public static final int EXIT_GENERATION_ERROR = 2;

    /** Exit code for configuration/setup errors. */
    public static final int EXIT_CONFIG_ERROR = 3;

    private final GeneratorConfig config;
    private final ObjectMapper mapper;

    /**
     * Creates the generator main with the given configuration.
     *
     * @param config generator configuration
     */
    public DatasetGeneratorMain(GeneratorConfig config) {
        this.config = config;
        this.mapper = JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build();
    }

    /**
     * CLI entry point.
     *
     * @param args command-line arguments in --key=value format
     */
    public static void main(String[] args) {
        try {
            GeneratorConfig config = GeneratorConfig.fromArgs(args);
            DatasetGeneratorMain generator = new DatasetGeneratorMain(config);
            int exitCode = generator.run();
            System.exit(exitCode);
        } catch (IllegalArgumentException e) {
            System.err.println("Configuration error: " + e.getMessage());
            printUsage();
            System.exit(EXIT_CONFIG_ERROR);
        } catch (Exception e) {
            log.error("Unexpected error during dataset generation", e);
            System.exit(EXIT_GENERATION_ERROR);
        }
    }

    /**
     * Runs the full generation pipeline with incremental writes after each phase.
     *
     * @return exit code (0 = success, non-zero = failure)
     */
    public int run() {
        log.info("=== Cognitive Benchmark Dataset Generator ===");
        log.info("Config: model={}, corpusSize={}, numDays={}, output={}",
                config.modelName(), config.totalCorpusSize(), config.numDays(), config.outputDir());

        try {
            Files.createDirectories(config.outputDir());

            // Step 1: Load persona
            PersonaLoader personaLoader = new PersonaLoader();
            PersonaDef persona = personaLoader.load(config.personaPath());

            // Write persona immediately
            Files.writeString(config.outputDir().resolve("persona.json"),
                    mapper.writeValueAsString(persona));
            log.info("Wrote persona.json");

            // Step 2: Initialize Ollama client and verify connectivity
            OllamaCompletionClient client = new OllamaCompletionClient(config);
            if (!client.isAvailable()) {
                log.error("Ollama server is not available at {}", config.ollamaUrl());
                return EXIT_GENERATION_ERROR;
            }

            // Step 3: Load seed/approved corpus
            List<BenchmarkCorpusRecord> existingCorpus = loadExistingCorpus();
            int startingId = existingCorpus.size() + 1;

            // Step 4: Generate conversations — write each day incrementally
            log.info("--- Phase 1: Generating daily conversations ---");
            ConversationGenerator conversationGen = new ConversationGenerator(client, persona, config);
            conversationGen.setNextMemoryId(startingId);

            List<BenchmarkCorpusRecord> generatedCorpus = new ArrayList<>(existingCorpus);
            int targetNewRecords = config.totalCorpusSize() - existingCorpus.size();
            int conversationTarget = (int) (targetNewRecords * 0.8);

            Path corpusFile = config.outputDir().resolve("corpus.jsonl");
            // Write existing/approved records first
            try (var writer = Files.newBufferedWriter(corpusFile)) {
                ObjectMapper jsonlMapper = JsonMapper.builder().build();
                for (BenchmarkCorpusRecord r : existingCorpus) {
                    writer.write(jsonlMapper.writeValueAsString(r));
                    writer.newLine();
                }
            }

            for (int day = 0; day < config.numDays() && generatedCorpus.size() < existingCorpus.size() + conversationTarget; day++) {
                List<BenchmarkCorpusRecord> dayRecords = conversationGen.generateDay(day, generatedCorpus);
                generatedCorpus.addAll(dayRecords);

                // Append this day's records to corpus.jsonl immediately
                appendJsonl(corpusFile, dayRecords);
                log.info("Phase 1: Day {}/{} complete, {} total records (written to disk)",
                        day + 1, config.numDays(), generatedCorpus.size());
            }

            // Step 5: Generate biographical memories
            log.info("--- Phase 2: Generating biographical memories ---");
            BiographicalGenerator bioGen = new BiographicalGenerator(client, persona, config);
            bioGen.setNextMemoryId(generatedCorpus.size() + 1);
            List<BenchmarkCorpusRecord> bioRecords = bioGen.generateBiographical(generatedCorpus);
            generatedCorpus.addAll(bioRecords);

            // Append biographical records to corpus.jsonl
            appendJsonl(corpusFile, bioRecords);
            log.info("Phase 2 complete: {} biographical records written", bioRecords.size());

            // Step 6: Annotate cognitive metadata
            log.info("--- Phase 3: Annotating cognitive metadata ---");
            CognitiveAnnotator annotator = new CognitiveAnnotator(client, persona);
            List<BenchmarkCorpusRecord> toAnnotate = generatedCorpus.subList(
                    existingCorpus.size(), generatedCorpus.size());
            List<BenchmarkCorpusRecord> annotated = annotator.annotateAll(toAnnotate);

            // Reassemble and rewrite corpus with annotations
            List<BenchmarkCorpusRecord> finalCorpus = new ArrayList<>(existingCorpus);
            finalCorpus.addAll(annotated);

            // Rewrite corpus.jsonl with annotated data
            writeJsonl(corpusFile, finalCorpus);
            log.info("Phase 3 complete: corpus.jsonl rewritten with annotations ({} records)", finalCorpus.size());

            // Step 7: Build graphs — write each immediately
            log.info("--- Phase 4: Building graphs ---");
            GraphBuilder graphBuilder = new GraphBuilder();

            List<EntityRelation> entityRelations = graphBuilder.buildEntityGraph(finalCorpus);
            writeJsonl(config.outputDir().resolve("entities.jsonl"), entityRelations);
            log.info("Phase 4: entities.jsonl written ({} relations)", entityRelations.size());

            List<TemporalChainDef> temporalChains = graphBuilder.buildTemporalChains(finalCorpus);
            writeJsonl(config.outputDir().resolve("temporal_chains.jsonl"), temporalChains);
            log.info("Phase 4: temporal_chains.jsonl written ({} chains)", temporalChains.size());

            List<HebbianEdgeDef> hebbianEdges = graphBuilder.buildHebbianEdges(finalCorpus);
            writeJsonl(config.outputDir().resolve("hebbian_edges.jsonl"), hebbianEdges);
            log.info("Phase 4: hebbian_edges.jsonl written ({} edges)", hebbianEdges.size());

            // Step 8: Generate queries — write immediately
            log.info("--- Phase 5: Generating queries ---");
            QueryGenerator queryGen = new QueryGenerator(client, persona);
            QueryGenerator.QueryResult queryResult = queryGen.generate(finalCorpus);

            writeJsonl(config.outputDir().resolve("queries.jsonl"), queryResult.queries());
            writeQrels(config.outputDir().resolve("qrels.tsv"), queryResult.judgments());
            log.info("Phase 5: queries.jsonl ({}) and qrels.tsv ({}) written",
                    queryResult.queries().size(), queryResult.judgments().size());

            // Step 9: Validate
            log.info("--- Phase 6: Validating dataset ---");
            DatasetValidator validator = new DatasetValidator();
            DatasetValidator.ValidationResult validationResult = validator.validate(
                    finalCorpus, queryResult.queries(), queryResult.judgments(),
                    entityRelations, temporalChains, hebbianEdges);

            validator.writeReport(config.outputDir(), validationResult);

            if (!validationResult.isValid()) {
                log.error("Dataset generation complete but validation FAILED");
                return EXIT_VALIDATION_FAILURE;
            }

            log.info("=== Dataset generation complete: {} corpus, {} queries, {} judgments ===",
                    finalCorpus.size(), queryResult.queries().size(), queryResult.judgments().size());
            client.close();
            return EXIT_SUCCESS;

        } catch (OllamaCompletionException e) {
            log.error("Ollama communication failure: {}", e.getMessage());
            savePartialOutput();
            return EXIT_GENERATION_ERROR;
        } catch (Exception e) {
            log.error("Generation pipeline failed", e);
            savePartialOutput();
            return EXIT_GENERATION_ERROR;
        }
    }

    // ─────────────── Output writing ───────────────

    private void writeOutput(List<BenchmarkCorpusRecord> corpus,
                             List<BenchmarkQuery> queries,
                             List<RelevanceJudgment> judgments,
                             List<EntityRelation> entityRelations,
                             List<TemporalChainDef> temporalChains,
                             List<HebbianEdgeDef> hebbianEdges,
                             PersonaDef persona) throws IOException {
        Path outputDir = config.outputDir();
        Files.createDirectories(outputDir);

        // Write corpus.jsonl
        writeJsonl(outputDir.resolve("corpus.jsonl"), corpus);

        // Write queries.jsonl
        writeJsonl(outputDir.resolve("queries.jsonl"), queries);

        // Write qrels.tsv
        writeQrels(outputDir.resolve("qrels.tsv"), judgments);

        // Write entities.jsonl
        writeJsonl(outputDir.resolve("entities.jsonl"), entityRelations);

        // Write temporal_chains.jsonl
        writeJsonl(outputDir.resolve("temporal_chains.jsonl"), temporalChains);

        // Write hebbian_edges.jsonl
        writeJsonl(outputDir.resolve("hebbian_edges.jsonl"), hebbianEdges);

        // Write persona.json (pretty-printed)
        Files.writeString(outputDir.resolve("persona.json"),
                mapper.writeValueAsString(persona));

        log.info("All output files written to: {}", outputDir);
    }

    private void writeJsonl(Path file, List<?> records) throws IOException {
        ObjectMapper jsonlMapper = JsonMapper.builder().build(); // compact for JSONL
        StringBuilder sb = new StringBuilder();
        for (Object record : records) {
            sb.append(jsonlMapper.writeValueAsString(record)).append('\n');
        }
        Files.writeString(file, sb.toString());
        log.debug("Wrote {} records to {}", records.size(), file.getFileName());
    }

    private void appendJsonl(Path file, List<?> records) throws IOException {
        if (records.isEmpty()) return;
        ObjectMapper jsonlMapper = JsonMapper.builder().build();
        StringBuilder sb = new StringBuilder();
        for (Object record : records) {
            sb.append(jsonlMapper.writeValueAsString(record)).append('\n');
        }
        Files.writeString(file, sb.toString(), java.nio.file.StandardOpenOption.APPEND);
        log.debug("Appended {} records to {}", records.size(), file.getFileName());
    }

    private void writeQrels(Path file, List<RelevanceJudgment> judgments) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("query_id\tcorpus_id\trelevance_grade\n");
        for (RelevanceJudgment j : judgments) {
            sb.append(j.queryId()).append('\t')
              .append(j.corpusId()).append('\t')
              .append(j.grade()).append('\n');
        }
        Files.writeString(file, sb.toString());
        log.debug("Wrote {} qrels to {}", judgments.size(), file.getFileName());
    }

    // ─────────────── Incremental generation support ───────────────

    private List<BenchmarkCorpusRecord> loadExistingCorpus() {
        // Load approved corpus if specified (incremental generation)
        if (config.approvedPath() != null && Files.exists(config.approvedPath())) {
            return loadApprovedCorpus(config.approvedPath());
        }
        // Load seed corpus if specified
        if (config.seedPath() != null && Files.exists(config.seedPath())) {
            return loadSeedCorpus(config.seedPath());
        }
        return new ArrayList<>();
    }

    private List<BenchmarkCorpusRecord> loadApprovedCorpus(Path approvedDir) {
        try {
            DatasetLoader loader = new DatasetLoader();
            Path corpusFile = approvedDir.resolve("corpus.jsonl");
            if (Files.exists(corpusFile)) {
                List<BenchmarkCorpusRecord> approved = loader.loadCorpus(corpusFile);
                log.info("Loaded {} approved corpus records from {}", approved.size(), approvedDir);
                return approved;
            }
        } catch (Exception e) {
            log.warn("Failed to load approved corpus from {}: {}", approvedDir, e.getMessage());
        }
        return new ArrayList<>();
    }

    private List<BenchmarkCorpusRecord> loadSeedCorpus(Path seedDir) {
        try {
            DatasetLoader loader = new DatasetLoader();
            Path corpusFile = seedDir.resolve("corpus.jsonl");
            if (Files.exists(corpusFile)) {
                List<BenchmarkCorpusRecord> seed = loader.loadCorpus(corpusFile);
                log.info("Loaded {} seed corpus records from {}", seed.size(), seedDir);
                return seed;
            }
        } catch (Exception e) {
            log.warn("Failed to load seed corpus from {}: {}", seedDir, e.getMessage());
        }
        return new ArrayList<>();
    }

    // ─────────────── Error recovery ───────────────

    private void savePartialOutput() {
        try {
            Path partialDir = config.outputDir().resolve("partial");
            Files.createDirectories(partialDir);
            Files.writeString(partialDir.resolve("generation-error.txt"),
                    "Generation failed. Partial output may be incomplete.\n"
                            + "Timestamp: " + java.time.Instant.now() + "\n");
            log.info("Partial output marker saved to: {}", partialDir);
        } catch (IOException e) {
            log.error("Failed to save partial output marker", e);
        }
    }

    private static void printUsage() {
        System.err.println("""
                Usage: DatasetGeneratorMain [options]
                
                Required:
                  --persona=<path>      Path to persona.json file
                  --output=<path>       Output directory for generated dataset
                
                Optional:
                  --ollama-url=<url>    Ollama server URL (default: http://localhost:11434)
                  --model=<name>        LLM model name (default: llama3.1)
                  --max-retries=<n>     Max retry attempts (default: 3)
                  --seed=<path>         Path to seed corpus directory
                  --approved=<path>     Path to approved corpus for incremental generation
                  --corpus-size=<n>     Target corpus size (default: 2000)
                  --num-days=<n>        Number of simulated days (default: 30)
                  --conversations-per-day=<n>  Conversations per day (default: 8)
                  --biographical-depth=<n>     Biographical depth in years (default: 10)
                """);
    }
}
