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
 * Retrieval-Augmented Generation tool.
 *
 * <p>Retrieves relevant context from the Spector index and assembles
 * it with source attributions within a token budget. Designed for
 * grounded responses — each retrieved chunk includes its document ID
 * and relevance score for citation.</p>
 */
public final class EngineRagTool extends McpToolHandler {

    @Override
    public String name() {
        return "engine_rag";
    }

    @Override
    public Set<String> requiredScopes() {
        return Set.of(SpectorScopes.SEARCH_READ);
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
