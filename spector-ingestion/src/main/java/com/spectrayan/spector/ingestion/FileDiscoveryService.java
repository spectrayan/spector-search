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
 * File discovery service — finds files matching patterns in a directory tree.
 *
 * <p>This is a pure utility service that discovers files without performing
 * ingestion. It reads configuration from {@link SpectorProperties} and
 * provides the file list to be ingested via {@link IngestionPipeline}.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   var discovery = FileDiscoveryService.fromProperties(props, rootDir);
 *   List<Path> files = discovery.discover();
 *
 *   for (Path file : files) {
 *       pipeline.ingest(file, file.getFileName().toString());
 *   }
 * }</pre>
 */
public class FileDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(FileDiscoveryService.class);

    private final Path rootDirectory;
    private final String filePattern;
    private final Set<String> skipDirs;
    private final int chunkSize;
    private final int chunkOverlap;

    private FileDiscoveryService(Builder builder) {
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
     * @param rootDir the root directory to discover files from
     * @return configured file discovery service
     */
    public static FileDiscoveryService fromProperties(SpectorProperties props, Path rootDir) {
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
        Set<String> extensions = extractExtensions(filePattern);

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
                if (matchesPattern(file, extensions)) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        files.sort(Path::compareTo);
        log.info("Discovered {} files matching '{}' in {}", files.size(), filePattern, rootDirectory);
        return files;
    }

    // ─────────────── Accessors ───────────────

    /** Returns the root directory. */
    public Path rootDirectory() { return rootDirectory; }

    /** Returns the file pattern. */
    public String filePattern() { return filePattern; }

    /** Returns the chunk size. */
    public int chunkSize() { return chunkSize; }

    /** Returns the chunk overlap. */
    public int chunkOverlap() { return chunkOverlap; }

    // ─────────────── Utilities ───────────────

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

    /**
     * Extracts all file extensions from a comma-separated glob pattern.
     *
     * <p>For example, {@code "**&#47;*.md,**&#47;*.txt"} returns {@code [".md", ".txt"]}.
     * Returns an empty set if no extensions can be extracted.</p>
     */
    private static Set<String> extractExtensions(String pattern) {
        var extensions = new java.util.HashSet<String>();
        for (String part : pattern.split(",")) {
            String trimmed = part.trim();
            int lastDot = trimmed.lastIndexOf('.');
            if (lastDot >= 0) {
                extensions.add(trimmed.substring(lastDot));
            }
        }
        return extensions;
    }

    private static boolean matchesPattern(Path file, Set<String> extensions) {
        if (extensions.isEmpty()) return true;
        String fileName = file.getFileName().toString();
        for (String ext : extensions) {
            if (fileName.endsWith(ext)) return true;
        }
        return false;
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
        public FileDiscoveryService build() { return new FileDiscoveryService(this); }
    }
}
