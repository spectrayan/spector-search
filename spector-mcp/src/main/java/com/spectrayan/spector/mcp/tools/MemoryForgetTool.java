package com.spectrayan.spector.mcp.tools;

import java.util.Map;

import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.memory.SpectorMemory;
import com.spectrayan.spector.mcp.schema.ToolSchemaBuilder;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool: {@code memory_forget} — explicitly forget a memory by ID.
 */
public final class MemoryForgetTool extends MemoryToolHandler {

    public MemoryForgetTool(SpectorMemory memory) {
        super(memory);
    }

    @Override public String name() { return "memory_forget"; }

    @Override
    public String description() {
        return "Explicitly forget a memory by ID. The memory is tombstoned (logical deletion) "
                + "and will be cleaned up during the next Deep Sleep consolidation cycle.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchemaBuilder.object()
                .requiredString("memory_id", "The ID of the memory to forget.")
                .build();
    }

    @Override
    protected McpSchema.CallToolResult executeMemory(SpectorMemory memory,
                                                       SpectorEngine engine,
                                                       Map<String, Object> args) throws Exception {
        String memoryId = requireString(args, "memory_id");
        memory.forget(memoryId);
        return textResult("🗑️ Memory '" + memoryId + "' has been forgotten (tombstoned).");
    }
}
