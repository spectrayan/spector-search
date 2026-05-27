package com.spectrayan.spector.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.engine.SpectorConfig;
import com.spectrayan.spector.engine.SpectorEngine;

/**
 * CLI entry point for the Spector MCP Server.
 *
 * <p>Starts an MCP server on stdio transport, allowing AI agents
 * (Claude Desktop, Cursor, etc.) to connect via JSON-RPC 2.0.</p>
 *
 * <h3>Usage</h3>
 * <pre>
 *   # Basic (384-dim, in-memory)
 *   java --add-modules jdk.incubator.vector -jar spector-mcp.jar
 *
 *   # Custom dimensions
 *   java --add-modules jdk.incubator.vector -jar spector-mcp.jar --dims 768
 *
 *   # With Ollama embedding provider
 *   java --add-modules jdk.incubator.vector -jar spector-mcp.jar --dims 768 --ollama-url http://localhost:11434 --ollama-model nomic-embed-text
 * </pre>
 *
 * <h3>Claude Desktop Configuration</h3>
 * <pre>{@code
 *   {
 *     "mcpServers": {
 *       "spector-memory": {
 *         "command": "java",
 *         "args": [
 *           "--add-modules", "jdk.incubator.vector",
 *           "--enable-native-access=ALL-UNNAMED",
 *           "--enable-preview",
 *           "-jar", "/path/to/spector-mcp.jar",
 *           "--dims", "768"
 *         ]
 *       }
 *     }
 *   }
 * }</pre>
 */
public class SpectorMcpMain {

    private static final Logger log = LoggerFactory.getLogger(SpectorMcpMain.class);

    public static void main(String[] args) {
        // ── Parse CLI arguments ──
        int dims = getIntArg(args, "--dims", 384);
        int capacity = getIntArg(args, "--capacity", 100_000);
        String ollamaUrl = getStringArg(args, "--ollama-url", null);
        String ollamaModel = getStringArg(args, "--ollama-model", null);

        // ── Handle --help ──
        if (hasFlag(args, "--help") || hasFlag(args, "-h")) {
            printHelp();
            return;
        }

        // ── Build the engine ──
        SpectorConfig config = SpectorConfig.DEFAULT
                .withDimensions(dims)
                .withCapacity(capacity);

        final SpectorEngine engine = buildEngine(config, ollamaUrl, ollamaModel);

        // ── Start the MCP server ──
        SpectorMcpServer server = new SpectorMcpServer(engine);

        // Graceful shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            engine.close();
            log.info("[Spector MCP] Shutdown complete");
        }));

        server.start();
    }

    // ─────────────── Engine Builder ───────────────

    private static SpectorEngine buildEngine(SpectorConfig config,
                                              String ollamaUrl, String ollamaModel) {
        if (ollamaUrl != null && ollamaModel != null) {
            try {
                // Dynamically load the Ollama embedding provider if available
                var providerClass = Class.forName(
                        "com.spectrayan.spector.embed.ollama.OllamaEmbeddingProvider");
                var constructor = providerClass.getConstructor(String.class, String.class);
                var provider = (com.spectrayan.spector.embed.EmbeddingProvider)
                        constructor.newInstance(ollamaUrl, ollamaModel);
                log.info("[Spector MCP] Embedding provider: {} ({})", ollamaModel, ollamaUrl);
                return new SpectorEngine(config, provider);
            } catch (ClassNotFoundException e) {
                log.warn("[Spector MCP] spector-embed-ollama not on classpath. "
                        + "Starting without embedding provider.");
            } catch (Exception e) {
                log.error("[Spector MCP] Failed to initialize embedding provider", e);
            }
        } else {
            log.info("[Spector MCP] No embedding provider configured. "
                    + "Semantic search will be unavailable. "
                    + "Use --ollama-url and --ollama-model to enable.");
        }
        return new SpectorEngine(config);
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
                
                Options:
                  --dims <N>             Vector dimensionality (default: 384)
                  --capacity <N>         Max document capacity (default: 100000)
                  --ollama-url <URL>     Ollama server URL (e.g., http://localhost:11434)
                  --ollama-model <NAME>  Ollama embedding model (e.g., nomic-embed-text)
                  --help, -h             Show this help message
                
                MCP Tools:
                  semantic_search     Semantic similarity search with auto-embedding
                  hybrid_search       Keyword (BM25) + vector hybrid search
                  rag_query           Retrieval-Augmented Generation with citations
                  ingest_document     Document ingestion with auto-embedding
                  delete_document     Document deletion by ID
                  engine_status       Engine status and capabilities
                
                Claude Desktop config example:
                  {
                    "mcpServers": {
                      "spector-memory": {
                        "command": "java",
                        "args": [
                          "--add-modules", "jdk.incubator.vector",
                          "--enable-native-access=ALL-UNNAMED",
                          "--enable-preview",
                          "-jar", "/path/to/spector-mcp.jar"
                        ]
                      }
                    }
                  }
                """);
    }
}
