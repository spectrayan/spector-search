package com.spectrayan.spector.ingestion;

import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.commons.config.SpectorConfigFactory;
import com.spectrayan.spector.commons.config.SpectorProperties;
import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.engine.SpectorConfig;
import com.spectrayan.spector.engine.SpectorEngine;

/**
 * CLI entry point for standalone file ingestion.
 *
 * <p>Uses {@link SpectorProperties} for configuration with CLI arg overrides.
 * Creates a {@link FileIngestionService} to discover files and a
 * {@link SpectorEngine} to ingest them.</p>
 *
 * <h3>Usage</h3>
 * <pre>
 *   # Ingest markdown files from current directory (config from spector.yml)
 *   java -jar spector-ingestion.jar
 *
 *   # Ingest from a specific directory with explicit config
 *   java -jar spector-ingestion.jar --root /path/to/project --config spector.yml
 *
 *   # Override file pattern to ingest Java files
 *   java -jar spector-ingestion.jar --root . --pattern "**\/*.java"
 *
 *   # Override embedding model
 *   java -jar spector-ingestion.jar --root . --ollama-model mxbai-embed-large
 * </pre>
 */
public class FileIngestionMain {

    private static final Logger log = LoggerFactory.getLogger(FileIngestionMain.class);

    public static void main(String[] args) throws Exception {
        if (hasFlag(args, "--help") || hasFlag(args, "-h")) {
            printHelp();
            return;
        }

        // ── Load configuration ──
        SpectorProperties.Builder propsBuilder = SpectorProperties.builder();

        String configFile = getArg(args, "--config", null);
        if (configFile != null) propsBuilder.configFile(Path.of(configFile));

        String profile = getArg(args, "--profile", null);
        if (profile != null) propsBuilder.profile(profile);

        // CLI overrides
        applyOverride(args, "--pattern", "spector.ingestion.file-pattern", propsBuilder);
        applyOverride(args, "--chunk-size", "spector.ingestion.chunk-size", propsBuilder);
        applyOverride(args, "--ollama-url", "spector.embedding.base-url", propsBuilder);
        applyOverride(args, "--ollama-model", "spector.embedding.model", propsBuilder);

        String dataDir = getArg(args, "--data-dir", null);
        if (dataDir != null) {
            propsBuilder.override("spector.engine.data-directory", dataDir);
            propsBuilder.override("spector.engine.persistence-mode", "DISK");
        }

        // CLI override for root-directory
        applyOverride(args, "--root", "spector.ingestion.root-directory", propsBuilder);

        SpectorProperties props = propsBuilder.build();

        // ── Root directory (from config, overridden by --root CLI flag) ──
        var ingestionConfig = SpectorConfigFactory.ingestionDefaults(props);
        Path rootPath = ingestionConfig.rootDirectory().toAbsolutePath().normalize();

        // ── Create discovery service ──
        FileIngestionService service = FileIngestionService.fromProperties(props, rootPath);

        // ── Print banner ──
        var embedConfig = SpectorConfigFactory.embeddingDefaults(props);
        var engineConfig = SpectorConfigFactory.engineDefaults(props);

        System.out.printf("═══════════════════════════════════════════════════%n");
        System.out.printf("  Spector File Ingestion%n");
        System.out.printf("  Root:    %s%n", rootPath);
        System.out.printf("  Pattern: %s%n", service.filePattern());
        System.out.printf("  Data:    %s%n", engineConfig.dataDirectory());
        System.out.printf("  Model:   %s @ %s%n", embedConfig.model(), embedConfig.baseUrl());
        System.out.printf("  Chunk:   %d chars%n", service.chunkSize());
        System.out.printf("═══════════════════════════════════════════════════%n%n");

        // ── Discover files ──
        var files = service.discover();
        System.out.printf("[Discovery] Found %d files%n%n", files.size());
        if (files.isEmpty()) {
            System.err.println("No matching files found. Exiting.");
            return;
        }

        // ── Create embedding provider ──
        EmbeddingProvider embedder = EmbeddingProviderFactory.create(
                embedConfig.baseUrl(), embedConfig.model());
        int dims = embedder.embed("probe").dimensions();
        System.out.printf("[Embedding] Dimensions: %d%n%n", dims);

        // ── Build engine with probed dimensions ──
        propsBuilder.override("spector.engine.dimensions", String.valueOf(dims));
        props = propsBuilder.build();
        SpectorConfig config = SpectorConfig.from(props);

        try (SpectorEngine engine = new SpectorEngine(config, embedder)) {
            int fileCount = 0;
            int totalChunks = 0;
            long startMs = System.currentTimeMillis();

            for (Path file : files) {
                String relativePath = rootPath.relativize(file).toString().replace('\\', '/');
                try {
                    String content = Files.readString(file);
                    if (content.isBlank()) continue;

                    fileCount++;
                    String title = FileIngestionService.extractTitle(content, relativePath);
                    long fileStart = System.currentTimeMillis();

                    if (content.length() <= service.chunkSize()) {
                        engine.ingest(relativePath, title, content);
                        totalChunks++;
                        System.out.printf("  [%3d] %-60s  1 chunk   %4dms%n",
                                fileCount, relativePath, System.currentTimeMillis() - fileStart);
                    } else {
                        int chunks = engine.ingestChunkedAuto(relativePath, content);
                        totalChunks += chunks;
                        System.out.printf("  [%3d] %-60s  %d chunks  %4dms%n",
                                fileCount, relativePath, chunks, System.currentTimeMillis() - fileStart);
                    }
                } catch (Exception e) {
                    log.error("Failed to ingest '{}': {}", relativePath, e.getMessage());
                }
            }

            long elapsed = System.currentTimeMillis() - startMs;
            System.out.printf("%n═══════════════════════════════════════════════════%n");
            System.out.printf("  Ingestion Complete%n");
            System.out.printf("  Files:   %d%n", fileCount);
            System.out.printf("  Chunks:  %d%n", totalChunks);
            System.out.printf("  Docs:    %d (in engine)%n", engine.documentCount());
            System.out.printf("  Time:    %dms%n", elapsed);
            System.out.printf("═══════════════════════════════════════════════════%n");
        }
    }

    // ─────────────── CLI Helpers ───────────────

    private static void applyOverride(String[] args, String cliFlag, String propKey,
                                       SpectorProperties.Builder builder) {
        String val = getArg(args, cliFlag, null);
        if (val != null) builder.override(propKey, val);
    }

    private static String getArg(String[] args, String name, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) return args[i + 1];
        }
        return defaultValue;
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (flag.equals(arg)) return true;
        }
        return false;
    }

    private static void printHelp() {
        System.err.println("""
                ⚡ Spector File Ingestion — Bulk Document Ingestion
                
                Usage:
                  java -jar spector-ingestion.jar [options]
                
                Configuration:
                  --config <FILE>        Config file (YAML or .properties)
                  --profile <NAME>       Active profile (loads spector-{profile}.yml)
                
                Options:
                  --root <DIR>           Root directory to ingest from (default: .)
                  --pattern <GLOB>       File pattern (default: **/*.md)
                  --chunk-size <N>       Chunk size in characters (default: 800)
                  --data-dir <PATH>      Data directory (enables DISK persistence)
                  --ollama-url <URL>     Ollama server URL
                  --ollama-model <NAME>  Ollama embedding model
                  --help, -h             Show this help
                """);
    }
}
