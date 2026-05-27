package com.spectrayan.spector.mcp.tools;

import java.util.Map;

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
public final class IngestDocumentTool extends McpToolHandler {

    @Override
    public String name() {
        return "ingest_document";
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
