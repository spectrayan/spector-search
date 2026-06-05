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
package com.spectrayan.spector.mcp.tools.memory;

import java.util.Map;
import java.util.List;

import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.memory.ConfidenceBand;
import com.spectrayan.spector.memory.CognitiveProfile;
import com.spectrayan.spector.memory.CognitiveResult;
import com.spectrayan.spector.memory.RecallMode;
import com.spectrayan.spector.memory.RecallOptions;
import com.spectrayan.spector.memory.ScoreBreakdown;
import com.spectrayan.spector.memory.SpectorMemory;
import com.spectrayan.spector.memory.ScoringMode;
import com.spectrayan.spector.memory.TextSearchMode;
import com.spectrayan.spector.mcp.schema.ToolSchemaBuilder;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool: {@code recall_context} — cross-tier fused recall with rich provenance.
 *
 * <p>Performs the full 6-phase SIMD scoring pipeline across all memory tiers,
 * returning results with full provenance metadata for LLM grounding.</p>
 */
public final class MemoryRecallTool extends MemoryToolHandler {

    public MemoryRecallTool(SpectorMemory memory) {
        super(memory);
    }

    @Override public String name() { return "memory_recall"; }

    @Override
    public String description() {
        return "Recall relevant memories using fused cognitive scoring across all memory tiers "
                + "(Working, Episodic, Semantic, Procedural). Returns results with full provenance: "
                + "confidence, age, importance, valence, source, and decay factors. "
                + "Use 'profile' for preset scoring modes (e.g., DEBUGGING, EXPLORING, HYPERFOCUS). "
                + "Use synaptic_filter for contextual pre-filtering (e.g., 'debugging,database').";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchemaBuilder.object()
                .requiredString("query", "Natural language query for memory recall.")
                .optionalInt("top_k", "Number of results to return (1-50).", 5)
                .optionalString("profile",
                        "Cognitive scoring profile preset. Controls how memories are ranked. "
                        + "Options: BALANCED (default), EXPLORING (creative/associative), "
                        + "DEBUGGING (errors/failures), RECALLING (proven solutions), "
                        + "CRITICAL (high-stakes), HYPERFOCUS (narrow deep-dive), "
                        + "SYSTEMATIZER (encyclopedic detail), DIVERGENT (cross-domain), "
                        + "PARANOID_SENTINEL (threat detection), THE_EXECUTOR (strict task), "
                        + "HIGHLY_SENSITIVE (fine detail), DEFAULT_MODE_NETWORK (deep knowledge).", "")
                .optionalString("synaptic_filter",
                        "Comma-separated tags for Bloom filter pre-filtering.", "")
                .optionalString("min_importance",
                        "Minimum importance threshold (0.0-10.0).", "0.0")
                .optionalString("min_valence",
                        "Minimum valence filter (e.g., -128 for all, 10 for positive only).", "")
                .optionalString("max_valence",
                        "Maximum valence filter (e.g., -10 for failures only).", "")
                .optionalString("recall_mode",
                        "Controls whether recall mutates memory state. "
                        + "LEARN (default): full biological memory, recall strengthens memories. "
                        + "OBSERVE: pure read, no side effects, deterministic results.", "LEARN")
                .optionalString("text_search_mode",
                        "Controls text search retrieval path. "
                        + "HYBRID (default): parallel vector + BM25 keyword search with fused scoring. "
                        + "KEYWORD_ONLY: BM25 keyword search only (exact terms, error codes). "
                        + "VECTOR_ONLY: vector similarity only (no keyword boost).", "HYBRID")
                .optionalString("scoring_mode",
                        "Controls how retrieved candidates are ranked. "
                        + "COGNITIVE (default): full biological scoring — importance, decay, tag boost. "
                        + "SIMILARITY: pure vector cosine similarity — ideal for search/retrieval benchmarks.", "COGNITIVE")
                .optionalString("namespace",
                        "Memory namespace to query. Isolates agent/user memory spaces. "
                        + "Leave empty for default namespace.", "")
                .build();
    }

