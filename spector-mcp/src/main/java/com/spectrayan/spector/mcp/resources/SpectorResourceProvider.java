package com.spectrayan.spector.mcp.resources;

import java.util.List;

import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.mcp.util.ResultFormatter;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Factory for Spector MCP resource specifications.
 *
 * <p>Resources expose read-only data to MCP clients. Currently provides:</p>
 * <ul>
 *   <li>{@code spector://status} — real-time engine status as JSON</li>
 * </ul>
 *
 * <p>Resources are defined separately from the server orchestrator
 * for clean separation of concerns and independent extensibility.</p>
 */
public final class SpectorResourceProvider {

    /** URI scheme for Spector resources. */
    private static final String SCHEME = "spector://";

    private SpectorResourceProvider() {} // static factory

    /**
     * Creates all resource specifications for MCP server registration.
     *
     * @param engine        the Spector engine instance
     * @param serverVersion the server version string
     * @return list of resource specifications
     */
    public static List<McpServerFeatures.SyncResourceSpecification> create(
            SpectorEngine engine, String serverVersion) {
        return List.of(
                createStatusResource(engine, serverVersion)
        );
    }

    // ─────────────── Status Resource ───────────────

    private static McpServerFeatures.SyncResourceSpecification createStatusResource(
            SpectorEngine engine, String serverVersion) {

        var resource = McpSchema.Resource.builder(SCHEME + "status", "Engine Status")
                .description("Real-time Spector Search engine status including document count, "
                        + "index type, SIMD capabilities, GPU status, and embedding configuration.")
                .mimeType("application/json")
                .build();

        return new McpServerFeatures.SyncResourceSpecification(resource, (exchange, request) -> {
            // Build status as a structured map, then serialize to JSON
            var statusMap = ResultFormatter.buildEngineStatusMap(engine, serverVersion);
            String json = mapToJson(statusMap);

            return new McpSchema.ReadResourceResult(
                    List.of(new McpSchema.TextResourceContents(
                            request.uri(), "application/json", json))
            );
        });
    }

    // ─────────────── Internal ───────────────

    /**
     * Simple JSON serialization for flat maps — avoids adding Jackson
     * as a direct dependency for the resource provider.
     *
     * <p>For nested or complex structures, inject an ObjectMapper instead.</p>
     */
    private static String mapToJson(java.util.Map<String, Object> map) {
        var sb = new StringBuilder(256);
        sb.append("{\n");
        var entries = map.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            sb.append("  \"").append(entry.getKey()).append("\": ");
            Object val = entry.getValue();
            if (val instanceof Number) {
                sb.append(val);
            } else {
                sb.append('"').append(val).append('"');
            }
            if (entries.hasNext()) sb.append(',');
            sb.append('\n');
        }
        sb.append('}');
        return sb.toString();
    }
}
