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
package com.spectrayan.spector.mcp.tools.memory;

import java.util.Map;
import java.util.Set;
import com.spectrayan.spector.commons.security.SpectorScopes;

import io.modelcontextprotocol.spec.McpSchema;

import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.mcp.schema.ToolSchemaBuilder;
import com.spectrayan.spector.memory.SpectorMemory;

/**
 * MCP tool: {@code memory_export} — bulk export all memories as JSON.
 *
 * <p>Exports all live (non-tombstoned) memories as a JSON array.
 * Each memory includes its full cognitive profile: text, header fields,
 * tags, source, and physical location metadata.</p>
 *
 * <p>Use for backup, migration, audit, and debugging.
 * For large stores, consider using {@code memory_browse} with tag filters first.</p>
 *
 * <p>Maps to {@link SpectorMemory#exportJson()}.</p>
 */
public final class MemoryExportTool extends MemoryToolHandler {

    public MemoryExportTool(SpectorMemory memory) {
        super(memory);
    }

    @Override public String name() { return "memory_export"; }

    @Override public Set<String> requiredScopes() { return Set.of(SpectorScopes.MEMORY_READ); }

    @Override
    public String description() {
        return "Export all memories as a JSON array. "
                + "Each entry contains the full cognitive profile: text, memory type, source, "
                + "tags, importance, valence, arousal, recall counts, storage strength, "
                + "synaptic tags bloom filter, flags, and physical location. "
                + "Use for backup, migration to other systems, audit, or debugging. "
                + "For filtered exports, use memory_browse with tag filters first.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        // No required arguments — exports everything
        return ToolSchemaBuilder.object()
                .optionalString("format",
                        "Export format: 'json' (default). Future: 'csv', 'markdown'.", "json")
                .build();
    }

    @Override
    protected McpSchema.CallToolResult executeMemory(SpectorMemory memory,
                                                       SpectorEngine engine,
                                                       Map<String, Object> args) throws Exception {
        int totalCount = memory.totalMemories();
        if (totalCount == 0) {
            return textResult("📭 No memories to export. The memory store is empty.");
        }

        String json = memory.exportJson();

        StringBuilder sb = new StringBuilder();
        sb.append("📦 Exported ").append(totalCount).append(" memories\n\n");
        sb.append(json);

        return textResult(sb.toString());
    }
}
