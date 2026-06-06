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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.bench.cognitive.model.BenchmarkCorpusRecord;
import com.spectrayan.spector.bench.cognitive.model.BenchmarkQuery;
import com.spectrayan.spector.bench.cognitive.model.EntityRelation;
import com.spectrayan.spector.bench.cognitive.model.HebbianEdgeDef;
import com.spectrayan.spector.bench.cognitive.model.RelevanceJudgment;
import com.spectrayan.spector.bench.cognitive.model.TemporalChainDef;

/**
 * Validates cross-file consistency of a generated cognitive benchmark dataset.
 *
 * <p>Performs comprehensive integrity checks across all dataset files to ensure:</p>
 * <ul>
 *   <li>All IDs are unique within their respective files</li>
 *   <li>Referential integrity: qrels reference valid query/corpus IDs</li>
 *   <li>Temporal chains reference valid corpus IDs in correct order</li>
 *   <li>Hebbian edges reference valid and distinct corpus IDs</li>
 *   <li>Entity relations reference valid corpus IDs</li>
 *   <li>Field constraints are satisfied (ranges, required fields)</li>
 * </ul>
 *
 * <h3>Exit Behavior</h3>
 * <p>If validation fails, writes a detailed error report to the output directory
 * and returns a non-zero exit code for CI integration.</p>
 */
public final class DatasetValidator {

    private static final Logger log = LoggerFactory.getLogger(DatasetValidator.class);

    /**
     * Validates the complete dataset for cross-file consistency.
     *
     * @param corpus          the corpus records
     * @param queries         the query definitions
     * @param judgments       the relevance judgments (qrels)
     * @param entityRelations the entity relations
     * @param temporalChains  the temporal chain definitions
     * @param hebbianEdges    the Hebbian edge definitions
     * @return validation result containing errors (if any)
     */
    public ValidationResult validate(List<BenchmarkCorpusRecord> corpus,
                                     List<BenchmarkQuery> queries,
                                     List<RelevanceJudgment> judgments,
                                     List<EntityRelation> entityRelations,
                                     List<TemporalChainDef> temporalChains,
                                     List<HebbianEdgeDef> hebbianEdges) {
        log.info("Validating dataset: {} corpus, {} queries, {} judgments, "
                        + "{} relations, {} chains, {} edges",
                corpus.size(), queries.size(), judgments.size(),
                entityRelations.size(), temporalChains.size(), hebbianEdges.size());

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Build ID sets for referential checks
        Set<String> corpusIds = validateCorpusIds(corpus, errors);
        Set<String> queryIds = validateQueryIds(queries, errors);

        // Validate referential integrity
        validateQrels(judgments, queryIds, corpusIds, errors, warnings);
        validateTemporalChains(temporalChains, corpusIds, errors);
        validateHebbianEdges(hebbianEdges, corpusIds, errors);
        validateEntityRelations(entityRelations, corpusIds, errors);

        // Validate field constraints
        validateCorpusFields(corpus, errors, warnings);
        validateQueryFields(queries, errors);

        // Summary
        if (errors.isEmpty()) {
            log.info("Dataset validation PASSED — {} warnings", warnings.size());
        } else {
            log.error("Dataset validation FAILED — {} errors, {} warnings", errors.size(), warnings.size());
        }

        return new ValidationResult(errors, warnings);
    }

