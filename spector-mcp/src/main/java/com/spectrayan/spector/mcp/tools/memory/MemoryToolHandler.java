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
import com.spectrayan.spector.mcp.tools.McpToolHandler;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Base handler for memory-aware MCP tools.
 *
 * <p>Memory tools need both the {@link SpectorEngine} (for embedding) and
 * {@link SpectorMemory} (for cognitive operations). Subclasses implement
 * {@link #executeMemory(SpectorMemory, SpectorEngine, Map)} instead of
 * the standard {@code execute()} method.</p>
 */
public abstract class MemoryToolHandler extends McpToolHandler {

    private final SpectorMemory memory;

    protected MemoryToolHandler(SpectorMemory memory) {
        this.memory = memory;
    }

    /**
     * Executes the memory tool logic.
     *
     * @param memory the cognitive memory instance
     * @param engine the search engine (for embedding provider access)
     * @param args   the parsed MCP request arguments
     * @return the tool result
     */
    protected abstract McpSchema.CallToolResult executeMemory(SpectorMemory memory,
                                                               SpectorEngine engine,
                                                               Map<String, Object> args) throws Exception;

    @Override
    public final McpSchema.CallToolResult execute(SpectorEngine engine,
                                                    Map<String, Object> args) throws Exception {
        if (memory == null) {
            return errorResult("SpectorMemory is not configured. Start the server with --memory-enabled.");
        }
        return executeMemory(memory, engine, args);
    }

    /**
     * Extracts an optional float argument.
     */
    protected static float optionalFloat(Map<String, Object> args, String key, float defaultValue) {
        Object val = args.get(key);
        if (val == null) return defaultValue;
        if (val instanceof Number n) return n.floatValue();
        try {
            return Float.parseFloat(val.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Extracts an optional byte argument.
     */
    protected static byte optionalByte(Map<String, Object> args, String key, byte defaultValue) {
        Object val = args.get(key);
        if (val == null) return defaultValue;
        if (val instanceof Number n) return n.byteValue();
        try {
            return Byte.parseByte(val.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Extracts an optional string array argument (comma-separated).
     */
    protected static String[] optionalTags(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) return new String[0];
        String str = val.toString().trim();
        if (str.isEmpty()) return new String[0];
        return str.split("\\s*,\\s*");
    }
}
