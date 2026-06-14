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
 * MCP tool: Suppress or unsuppress a memory.
 *
 * <p>Suppressed memories are excluded from future recall results
 * until explicitly unsuppressed. Useful for hiding irrelevant,
 * outdated, or unwanted memories without permanently deleting them.</p>
 */
public final class MemorySuppressTool extends MemoryToolHandler {

    public MemorySuppressTool(SpectorMemory memory) {
        super(memory);
    }

    @Override public String name() { return "memory_suppress"; }

    @Override public Set<String> requiredScopes() { return Set.of(SpectorScopes.MEMORY_WRITE); }

    @Override
    public String description() {
        return "Suppress or unsuppress a memory. Suppressed memories are hidden from "
                + "recall without being deleted. Use SUPPRESS to hide irrelevant or "
                + "outdated memories, UNSUPPRESS to restore them.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchemaBuilder.object()
                .requiredString("memory_id", "The ID of the memory to suppress or unsuppress.")
                .requiredString("action", "SUPPRESS or UNSUPPRESS.")
                .optionalString("reason", "Why this memory is being suppressed (for audit trail).", "")
                .build();
    }

    @Override
    protected McpSchema.CallToolResult executeMemory(SpectorMemory memory,
                                                       SpectorEngine engine,
                                                       Map<String, Object> args) throws Exception {
        String memoryId = requireString(args, "memory_id");
        String action = requireString(args, "action").toUpperCase();
        String reason = optionalString(args, "reason", "");

        return switch (action) {
            case "SUPPRESS" -> {
                if (reason.isBlank()) {
                    memory.suppress(memoryId);
                } else {
                    memory.suppress(memoryId, reason);
                }
                yield textResult("🔇 Suppressed memory '" + memoryId + "'"
                        + (reason.isBlank() ? "." : " (reason: " + reason + ")."));
            }
            case "UNSUPPRESS" -> {
                memory.unsuppress(memoryId);
                yield textResult("🔊 Unsuppressed memory '" + memoryId + "'. It will appear in recall again.");
            }
            default -> textResult("❌ Unknown action '" + action + "'. Use SUPPRESS or UNSUPPRESS.");
        };
    }
}
