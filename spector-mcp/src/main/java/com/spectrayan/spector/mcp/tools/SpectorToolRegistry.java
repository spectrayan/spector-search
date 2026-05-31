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

import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.memory.SpectorMemory;
import com.spectrayan.spector.runtime.SpectorRuntime;

import io.modelcontextprotocol.server.McpServerFeatures;

/**
 * Central registry for all Spector MCP tool handlers.
 *
 * <p>To add a new tool:</p>
 * <ol>
 *   <li>Create a class extending {@link McpToolHandler}</li>
 *   <li>Add a single entry to the handlers list below</li>
 * </ol>
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

        // Core search/ingest tools
        handlers.add(new SemanticSearchTool());
        handlers.add(new HybridSearchTool());
        handlers.add(new RagQueryTool());
        handlers.add(new IngestDocumentTool());
        handlers.add(new DeleteDocumentTool());
        handlers.add(new EngineStatusTool(serverVersion));

        // Memory tools (available when SpectorMemory is configured)
        if (memory != null) {
            handlers.add(new CoreMemoryAppendTool(memory));
            handlers.add(new WorkingMemoryScratchpadTool(memory));
            handlers.add(new RecallContextTool(memory));
            handlers.add(new MemoryReinforceTool(memory));
            handlers.add(new MemoryForgetTool(memory));
            handlers.add(new MemoryStatusTool(memory));
            handlers.add(new MemoryIntrospectTool(memory));
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
     * Creates all tool specifications with mode-aware runtime support.
     *
     * <p>When a {@link SpectorRuntime} is provided, tools can access the
     * runtime for mode-aware search and ingestion routing.</p>
     *
     * @param runtime       the Spector runtime (engine + optional memory)
     * @param serverVersion the server version string
     * @return list of MCP tool specifications
     */
    public static List<McpServerFeatures.SyncToolSpecification> createAll(
            SpectorRuntime runtime, String serverVersion) {
        SpectorMemory memory = runtime.hasMemory() ? runtime.memory() : null;
        return handlers(serverVersion, memory).stream()
                .map(handler -> handler.toToolSpecification(runtime.engine(), runtime))
                .toList();
    }
}
