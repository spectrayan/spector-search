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
public final class MemoryScratchpadTool extends MemoryToolHandler {

    public MemoryScratchpadTool(SpectorMemory memory) {
        super(memory);
    }

    @Override public String name() { return "memory_scratchpad"; }

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
