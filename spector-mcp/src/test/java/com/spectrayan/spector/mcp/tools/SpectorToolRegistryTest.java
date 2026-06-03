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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.embed.EmbeddingResult;
import com.spectrayan.spector.config.SpectorConfig;
import com.spectrayan.spector.engine.DefaultSpectorEngine;
import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.mcp.schema.ToolSchemaBuilder;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Unit tests for the refactored Spector MCP tool system.
 *
 * <p>Tests cover:</p>
 * <ul>
 *   <li>Tool registry — all 6 tools registered with correct names</li>
 *   <li>Individual tool handlers — correct behavior via the abstract base</li>
 *   <li>Schema builder — produces valid input schemas</li>
 *   <li>Argument validation — missing/empty required args produce errors</li>
 * </ul>
 */
class SpectorToolRegistryTest {

    private static final String TEST_VERSION = "0.1.0-test";

    private static SpectorEngine engine;
    private static List<McpServerFeatures.SyncToolSpecification> specs;

    @BeforeAll
    static void setUp() {
        SpectorConfig config = SpectorConfig.DEFAULT.withDimensions(4);
        engine = new DefaultSpectorEngine(config, new MockEmbeddingProvider());

        engine.ingest("doc-1", "Java Panama SIMD vector search engine");
        engine.ingest("doc-2", "Machine learning and artificial intelligence");
        engine.ingest("doc-3", "Kubernetes container orchestration platform");

        specs = SpectorToolRegistry.createAll(engine, TEST_VERSION);
    }

    @AfterAll
    static void tearDown() {
        if (engine != null) engine.close();
    }

    // ─────────────── Registry Tests ───────────────

    @Test
    void shouldRegister6Tools() {
        assertThat(specs).hasSize(6);
    }

    @Test
    void shouldHaveCorrectToolNames() {
        var names = specs.stream()
                .map(t -> t.tool().name())
                .toList();
        assertThat(names).containsExactlyInAnyOrder(
                "engine_search",
                "engine_hybrid_search",
                "engine_rag",
                "engine_ingest",
                "engine_delete",
                "engine_status"
        );
    }

    @Test
    void allToolsShouldHaveDescriptions() {
        for (var spec : specs) {
            assertThat(spec.tool().description())
                    .as("Description for tool: %s", spec.tool().name())
                    .isNotBlank();
        }
    }

    @Test
    void allToolsShouldHaveInputSchemas() {
        for (var spec : specs) {
            assertThat(spec.tool().inputSchema())
                    .as("Input schema for tool: %s", spec.tool().name())
                    .isNotNull()
                    .containsKey("type");
        }
    }

    // ─────────────── Tool Handler Tests ───────────────

    @Test
    void semanticSearchShouldReturnResults() {
        var result = callTool("engine_search",
                Map.of("query", "vector search", "top_k", 3));

        assertThat(result.isError()).isNotEqualTo(true);
        assertText(result).contains("Found").contains("results");
    }

    @Test
    void semanticSearchShouldRejectEmptyQuery() {
        var result = callTool("engine_search", Map.of("query", ""));
        assertThat(result.isError()).isTrue();
    }

    @Test
    void semanticSearchInvalidTopKShouldReturnStructuredError() {
        var result = callTool("engine_search",
                Map.of("query", "vector search", "top_k", 0));

        assertThat(result.isError()).isTrue();
        assertText(result).contains("[SPE-100-005]");
    }

    @Test
    void hybridSearchShouldWork() {
        var result = callTool("engine_hybrid_search",
                Map.of("query", "machine learning", "top_k", 2, "mode", "hybrid"));

        assertThat(result.isError()).isNotEqualTo(true);
        assertThat(result.content()).isNotEmpty();
    }

    @Test
    void hybridSearchKeywordModeShouldWork() {
        var result = callTool("engine_hybrid_search",
                Map.of("query", "kubernetes", "mode", "keyword"));

        assertThat(result.isError()).isNotEqualTo(true);
    }

