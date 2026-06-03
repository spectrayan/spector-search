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

import java.time.Duration;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;

import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.mcp.schema.ToolSchemaBuilder;
import com.spectrayan.spector.memory.SpectorMemory;
import com.spectrayan.spector.memory.prospective.Reminder;

/**
 * MCP tool: Schedule a prospective memory reminder.
 *
 * <p>Creates a time-based reminder that will be surfaced in future
 * recall results when the trigger time is reached. Models the
 * prospective memory system — "remember to do X at time Y".</p>
 */
public final class MemoryReminderTool extends MemoryToolHandler {

    public MemoryReminderTool(SpectorMemory memory) {
        super(memory);
    }

    @Override public String name() { return "memory_reminder"; }

    @Override
    public String description() {
        return "Schedule a prospective memory reminder. The reminder will be "
                + "surfaced in future recall results after the specified delay. "
                + "Use for 'remember to do X in Y minutes/hours' scenarios.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchemaBuilder.object()
                .requiredString("text", "The reminder text (what to remember).")
                .requiredNumber("delay_seconds",
                        "Seconds from now until the reminder triggers (e.g., 3600 for 1 hour).")
                .optionalString("tags", "Comma-separated contextual tags.", "")
                .build();
    }

    @Override
    protected McpSchema.CallToolResult executeMemory(SpectorMemory memory,
                                                       SpectorEngine engine,
                                                       Map<String, Object> args) throws Exception {
        String text = requireString(args, "text");
        int delaySecs = requireInt(args, "delay_seconds");
        String[] tags = optionalTags(args, "tags");

        if (delaySecs <= 0) {
            return textResult("❌ delay_seconds must be positive (got " + delaySecs + ").");
        }

        Duration delay = Duration.ofSeconds(delaySecs);
        Reminder reminder = memory.scheduleReminder(text, delay, tags);

        String formatted = formatDuration(delay);
        return textResult("⏰ Reminder scheduled: \"" + text + "\"\n"
                + "Triggers in: " + formatted + "\n"
                + "Tags: " + (tags.length > 0 ? String.join(", ", tags) : "none"));
    }

    private int requireInt(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) throw new IllegalArgumentException("Missing required parameter: " + key);
        if (val instanceof Number n) return n.intValue();
        return Integer.parseInt(val.toString());
    }

    private String formatDuration(Duration d) {
        long hours = d.toHours();
        long mins = d.toMinutesPart();
        long secs = d.toSecondsPart();
        if (hours > 0) return hours + "h " + mins + "m";
        if (mins > 0) return mins + "m " + secs + "s";
        return secs + "s";
    }
}
