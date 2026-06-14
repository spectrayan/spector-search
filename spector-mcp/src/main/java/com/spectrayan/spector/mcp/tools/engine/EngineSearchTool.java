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
package com.spectrayan.spector.mcp.tools.engine;

import com.spectrayan.spector.mcp.tools.McpToolHandler;

import java.util.Map;
import java.util.Set;
import com.spectrayan.spector.commons.security.SpectorScopes;

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
 * against the HNSW/IVF-SVASQ index for sub-millisecond latency.</p>
 */
public final class EngineSearchTool extends McpToolHandler {

    @Override
    public String name() {
        return "engine_search";
    }

    @Override
    public Set<String> requiredScopes() {
        return Set.of(SpectorScopes.SEARCH_READ);
    }

    @Override
    public String description() {
        return "Perform semantic similarity search over the Spector vector index. "
                + "Returns the most relevant documents based on meaning, powered by "
                + "SIMD-accelerated HNSW/IVF-SVASQ indexes for sub-millisecond latency. "
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
