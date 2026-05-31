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
package com.spectrayan.spector.mcp.tools;

import java.util.Map;

import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.memory.MemoryType;
import com.spectrayan.spector.memory.SpectorMemory;
import com.spectrayan.spector.memory.cortex.MemorySource;
import com.spectrayan.spector.mcp.schema.ToolSchemaBuilder;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool: {@code core_memory_append} — stores a permanent semantic fact.
 *
 * <p>Maps to {@link SpectorMemory#remember} with {@link MemoryType#SEMANTIC}.</p>
 */
public final class CoreMemoryAppendTool extends MemoryToolHandler {

    public CoreMemoryAppendTool(SpectorMemory memory) {
        super(memory);
    }

    @Override public String name() { return "core_memory_append"; }

    @Override
    public String description() {
        return "Store a permanent fact in the agent's semantic memory. "
                + "Use this to save key user preferences, important decisions, "
                + "and factual knowledge that should persist across sessions. "
                + "Tags help with contextual recall (e.g., 'preferences', 'architecture').";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchemaBuilder.object()
                .requiredString("id", "Unique identifier for this memory (e.g., 'user-pref-dark-mode').")
                .requiredString("text", "The fact or preference to remember.")
                .optionalString("tags", "Comma-separated contextual tags for Bloom filter encoding.", "")
                .optionalString("source",
                        "Memory source: USER_STATED, OBSERVED, INFERRED, PROCEDURAL.", "OBSERVED")
                .build();
    }

    @Override
    protected McpSchema.CallToolResult executeMemory(SpectorMemory memory,
                                                       SpectorEngine engine,
                                                       Map<String, Object> args) throws Exception {
        String id = requireString(args, "id");
        String text = requireString(args, "text");
        String[] tags = optionalTags(args, "tags");
        String sourceName = optionalString(args, "source", "OBSERVED");

        MemorySource source;
        try {
            source = MemorySource.valueOf(sourceName.toUpperCase());
        } catch (IllegalArgumentException e) {
            source = MemorySource.OBSERVED;
        }

        memory.remember(id, text, MemoryType.SEMANTIC, source, tags).join();

        return textResult("✅ Stored semantic memory '" + id + "' with " + tags.length
                + " tags (source=" + source + ").");
    }
}
