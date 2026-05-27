package com.spectrayan.spector.mcp.schema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Type-safe fluent builder for MCP tool input schemas.
 *
 * <p>Replaces error-prone nested {@code Map.of()} literals with a
 * composable builder that generates the {@code Map<String, Object>}
 * structure expected by {@link io.modelcontextprotocol.spec.McpSchema.Tool}.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   Map<String, Object> schema = ToolSchemaBuilder.object()
 *       .requiredString("query", "Natural language search query.")
 *       .optionalInt("top_k", "Number of results to return.", 5)
 *       .optionalEnum("mode", "Search mode.", "hybrid", "hybrid", "keyword", "vector")
 *       .build();
 * }</pre>
 *
 * <p>The resulting map is structurally equivalent to a JSON Schema object
 * with the standard {@code type}, {@code properties}, and {@code required}
 * fields. Built maps are unmodifiable.</p>
 *
 * @see io.modelcontextprotocol.spec.McpSchema.Tool
 */
public final class ToolSchemaBuilder {

    private final LinkedHashMap<String, Map<String, Object>> properties = new LinkedHashMap<>();
    private final List<String> required = new ArrayList<>();

    private ToolSchemaBuilder() {}

    /**
     * Creates a new builder for an {@code "object"}-type JSON schema.
     *
     * @return a fresh builder instance
     */
    public static ToolSchemaBuilder object() {
        return new ToolSchemaBuilder();
    }

    // ─────────────── Required Parameters ───────────────

    /**
     * Adds a required string parameter.
     *
     * @param name        parameter name (JSON key)
     * @param description human-readable description for AI agents
     * @return this builder for chaining
     */
    public ToolSchemaBuilder requiredString(String name, String description) {
        properties.put(name, propertyOf("string", description));
        required.add(name);
        return this;
    }

    /**
     * Adds a required integer parameter.
     *
     * @param name        parameter name
     * @param description human-readable description
     * @return this builder
     */
    public ToolSchemaBuilder requiredInt(String name, String description) {
        properties.put(name, propertyOf("integer", description));
        required.add(name);
        return this;
    }

    // ─────────────── Optional Parameters ───────────────

    /**
     * Adds an optional string parameter with a default value.
     *
     * @param name         parameter name
     * @param description  human-readable description
     * @param defaultValue default value (may be {@code null})
     * @return this builder
     */
    public ToolSchemaBuilder optionalString(String name, String description, String defaultValue) {
        Map<String, Object> prop = propertyOf("string", description);
        if (defaultValue != null) prop.put("default", defaultValue);
        properties.put(name, prop);
        return this;
    }

    /**
     * Adds an optional integer parameter with a default value.
     *
     * @param name         parameter name
     * @param description  human-readable description
     * @param defaultValue default value
     * @return this builder
     */
    public ToolSchemaBuilder optionalInt(String name, String description, int defaultValue) {
        Map<String, Object> prop = propertyOf("integer", description);
        prop.put("default", defaultValue);
        properties.put(name, prop);
        return this;
    }

    /**
     * Adds an optional boolean parameter with a default value.
     *
     * @param name         parameter name
     * @param description  human-readable description
     * @param defaultValue default value
     * @return this builder
     */
    public ToolSchemaBuilder optionalBoolean(String name, String description, boolean defaultValue) {
        Map<String, Object> prop = propertyOf("boolean", description);
        prop.put("default", defaultValue);
        properties.put(name, prop);
        return this;
    }

    /**
     * Adds an optional enum (string) parameter with allowed values.
     *
     * @param name         parameter name
     * @param description  human-readable description
     * @param defaultValue default value
     * @param values       allowed enum values
     * @return this builder
     */
    public ToolSchemaBuilder optionalEnum(String name, String description,
                                          String defaultValue, String... values) {
        Map<String, Object> prop = propertyOf("string", description);
        prop.put("enum", List.of(values));
        prop.put("default", defaultValue);
        properties.put(name, prop);
        return this;
    }

    // ─────────────── Build ───────────────

    /**
     * Builds the final unmodifiable schema map.
     *
     * @return {@code Map<String, Object>} conforming to JSON Schema "object" type
     */
    public Map<String, Object> build() {
        Map<String, Object> schema = new HashMap<>(4);
        schema.put("type", "object");
        schema.put("properties", Map.copyOf(properties));
        if (!required.isEmpty()) {
            schema.put("required", List.copyOf(required));
        }
        return Map.copyOf(schema);
    }

    // ─────────────── Empty Schema ───────────────

    /**
     * Convenience method for tools with no input parameters.
     *
     * @return an empty object schema
     */
    public static Map<String, Object> empty() {
        return Map.of("type", "object", "properties", Map.of());
    }

    // ─────────────── Internal ───────────────

    private static Map<String, Object> propertyOf(String type, String description) {
        // Use HashMap so callers can add "default", "enum", etc.
        Map<String, Object> prop = new HashMap<>(4);
        prop.put("type", type);
        prop.put("description", description);
        return prop;
    }
}
