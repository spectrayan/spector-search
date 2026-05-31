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
package com.spectrayan.spector.mcp.prompts;

import java.util.List;
import java.util.Map;

import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.mcp.util.ResultFormatter;
import com.spectrayan.spector.query.SearchResponse;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Factory for Spector MCP prompt specifications.
 *
 * <p>Prompts are reusable message templates that MCP clients can invoke
 * to get pre-formatted context for AI model interactions. Currently
 * provides:</p>
 * <ul>
 *   <li>{@code rag_with_citations} — RAG prompt with retrieved context and citation instructions</li>
 * </ul>
 */
public final class SpectorPromptProvider {

    /** System instruction template for RAG prompts. */
    private static final String RAG_SYSTEM_INSTRUCTION =
            "You are a helpful assistant. Use the following context "
            + "retrieved from the Spector knowledge base to answer the user's "
            + "question. Always cite your sources using the document IDs provided. "
            + "If the context does not contain relevant information, say so.";

    /** Fallback message when no embedding provider is configured. */
    private static final String NO_EMBEDDING_PROVIDER_MSG =
            "[No embedding provider configured — cannot perform semantic search]";

    private SpectorPromptProvider() {} // static factory

    /**
     * Creates all prompt specifications for MCP server registration.
     *
     * @param engine the Spector engine instance
     * @return list of prompt specifications
     */
    public static List<McpServerFeatures.SyncPromptSpecification> create(SpectorEngine engine) {
        return List.of(
                createRagPrompt(engine)
        );
    }

    // ─────────────── RAG Prompt ───────────────

    private static McpServerFeatures.SyncPromptSpecification createRagPrompt(SpectorEngine engine) {
        var prompt = new McpSchema.Prompt(
                "rag_with_citations",
                "RAG prompt template that retrieves relevant context from the Spector index "
                        + "and formats results with source citations for grounded responses.",
                List.of(
                        new McpSchema.PromptArgument("query",
                                "The question or topic to search for", true),
                        new McpSchema.PromptArgument("top_k",
                                "Number of context chunks to retrieve (default: 5)", false),
                        new McpSchema.PromptArgument("token_limit",
                                "Maximum context tokens (default: 4096)", false)
                )
        );

        return new McpServerFeatures.SyncPromptSpecification(prompt, (exchange, request) -> {
            String query = extractStringArg(request.arguments(), "query", "");
            int topK = extractIntArg(request.arguments(), "top_k", 5);

            String contextText = retrieveContext(engine, query, topK);

            String message = RAG_SYSTEM_INSTRUCTION + "\n\n"
                    + "--- RETRIEVED CONTEXT ---\n" + contextText + "\n--- END CONTEXT ---"
                    + "\n\nQuestion: " + query;

            return new McpSchema.GetPromptResult(
                    "RAG query with citations from Spector",
                    List.of(new McpSchema.PromptMessage(
                            McpSchema.Role.USER,
                            new McpSchema.TextContent(message)
                    ))
            );
        });
    }

    // ─────────────── Internal Helpers ───────────────

    /**
     * Retrieves search context for the RAG prompt, handling errors gracefully.
     */
    private static String retrieveContext(SpectorEngine engine, String query, int topK) {
        try {
            if (engine.hasEmbeddingProvider()) {
                SearchResponse response = engine.search(query, topK);
                return ResultFormatter.formatSearchResults(response, engine);
            } else {
                return NO_EMBEDDING_PROVIDER_MSG;
            }
        } catch (Exception e) {
            return "[Search failed: " + e.getMessage() + "]";
        }
    }

    private static String extractStringArg(Map<String, Object> args, String key,
                                            String defaultValue) {
        if (args == null) return defaultValue;
        Object val = args.get(key);
        return val != null ? val.toString() : defaultValue;
    }

    private static int extractIntArg(Map<String, Object> args, String key, int defaultValue) {
        if (args == null) return defaultValue;
        Object val = args.get(key);
        if (val == null) return defaultValue;
        if (val instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(val.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
