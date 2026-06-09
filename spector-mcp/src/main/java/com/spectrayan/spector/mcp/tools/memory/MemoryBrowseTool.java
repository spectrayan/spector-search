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

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;

import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.mcp.schema.ToolSchemaBuilder;
import com.spectrayan.spector.memory.SpectorMemory;
import com.spectrayan.spector.memory.model.CognitiveRecord;

/**
 * MCP tool: {@code memory_browse} — browse memories by tag without vector search.
 *
 * <p>Performs a metadata-only scan over the memory index, returning all memories
 * that match the given tags (AND semantics). No embedding or similarity scoring
 * is involved — this is pure tag-based filtering.</p>
 *
 * <p>Use cases:</p>
 * <ul>
 *   <li>"Show me all memories tagged 'payments'" — audit and review</li>
 *   <li>"List everything tagged 'architecture' and 'decisions'" — knowledge graph</li>
 *   <li>"What did the agent learn about 'user-preferences'?" — debugging</li>
 * </ul>
 *
 * <p>Maps to {@link SpectorMemory#browse(String...)}.</p>
 */
public final class MemoryBrowseTool extends MemoryToolHandler {

    public MemoryBrowseTool(SpectorMemory memory) {
        super(memory);
    }

    @Override public String name() { return "memory_browse"; }

    @Override
    public String description() {
        return "Browse memories by tag — no vector search needed. "
                + "Returns all memories matching the given tags (AND: must contain ALL tags). "
                + "Useful for auditing ('show me everything tagged payments'), "
                + "knowledge review ('list architecture decisions'), "
                + "and bulk operations. Use comma-separated tags for multiple filters.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchemaBuilder.object()
                .requiredString("tags", "Comma-separated tags to filter by (AND semantics). "
                        + "Example: 'payments,architecture' returns memories with BOTH tags.")
                .build();
    }

    @Override
    protected McpSchema.CallToolResult executeMemory(SpectorMemory memory,
                                                       SpectorEngine engine,
                                                       Map<String, Object> args) throws Exception {
        String[] filterTags = optionalTags(args, "tags");
        if (filterTags.length == 0) {
            return errorResult("At least one tag is required for browsing.");
        }

        List<CognitiveRecord> results = memory.browse(filterTags);

        if (results.isEmpty()) {
            return textResult("📭 No memories found matching tags: ["
                    + String.join(", ", filterTags) + "]");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🏷️ Found ").append(results.size())
          .append(" memories matching tags: [").append(String.join(", ", filterTags)).append("]\n\n");

        for (int i = 0; i < results.size(); i++) {
            CognitiveRecord r = results.get(i);
            sb.append("─── ").append(i + 1).append(". ").append(r.id()).append(" ───\n");
            sb.append("📝 ").append(truncate(r.text(), 200)).append("\n");
            sb.append("🏷️ Type: ").append(r.memoryType())
              .append(" | Source: ").append(r.source()).append("\n");
            sb.append(String.format("📊 Importance: %.2f | Valence: %d | Recalls: %d\n",
                    r.importance(), r.valence(), r.totalRecallCount()));
            if (r.tags() != null && r.tags().length > 0) {
                sb.append("🔖 Tags: ").append(String.join(", ", r.tags())).append("\n");
            }
            sb.append("📅 Created: ").append(r.createdAt())
              .append(String.format(" (%.1f days ago)\n\n", r.ageDays()));
        }

        return textResult(sb.toString());
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "…";
    }
}
