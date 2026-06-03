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
import com.spectrayan.spector.memory.MemoryType;
import com.spectrayan.spector.memory.SpectorMemory;
import com.spectrayan.spector.memory.cortex.MemorySource;
import com.spectrayan.spector.memory.neurodivergent.IngestionHints;

/**
 * MCP tool: {@code memory_remember} — store a memory with full cognitive metadata.
 *
 * <p>Supports all 4 memory tiers (WORKING, EPISODIC, SEMANTIC, PROCEDURAL),
 * ICNU importance hints (Interest, Challenge, Urgency — Novelty is computed
 * natively), and emotional context (valence + arousal).</p>
 *
 * <p>All cognitive parameters are optional for backward compatibility.
 * When omitted, the memory is stored as SEMANTIC with novelty-only importance.</p>
 *
 * <p>Maps to {@link SpectorMemory#remember} with optional {@link IngestionHints}.</p>
 */
public final class MemoryRememberTool extends MemoryToolHandler {

    public MemoryRememberTool(SpectorMemory memory) {
        super(memory);
    }

    @Override public String name() { return "memory_remember"; }

    @Override
    public String description() {
        return "Store a memory with optional cognitive metadata. "
                + "Use 'tier' to choose where it goes: WORKING (ephemeral scratchpad), "
                + "EPISODIC (personal experiences with time context), "
                + "SEMANTIC (facts and knowledge, default), "
                + "PROCEDURAL (skills, patterns, how-to). "
                + "Set 'interest', 'challenge', 'urgency' (0.0-1.0) for importance tuning. "
                + "Set 'valence' for emotional memories (-128=very negative, +127=very positive). "
                + "Set 'arousal' for intensity (0=calm, 255=extreme). "
                + "Tags help with contextual recall (e.g., 'preferences', 'architecture').";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchemaBuilder.object()
                .requiredString("id", "Unique identifier for this memory (e.g., 'user-pref-dark-mode').")
                .requiredString("text", "The fact, experience, or knowledge to remember.")
                .optionalString("tags", "Comma-separated contextual tags for Bloom filter encoding.", "")
                .optionalString("source",
                        "Memory source: USER_STATED, OBSERVED, INFERRED, PROCEDURAL.", "OBSERVED")
                .optionalString("tier",
                        "Memory tier: WORKING (ephemeral), EPISODIC (experiences), "
                        + "SEMANTIC (facts, default), PROCEDURAL (skills/patterns).", "SEMANTIC")
                .optionalNumber("interest",
                        "ICNU: how relevant to current task (0.0-1.0). "
                        + "High for directly actionable info.", 0.0)
                .optionalNumber("challenge",
                        "ICNU: how complex or difficult the problem is (0.0-1.0). "
                        + "High for novel technical problems.", 0.0)
                .optionalNumber("urgency",
                        "ICNU: how time-critical this information is (0.0-1.0). "
                        + "High for deadlines, incidents.", 0.0)
                .optionalNumber("valence",
                        "Emotional valence: -128 (extremely negative) to +127 (extremely positive). "
                        + "0 = neutral. Use for emotionally significant memories.", 0)
                .optionalNumber("arousal",
                        "Emotional intensity: 0 (calm) to 255 (extreme). "
                        + "0 = neutral. Auto-derived from |valence| if not set.", 0)
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
        String tierName = optionalString(args, "tier", "SEMANTIC");

        // Parse tier
        MemoryType type;
        try {
            type = MemoryType.valueOf(tierName.toUpperCase());
        } catch (IllegalArgumentException e) {
            type = MemoryType.SEMANTIC;
        }

        // Parse source
        MemorySource source;
        try {
            source = MemorySource.valueOf(sourceName.toUpperCase());
        } catch (IllegalArgumentException e) {
            source = MemorySource.OBSERVED;
        }

        // Parse ICNU hints + emotional context
        float interest = optionalFloat(args, "interest", 0f);
        float challenge = optionalFloat(args, "challenge", 0f);
        float urgency = optionalFloat(args, "urgency", 0f);
        int valence = optionalInt(args, "valence", 0);
        int arousal = optionalInt(args, "arousal", 0);

        // Build IngestionHints only if any cognitive params were provided
        IngestionHints hints = null;
        if (interest > 0 || challenge > 0 || urgency > 0 || valence != 0 || arousal != 0) {
            hints = new IngestionHints(interest, challenge, urgency,
                    (byte) Math.clamp(valence, -128, 127),
                    (byte) Math.clamp(arousal, 0, 255));
        }

        memory.remember(id, text, type, source, hints, tags).join();

        StringBuilder sb = new StringBuilder();
        sb.append("✅ Stored ").append(type).append(" memory '").append(id).append("'");
        if (tags.length > 0) sb.append(" with ").append(tags.length).append(" tags");
        sb.append(" (source=").append(source).append(")");
        if (hints != null) {
            sb.append("\n📊 ICNU: I=").append(interest).append(", C=").append(challenge)
              .append(", U=").append(urgency);
            if (valence != 0) sb.append(" | valence=").append(valence);
            if (arousal != 0) sb.append(" | arousal=").append(arousal);
        }

        return textResult(sb.toString());
    }
}
