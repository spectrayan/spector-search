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
import java.util.Set;
import com.spectrayan.spector.commons.security.SpectorScopes;

import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.memory.model.RecallOptions;
import com.spectrayan.spector.memory.model.ScoreBreakdown;
import com.spectrayan.spector.memory.SpectorMemory;
import com.spectrayan.spector.memory.model.WhyNotExplanation;
import com.spectrayan.spector.mcp.schema.ToolSchemaBuilder;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool: {@code memory_why_not} — explain why a specific memory was NOT recalled.
 *
 * <p>When a developer or LLM expects a specific memory to be returned by a recall
 * query but it isn't, this tool diagnoses the exact reason: not found, tombstoned,
 * suppressed, outranked, or pre-filtered.</p>
 *
 * <p>Always runs in OBSERVE mode — diagnosing a miss never mutates memory state.</p>
 *
 * @see WhyNotExplanation
 */
public final class MemoryWhyNotTool extends MemoryToolHandler {

    public MemoryWhyNotTool(SpectorMemory memory) {
        super(memory);
    }

    @Override public String name() { return "memory_why_not"; }

    @Override public Set<String> requiredScopes() { return Set.of(SpectorScopes.MEMORY_READ); }

    @Override
    public String description() {
        return "Explain why a specific memory was NOT returned for a query. "
                + "Diagnoses: not found, deleted, suppressed, outranked (below topK), "
                + "or eliminated by pre-filters (tags/valence/importance). "
                + "Always runs in OBSERVE mode (no state mutations).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchemaBuilder.object()
                .requiredString("memory_id", "The ID of the memory to investigate.")
                .requiredString("query", "The query it was expected to match.")
                .optionalInt("top_k", "The topK used in the original recall (default 5).", 5)
                .build();
    }

    @Override
    protected McpSchema.CallToolResult executeMemory(SpectorMemory memory,
                                                       SpectorEngine engine,
                                                       Map<String, Object> args) throws Exception {
        String memoryId = requireString(args, "memory_id");
        String query = requireString(args, "query");
        int topK = optionalInt(args, "top_k", 5);

        RecallOptions options = RecallOptions.builder().topK(topK).build();
        WhyNotExplanation explanation = memory.whyNot(memoryId, query, options);

        var sb = new StringBuilder();
        sb.append("🔍 Why-Not Analysis for memory '").append(memoryId).append("'\n");
        sb.append("Query: '").append(query).append("'\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n");

        sb.append("Reason: ").append(explanation.reason()).append("\n");
        sb.append("Exists: ").append(explanation.exists() ? "✅ Yes" : "❌ No").append("\n");
        sb.append("Suppressed: ").append(explanation.suppressed() ? "⛔ Yes" : "✅ No").append("\n");

        if (explanation.scoreGap() > 0f) {
            sb.append("Score Gap: ").append(String.format("%.4f", explanation.scoreGap()))
                    .append(" (topK cutoff score)\n");
        }

        // Show breakdown if available (OUTRANKED case)
        if (explanation.breakdown() != null) {
            ScoreBreakdown bd = explanation.breakdown();
            sb.append("\nScore Breakdown:\n");
            sb.append("  similarity:      ").append(String.format("%.4f", bd.similarity())).append("\n");
            sb.append("  imp×decay:       ").append(String.format("%.4f", bd.importanceDecay())).append("\n");
            sb.append("  tag_boost:       ").append(String.format("%.2f×", bd.tagBoostFactor())).append("\n");
            sb.append("  habituation:     ").append(String.format("%.2f×", bd.habituationPenalty())).append("\n");
            sb.append("  graph_boost:     ").append(String.format("%.2f×", bd.graphBoost())).append("\n");
            sb.append("  valence_align:   ").append(String.format("%.2f×", bd.valenceAlignment())).append("\n");
            sb.append("  → final:         ").append(String.format("%.4f", bd.finalScore())).append("\n");
            sb.append("  weakest factor:  ").append(bd.weakestMultiplier()).append("\n");
        }

        sb.append("\n").append(explanation.summary());

        return textResult(sb.toString());
    }
}
