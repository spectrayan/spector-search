/*
 * Copyright 2026 Spectrayan
 */
package com.spectrayan.spector.mcp.schema;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ToolSchemaBuilder} — fluent JSON schema builder for MCP tools.
 */
@DisplayName("ToolSchemaBuilder")
class ToolSchemaBuilderTest {

    // ══════════════════════════════════════════════════════════════
    // Basic structure
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("empty schema has type=object and empty properties")
    void emptySchema() {
        var schema = ToolSchemaBuilder.empty();
        assertThat(schema).containsEntry("type", "object");
        assertThat((Map<?, ?>) schema.get("properties")).isEmpty();
    }

    @Test
    @DisplayName("builder creates object-type schema")
    void objectTypeSchema() {
        var schema = ToolSchemaBuilder.object().build();
        assertThat(schema.get("type")).isEqualTo("object");
        assertThat(schema).containsKey("properties");
    }

    // ══════════════════════════════════════════════════════════════
    // Required parameters
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("required parameters")
    class RequiredParams {

        @Test
        @DisplayName("requiredString adds string property and marks required")
        void requiredString() {
            var schema = ToolSchemaBuilder.object()
                    .requiredString("query", "The search query.")
                    .build();

            @SuppressWarnings("unchecked")
            var props = (Map<String, Map<String, Object>>) schema.get("properties");
            assertThat(props).containsKey("query");
            assertThat(props.get("query").get("type")).isEqualTo("string");
            assertThat(props.get("query").get("description")).isEqualTo("The search query.");

            @SuppressWarnings("unchecked")
            var required = (List<String>) schema.get("required");
            assertThat(required).contains("query");
        }

        @Test
        @DisplayName("requiredInt adds integer property")
        void requiredInt() {
            var schema = ToolSchemaBuilder.object()
                    .requiredInt("count", "Number of items.")
                    .build();

            @SuppressWarnings("unchecked")
            var props = (Map<String, Map<String, Object>>) schema.get("properties");
            assertThat(props.get("count").get("type")).isEqualTo("integer");
        }

        @Test
        @DisplayName("requiredBoolean adds boolean property")
        void requiredBoolean() {
            var schema = ToolSchemaBuilder.object()
                    .requiredBoolean("enabled", "Whether to enable.")
                    .build();

            @SuppressWarnings("unchecked")
            var props = (Map<String, Map<String, Object>>) schema.get("properties");
            assertThat(props.get("enabled").get("type")).isEqualTo("boolean");
        }

        @Test
        @DisplayName("requiredNumber adds number property")
        void requiredNumber() {
            var schema = ToolSchemaBuilder.object()
                    .requiredNumber("score", "Relevance score.")
                    .build();

            @SuppressWarnings("unchecked")
            var props = (Map<String, Map<String, Object>>) schema.get("properties");
            assertThat(props.get("score").get("type")).isEqualTo("number");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Optional parameters
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("optional parameters")
    class OptionalParams {

        @Test
        @DisplayName("optionalString with default")
        void optionalString() {
            var schema = ToolSchemaBuilder.object()
                    .optionalString("format", "Output format.", "json")
                    .build();

            @SuppressWarnings("unchecked")
            var props = (Map<String, Map<String, Object>>) schema.get("properties");
            assertThat(props.get("format").get("default")).isEqualTo("json");
            assertThat(schema.containsKey("required")).isFalse();
        }

        @Test
        @DisplayName("optionalInt with default")
        void optionalInt() {
            var schema = ToolSchemaBuilder.object()
                    .optionalInt("top_k", "Results count.", 5)
                    .build();

            @SuppressWarnings("unchecked")
            var props = (Map<String, Map<String, Object>>) schema.get("properties");
            assertThat(props.get("top_k").get("default")).isEqualTo(5);
        }

        @Test
        @DisplayName("optionalBoolean with default")
        void optionalBoolean() {
            var schema = ToolSchemaBuilder.object()
                    .optionalBoolean("verbose", "Verbose output.", true)
                    .build();

            @SuppressWarnings("unchecked")
            var props = (Map<String, Map<String, Object>>) schema.get("properties");
            assertThat(props.get("verbose").get("default")).isEqualTo(true);
        }

        @Test
        @DisplayName("optionalEnum with values")
        void optionalEnum() {
            var schema = ToolSchemaBuilder.object()
                    .optionalEnum("mode", "Search mode.", "hybrid", "hybrid", "keyword", "vector")
                    .build();

            @SuppressWarnings("unchecked")
            var props = (Map<String, Map<String, Object>>) schema.get("properties");
            assertThat(props.get("mode").get("default")).isEqualTo("hybrid");
            @SuppressWarnings("unchecked")
            var enumValues = (List<String>) props.get("mode").get("enum");
            assertThat(enumValues).containsExactly("hybrid", "keyword", "vector");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Complex schema
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("complex schema with mixed required and optional")
    void complexSchema() {
        var schema = ToolSchemaBuilder.object()
                .requiredString("query", "Search query")
                .optionalInt("top_k", "Results count", 10)
                .optionalEnum("mode", "Search mode", "hybrid", "hybrid", "keyword")
                .build();

        @SuppressWarnings("unchecked")
        var props = (Map<String, Map<String, Object>>) schema.get("properties");
        assertThat(props).hasSize(3);

        @SuppressWarnings("unchecked")
        var required = (List<String>) schema.get("required");
        assertThat(required).containsExactly("query");
    }

    // ══════════════════════════════════════════════════════════════
    // Immutability
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("built schema is unmodifiable")
    void builtSchemaUnmodifiable() {
        var schema = ToolSchemaBuilder.object()
                .requiredString("q", "query")
                .build();

        assertThatThrownBy(() -> schema.put("extra", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
