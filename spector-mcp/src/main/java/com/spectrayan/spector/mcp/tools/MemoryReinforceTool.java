package com.spectrayan.spector.mcp.tools;

import java.util.Map;

import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.memory.SpectorMemory;
import com.spectrayan.spector.mcp.schema.ToolSchemaBuilder;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool: {@code memory_reinforce} — report outcome (+/-) after using a memory.
 *
 * <p>Outcome-driven reinforcement learning. Valence is learned from results,
 * not guessed at encoding time.</p>
 */
public final class MemoryReinforceTool extends MemoryToolHandler {

    public MemoryReinforceTool(SpectorMemory memory) {
        super(memory);
    }

    @Override public String name() { return "memory_reinforce"; }

    @Override
    public String description() {
        return "Report the outcome after using a recalled memory. "
                + "If the memory helped solve the problem, reinforce positively (+50). "
                + "If it was misleading, reinforce negatively (-50). "
                + "This teaches the memory system which facts are reliable.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchemaBuilder.object()
                .requiredString("memory_id", "The ID of the memory to reinforce.")
                .requiredString("valence",
                        "Outcome: 'strongly_positive' (+100), 'positive' (+50), "
                        + "'neutral' (0), 'negative' (-50), 'strongly_negative' (-100), "
                        + "or a numeric byte value (-128 to 127).")
                .build();
    }

    @Override
    protected McpSchema.CallToolResult executeMemory(SpectorMemory memory,
                                                       SpectorEngine engine,
                                                       Map<String, Object> args) throws Exception {
        String memoryId = requireString(args, "memory_id");
        String valenceStr = requireString(args, "valence");

        byte valence = parseValence(valenceStr);
        memory.reinforce(memoryId, valence);

        String emoji = valence > 0 ? "👍" : valence < 0 ? "👎" : "😐";
        return textResult(emoji + " Reinforced '" + memoryId + "' with valence=" + valence);
    }

    private static byte parseValence(String str) {
        return switch (str.toLowerCase().replace("_", "").replace("-", "")) {
            case "stronglypositive" -> 100;
            case "positive" -> 50;
            case "neutral" -> 0;
            case "negative" -> -50;
            case "stronglynegative" -> -100;
            default -> {
                try {
                    yield Byte.parseByte(str);
                } catch (NumberFormatException e) {
                    yield 0;
                }
            }
        };
    }
}
