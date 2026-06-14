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
package com.spectrayan.spector.mcp.tools.engine;

import com.spectrayan.spector.mcp.tools.McpToolHandler;

import java.util.Map;
import java.util.Set;
import com.spectrayan.spector.commons.security.SpectorScopes;

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
    public Set<String> requiredScopes() {
        return Set.of(SpectorScopes.SEARCH_READ);
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