    @Test
    void ragQueryShouldReturnContext() {
        var result = callTool("engine_rag",
                Map.of("query", "Panama SIMD", "top_k", 5));

        assertThat(result.isError()).isNotEqualTo(true);
        assertText(result).containsAnyOf("RETRIEVED CONTEXT", "No relevant context");
    }

    @Test
    void ingestDocumentShouldAddDocument() {
        int countBefore = engine.documentCount();
        var result = callTool("engine_ingest",
                Map.of("id", "test-mcp-doc", "content", "Test document for MCP"));

        assertThat(result.isError()).isNotEqualTo(true);
        assertThat(engine.documentCount()).isEqualTo(countBefore + 1);
        assertText(result).contains("ingested successfully");
    }

    @Test
    void deleteDocumentShouldRemoveDocument() {
        engine.ingest("to-delete", "Document to be deleted via MCP");

        var result = callTool("engine_delete", Map.of("id", "to-delete"));

        assertThat(result.isError()).isNotEqualTo(true);
        assertText(result).contains("deleted");
    }

    @Test
    void deleteNonexistentDocumentShouldReportNotFound() {
        var result = callTool("engine_delete",
                Map.of("id", "nonexistent-doc"));

        assertThat(result.isError()).isNotEqualTo(true);
        assertText(result).contains("not found");
    }

    @Test
    void engineStatusShouldReturnInfo() {
        var result = callTool("engine_status", Map.of());

        assertThat(result.isError()).isNotEqualTo(true);
        assertText(result)
                .contains("Documents:")
                .contains("Dimensions:")
                .contains("Simd:");
    }

    // ─────────────── Schema Builder Tests ───────────────

    @Test
    void schemaBuilderShouldProduceValidSchema() {
        var schema = ToolSchemaBuilder.object()
                .requiredString("name", "The name")
                .optionalInt("count", "Number of items", 10)
                .optionalBoolean("verbose", "Verbose output", false)
                .optionalEnum("format", "Output format", "json", "json", "text", "csv")
                .build();

        assertThat(schema).containsEntry("type", "object");
        assertThat(schema).containsKey("properties");
        assertThat(schema).containsKey("required");

        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).containsKeys("name", "count", "verbose", "format");

        @SuppressWarnings("unchecked")
        var required = (List<String>) schema.get("required");
        assertThat(required).containsExactly("name");
    }

    @Test
    void emptySchemaIsValid() {
        var schema = ToolSchemaBuilder.empty();
        assertThat(schema).containsEntry("type", "object");
        assertThat(schema).containsKey("properties");
    }

    // ─────────────── Helpers ───────────────

    /**
     * Calls a tool by name via its registered spec.
     */
    private McpSchema.CallToolResult callTool(String toolName, Map<String, Object> arguments) {
        var spec = specs.stream()
                .filter(s -> s.tool().name().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not found: " + toolName));

        var request = new McpSchema.CallToolRequest(toolName, arguments);
        return spec.callHandler().apply(null, request);
    }

    /**
     * Extracts text content for assertion chaining.
     */
    private static org.assertj.core.api.AbstractStringAssert<?> assertText(
            McpSchema.CallToolResult result) {
        assertThat(result.content()).isNotEmpty();
        String text = ((McpSchema.TextContent) result.content().getFirst()).text();
        return assertThat(text);
    }

    /**
     * Mock embedding provider for deterministic tests.
     */
    static class MockEmbeddingProvider implements EmbeddingProvider {
        @Override
        public EmbeddingResult embed(String text) {
            float[] vec = new float[4];
            int hash = text.hashCode();
            for (int i = 0; i < 4; i++) {
                vec[i] = ((hash >> (i * 8)) & 0xFF) / 255.0f;
            }
            float norm = 0;
            for (float v : vec) norm += v * v;
            norm = (float) Math.sqrt(norm);
            if (norm > 0) {
                for (int i = 0; i < vec.length; i++) vec[i] /= norm;
            }
            return new EmbeddingResult(vec, text.split("\\s+").length, "mock-embed");
        }

        @Override
        public int dimensions() { return 4; }

        @Override
        public String modelName() { return "mock-embed"; }
    }
}
