package com.spectrayan.spector.cli;

import com.spectrayan.spector.client.SpectorClient;
import com.spectrayan.spector.client.SpectorClientException;
import com.spectrayan.spector.client.SpectorConnectionException;
import com.spectrayan.spector.client.model.IngestRequest;
import com.spectrayan.spector.client.model.IngestResponse;
import com.spectrayan.spector.config.SpectorConfigFactory;
import com.spectrayan.spector.config.SpectorProperties;
import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.ingestion.EmbeddingProviderFactory;
import com.spectrayan.spector.runtime.IngestionHandler;
import com.spectrayan.spector.runtime.SpectorRuntime;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ingest documents into Spector Search.
 *
 * <p>Supports two modes, auto-detected from the flags provided:</p>
 * <ul>
 *   <li><strong>Remote</strong> — {@code --content} or {@code --file}: sends a single
 *       document to a running Spector server via HTTP.</li>
 *   <li><strong>Local batch</strong> — {@code --root}: discovers and ingests files
 *       locally through {@link SpectorRuntime}, honoring {@code spector.yml} config.</li>
 * </ul>
 *
 * <h3>Examples</h3>
 * <pre>
 *   spectorctl ingest --content "Hello world"             # remote
 *   spectorctl ingest --file README.md                    # remote
 *   spectorctl ingest --root /docs --pattern "**\/*.md"   # local batch
 *   spectorctl ingest --root . --config spector.yml       # local batch
 * </pre>
 */
@Command(
        name = "ingest",
        description = "Ingest documents into Spector Search (remote or local batch).",
        mixinStandardHelpOptions = true
)
class IngestCommand extends BaseCommand {

    // ── Remote mode options ──
    @CommandLine.Option(names = {"--id"}, description = "Document ID (auto-generated if not provided).")
    private String documentId;

    @CommandLine.Option(names = {"--title"}, description = "Document title.")
    private String title;

    @CommandLine.Option(names = {"--content"}, description = "Document content (text). Remote mode.")
    private String content;

    @CommandLine.Option(names = {"--file"}, description = "Path to file to ingest. Remote mode.")
    private Path file;

    // ── Local batch mode options ──
    @CommandLine.Option(names = {"--root"}, description = "Root directory for local batch ingestion.")
    private Path rootDir;

    @CommandLine.Option(names = {"--pattern"}, description = "File glob pattern (default from config).")
    private String pattern;

    @CommandLine.Option(names = {"--chunk-size"}, description = "Chunk size in characters (default from config).")
    private Integer chunkSize;

    @CommandLine.Option(names = {"--config"}, description = "Path to spector.yml config file.")
    private Path configFile;

    @Override
    public void run() {
        if (rootDir != null) {
            runLocalBatch();
        } else if (configFile != null) {
            // Config provided — check if it has a root-directory for local batch
            var props = SpectorProperties.builder().configFile(configFile).build();
            var ingestionConfig = SpectorConfigFactory.ingestionDefaults(props);
            if (ingestionConfig.rootDirectory() != null) {
                rootDir = ingestionConfig.rootDirectory();
                runLocalBatch();
            } else {
                runRemote();
            }
        } else if (content != null || file != null) {
            runRemote();
        } else {
            err().println("Error: Provide --content, --file, or --root (or --config with root-directory).");
            spec.commandLine().usage(err());
        }
    }

    // ─────────────── Local Batch Mode ───────────────

