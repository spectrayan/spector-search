package com.spectrayan.spector.mcp.tools;

import java.util.Map;

import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.mcp.schema.ToolSchemaBuilder;
import com.spectrayan.spector.mcp.util.ResultFormatter;
import com.spectrayan.spector.query.SearchResponse;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Retrieval-Augmented Generation tool.
 *
 * <p>Retrieves relevant context from the Spector index and assembles
 * it with source attributions within a token budget. Designed for
 * grounded responses — each retrieved chunk includes its document ID
 * and relevance score for citation.</p>
 */
public final class RagQueryTool extends McpToolHandler {

    @Override
    public String name() {
        return "rag_query";
    }

    @Override
    public String description() {
        return "Retrieval-Augmented Generation: retrieves relevant context from the Spector "
                + "index and assembles it within a token budget. Returns context text with "
                + "source attributions for grounded responses.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchemaBuilder.object()
                .requiredString("query",
                        "The question or topic to retrieve context for.")
                .optionalInt("top_k",
                        "Number of candidate chunks to consider (1-50).", 10)
                .optionalInt("token_limit",
                        "Maximum tokens in returned context (256-8192).", 4096)
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute(SpectorEngine engine, Map<String, Object> args) {
        requireEmbeddingProvider(engine);
        String query = requireString(args, "query");
        int topK = optionalInt(args, "top_k", 10);

        long startNs = System.nanoTime();
        SearchResponse response = engine.search(query, topK);
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

        String context = ResultFormatter.formatRagContext(response, engine);
        int sourceCount = response.results() != null ? response.results().length : 0;

        String footer = String.format(
                "\n[%d sources retrieved in %dms via Spector SIMD search]",
                sourceCount, elapsedMs);

        return textResult(context + footer);
    }
}
