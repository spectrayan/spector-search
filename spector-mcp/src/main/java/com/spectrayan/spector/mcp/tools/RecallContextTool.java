package com.spectrayan.spector.mcp.tools;

import java.util.Map;
import java.util.List;

import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.memory.CognitiveResult;
import com.spectrayan.spector.memory.RecallOptions;
import com.spectrayan.spector.memory.SpectorMemory;
import com.spectrayan.spector.mcp.schema.ToolSchemaBuilder;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool: {@code recall_context} — cross-tier fused recall with rich provenance.
 *
 * <p>Performs the full 6-phase SIMD scoring pipeline across all memory tiers,
 * returning results with full provenance metadata for LLM grounding.</p>
 */
public final class RecallContextTool extends MemoryToolHandler {

    public RecallContextTool(SpectorMemory memory) {
        super(memory);
    }

    @Override public String name() { return "recall_context"; }

    @Override
    public String description() {
        return "Recall relevant memories using fused cognitive scoring across all memory tiers "
                + "(Working, Episodic, Semantic, Procedural). Returns results with full provenance: "
                + "confidence, age, importance, valence, source, and decay factors. "
                + "Use synaptic_filter for contextual pre-filtering (e.g., 'debugging,database').";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchemaBuilder.object()
                .requiredString("query", "Natural language query for memory recall.")
                .optionalInt("top_k", "Number of results to return (1-50).", 5)
                .optionalString("synaptic_filter",
                        "Comma-separated tags for Bloom filter pre-filtering.", "")
                .optionalString("min_importance",
                        "Minimum importance threshold (0.0-10.0).", "0.0")
                .optionalString("min_valence",
                        "Minimum valence filter (e.g., -128 for all, 10 for positive only).", "")
                .optionalString("max_valence",
                        "Maximum valence filter (e.g., -10 for failures only).", "")
                .build();
    }

    @Override
    protected McpSchema.CallToolResult executeMemory(SpectorMemory memory,
                                                       SpectorEngine engine,
                                                       Map<String, Object> args) throws Exception {
        String query = requireString(args, "query");
        int topK = optionalInt(args, "top_k", 5);

        var builder = RecallOptions.builder().topK(topK);

        String[] filterTags = optionalTags(args, "synaptic_filter");
        if (filterTags.length > 0) {
            builder.synapticFilter(filterTags);
        }

        float minImp = optionalFloat(args, "min_importance", 0.0f);
        if (minImp > 0) builder.minImportance(minImp);

        byte minVal = optionalByte(args, "min_valence", Byte.MIN_VALUE);
        byte maxVal = optionalByte(args, "max_valence", Byte.MAX_VALUE);
        builder.minValence(minVal).maxValence(maxVal);

        long startNs = System.nanoTime();
        List<CognitiveResult> results = memory.recall(query, builder.build());
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

        if (results.isEmpty()) {
            return textResult("No memories found for query: '" + query + "'");
        }

        var sb = new StringBuilder();
        sb.append("🧠 Recalled ").append(results.size()).append(" memories (").append(elapsedMs).append("ms):\n\n");

        for (int i = 0; i < results.size(); i++) {
            CognitiveResult r = results.get(i);
            sb.append("--- Memory ").append(i + 1).append(" ---\n");
            sb.append("ID: ").append(r.id()).append("\n");
            sb.append("Text: ").append(r.text()).append("\n");
            sb.append("Score: ").append(String.format("%.4f", r.score())).append("\n");

            // Rich provenance (from analysis doc §Explainability)
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
