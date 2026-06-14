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
 * Document deletion tool.
 *
 * <p>Removes a document from the keyword index and document store by ID.
 * Vector index entries become orphaned and are excluded from future
 * search results.</p>
 */
public final class EngineDeleteTool extends McpToolHandler {

    @Override
    public String name() {
        return "engine_delete";
    }

    @Override
    public Set<String> requiredScopes() {
        return Set.of(SpectorScopes.SEARCH_WRITE);
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
