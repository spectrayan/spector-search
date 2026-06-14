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

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Document ingestion tool with auto-embedding and optional chunking.
 *
 * <p>Ingests a document into the Spector index. The embedding provider
 * automatically generates vectors from the document content. For large
 * documents, enable {@code chunked} mode for automatic splitting.</p>
 */
public final class EngineIngestTool extends McpToolHandler {

    @Override
    public String name() {
        return "engine_ingest";
    }

    @Override
    public Set<String> requiredScopes() {
        return Set.of(SpectorScopes.SEARCH_WRITE);
    }

    @Override
    public String description() {
        return "Ingest a document into the Spector index with automatic embedding generation. "
                + "Supports chunked ingestion for large documents. "
                + "Requires an embedding provider to be configured.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchemaBuilder.object()
                .requiredString("id",
                        "Unique document identifier.")
                .requiredString("content",
                        "Document text content to ingest.")
                .optionalString("title",
                        "Optional document title.", null)
                .optionalBoolean("chunked",
                        "Enable automatic chunking for large documents.", false)
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute(SpectorEngine engine, Map<String, Object> args) {
        requireEmbeddingProvider(engine);
        String id = requireString(args, "id");
        String content = requireString(args, "content");
        String title = optionalString(args, "title", null);
        boolean chunked = optionalBoolean(args, "chunked", false);

        long startNs = System.nanoTime();

        if (chunked) {
            int chunks = engine.ingestChunkedAuto(id, content);
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
            return textResult(String.format(
                    "Document '%s' ingested as %d chunks in %dms.",
                    id, chunks, elapsedMs));
        }

        if (title != null && !title.isBlank()) {
            engine.ingest(id, title, content);
        } else {
            engine.ingest(id, content);
        }

        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
        return textResult(String.format(
                "Document '%s' ingested successfully in %dms. Total documents: %d",
                id, elapsedMs, engine.documentCount()));
    }
}
