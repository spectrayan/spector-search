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

import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.memory.model.MemoryType;
import com.spectrayan.spector.memory.SpectorMemory;
import com.spectrayan.spector.memory.neurodivergent.LateralEvaluator;
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

    @Override public Set<String> requiredScopes() { return Set.of(SpectorScopes.MEMORY_READ); }

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
        sb.append("  Pending Reminders:   ").append(memory.prospective().pendingCount()).append("\n\n");

        // Lateral evaluator metrics
        LateralEvaluator lateral = memory.lateralEvaluator();
        LateralEvaluator.LateralMetrics metrics = lateral.metrics();
        sb.append("Lateral Retrieval:\n");
        sb.append("  Enabled:    ").append(lateral.isLateralEnabled()).append("\n");
        sb.append("  Threshold:  ").append(String.format("%.2f", lateral.currentDistanceThreshold())).append("\n");
        sb.append("  Samples:    ").append(metrics.sampleSize()).append("\n");
        sb.append("  LUR (util): ").append(String.format("%.2f", metrics.utilityRate())).append("\n");
        sb.append("  LSR (supp): ").append(String.format("%.2f", metrics.suppressionRate())).append("\n");
        sb.append("  LHI (hall): ").append(String.format("%.2f", metrics.hallucinationIndex())).append("\n");

        return textResult(sb.toString());
    }
}
