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
import com.spectrayan.spector.memory.model.ImportanceEstimate;
import com.spectrayan.spector.memory.SpectorMemory;
import com.spectrayan.spector.memory.neurodivergent.IcnuWeights;
import com.spectrayan.spector.memory.neurodivergent.IngestionHints;

/**
 * MCP tool: {@code memory_compute_importance} — pre-ingestion importance estimation.
 *
 * <p>Computes what importance a memory <em>would</em> receive if ingested, without
 * actually storing anything. This is a <b>read-only, side-effect-free</b> operation
 * that enables a "compute, then decide" workflow for LLM agents:</p>
 *
 * <ol>
 *   <li>LLM calls {@code memory_compute_importance} with the prospective memory text</li>
 *   <li>Spector embeds the text, computes novelty against the existing store,
 *       and fuses with optional ICNU hints</li>
 *   <li>Returns: novelty score, fused importance, nearest existing memory ID,
 *       flashbulb status, and profile-specific variations</li>
 *   <li>LLM makes an informed decision: adjust hints, skip if duplicate, or proceed</li>
 * </ol>
 *
 * <p>This eliminates the problem of LLMs blindly guessing ICNU values without
 * feedback on what the resulting importance will actually be.</p>
 *
 * <p>Maps to {@link SpectorMemory#estimateImportance(String, IngestionHints)}.</p>
 */
public final class MemoryComputeImportanceTool extends MemoryToolHandler {

    public MemoryComputeImportanceTool(SpectorMemory memory) {
        super(memory);
    }

    @Override public String name() { return "memory_compute_importance"; }

    @Override public Set<String> requiredScopes() { return Set.of(SpectorScopes.MEMORY_READ); }

    @Override
    public String description() {
        return "Compute what importance a memory WOULD receive without actually storing it. "
                + "Use this BEFORE memory_remember to preview novelty, detect duplicates, "
                + "and understand how your ICNU hints affect the final score. "
                + "Returns: novelty score (Spector-native), fused importance, nearest existing memory, "
                + "and profile-specific importance variations. "
                + "This is read-only — nothing is stored or modified.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchemaBuilder.object()
                .requiredString("text", "The memory text to evaluate for importance.")
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
                        + "0 = neutral.", 0)
                .optionalNumber("arousal",
                        "Emotional intensity: 0 (calm) to 255 (extreme). "
                        + "0 = neutral.", 0)
                .build();
    }

    @Override
    protected McpSchema.CallToolResult executeMemory(SpectorMemory memory,
                                                       SpectorEngine engine,
                                                       Map<String, Object> args) throws Exception {
        String text = requireString(args, "text");

        // Parse optional ICNU hints
        float interest = optionalFloat(args, "interest", 0f);
        float challenge = optionalFloat(args, "challenge", 0f);
        float urgency = optionalFloat(args, "urgency", 0f);
        int valence = optionalInt(args, "valence", 0);
        int arousal = optionalInt(args, "arousal", 0);

        // Build hints only if any params provided
        IngestionHints hints = null;
        boolean hasHints = interest > 0 || challenge > 0 || urgency > 0
                || valence != 0 || arousal != 0;
        if (hasHints) {
            hints = new IngestionHints(interest, challenge, urgency,
                    (byte) Math.clamp(valence, -128, 127),
                    (byte) Math.clamp(arousal, 0, 255));
        }

        // Compute the primary estimate
        ImportanceEstimate estimate = memory.estimateImportance(text, hints);

        // Build response
        StringBuilder sb = new StringBuilder();
        sb.append(estimate.toSummary());

        // Show ICNU breakdown if hints were provided
        if (hasHints) {
            sb.append("\n\n📐 Your Hints:\n");
            sb.append("  Interest=").append(String.format("%.2f", interest));
            sb.append("  Challenge=").append(String.format("%.2f", challenge));
            sb.append("  Urgency=").append(String.format("%.2f", urgency));
            if (valence != 0) sb.append("  Valence=").append(valence);
            if (arousal != 0) sb.append("  Arousal=").append(arousal);
        }

        // Profile-specific variations
        sb.append("\n\n🔬 ICNU Weight Variations:");

        // Show what importance would be with different weight presets
        float noveltyNorm = estimate.noveltyScore();

        // Default weights (I=30% C=10% N=40% U=20%)
        if (hasHints) {
            float defaultFused = IcnuWeights.DEFAULT.fuse(
                    interest, challenge, noveltyNorm, urgency);
            sb.append(String.format("\n  DEFAULT   (I=30%% C=10%% N=40%% U=20%%): %.2f", defaultFused));

            // Novelty-only (no LLM hints)
            sb.append(String.format("\n  NOVELTY   (pure novelty, no hints):     %.2f",
                    estimate.noveltyOnlyImportance()));

            // Linear mode (no sigmoid gating)
            float linearFused = IcnuWeights.LINEAR.fuse(
                    interest, challenge, noveltyNorm, urgency);
            sb.append(String.format("\n  LINEAR    (no sigmoid, raw fusion):     %.2f", linearFused));

            // Interest-dominant (for coding agents focused on current task)
            IcnuWeights interestDominant = new IcnuWeights(0.5f, 0.1f, 0.2f, 0.2f);
            float interestFused = interestDominant.fuse(
                    interest, challenge, noveltyNorm, urgency);
            sb.append(String.format("\n  INTEREST  (I=50%% C=10%% N=20%% U=20%%): %.2f", interestFused));

            // Urgency-dominant (for SRE/incident response)
            IcnuWeights urgencyDominant = new IcnuWeights(0.2f, 0.1f, 0.2f, 0.5f);
            float urgencyFused = urgencyDominant.fuse(
                    interest, challenge, noveltyNorm, urgency);
            sb.append(String.format("\n  URGENCY   (I=20%% C=10%% N=20%% U=50%%): %.2f", urgencyFused));
        } else {
            sb.append("\n  (Provide interest/challenge/urgency hints to see weight variations)");
            sb.append(String.format("\n  NOVELTY-ONLY: %.2f", estimate.noveltyOnlyImportance()));
        }

        // Actionable advice
        sb.append("\n\n");
        if (estimate.nearestMemoryId() != null && estimate.nearestDistance() < 0.15f) {
            sb.append("⚠️ Very close to existing memory '")
                    .append(estimate.nearestMemoryId())
                    .append("' — consider skipping or using memory_reinforce instead.");
        } else if (estimate.fusedImportance() < 1.0f) {
            sb.append("💡 Low importance — this memory may fade quickly. "
                    + "Consider increasing interest or urgency if this is significant.");
        } else if (estimate.fusedImportance() > 7.0f) {
            sb.append("🔥 High importance — this memory will be strongly retained.");
        } else {
            sb.append("✅ Moderate importance — this memory will be retained normally.");
        }

        return textResult(sb.toString());
    }
}
