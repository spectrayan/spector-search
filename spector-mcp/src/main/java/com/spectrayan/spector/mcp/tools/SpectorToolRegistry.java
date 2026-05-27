package com.spectrayan.spector.mcp.tools;

import java.util.List;

import com.spectrayan.spector.engine.SpectorEngine;

import io.modelcontextprotocol.server.McpServerFeatures;

/**
 * Central registry for all Spector MCP tool handlers.
 *
 * <p>To add a new tool:</p>
 * <ol>
 *   <li>Create a class extending {@link McpToolHandler}</li>
 *   <li>Add a single entry to the {@link #HANDLERS} list below</li>
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
     * <p>The list is intentionally declared inline for simplicity.
     * Adding a new tool requires only one new entry here.</p>
     *
     * @param serverVersion the server version string (passed to EngineStatusTool)
     * @return unmodifiable list of tool handlers
     */
    public static List<McpToolHandler> handlers(String serverVersion) {
        return List.of(
                new SemanticSearchTool(),
                new HybridSearchTool(),
                new RagQueryTool(),
                new IngestDocumentTool(),
                new DeleteDocumentTool(),
                new EngineStatusTool(serverVersion)
        );
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
        return handlers(serverVersion).stream()
                .map(handler -> handler.toToolSpecification(engine))
                .toList();
    }
}
