package com.spectrayan.spector.mcp.tools;

import java.util.Map;

import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.memory.MemoryType;
import com.spectrayan.spector.memory.SpectorMemory;
import com.spectrayan.spector.mcp.schema.ToolSchemaBuilder;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool: {@code memory_status} — memory stats per tier.
 */
public final class MemoryStatusTool extends MemoryToolHandler {

    public MemoryStatusTool(SpectorMemory memory) {
        super(memory);
    }

    @Override public String name() { return "memory_status"; }

    @Override
    public String description() {
        return "View memory system statistics: total memories, per-tier counts, "
                + "WAL event count, suppression set size, and pending reminders.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchemaBuilder.object().build();
    }

    @Override
    protected McpSchema.CallToolResult executeMemory(SpectorMemory memory,
                                                       SpectorEngine engine,
                                                       Map<String, Object> args) {
        var sb = new StringBuilder();
        sb.append("🧠 Spector Memory Status\n");
        sb.append("========================\n\n");

        sb.append("Total Memories: ").append(memory.totalMemories()).append("\n\n");

        sb.append("Per-Tier Breakdown:\n");
        sb.append("  Working (Prefrontal Cortex):  ").append(memory.memoryCount(MemoryType.WORKING)).append("\n");
        sb.append("  Episodic (Hippocampus):       ").append(memory.memoryCount(MemoryType.EPISODIC)).append("\n");
        sb.append("  Semantic (Neocortex):         ").append(memory.memoryCount(MemoryType.SEMANTIC)).append("\n");
        sb.append("  Procedural (Basal Ganglia):   ").append(memory.memoryCount(MemoryType.PROCEDURAL)).append("\n\n");

        sb.append("Subsystem Status:\n");
        sb.append("  WAL Events:          ").append(memory.wal().size()).append("\n");
        sb.append("  WAL High-Water Mark: ").append(memory.wal().highWaterMark()).append("\n");
        sb.append("  Suppressed Memories: ").append(memory.suppression().size()).append("\n");
        sb.append("  Pending Reminders:   ").append(memory.prospective().pendingCount()).append("\n");

        return textResult(sb.toString());
    }
}
