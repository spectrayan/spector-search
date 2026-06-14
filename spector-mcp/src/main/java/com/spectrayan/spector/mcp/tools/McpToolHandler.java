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

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.runtime.SpectorRuntime;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Abstract base for all Spector MCP tool handlers.
 *
 * <p>Provides a structured contract for tool implementation with built-in
 * support for timing, error handling, argument parsing, and result
 * construction. Subclasses implement four methods:</p>
 *
 * <ul>
 *   <li>{@link #name()} — the MCP tool name (e.g., {@code "semantic_search"})</li>
 *   <li>{@link #description()} — human-readable description for AI agents</li>
 *   <li>{@link #inputSchema()} — JSON Schema map for tool parameters</li>
 *   <li>{@link #execute(SpectorEngine, Map)} — the actual tool logic</li>
 * </ul>
 *
 * <h3>What the base class provides</h3>
 * <ul>
 *   <li>Automatic nanosecond-precision timing of every invocation</li>
 *   <li>Structured exception handling with logging and error result wrapping</li>
 *   <li>Type-safe argument extraction ({@link #requireString}, {@link #optionalInt}, etc.)</li>
 *   <li>Factory methods for results ({@link #textResult}, {@link #errorResult})</li>
 *   <li>Embedding provider precondition check ({@link #requireEmbeddingProvider})</li>
 * </ul>
 *
 * <h3>Adding a new tool</h3>
 * <ol>
 *   <li>Create a class extending {@code McpToolHandler}</li>
 *   <li>Implement the four abstract methods</li>
 *   <li>Add one line to {@link SpectorToolRegistry}</li>
 * </ol>
 */
public abstract class McpToolHandler {

    private final Logger log = LoggerFactory.getLogger(getClass());

    // ═══════════════════════════════════════════════════════════════
    //  Contract — subclass must implement
    // ═══════════════════════════════════════════════════════════════

    /**
     * The unique MCP tool name (e.g., {@code "semantic_search"}).
     * Must be a valid JSON-RPC method identifier.
     */
    public abstract String name();

    /**
     * Human-readable description shown to AI agents for tool selection.
     */
    public abstract String description();

    /**
     * JSON Schema describing the tool's input parameters.
     * Use {@link com.spectrayan.spector.mcp.schema.ToolSchemaBuilder} to construct.
     *
     * @return unmodifiable map conforming to JSON Schema "object" type
     */
    public abstract Map<String, Object> inputSchema();

    /**
     * Executes the tool logic against the engine.
     *
     * <p>This method is called inside the timing/error-handling wrapper
     * provided by {@link #toToolSpecification}. Implementations should
     * focus purely on business logic — no try/catch or timing needed.</p>
     *
     * @param engine the Spector engine instance
     * @param args   the parsed arguments from the MCP request (never null)
     * @return the tool result
     * @throws ToolArgumentException if a required argument is missing or invalid
     * @throws Exception             for any other failure (will be caught and wrapped)
     */
    public abstract McpSchema.CallToolResult execute(SpectorEngine engine,
                                                      Map<String, Object> args) throws Exception;

    /**
     * OAuth 2.1 scopes required to invoke this tool.
     *
     * <p>Returns an empty set by default (no restrictions in OSS mode).
     * Enterprise reads this for tool filtering at {@code list_tools} time
     * and request-time authorization.</p>
     *
     * @return unmodifiable set of required scope strings
     * @see com.spectrayan.spector.commons.security.SpectorScopes
     * @see com.spectrayan.spector.commons.security.SpectorRoles
     */
    public Set<String> requiredScopes() {
        return Set.of();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Tool Specification Builder
    // ═══════════════════════════════════════════════════════════════

    /**
     * Builds the MCP SDK {@link McpServerFeatures.SyncToolSpecification}
     * for this tool, wrapping the handler with timing and error handling.
     *
     * @param engine the Spector engine instance
     * @return fully-configured tool specification ready for server registration
     */
    public final McpServerFeatures.SyncToolSpecification toToolSpecification(SpectorEngine engine) {
        return toToolSpecification(engine, null);
    }

    /**
     * Builds the MCP tool specification with optional runtime for mode-aware routing.
     *
     * @param engine  the Spector engine instance
     * @param runtime the Spector runtime (nullable, for mode-aware tools)
     * @return fully-configured tool specification
     */
    public final McpServerFeatures.SyncToolSpecification toToolSpecification(
            SpectorEngine engine, SpectorRuntime runtime) {
        var tool = McpSchema.Tool.builder(name())
                .description(description())
                .inputSchema(inputSchema())
                .build();

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments() != null
                    ? request.arguments()
                    : Map.of();
            try {
                long startNs = System.nanoTime();
                McpSchema.CallToolResult result = execute(engine, args);
                long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

                if (log.isDebugEnabled()) {
                    log.debug("{} completed in {}ms", name(), elapsedMs);
                }
                return result;

            } catch (ToolArgumentException e) {
                // Validation errors — expected, no stack trace
                return errorResult(e.getMessage());

            } catch (com.spectrayan.spector.commons.error.SpectorException e) {
                log.error("{} failed", name(), e);
                return errorResult(e.getMessage());

            } catch (Exception e) {
                log.error("{} failed", name(), e);
                return errorResult(com.spectrayan.spector.commons.error.ErrorCode.MCP_TOOL_FAILED.format(e.getMessage()));
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════
    //  Argument Extraction — type-safe with validation
    // ═══════════════════════════════════════════════════════════════

    /**
     * Extracts a required string argument.
     *
     * @param args the request arguments
     * @param key  the parameter name
     * @return the non-blank string value
     * @throws ToolArgumentException if missing or blank
     */
    protected static String requireString(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null || val.toString().isBlank()) {
            throw new ToolArgumentException("Parameter '" + key + "' is required and must be non-empty.");
        }
        return val.toString();
    }

    /**
     * Extracts an optional string argument with a default.
     */
    protected static String optionalString(Map<String, Object> args, String key, String defaultValue) {
        Object val = args.get(key);
        return val != null ? val.toString() : defaultValue;
    }

    /**
     * Extracts an optional integer argument with a default.
     */
    protected static int optionalInt(Map<String, Object> args, String key, int defaultValue) {
        Object val = args.get(key);
        if (val == null) return defaultValue;
        if (val instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(val.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Extracts an optional boolean argument with a default.
     */
    protected static boolean optionalBoolean(Map<String, Object> args, String key,
                                              boolean defaultValue) {
        Object val = args.get(key);
        if (val == null) return defaultValue;
        if (val instanceof Boolean b) return b;
        return Boolean.parseBoolean(val.toString());
    }

    // ═══════════════════════════════════════════════════════════════
    //  Precondition Checks
    // ═══════════════════════════════════════════════════════════════

    /**
     * Validates that the engine has an embedding provider configured.
     *
     * @param engine the engine to check
     * @throws ToolArgumentException if no embedding provider is available
     */
    protected static void requireEmbeddingProvider(SpectorEngine engine) {
        if (!engine.hasEmbeddingProvider()) {
            throw new ToolArgumentException(
                    "This operation requires an embedding provider. "
                    + "Configure the engine with --ollama-url and --ollama-model.");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Result Factories
    // ═══════════════════════════════════════════════════════════════

    /**
     * Creates a successful text result.
     */
    protected static McpSchema.CallToolResult textResult(String text) {
        return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent(text)), false, null, null);
    }

    /**
     * Creates an error result.
     */
    protected static McpSchema.CallToolResult errorResult(String message) {
        return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("Error: " + message)), true, null, null);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Argument Validation Exception
    // ═══════════════════════════════════════════════════════════════

    /**
     * Thrown when a tool argument is missing, invalid, or a precondition fails.
     * Caught by the base handler and returned as an MCP error result without a stack trace.
     */
    public static final class ToolArgumentException extends com.spectrayan.spector.commons.error.SpectorValidationException {
        public ToolArgumentException(String message) {
            super(com.spectrayan.spector.commons.error.ErrorCode.ARGUMENT_INVALID, message);
        }
    }
}
