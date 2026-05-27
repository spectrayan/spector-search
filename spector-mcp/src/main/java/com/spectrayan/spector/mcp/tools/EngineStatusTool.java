package com.spectrayan.spector.mcp.tools;

import java.util.Map;

import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.mcp.schema.ToolSchemaBuilder;
import com.spectrayan.spector.mcp.util.ResultFormatter;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Engine status and capabilities tool.
 *
 * <p>Returns a comprehensive status report including document count,
 * vector dimensionality, index type, SIMD capabilities, GPU status,
 * and embedding provider info.</p>
 */
public final class EngineStatusTool extends McpToolHandler {

    /** Server version — injected at construction to avoid hardcoding. */
    private final String serverVersion;

    public EngineStatusTool(String serverVersion) {
        this.serverVersion = serverVersion;
    }

    @Override
    public String name() {
        return "engine_status";
    }

    @Override
    public String description() {
        return "Returns current Spector engine status including document count, vector "
                + "dimensionality, index type (HNSW/IVF-PQ/SPECTRUM), SIMD capabilities, "
                + "GPU acceleration status, and embedding provider info.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchemaBuilder.empty();
    }

    @Override
    public McpSchema.CallToolResult execute(SpectorEngine engine, Map<String, Object> args) {
        return textResult(ResultFormatter.formatEngineStatus(engine, serverVersion));
    }
}
