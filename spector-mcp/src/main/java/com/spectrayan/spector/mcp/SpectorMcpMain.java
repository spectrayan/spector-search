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
package com.spectrayan.spector.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.config.SpectorProperties;
import com.spectrayan.spector.config.SpectorConfigFactory;
import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.ingestion.EmbeddingProviderFactory;
import com.spectrayan.spector.runtime.SpectorRuntime;

/**
 * CLI entry point for the Spector MCP Server.
 *
 * <p>Starts an MCP server on stdio transport, allowing AI agents
 * (Claude Desktop, Cursor, etc.) to connect via JSON-RPC 2.0.</p>
 *
 * <h3>Configuration Hierarchy (highest priority wins)</h3>
 * <ol>
 *   <li>CLI arguments ({@code --dims 768})</li>
 *   <li>System properties ({@code -Dspector.engine.dimensions=768})</li>
 *   <li>Environment variables ({@code SPECTOR_ENGINE_DIMENSIONS=768})</li>
 *   <li>Profile config file ({@code spector-{profile}.yml})</li>
 *   <li>User config file ({@code spector.yml} in working directory)</li>
 *   <li>{@code --config /path/to/config.yml} (explicit file)</li>
 *   <li>Classpath defaults ({@code spector-defaults.yml} in JAR)</li>
 * </ol>
 *
 * <h3>Usage</h3>
 * <pre>
 *   # With config file (all settings from YAML)
 *   java --add-modules jdk.incubator.vector -jar spector-mcp.jar --config spector.yml
 *
 *   # CLI overrides on top of config file
 *   java --add-modules jdk.incubator.vector -jar spector-mcp.jar --dims 768 --ollama-model qwen3-embedding
 *
 *   # Minimal (all defaults from spector-defaults.yml)
 *   java --add-modules jdk.incubator.vector -jar spector-mcp.jar
 * </pre>
 */
public class SpectorMcpMain {

    private static final Logger log = LoggerFactory.getLogger(SpectorMcpMain.class);

    public static void main(String[] args) {
        // ── Handle --help ──
        if (hasFlag(args, "--help") || hasFlag(args, "-h")) {
            printHelp();
            return;
        }

        // ── Load hierarchical configuration ──
        SpectorProperties.Builder propsBuilder = SpectorProperties.builder();

        // Explicit config file
        String configFile = getStringArg(args, "--config", null);
        if (configFile != null) {
            propsBuilder.configFile(java.nio.file.Path.of(configFile));
        }

        // Profile
        String profile = getStringArg(args, "--profile", null);
        if (profile != null) {
            propsBuilder.profile(profile);
        }

        // CLI args as overrides (highest priority after system props / env vars)
        String cliDims = getStringArg(args, "--dims", null);
        if (cliDims != null) propsBuilder.override("spector.engine.dimensions", cliDims);

        String cliCapacity = getStringArg(args, "--capacity", null);
        if (cliCapacity != null) propsBuilder.override("spector.engine.capacity", cliCapacity);

        String cliOllamaUrl = getStringArg(args, "--ollama-url", null);
        if (cliOllamaUrl != null) propsBuilder.override("spector.embedding.base-url", cliOllamaUrl);

        String cliOllamaModel = getStringArg(args, "--ollama-model", null);
        if (cliOllamaModel != null) propsBuilder.override("spector.embedding.model", cliOllamaModel);

        String cliDataDir = getStringArg(args, "--data-dir", null);
        if (cliDataDir != null) {
            propsBuilder.override("spector.engine.data-directory", cliDataDir);
            propsBuilder.override("spector.engine.persistence-mode", "DISK");
        }

        SpectorProperties props = propsBuilder.build();

        // ── Create embedding provider ──
        var embedDefaults = SpectorConfigFactory.embeddingDefaults(props);
        EmbeddingProvider embedder = EmbeddingProviderFactory.create(
                embedDefaults.baseUrl(), embedDefaults.model());
        log.info("[Spector MCP] Embedding: {} @ {}", embedDefaults.model(), embedDefaults.baseUrl());

        // ── Create runtime (engine + optional memory) ──
        SpectorRuntime runtime = SpectorRuntime.from(props, embedder);

        // ── Start the MCP server ──
        SpectorMcpServer server = new SpectorMcpServer(runtime);

        // Graceful shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            runtime.close();
            log.info("[Spector MCP] Shutdown complete");
        }));

        server.start();
    }



    // ─────────────── CLI Parsing Helpers ───────────────

    private static String getStringArg(String[] args, String name, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                return args[i + 1];
            }
        }
        return defaultValue;
    }

    private static int getIntArg(String[] args, String name, int defaultValue) {
        String val = getStringArg(args, name, null);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (flag.equals(arg)) return true;
        }
        return false;
    }

    private static void printHelp() {
        System.err.println("""
                ⚡ Spector MCP Server — AI-Native Memory Backbone
                
                Usage:
                  java --add-modules jdk.incubator.vector -jar spector-mcp.jar [options]
                
                Configuration:
                  --config <FILE>        Explicit config file (YAML or .properties)
                  --profile <NAME>       Active profile (loads spector-{profile}.yml)
                
                Override Options (highest priority):
                  --dims <N>             Vector dimensionality
                  --capacity <N>         Max document capacity
                  --data-dir <PATH>      Data directory (enables DISK persistence)
                  --ollama-url <URL>     Ollama server URL
                  --ollama-model <NAME>  Ollama embedding model
                  --help, -h             Show this help message
                
                Config Hierarchy (highest priority wins):
                  1. CLI arguments (--dims, --capacity, etc.)
                  2. System properties (-Dspector.engine.dimensions=768)
                  3. Environment variables (SPECTOR_ENGINE_DIMENSIONS=768)
                  4. spector-{profile}.yml (profile-specific)
                  5. spector.yml (working directory)
                  6. spector-defaults.yml (bundled in JAR)
                
                MCP Tools:
                  semantic_search     Semantic similarity search with auto-embedding
                  hybrid_search       Keyword (BM25) + vector hybrid search
                  rag_query           Retrieval-Augmented Generation with citations
                  ingest_document     Document ingestion with auto-embedding
                  delete_document     Document deletion by ID
                  engine_status       Engine status and capabilities
                """);
    }
}
