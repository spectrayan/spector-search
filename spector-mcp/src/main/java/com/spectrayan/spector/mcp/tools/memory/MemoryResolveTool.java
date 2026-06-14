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
 * MCP tool: Mark a memory as resolved or unresolved (Zeigarnik Effect).
 *
 * <p>The Zeigarnik Effect causes unresolved/incomplete tasks to persist
 * at the top of recall — they resist time-decay. Marking a memory as
 * resolved allows it to decay normally and gradually fade.</p>
 *
 * <p>Use this to manage task-related memories: mark them unresolved when
 * work begins (keeps them top-of-mind) and resolved when complete.</p>
 */
public final class MemoryResolveTool extends MemoryToolHandler {

    public MemoryResolveTool(SpectorMemory memory) {
        super(memory);
    }

    @Override public String name() { return "memory_resolve"; }

    @Override public Set<String> requiredScopes() { return Set.of(SpectorScopes.MEMORY_WRITE); }

    @Override
    public String description() {
        return "Mark a memory as resolved or unresolved (Zeigarnik Effect). "
                + "Unresolved memories resist time-decay and stay top-of-mind during recall. "
                + "Resolved memories return to normal decay. "
                + "Use for task tracking: mark unresolved when work begins, resolved when done.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchemaBuilder.object()
                .requiredString("memory_id", "The ID of the memory to resolve or unresolve.")
                .requiredBoolean("resolved",
                        "true = mark resolved (normal decay), false = mark unresolved (stays top-of-mind).")
                .build();
    }

    @Override
    protected McpSchema.CallToolResult executeMemory(SpectorMemory memory,
                                                       SpectorEngine engine,
                                                       Map<String, Object> args) throws Exception {
        String memoryId = requireString(args, "memory_id");
        boolean resolved = requireBoolean(args, "resolved");

        if (resolved) {
            memory.markResolved(memoryId);
            return textResult("✅ Memory '" + memoryId + "' marked as resolved. "
                    + "It will now decay normally.");
        } else {
            memory.markUnresolved(memoryId);
            return textResult("⏳ Memory '" + memoryId + "' marked as unresolved. "
                    + "It will resist decay and stay top-of-mind (Zeigarnik Effect).");
        }
    }

    private boolean requireBoolean(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) throw new IllegalArgumentException("Missing required parameter: " + key);
        if (val instanceof Boolean b) return b;
        return Boolean.parseBoolean(val.toString());
    }
}
