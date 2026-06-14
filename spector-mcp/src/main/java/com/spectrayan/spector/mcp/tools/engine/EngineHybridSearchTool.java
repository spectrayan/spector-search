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
 * Combined keyword (BM25) + semantic (vector) search with mode selection.
 *
 * <p>Supports three modes:</p>
 * <ul>
 *   <li>{@code hybrid} — reciprocal rank fusion of BM25 + vector results (default)</li>
 *   <li>{@code keyword} — BM25 keyword matching only</li>
 *   <li>{@code vector} — semantic vector search only</li>
 * </ul>
 *
 * <p>Falls back to keyword-only if no embedding provider is configured
 * and {@code hybrid} mode is requested.</p>
 */
public final class EngineHybridSearchTool extends McpToolHandler {

    @Override
    public String name() {
        return "engine_hybrid_search";
    }

    @Override
    public Set<String> requiredScopes() {
        return Set.of(SpectorScopes.SEARCH_READ);
    }

    @Override
    public String description() {
        return "Combined keyword (BM25) + semantic (vector) search with reciprocal rank fusion. "
                + "Best for queries mixing specific terms with conceptual intent. "
                + "Falls back to keyword-only if no embedding provider is configured.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchemaBuilder.object()
                .requiredString("query",
                        "Search query for both keyword matching (BM25) and semantic similarity.")
                .optionalInt("top_k",
                        "Number of results to return (1-100).", 5)
                .optionalEnum("mode",
                        "Search mode. 'hybrid' combines keyword and vector.",
                        "hybrid", "hybrid", "keyword", "vector")
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute(SpectorEngine engine, Map<String, Object> args) {
        String query = requireString(args, "query");
        int topK = optionalInt(args, "top_k", 5);
        String mode = optionalString(args, "mode", "hybrid");

        long startNs = System.nanoTime();
        SearchResponse response = dispatchSearch(engine, query, topK, mode);
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

        String text = ResultFormatter.formatSearchResults(response, engine);
        return textResult(ResultFormatter.withTimingFooter(
                text, "Hybrid search (" + mode + " mode)", elapsedMs));
    }

    private static SearchResponse dispatchSearch(SpectorEngine engine, String query,
                                                  int topK, String mode) {
        return switch (mode.toLowerCase()) {
            case "keyword" -> engine.keywordSearch(query, topK);
            case "vector" -> {
                requireEmbeddingProvider(engine);
                yield engine.search(query, topK);
            }
            default -> {
                // hybrid: use vector if available, fallback to keyword
                if (engine.hasEmbeddingProvider()) {
                    yield engine.search(query, topK);
                } else {
                    yield engine.keywordSearch(query, topK);
                }
            }
        };
    }
}
