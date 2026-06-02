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
package com.spectrayan.spector.mcp;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.core.simd.SimdCapability;
import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.memory.SpectorMemory;
import com.spectrayan.spector.runtime.SpectorRuntime;
import com.spectrayan.spector.mcp.prompts.SpectorPromptProvider;
import com.spectrayan.spector.mcp.resources.SpectorResourceProvider;
import com.spectrayan.spector.mcp.tools.SpectorToolRegistry;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * High-performance MCP Server for Spector.
 *
 * <p>Thin orchestrator that assembles tool, resource, and prompt providers
 * into an MCP server. All search operations run in-process with zero
 * network overhead — tool handlers call {@link SpectorEngine} directly.</p>
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Transport setup (stdio via JSON-RPC)</li>
 *   <li>Capability declaration</li>
 *   <li>Provider assembly — delegates to:
 *     <ul>
 *       <li>{@link SpectorToolRegistry} — tool discovery and registration</li>
 *       <li>{@link SpectorResourceProvider} — resource definitions</li>
 *       <li>{@link SpectorPromptProvider} — prompt templates</li>
 *     </ul>
 *   </li>
 *   <li>Lifecycle management (start/stop)</li>
 * </ul>
 *
 * @see SpectorMcpMain
 * @see SpectorToolRegistry
 */
public class SpectorMcpServer {

    private static final Logger log = LoggerFactory.getLogger(SpectorMcpServer.class);

    static final String SERVER_NAME = "spector-mcp";
    static final String SERVER_VERSION = "0.1.0";

    private final SpectorRuntime runtime;
    private final SpectorEngine engine;
    private final SpectorMemory memory; // nullable
    private final TransportMode transportMode;
    private final int httpPort;
    private volatile McpSyncServer mcpServer;

    /**
     * Creates an MCP server backed by the given runtime.
     *
     * @param runtime       the Spector runtime (engine + optional memory)
     * @param transportMode transport mode (STDIO or HTTP)
     * @param httpPort      port for HTTP transport (ignored for STDIO)
     */
    public SpectorMcpServer(SpectorRuntime runtime, TransportMode transportMode, int httpPort) {
        this.runtime = runtime;
        this.engine = runtime.engine();
        this.memory = runtime.memory();
        this.transportMode = transportMode;
        this.httpPort = httpPort;
    }

    /**
     * Creates an MCP server with STDIO transport (backward-compatible).
     */
    public SpectorMcpServer(SpectorRuntime runtime) {
        this(runtime, TransportMode.STDIO, 8080);
    }

    /**
     * Starts the MCP server on stdio transport.
     *
     * <p>This method blocks indefinitely, reading JSON-RPC messages from stdin
     * and writing responses to stdout. All logging is directed to stderr to
     * prevent corruption of the JSON-RPC stream.</p>
     */
    public void start() {
        log.info("[Spector MCP] Starting server: {}, transport={}, dims={}, indexType={}, embedding={}, {}",
                SERVER_NAME, transportMode,
                engine.config().dimensions(),
                engine.config().indexType(),
                engine.hasEmbeddingProvider() ? "configured" : "none",
                SimdCapability.report());

        // ── Assemble providers (runtime-aware for mode routing) ──
        var toolSpecs  = SpectorToolRegistry.createAll(runtime, SERVER_VERSION);
        var resources  = SpectorResourceProvider.create(engine, SERVER_VERSION);
        var prompts    = SpectorPromptProvider.create(engine);

        // ── Configure transport ──
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(
                tools.jackson.databind.json.JsonMapper.builder().build());

        var transportProvider = switch (transportMode) {
            case STDIO -> new StdioServerTransportProvider(jsonMapper);
            case HTTP -> {
                log.info("[Spector MCP] HTTP transport on port {}", httpPort);
                // The MCP SDK's HttpServletStreamableServerTransportProvider requires
                // a servlet container. For now, use stdio as fallback and log guidance.
                log.warn("[Spector MCP] HTTP transport requires a servlet container (Jetty/Tomcat). " +
                        "Configure via SpectorMcpServer.startHttp() with your servlet container. " +
                        "Falling back to stdio transport for standalone mode.");
                yield new StdioServerTransportProvider(jsonMapper);
            }
        };

        // ── Build the MCP server ──
        mcpServer = McpServer.sync(transportProvider)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .resources(false, false)
                        .prompts(false)
                        .build())
                .tools(toolSpecs)
                .resources(resources)
                .prompts(prompts)
                .build();

        log.info("[Spector MCP] Server initialized with {} tools, {} resources, {} prompts",
                toolSpecs.size(), resources.size(), prompts.size());

        // The SDK handles the stdio read loop internally.
        // Block the main thread to keep the server alive.
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            log.info("[Spector MCP] Server interrupted, shutting down");
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Stops the MCP server and releases resources.
     */
    public void stop() {
        if (mcpServer != null) {
            mcpServer.close();
            log.info("[Spector MCP] Server stopped");
        }
    }
}
