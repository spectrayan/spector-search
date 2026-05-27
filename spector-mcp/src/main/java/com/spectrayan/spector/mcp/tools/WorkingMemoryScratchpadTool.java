package com.spectrayan.spector.mcp.tools;

import java.util.Map;

import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.memory.SpectorMemory;
import com.spectrayan.spector.mcp.schema.ToolSchemaBuilder;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool: {@code working_memory_scratchpad} — stores in-progress reasoning.
 *
 * <p>Working memory is volatile (RAM-only). When capacity is reached,
 * the oldest items are evicted via FIFO.</p>
 */
public final class WorkingMemoryScratchpadTool extends MemoryToolHandler {

    public WorkingMemoryScratchpadTool(SpectorMemory memory) {
        super(memory);
    }

    @Override public String name() { return "working_memory_scratchpad"; }

    @Override
    public String description() {
        return "Store a short-lived scratchpad note in working memory. "
                + "Use this for in-progress reasoning, temporary hypotheses, "
                + "or chain-of-thought steps. Working memory is volatile and "
                + "auto-evicts old entries when capacity is reached.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchemaBuilder.object()
                .requiredString("text", "The scratchpad note to store.")
                .build();
    }

    @Override
    protected McpSchema.CallToolResult executeMemory(SpectorMemory memory,
                                                       SpectorEngine engine,
                                                       Map<String, Object> args) throws Exception {
        String text = requireString(args, "text");
        memory.scratchpad(text).join();
        return textResult("📝 Stored in working memory scratchpad.");
    }
}
