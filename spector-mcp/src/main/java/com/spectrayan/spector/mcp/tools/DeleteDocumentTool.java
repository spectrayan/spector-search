package com.spectrayan.spector.mcp.tools;

import java.util.Map;

import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.mcp.schema.ToolSchemaBuilder;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Document deletion tool.
 *
 * <p>Removes a document from the keyword index and document store by ID.
 * Vector index entries become orphaned and are excluded from future
 * search results.</p>
 */
public final class DeleteDocumentTool extends McpToolHandler {

    @Override
    public String name() {
        return "delete_document";
    }

    @Override
    public String description() {
        return "Deletes a document from the Spector index by ID. Removes it from "
                + "keyword index and document store. Vector index entries become orphaned "
                + "and are excluded from future results.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchemaBuilder.object()
                .requiredString("id",
                        "The document identifier to delete.")
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute(SpectorEngine engine, Map<String, Object> args) {
        String id = requireString(args, "id");

        boolean deleted = engine.delete(id);
        if (deleted) {
            return textResult(String.format(
                    "Document '%s' deleted. Remaining documents: %d",
                    id, engine.documentCount()));
        } else {
            return textResult(String.format(
                    "Document '%s' not found.", id));
        }
    }
}
