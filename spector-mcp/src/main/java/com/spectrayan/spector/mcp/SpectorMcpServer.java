package com.spectrayan.spector.mcp;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.core.simd.SimdCapability;
import com.spectrayan.spector.engine.SpectorEngine;
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
 * High-performance MCP Server for Spector Search.
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

    static final String SERVER_NAME = "spector-search-mcp";
    static final String SERVER_VERSION = "0.1.0";

    private final SpectorEngine engine;
    private volatile McpSyncServer mcpServer;

    /**
     * Creates an MCP server backed by the given engine.
     *
     * @param engine the Spector engine instance (must be initialized)
     */
    public SpectorMcpServer(SpectorEngine engine) {
        this.engine = engine;
    }

    /**
     * Starts the MCP server on stdio transport.
     *
     * <p>This method blocks indefinitely, reading JSON-RPC messages from stdin
     * and writing responses to stdout. All logging is directed to stderr to
     * prevent corruption of the JSON-RPC stream.</p>
     */
    public void start() {
        log.info("[Spector MCP] Starting server: {}, dims={}, indexType={}, embedding={}, {}",
                SERVER_NAME,
                engine.config().dimensions(),
                engine.config().indexType(),
                engine.hasEmbeddingProvider() ? "configured" : "none",
                SimdCapability.report());

        // ── Assemble providers ──
        var toolSpecs  = SpectorToolRegistry.createAll(engine, SERVER_VERSION);
        var resources  = SpectorResourceProvider.create(engine, SERVER_VERSION);
        var prompts    = SpectorPromptProvider.create(engine);

        // ── Configure transport ──
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(
                tools.jackson.databind.json.JsonMapper.builder().build());
        var transportProvider = new StdioServerTransportProvider(jsonMapper);

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
