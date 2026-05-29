package com.spectrayan.spector.ingestion;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.config.SpectorConfigFactory;
import com.spectrayan.spector.config.SpectorProperties;

/**
 * Generic file ingestion service that discovers and ingests files from a directory tree.
 *
 * <p>Unlike the MCP-specific {@code IngestMarkdownMain}, this service is:
 * <ul>
 *   <li>Format-agnostic — any file extension via configurable glob patterns</li>
 *   <li>Reusable — callable from MCP server, REST API, or Java CLI</li>
 *   <li>Configurable — reads from {@link SpectorProperties} for file pattern, skip dirs, chunk sizes</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   // From SpectorProperties (reads spector.yml)
 *   var service = FileIngestionService.fromProperties(SpectorProperties.load(), rootDir);
 *
 *   // Discover files
 *   List<Path> files = service.discover();
 *
 *   // Ingest into an engine
 *   FileIngestionResult result = service.ingest(engine);
 *
 *   // With progress reporting
 *   FileIngestionResult result = service.ingest(engine, (idx, path) ->
 *       System.out.printf("[%d] %s%n", idx, path));
 * }</pre>
 */
public class FileIngestionService {

    private static final Logger log = LoggerFactory.getLogger(FileIngestionService.class);

    private final Path rootDirectory;
    private final String filePattern;
    private final Set<String> skipDirs;
    private final int chunkSize;
    private final int chunkOverlap;

    private FileIngestionService(Builder builder) {
        this.rootDirectory = builder.rootDirectory.toAbsolutePath().normalize();
        this.filePattern = builder.filePattern;
        this.skipDirs = Set.copyOf(builder.skipDirs);
        this.chunkSize = builder.chunkSize;
        this.chunkOverlap = builder.chunkOverlap;
    }

    // ─────────────── Factory Methods ───────────────

    /**
     * Creates a service from hierarchical properties.
     *
     * @param props   configuration properties
     * @param rootDir the root directory to ingest from
     * @return configured file ingestion service
     */
    public static FileIngestionService fromProperties(SpectorProperties props, Path rootDir) {
        var ingestion = SpectorConfigFactory.ingestionDefaults(props);
        return builder()
                .rootDirectory(rootDir)
                .filePattern(ingestion.filePattern())
                .skipDirs(ingestion.skipDirs().split(","))
                .chunkSize(ingestion.chunkSize())
                .chunkOverlap(ingestion.chunkOverlap())
                .build();
    }

    /** Creates a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    // ─────────────── Discovery ───────────────

    /**
     * Discovers files matching the configured pattern in the root directory.
     *
     * @return sorted list of matching file paths
     * @throws IOException if directory traversal fails
     */
    public List<Path> discover() throws IOException {
        List<Path> files = new ArrayList<>();
        String extension = extractExtension(filePattern);

        Files.walkFileTree(rootDirectory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String dirName = dir.getFileName().toString();
                if (skipDirs.contains(dirName)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (matchesPattern(file, extension)) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        files.sort(Path::compareTo);
        log.info("Discovered {} files matching '{}' in {}", files.size(), filePattern, rootDirectory);
        return files;
    }

    // ─────────────── Ingestion ───────────────

    /**
     * Result of a file ingestion batch.
     *
     * @param filesProcessed number of files successfully processed
     * @param totalChunks    total chunks ingested across all files
     * @param failures       list of file paths that failed
     * @param elapsedMs      total elapsed time in milliseconds
     */
    public record FileIngestionResult(
            int filesProcessed, int totalChunks,
            List<String> failures, long elapsedMs
    ) {}


    // ─────────────── Accessors ───────────────

    /** Returns the root directory. */
    public Path rootDirectory() { return rootDirectory; }

    /** Returns the file pattern. */
    public String filePattern() { return filePattern; }

    /** Returns the chunk size. */
    public int chunkSize() { return chunkSize; }

    // ─────────────── Internal ───────────────

    /**
     * Extracts a title from the first heading in the content, or uses the filename as fallback.
     */
    public static String extractTitle(String content, String fallback) {
        for (String line : content.split("\n", 10)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                return trimmed.substring(2).trim();
            }
        }
        int lastDot = fallback.lastIndexOf('.');
        return (lastDot > 0 ? fallback.substring(0, lastDot) : fallback)
                .replace('/', ' ')
                .replace('\\', ' ');
    }

    private static String extractExtension(String pattern) {
        int lastDot = pattern.lastIndexOf('.');
        return lastDot >= 0 ? pattern.substring(lastDot) : "";
    }

    private static boolean matchesPattern(Path file, String extension) {
        if (extension.isEmpty()) return true;
        return file.getFileName().toString().endsWith(extension);
    }

    // ─────────────── Builder ───────────────

    public static class Builder {
        private Path rootDirectory = Path.of(".");
        private String filePattern = "**/*.md";
        private List<String> skipDirs = List.of(".git", ".idea", ".mvn", "target", "node_modules", ".github");
        private int chunkSize = 800;
        private int chunkOverlap = 100;

        public Builder rootDirectory(Path rootDirectory) { this.rootDirectory = rootDirectory; return this; }
        public Builder filePattern(String filePattern) { this.filePattern = filePattern; return this; }
        public Builder skipDirs(String... dirs) { this.skipDirs = Arrays.asList(dirs); return this; }
        public Builder skipDirs(List<String> dirs) { this.skipDirs = dirs; return this; }
        public Builder chunkSize(int chunkSize) { this.chunkSize = chunkSize; return this; }
        public Builder chunkOverlap(int chunkOverlap) { this.chunkOverlap = chunkOverlap; return this; }
        public FileIngestionService build() { return new FileIngestionService(this); }
    }
}