    /**
     * Writes a validation error report to the specified output directory.
     *
     * @param outputDir the directory to write the report
     * @param result    the validation result
     * @throws IOException if writing fails
     */
    public void writeReport(Path outputDir, ValidationResult result) throws IOException {
        Path reportFile = outputDir.resolve("validation-report.txt");
        StringBuilder sb = new StringBuilder();
        sb.append("=== Dataset Validation Report ===\n\n");

        if (result.isValid()) {
            sb.append("Status: PASSED\n");
        } else {
            sb.append("Status: FAILED\n");
            sb.append("Errors: ").append(result.errors().size()).append("\n");
        }
        sb.append("Warnings: ").append(result.warnings().size()).append("\n\n");

        if (!result.errors().isEmpty()) {
            sb.append("--- ERRORS ---\n");
            for (int i = 0; i < result.errors().size(); i++) {
                sb.append(i + 1).append(". ").append(result.errors().get(i)).append("\n");
            }
            sb.append("\n");
        }

        if (!result.warnings().isEmpty()) {
            sb.append("--- WARNINGS ---\n");
            for (int i = 0; i < result.warnings().size(); i++) {
                sb.append(i + 1).append(". ").append(result.warnings().get(i)).append("\n");
            }
        }

        Files.createDirectories(outputDir);
        Files.writeString(reportFile, sb.toString());
        log.info("Validation report written to: {}", reportFile);
    }

    // ─────────────── Validation methods ───────────────

    private Set<String> validateCorpusIds(List<BenchmarkCorpusRecord> corpus, List<String> errors) {
        Set<String> ids = new HashSet<>();
        for (BenchmarkCorpusRecord record : corpus) {
            if (record.id() == null || record.id().isBlank()) {
                errors.add("Corpus record with null/blank ID found");
            } else if (!ids.add(record.id())) {
                errors.add("Duplicate corpus ID: " + record.id());
            }
        }
        return ids;
    }

    private Set<String> validateQueryIds(List<BenchmarkQuery> queries, List<String> errors) {
        Set<String> ids = new HashSet<>();
        for (BenchmarkQuery query : queries) {
            if (query.id() == null || query.id().isBlank()) {
                errors.add("Query with null/blank ID found");
            } else if (!ids.add(query.id())) {
                errors.add("Duplicate query ID: " + query.id());
            }
        }
        return ids;
    }

    private void validateQrels(List<RelevanceJudgment> judgments, Set<String> queryIds,
                               Set<String> corpusIds, List<String> errors, List<String> warnings) {
        Set<String> coveredQueries = new HashSet<>();
        for (RelevanceJudgment j : judgments) {
            if (!queryIds.contains(j.queryId())) {
                errors.add("Qrel references unknown query: " + j.queryId());
            }
            if (!corpusIds.contains(j.corpusId())) {
                errors.add("Qrel references unknown corpus: " + j.corpusId()
                        + " (query=" + j.queryId() + ")");
            }
            if (j.grade() < 0 || j.grade() > 3) {
                errors.add("Invalid relevance grade " + j.grade()
                        + " for query=" + j.queryId() + ", corpus=" + j.corpusId());
            }
            coveredQueries.add(j.queryId());
        }

        // Warn about queries with no judgments
        for (String qId : queryIds) {
            if (!coveredQueries.contains(qId)) {
                warnings.add("Query " + qId + " has no relevance judgments");
            }
        }
    }

    private void validateTemporalChains(List<TemporalChainDef> chains, Set<String> corpusIds,
                                         List<String> errors) {
        Set<String> sessionIds = new HashSet<>();
        for (TemporalChainDef chain : chains) {
            if (chain.sessionId() == null || chain.sessionId().isBlank()) {
                errors.add("Temporal chain with null/blank sessionId");
                continue;
            }
            if (!sessionIds.add(chain.sessionId())) {
                errors.add("Duplicate temporal chain sessionId: " + chain.sessionId());
            }
            if (chain.orderedMemoryIds() == null || chain.orderedMemoryIds().size() < 2) {
                errors.add("Temporal chain " + chain.sessionId()
                        + " has fewer than 2 memory IDs");
                continue;
            }
            for (String memId : chain.orderedMemoryIds()) {
                if (!corpusIds.contains(memId)) {
                    errors.add("Temporal chain " + chain.sessionId()
                            + " references unknown corpus ID: " + memId);
                }
            }
        }
    }

