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
package com.spectrayan.spector.mcp.util;

import java.util.LinkedHashMap;
import java.util.Map;

import com.spectrayan.spector.core.simd.SimdCapability;
import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.query.SearchResponse;

/**
 * Shared formatting utilities for MCP tool and resource responses.
 *
 * <p>Centralizes all text and structured-data formatting that was previously
 * scattered across {@code SpectorMcpServer} and {@code SpectorToolProvider}.
 * Methods are stateless, thread-safe, and designed for zero-allocation
 * reuse across concurrent virtual-thread handlers.</p>
 */
public final class ResultFormatter {

    /** Maximum content length before truncation in search result summaries. */
    private static final int CONTENT_TRUNCATION_LIMIT = 500;

    /** Truncation suffix appended when content exceeds the limit. */
    private static final String TRUNCATION_SUFFIX = "...";

    private ResultFormatter() {} // static utility

    // ═══════════════════════════════════════════════════════════════
    //  Search Results
    // ═══════════════════════════════════════════════════════════════

    /**
     * Formats search results for LLM consumption with score and truncated content.
     *
     * @param response the search response from the engine
     * @param engine   the engine instance (for document store lookups)
     * @return formatted text suitable for MCP tool responses
     */
    public static String formatSearchResults(SearchResponse response, SpectorEngine engine) {
        if (response.results() == null || response.results().length == 0) {
            return "No results found.";
        }

        var sb = new StringBuilder(1024);
        sb.append("Found ").append(response.results().length)
          .append(" results in ").append(response.queryTimeMs()).append("ms:\n\n");

        for (ScoredResult r : response.results()) {
            sb.append('[').append(r.id()).append("] (score: ");
            appendScore(sb, r.score());
            sb.append(')');

            var doc = engine.documentStore().get(r.id());
            if (doc != null && doc.content() != null) {
                sb.append('\n');
                appendTruncated(sb, doc.content(), CONTENT_TRUNCATION_LIMIT);
            }
            sb.append("\n\n");
        }

        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    //  RAG Context
    // ═══════════════════════════════════════════════════════════════

    /**
     * Formats search results as RAG context with source attributions.
     *
     * @param response the search response
     * @param engine   the engine instance
     * @return formatted context block with source citations
     */
    public static String formatRagContext(SearchResponse response, SpectorEngine engine) {
        if (response.results() == null || response.results().length == 0) {
            return "No relevant context found for this query.";
        }

        var sb = new StringBuilder(2048);
        sb.append("--- RETRIEVED CONTEXT ---\n\n");
        int sourceIdx = 0;

        for (ScoredResult r : response.results()) {
            var doc = engine.documentStore().get(r.id());
            if (doc != null && doc.content() != null) {
                sourceIdx++;
                sb.append("[Source ").append(sourceIdx).append(": ").append(r.id())
                  .append(" (relevance: ");
                appendScore(sb, r.score());
                sb.append(")]\n");
                sb.append(doc.content());
                sb.append("\n\n");
            }
        }

        sb.append("--- END CONTEXT ---");
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Engine Status
    // ═══════════════════════════════════════════════════════════════

    /**
     * Builds a structured map of engine status fields.
     *
     * <p>Returns a {@code Map<String, Object>} that can be serialized
     * to JSON via Jackson or formatted as text — no {@code String.format}
     * JSON construction.</p>
     *
     * @param engine  the engine instance
     * @param version the server version string
     * @return ordered map of status fields
     */
    public static Map<String, Object> buildEngineStatusMap(SpectorEngine engine, String version) {
        var status = new LinkedHashMap<String, Object>(12);
        status.put("engine", "spector");
        status.put("version", version);
        status.put("documents", engine.documentCount());
        status.put("dimensions", engine.config().dimensions());
        status.put("similarity", engine.config().similarityFunction().name());
        status.put("indexType", engine.config().indexType().name());
        status.put("quantization", engine.config().quantization().name());
        status.put("gpu", engine.isGpuActive() ? "active" : "inactive");
        status.put("reranker", engine.isRerankerActive() ? "active" : "disabled");
        status.put("embedding", engine.hasEmbeddingProvider()
                ? engine.embeddingProvider().modelName() : "none");
        status.put("simd", SimdCapability.report());
        return status;
    }

    /**
     * Formats engine status as human-readable text for tool responses.
     *
     * @param engine  the engine instance
     * @param version the server version string
     * @return formatted status text
     */
    public static String formatEngineStatus(SpectorEngine engine, String version) {
        Map<String, Object> status = buildEngineStatusMap(engine, version);

        var sb = new StringBuilder(512);
        sb.append("Spector Engine Status:\n");
        sb.append("─────────────────────────────\n");
        for (var entry : status.entrySet()) {
            sb.append(String.format("%-15s %s%n",
                    capitalize(entry.getKey()) + ":", entry.getValue()));
        }
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Timing Footer
    // ═══════════════════════════════════════════════════════════════

    /**
     * Appends a timing footer to a result string.
     *
     * @param text      the result text
     * @param label     operation label (e.g., "Spector SIMD search")
     * @param elapsedMs elapsed time in milliseconds
     * @return text with timing footer appended
     */
    public static String withTimingFooter(String text, String label, long elapsedMs) {
        return text + "\n[" + label + " completed in " + elapsedMs + "ms]";
    }

    // ═══════════════════════════════════════════════════════════════
    //  Internal Helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Appends a float score formatted to 4 decimal places without
     * creating an intermediate String via String.format.
     */
    private static void appendScore(StringBuilder sb, float score) {
        // Manual formatting avoids String.format overhead on hot path
        int intPart = (int) score;
        int fracPart = Math.round((score - intPart) * 10_000);
        sb.append(intPart).append('.');
        if (fracPart < 1000) sb.append('0');
        if (fracPart < 100) sb.append('0');
        if (fracPart < 10) sb.append('0');
        sb.append(fracPart);
    }

    /**
     * Appends content to a StringBuilder, truncating if longer than maxLength.
     */
    private static void appendTruncated(StringBuilder sb, String content, int maxLength) {
        if (content.length() <= maxLength) {
            sb.append(content);
        } else {
            sb.append(content, 0, maxLength).append(TRUNCATION_SUFFIX);
        }
    }

    /**
     * Capitalizes the first letter of a camelCase key for display.
     * "indexType" → "IndexType", "gpu" → "Gpu"
     */
    private static String capitalize(String key) {
        if (key == null || key.isEmpty()) return key;
        return Character.toUpperCase(key.charAt(0)) + key.substring(1);
    }
}