    private void runLocalBatch() {
        // ── Build config from spector.yml + CLI overrides ──
        SpectorProperties.Builder propsBuilder = SpectorProperties.builder();

        if (configFile != null) propsBuilder.configFile(configFile);
        if (pattern != null)
            propsBuilder.override("spector.ingestion.file-pattern", pattern);
        if (chunkSize != null)
            propsBuilder.override("spector.ingestion.chunk-size", chunkSize.toString());

        if (rootDir != null)
            propsBuilder.override("spector.ingestion.root-directory", rootDir.toString());

        SpectorProperties props = propsBuilder.build();

        // ── Read configs ──
        var ingestionConfig = SpectorConfigFactory.ingestionDefaults(props);
        var embedConfig = SpectorConfigFactory.embeddingDefaults(props);
        var engineConfig = SpectorConfigFactory.engineDefaults(props);
        var mode = SpectorConfigFactory.mode(props);
        Path root = ingestionConfig.rootDirectory().toAbsolutePath().normalize();

        // ── Banner ──
        out().printf("===================================================%n");
        out().printf("  Spector Ingestion (local batch)%n");
        out().printf("  Mode:    %s%n", mode);
        out().printf("  Root:    %s%n", root);
        out().printf("  Pattern: %s%n", ingestionConfig.filePattern());
        out().printf("  Data:    %s%n", engineConfig.dataDirectory());
        out().printf("  Model:   %s @ %s%n", embedConfig.model(), embedConfig.baseUrl());
        out().printf("  Chunk:   %d chars%n", ingestionConfig.chunkSize());
        out().printf("  Threads: %d parallel, %d retries (delay: %dms)%n",
                ingestionConfig.parallelism(), ingestionConfig.maxRetries(),
                ingestionConfig.retryDelayMs());
        out().printf("===================================================%n%n");

        // ── Create embedder + probe dims ──
        EmbeddingProvider embedder = EmbeddingProviderFactory.create(
                embedConfig.baseUrl(), embedConfig.model());
        int dims = embedder.embed("probe").dimensions();
        out().printf("[Embedding] Dimensions: %d%n%n", dims);

        propsBuilder.override("spector.engine.dimensions", String.valueOf(dims));
        propsBuilder.override("spector.memory.dimensions", String.valueOf(dims));
        props = propsBuilder.build();

        // ── Create runtime + ingest ──
        try (SpectorRuntime runtime = SpectorRuntime.from(props, embedder, true)) {
            long startMs = System.currentTimeMillis();

            var results = runtime.ingestion().ingest(
                    root,
                    ingestionConfig.filePattern(),
                    ingestionConfig.chunkSize(),
                    ingestionConfig.chunkOverlap(),
                    ingestionConfig.skipDirs(),
                    new IngestionHandler.IngestionProgress() {
                        @Override
                        public void onFileStart(int fileIndex, int totalFiles, String relativePath) {
                            out().printf("  [%d/%d] > %s ...%n", fileIndex, totalFiles, relativePath);
                            out().flush();
                        }

                        @Override
                        public void onFile(int fileIdx, int total, String path,
                                           int chunks, long ms, String error) {
                            if (error != null) {
                                out().printf("  [%d/%d] X %s -- FAILED (%dms): %s%n",
                                        fileIdx, total, path, ms, error);
                            } else {
                                out().printf("  [%d/%d] OK %s -- %d chunk%s, %dms%n",
                                        fileIdx, total, path, chunks,
                                        chunks == 1 ? "" : "s", ms);
                            }
                            out().flush();
                        }
                    },
                    ingestionConfig.parallelism(),
                    ingestionConfig.maxRetries(),
                    ingestionConfig.retryDelayMs());

            int files = results.size();
            int chunks = results.stream().mapToInt(r -> r.chunksStored()).sum();
            int failures = (int) results.stream().filter(r -> !r.isFullSuccess()).count();
            long elapsed = System.currentTimeMillis() - startMs;

            out().printf("%n===================================================%n");
            out().printf("  Ingestion Complete%n");
            out().printf("  Mode:     %s%n", runtime.mode());
            out().printf("  Files:    %d%n", files);
            out().printf("  Chunks:   %d%n", chunks);
            out().printf("  Failures: %d%n", failures);
            out().printf("  Docs:     %d (in %s)%n", runtime.ingestion().count(),
                    runtime.mode().name().toLowerCase());
            out().printf("  Time:     %dms%n", elapsed);
            out().printf("===================================================%n");
        }
    }

    // ─────────────── Remote Mode ───────────────

    private void runRemote() {
        String text = resolveContent();
        if (text == null) {
            err().println("Error: Provide --content, --file, or --root.");
            spec.commandLine().usage(err());
            return;
        }

        try (var client = createClient()) {
            IngestRequest request = new IngestRequest();
            request.setId(documentId);
            request.setTitle(title);
            request.setContent(text);

            IngestResponse response = client.ingest(request);

            if (isJson()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("id", response.getId());
                result.put("indexed", response.isIndexed());
                result.put("autoEmbedded", response.isAutoEmbedded());
                OutputFormatter.printJson(out(), result);
            } else {
                out().println("Document ingested successfully.");
                out().println("  ID:            " + response.getId());
                out().println("  Indexed:       " + response.isIndexed());
                out().println("  Auto-Embedded: " + response.isAutoEmbedded());
            }
        } catch (SpectorConnectionException e) {
            handleConnectionError(e);
        } catch (SpectorClientException e) {
            err().println("Error: " + e.getMessage());
        }
    }

    private String resolveContent() {
        if (content != null && !content.isBlank()) {
            return content;
        }
        if (file != null) {
            try {
                return Files.readString(file);
            } catch (IOException e) {
                err().println("Error: Cannot read file '" + file + "': " + e.getMessage());
                return null;
            }
        }
        return null;
    }
}