    @Override
    protected McpSchema.CallToolResult executeMemory(SpectorMemory memory,
                                                       SpectorEngine engine,
                                                       Map<String, Object> args) throws Exception {
        String query = requireString(args, "query");
        int topK = optionalInt(args, "top_k", 5);

        var builder = RecallOptions.builder().topK(topK);

        // Apply cognitive profile preset (if specified)
        String profileStr = optionalString(args, "profile", "");
        CognitiveProfile profile = RecallOptions.parseProfile(profileStr);
        if (profile != null) {
            builder.profile(profile);
        }

        String[] filterTags = optionalTags(args, "synaptic_filter");
        if (filterTags.length > 0) {
            builder.synapticFilter(filterTags);
        }

        float minImp = optionalFloat(args, "min_importance", 0.0f);
        if (minImp > 0) builder.minImportance(minImp);

        // Valence overrides (applied after profile, so explicit values win)
        byte minVal = optionalByte(args, "min_valence", Byte.MIN_VALUE);
        byte maxVal = optionalByte(args, "max_valence", Byte.MAX_VALUE);
        builder.minValence(minVal).maxValence(maxVal);

        // Parse recall mode (LEARN or OBSERVE)
        String modeStr = optionalString(args, "recall_mode", "LEARN");
        try {
            builder.recallMode(RecallMode.valueOf(modeStr.strip().toUpperCase()));
        } catch (IllegalArgumentException e) {
            // Invalid mode name — fall back to LEARN
        }

        // Parse text search mode (HYBRID, KEYWORD_ONLY, VECTOR_ONLY)
        String textModeStr = optionalString(args, "text_search_mode", "HYBRID");
        try {
            builder.textSearchMode(TextSearchMode.valueOf(textModeStr.strip().toUpperCase()));
        } catch (IllegalArgumentException e) {
            // Invalid mode name — fall back to HYBRID
        }

        // Parse scoring mode (COGNITIVE or SIMILARITY)
        String scoringStr = optionalString(args, "scoring_mode", "COGNITIVE");
        try {
            builder.scoringMode(ScoringMode.valueOf(scoringStr.strip().toUpperCase()));
        } catch (IllegalArgumentException e) {
            // Invalid mode name — fall back to COGNITIVE
        }

        RecallOptions options = builder.build();
        options.validate(); // logs warnings for conflicting combos

        long startNs = System.nanoTime();
        List<CognitiveResult> results = memory.recall(query, options);
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

        if (results.isEmpty()) {
            return textResult("No memories found for query: '" + query + "'");
        }

        var sb = new StringBuilder();
        ConfidenceBand confidence = ConfidenceBand.classify(results);
        sb.append("🧠 Recalled ").append(results.size()).append(" memories (")
                .append(elapsedMs).append("ms) — Confidence: ").append(confidence);

        // Log namespace if specified
        String namespace = optionalString(args, "namespace", "");
        if (!namespace.isEmpty()) {
            sb.append(" [ns: ").append(namespace).append("]");
        }
        sb.append("\n\n");

        for (int i = 0; i < results.size(); i++) {
            CognitiveResult r = results.get(i);
            sb.append("--- Memory ").append(i + 1).append(" ---\n");
            sb.append("ID: ").append(r.id()).append("\n");
            sb.append("Text: ").append(r.text()).append("\n");
            sb.append("Score: ").append(String.format("%.4f", r.score())).append("\n");

            // Score breakdown (glass box)
            if (r.hasBreakdown()) {
                ScoreBreakdown bd = r.breakdown();
                sb.append("Score Breakdown:\n");
                sb.append("  similarity:      ").append(String.format("%.4f", bd.similarity())).append("\n");
                sb.append("  imp×decay:       ").append(String.format("%.4f", bd.importanceDecay())).append("\n");
                sb.append("  tag_boost:       ").append(String.format("%.2f×", bd.tagBoostFactor())).append("\n");
                sb.append("  habituation:     ").append(String.format("%.2f×", bd.habituationPenalty())).append("\n");
                sb.append("  graph_boost:     ").append(String.format("%.2f×", bd.graphBoost())).append("\n");
                sb.append("  valence_align:   ").append(String.format("%.2f×", bd.valenceAlignment())).append("\n");
            }

            // Rich provenance
            sb.append("Provenance:\n");
            sb.append("  confidence: ").append(String.format("%.2f", r.ltpAdjustedDecay())).append("\n");
            sb.append("  age_days: ").append(String.format("%.1f", r.ageDays())).append("\n");
            sb.append("  importance: ").append(String.format("%.2f", r.importance())).append("\n");
            sb.append("  memory_type: ").append(r.memoryType()).append("\n");
            if (r.synapticTags() != null && r.synapticTags().length > 0) {
                sb.append("  synaptic_context: [").append(String.join(", ", r.synapticTags())).append("]\n");
            }
            sb.append("  recall_count: ").append(r.recallCount()).append("\n");
            sb.append("  valence: ").append(r.valence()).append("\n");
            sb.append("  source: ").append(r.source()).append("\n");
            sb.append("  decay_factor: ").append(String.format("%.3f", r.decayFactor())).append("\n");
            sb.append("  ltp_adjusted_decay: ").append(String.format("%.3f", r.ltpAdjustedDecay())).append("\n");
            sb.append("\n");
        }

        return textResult(sb.toString());
    }
}
