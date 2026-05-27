package com.spectrayan.spector.mcp.tools;

import java.util.Map;

import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.mcp.schema.ToolSchemaBuilder;
import com.spectrayan.spector.mcp.util.ResultFormatter;
import com.spectrayan.spector.query.SearchResponse;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Semantic similarity search via SIMD-accelerated vector index.
 *
 * <p>Queries are automatically embedded into vectors using the configured
 * {@link com.spectrayan.spector.embed.EmbeddingProvider} and matched
 * against the HNSW/IVF-VASQ index for sub-millisecond latency.</p>
 */
public final class SemanticSearchTool extends McpToolHandler {

    @Override
    public String name() {
        return "semantic_search";
    }

    @Override
    public String description() {
        return "Perform semantic similarity search over the Spector vector index. "
                + "Returns the most relevant documents based on meaning, powered by "
                + "SIMD-accelerated HNSW/IVF-VASQ indexes for sub-millisecond latency. "
                + "Requires an embedding provider to be configured.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchemaBuilder.object()
                .requiredString("query",
                        "Natural language search query. Text is automatically "
                        + "embedded into a vector for similarity search.")
                .optionalInt("top_k",
                        "Number of results to return (1-100).", 5)
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute(SpectorEngine engine, Map<String, Object> args) {
        requireEmbeddingProvider(engine);
        String query = requireString(args, "query");
        int topK = optionalInt(args, "top_k", 5);

        long startNs = System.nanoTime();
        SearchResponse response = engine.search(query, topK);
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

        String text = ResultFormatter.formatSearchResults(response, engine);
        return textResult(ResultFormatter.withTimingFooter(
                text, "Spector SIMD search", elapsedMs));
    }
}