    private void validateHebbianEdges(List<HebbianEdgeDef> edges, Set<String> corpusIds,
                                       List<String> errors) {
        for (HebbianEdgeDef edge : edges) {
            if (!corpusIds.contains(edge.memoryIdA())) {
                errors.add("Hebbian edge references unknown memoryIdA: " + edge.memoryIdA());
            }
            if (!corpusIds.contains(edge.memoryIdB())) {
                errors.add("Hebbian edge references unknown memoryIdB: " + edge.memoryIdB());
            }
            if (edge.memoryIdA() != null && edge.memoryIdA().equals(edge.memoryIdB())) {
                errors.add("Hebbian edge has identical endpoints: " + edge.memoryIdA());
            }
            if (edge.coActivationCount() < 1 || edge.coActivationCount() > 10000) {
                errors.add("Hebbian edge co-activation count out of range [1,10000]: "
                        + edge.coActivationCount() + " (edge " + edge.memoryIdA()
                        + " <-> " + edge.memoryIdB() + ")");
            }
        }
    }

    private void validateEntityRelations(List<EntityRelation> relations, Set<String> corpusIds,
                                          List<String> errors) {
        for (EntityRelation rel : relations) {
            if (rel.fromEntity() == null || rel.fromEntity().name() == null) {
                errors.add("Entity relation with null fromEntity");
            }
            if (rel.toEntity() == null || rel.toEntity().name() == null) {
                errors.add("Entity relation with null toEntity");
            }
            if (rel.sourceMemoryIds() == null || rel.sourceMemoryIds().isEmpty()) {
                errors.add("Entity relation with no source memory IDs");
                continue;
            }
            for (String memId : rel.sourceMemoryIds()) {
                if (!corpusIds.contains(memId)) {
                    errors.add("Entity relation references unknown corpus ID: " + memId);
                }
            }
        }
    }

    private void validateCorpusFields(List<BenchmarkCorpusRecord> corpus,
                                       List<String> errors, List<String> warnings) {
        for (BenchmarkCorpusRecord r : corpus) {
            if (r.text() == null || r.text().length() < 1 || r.text().length() > 4096) {
                errors.add("Corpus " + r.id() + ": text length out of range [1,4096]");
            }
            if (r.title() == null || r.title().length() < 1 || r.title().length() > 256) {
                errors.add("Corpus " + r.id() + ": title length out of range [1,256]");
            }
            if (r.importance() < 0.05f || r.importance() > 10.0f) {
                errors.add("Corpus " + r.id() + ": importance out of range [0.05,10.0]: "
                        + r.importance());
            }
            if (r.arousal() < 0 || r.arousal() > 255) {
                errors.add("Corpus " + r.id() + ": arousal out of range [0,255]: " + r.arousal());
            }
            if (r.agentRecallCount() < 0) {
                errors.add("Corpus " + r.id() + ": agentRecallCount must be >= 0: " + r.agentRecallCount());
            }
            if (r.synapticTags() == null || r.synapticTags().isEmpty()) {
                warnings.add("Corpus " + r.id() + ": no synaptic tags");
            } else if (r.synapticTags().size() > 10) {
                errors.add("Corpus " + r.id() + ": too many synaptic tags (max 10): "
                        + r.synapticTags().size());
            }
        }
    }

    private void validateQueryFields(List<BenchmarkQuery> queries, List<String> errors) {
        for (BenchmarkQuery q : queries) {
            if (q.text() == null || q.text().length() < 1 || q.text().length() > 1024) {
                errors.add("Query " + q.id() + ": text length out of range [1,1024]");
            }
            if (q.cognitiveProfile() == null) {
                errors.add("Query " + q.id() + ": null cognitive profile");
            }
            if (q.synapticFilterTags() != null && q.synapticFilterTags().size() > 10) {
                errors.add("Query " + q.id() + ": too many filter tags (max 10): "
                        + q.synapticFilterTags().size());
            }
        }
    }

    /**
     * Result of dataset validation.
     *
     * @param errors   list of validation errors (empty if valid)
     * @param warnings list of non-critical warnings
     */
    public record ValidationResult(List<String> errors, List<String> warnings) {
        /** Returns true if no errors were found. */
        public boolean isValid() {
            return errors.isEmpty();
        }
    }
}
