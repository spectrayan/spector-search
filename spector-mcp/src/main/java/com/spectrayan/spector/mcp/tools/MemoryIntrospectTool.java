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
import com.spectrayan.spector.memory.SpectorMemory;
import com.spectrayan.spector.memory.metamemory.MemoryInsight;
import com.spectrayan.spector.mcp.schema.ToolSchemaBuilder;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool: {@code memory_introspect} — metamemory confidence/gaps analysis.
 *
 * <p>Lets the agent reason about what it knows and doesn't know.
 * Instead of hallucinating, the agent can say: "I don't have strong
 * memories about Kubernetes RBAC — let me ask you about that."</p>
 */
public final class MemoryIntrospectTool extends MemoryToolHandler {

    public MemoryIntrospectTool(SpectorMemory memory) {
        super(memory);
    }

    @Override public String name() { return "memory_introspect"; }

    @Override
    public String description() {
        return "Introspect the agent's knowledge about a topic. Returns confidence, "
                + "knowledge gaps, staleness, and actionable recommendations. "
                + "Use this before answering questions to check if you have reliable knowledge.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchemaBuilder.object()
                .requiredString("topic", "The topic to introspect (e.g., 'kubernetes', 'user preferences').")
                .build();
    }

    @Override
    protected McpSchema.CallToolResult executeMemory(SpectorMemory memory,
                                                       SpectorEngine engine,
                                                       Map<String, Object> args) throws Exception {
        String topic = requireString(args, "topic");

        MemoryInsight insight = memory.introspect(topic);

        var sb = new StringBuilder();
        sb.append("🔍 Memory Introspection: '").append(topic).append("'\n");
        sb.append("===============================\n\n");

        sb.append("Known: ").append(insight.isKnown() ? "Yes" : "No").append("\n");
        sb.append("Confidence: ").append(String.format("%.2f", insight.confidence())).append("\n");
        sb.append("Total Memories: ").append(insight.totalMemories()).append("\n");
        sb.append("Average Importance: ").append(String.format("%.2f", insight.avgImportance())).append("\n");
        sb.append("Average Age (days): ").append(String.format("%.1f", insight.avgAgeDays())).append("\n");
        sb.append("Staleness: ").append(String.format("%.2f", insight.staleness())).append("\n");
        sb.append("Stale: ").append(insight.isStale() ? "⚠️ Yes — knowledge may be outdated" : "No").append("\n\n");

        sb.append("Recommendation: ").append(insight.recommendation()).append("\n");

        return textResult(sb.toString());
    }
}
