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

import io.modelcontextprotocol.spec.McpSchema;

import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.mcp.schema.ToolSchemaBuilder;
import com.spectrayan.spector.memory.SpectorMemory;
import com.spectrayan.spector.memory.model.CognitiveRecord;

/**
 * MCP tool: {@code memory_inspect} — full cognitive X-ray of a single memory.
 *
 * <p>Returns the complete cognitive snapshot: text, header fields (importance,
 * valence, arousal, recall counts, synaptic tags, flags), and the quantized
 * vector. This is the "microscope" for debugging and auditing agent memories.</p>
 *
 * <p>Maps to {@link SpectorMemory#inspect(String)}.</p>
 */
public final class MemoryInspectTool extends MemoryToolHandler {

    public MemoryInspectTool(SpectorMemory memory) {
        super(memory);
    }

    @Override public String name() { return "memory_inspect"; }

    @Override
    public String description() {
        return "Inspect a single memory by ID — returns the full cognitive X-ray: "
                + "text content, cognitive header (importance, valence, arousal, recall counts, "
                + "synaptic tags bloom filter, storage strength, flags), vector dimensions, "
                + "physical location, and flag states (tombstoned, consolidated, pinned, resolved). "
                + "Use this to debug why a memory scores high or low, verify ingestion, "
                + "or understand the full internal state of a specific memory.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchemaBuilder.object()
                .requiredString("id", "The memory ID to inspect.")
                .build();
    }

    @Override
    protected McpSchema.CallToolResult executeMemory(SpectorMemory memory,
                                                       SpectorEngine engine,
                                                       Map<String, Object> args) throws Exception {
        String id = requireString(args, "id");
        CognitiveRecord record = memory.inspect(id);

        if (record == null) {
            return errorResult("Memory '" + id + "' not found in the index.");
        }

        // Build a rich, human-readable + machine-parseable response
        StringBuilder sb = new StringBuilder();
        sb.append("🔬 Cognitive X-Ray: '").append(id).append("'\n\n");

        // ── Text ──
        sb.append("📝 Text: ").append(record.text()).append("\n\n");

        // ── Identity ──
        sb.append("🏷️ Type: ").append(record.memoryType()).append("\n");
        sb.append("📦 Source: ").append(record.source()).append("\n");
        if (record.tags() != null && record.tags().length > 0) {
            sb.append("🔖 Tags: ").append(String.join(", ", record.tags())).append("\n");
        }
        sb.append("📅 Created: ").append(record.createdAt())
          .append(String.format(" (%.1f days ago)\n", record.ageDays()));

        // ── Cognitive Header ──
        sb.append("\n── Cognitive Header (64B) ──\n");
        sb.append(String.format("📊 Importance: %.4f / 10.0\n", record.importance()));
        sb.append("🔁 Agent Recall Count: ").append(record.agentRecallCount()).append("\n");
        sb.append("🤖 Auto Recall Count: ").append(record.spectorRecallCount()).append("\n");
        sb.append("💪 Storage Strength: ").append(String.format("%.4f", record.storageStrength())).append("\n");
        sb.append("😊 Valence: ").append(record.valence())
          .append(record.valence() > 0 ? " (positive)" : record.valence() < 0 ? " (negative)" : " (neutral)")
          .append("\n");
        sb.append("⚡ Arousal: ").append(Byte.toUnsignedInt(record.arousal())).append(" / 255\n");
        sb.append("🧲 Synaptic Tags: 0x").append(Long.toHexString(record.synapticTags())).append("\n");
        sb.append("📐 Norm: ").append(String.format("%.6f", record.exactNorm())).append("\n");
        sb.append("🎯 Centroid ID: ").append(record.centroidId()).append("\n");

        // ── Flags ──
        sb.append("\n── Flags ──\n");
        sb.append("🪦 Tombstoned: ").append(record.isTombstoned()).append("\n");
        sb.append("🔄 Consolidated: ").append(record.isConsolidated()).append("\n");
        sb.append("📌 Pinned: ").append(record.isPinned()).append("\n");
        sb.append("✅ Resolved: ").append(record.isResolved()).append("\n");

        // ── Physical Location ──
        sb.append("\n── Storage ──\n");
        sb.append("📍 Partition: ").append(record.partitionIndex()).append("\n");
        sb.append("📍 Byte Offset: ").append(record.byteOffset()).append("\n");
        if (record.hasVector()) {
            sb.append("📐 Vector: ").append(record.quantizedVector().length).append(" dimensions (INT8 quantized)\n");
        }

        // Append the raw JSON at the end for machine consumption
        sb.append("\n── Raw JSON ──\n").append(record.toJson());

        return textResult(sb.toString());
    }
}
