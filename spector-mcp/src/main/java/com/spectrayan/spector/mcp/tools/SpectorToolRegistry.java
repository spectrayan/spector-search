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

import java.util.ArrayList;
import java.util.List;

import com.spectrayan.spector.config.SpectorMode;
import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.memory.SpectorMemory;
import com.spectrayan.spector.runtime.SpectorRuntime;

import com.spectrayan.spector.mcp.tools.engine.EngineSearchTool;
import com.spectrayan.spector.mcp.tools.engine.EngineHybridSearchTool;
import com.spectrayan.spector.mcp.tools.engine.EngineRagTool;
import com.spectrayan.spector.mcp.tools.engine.EngineIngestTool;
import com.spectrayan.spector.mcp.tools.engine.EngineDeleteTool;
import com.spectrayan.spector.mcp.tools.engine.EngineStatusTool;

import com.spectrayan.spector.mcp.tools.memory.MemoryRememberTool;
import com.spectrayan.spector.mcp.tools.memory.MemoryScratchpadTool;
import com.spectrayan.spector.mcp.tools.memory.MemoryRecallTool;
import com.spectrayan.spector.mcp.tools.memory.MemoryReinforceTool;
import com.spectrayan.spector.mcp.tools.memory.MemoryForgetTool;
import com.spectrayan.spector.mcp.tools.memory.MemoryStatusTool;
import com.spectrayan.spector.mcp.tools.memory.MemoryIntrospectTool;
import com.spectrayan.spector.mcp.tools.memory.MemorySuppressTool;
import com.spectrayan.spector.mcp.tools.memory.MemoryResolveTool;
import com.spectrayan.spector.mcp.tools.memory.MemoryReminderTool;
import com.spectrayan.spector.mcp.tools.memory.MemoryWhyNotTool;

import io.modelcontextprotocol.server.McpServerFeatures;

/**
 * Central registry for all Spector MCP tool handlers.
 *
 * <p>Tools are organized into two sub-packages:</p>
 * <ul>
 *   <li>{@code tools.engine} — search, ingest, RAG, and engine status tools</li>
 *   <li>{@code tools.memory} — cognitive memory tools (remember, recall, forget, etc.)</li>
 * </ul>
 *
 * <p>All tools are instantiated once and reused across requests.
 * The {@link McpToolHandler} base class ensures thread-safe execution
 * on concurrent virtual threads.</p>
 */
public final class SpectorToolRegistry {

    private SpectorToolRegistry() {} // static utility

    /**
     * Returns the list of all tool handlers registered in this server.
     *
     * @param serverVersion the server version string
     * @return unmodifiable list of tool handlers
     */
    public static List<McpToolHandler> handlers(String serverVersion) {
        return handlers(serverVersion, null);
    }

    /**
     * Returns tool handlers including memory tools when SpectorMemory is available.
     *
     * @param serverVersion the server version string
     * @param memory        optional SpectorMemory instance (null if memory is not enabled)
     * @return list of tool handlers
     */
    public static List<McpToolHandler> handlers(String serverVersion, SpectorMemory memory) {
        var handlers = new ArrayList<McpToolHandler>();

        // Engine tools (search, ingest, RAG)
        handlers.add(new EngineSearchTool());
        handlers.add(new EngineHybridSearchTool());
        handlers.add(new EngineRagTool());
        handlers.add(new EngineIngestTool());
        handlers.add(new EngineDeleteTool());
        handlers.add(new EngineStatusTool(serverVersion));

        // Memory tools (available when SpectorMemory is configured)
        if (memory != null) {
            handlers.add(new MemoryRememberTool(memory));
            handlers.add(new MemoryScratchpadTool(memory));
            handlers.add(new MemoryRecallTool(memory));
            handlers.add(new MemoryReinforceTool(memory));
            handlers.add(new MemoryForgetTool(memory));
            handlers.add(new MemoryStatusTool(memory));
            handlers.add(new MemoryIntrospectTool(memory));
            handlers.add(new MemorySuppressTool(memory));
            handlers.add(new MemoryResolveTool(memory));
            handlers.add(new MemoryReminderTool(memory));
            handlers.add(new MemoryWhyNotTool(memory));
        }

        return List.copyOf(handlers);
    }

    /**
     * Creates all tool specifications for MCP server registration.
     *
     * @param engine        the Spector engine instance
     * @param serverVersion the server version string
     * @return list of MCP tool specifications ready for server builder
     */
    public static List<McpServerFeatures.SyncToolSpecification> createAll(
            SpectorEngine engine, String serverVersion) {
        return createAll(engine, serverVersion, null);
    }

    /**
     * Creates all tool specifications including memory tools.
     *
     * @param engine        the Spector engine instance
     * @param serverVersion the server version string
     * @param memory        optional SpectorMemory instance
     * @return list of MCP tool specifications
     */
    public static List<McpServerFeatures.SyncToolSpecification> createAll(
            SpectorEngine engine, String serverVersion, SpectorMemory memory) {
        return handlers(serverVersion, memory).stream()
                .map(handler -> handler.toToolSpecification(engine))
                .toList();
    }

    /**
     * Creates mode-aware tool specifications from a runtime.
     *
     * <p>In {@code SEARCH} mode, only engine tools are registered.
     * In {@code MEMORY} mode, only memory tools are registered.
     * In {@code HYBRID} mode, both are registered.</p>
     *
     * @param runtime       the Spector runtime (engine + optional memory)
     * @param serverVersion the server version string
     * @return list of MCP tool specifications filtered by mode
     */
    public static List<McpServerFeatures.SyncToolSpecification> createAll(
            SpectorRuntime runtime, String serverVersion) {
        SpectorMode mode = runtime.mode();
        SpectorMemory memory = runtime.hasMemory() ? runtime.memory() : null;

        var handlers = new ArrayList<McpToolHandler>();

        // Engine tools — registered when engine is enabled
        if (mode.engineEnabled()) {
            handlers.add(new EngineSearchTool());
            handlers.add(new EngineHybridSearchTool());
            handlers.add(new EngineRagTool());
            handlers.add(new EngineIngestTool());
            handlers.add(new EngineDeleteTool());
            handlers.add(new EngineStatusTool(serverVersion));
        }

        // Memory tools — registered when memory is enabled and available
        if (mode.memoryEnabled() && memory != null) {
            handlers.add(new MemoryRememberTool(memory));
            handlers.add(new MemoryScratchpadTool(memory));
            handlers.add(new MemoryRecallTool(memory));
            handlers.add(new MemoryReinforceTool(memory));
            handlers.add(new MemoryForgetTool(memory));
            handlers.add(new MemoryStatusTool(memory));
            handlers.add(new MemoryIntrospectTool(memory));
            handlers.add(new MemorySuppressTool(memory));
            handlers.add(new MemoryResolveTool(memory));
            handlers.add(new MemoryReminderTool(memory));
            handlers.add(new MemoryWhyNotTool(memory));
        }

        return handlers.stream()
                .map(handler -> handler.toToolSpecification(runtime.engine(), runtime))
                .toList();
    }
}
