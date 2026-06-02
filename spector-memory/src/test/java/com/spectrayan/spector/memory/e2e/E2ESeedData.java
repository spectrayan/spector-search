/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://github.com/spectrayan/spector/blob/main/spector-memory/LICENSE
 *
 * Change Date: May 27, 2030
 * Change License: Apache License, Version 2.0
 */
package com.spectrayan.spector.memory.e2e;

import com.spectrayan.spector.memory.MemoryType;
import com.spectrayan.spector.memory.cortex.MemorySource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Loads seed memory data from markdown files in {@code src/test/resources/e2e/memories/}.
 *
 * <h3>Markdown Format</h3>
 * <p>Each memory is separated by a YAML-like front matter block between {@code ---} delimiters:</p>
 * <pre>
 * ---
 * id: db-001
 * type: EPISODIC
 * source: OBSERVED
 * tags: database, postgresql
 * valence: -20
 * ---
 * The memory text content goes here.
 * </pre>
 *
 * <p>Lines starting with {@code #} at the file level are comments and are ignored.
 * Files are loaded from the classpath, so new seed data files can be added to
 * {@code src/test/resources/e2e/memories/} without any code changes.</p>
 */
public final class E2ESeedData {

    private static final Logger log = LoggerFactory.getLogger(E2ESeedData.class);

    /** Classpath directory containing seed memory markdown files. */
    private static final String MEMORIES_DIR = "e2e/memories";

    private E2ESeedData() {}

    /**
     * A single seed memory parsed from a markdown file.
     *
     * @param id      unique memory identifier
     * @param text    memory content text
     * @param type    cognitive memory tier
     * @param source  provenance source
     * @param tags    synaptic tag labels
     * @param valence emotional valence (-128 to +127)
     * @param file    source filename for diagnostics
     */
    public record SeedMemory(
            String id,
            String text,
            MemoryType type,
            MemorySource source,
            String[] tags,
            byte valence,
            String file
    ) {}

    /**
     * Loads all seed memories from markdown files in the {@code e2e/memories/} classpath directory.
     *
     * @return list of parsed seed memories, ordered by file name then declaration order
     * @throws RuntimeException if the resource directory cannot be read
     */
    public static List<SeedMemory> loadAll() {
        List<SeedMemory> allMemories = new ArrayList<>();

        try {
            List<String> filenames = discoverMemoryFiles();
            Collections.sort(filenames); // alphabetical order

            for (String filename : filenames) {
                String resourcePath = MEMORIES_DIR + "/" + filename;
                List<SeedMemory> parsed = parseMarkdownFile(resourcePath, filename);
                allMemories.addAll(parsed);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load E2E seed data from classpath: " + MEMORIES_DIR, e);
        }

        log.info("Loaded {} seed memories from {} files", allMemories.size(),
                allMemories.stream().map(SeedMemory::file).distinct().count());
        return allMemories;
    }

    /**
     * Loads seed memories of a specific type.
     */
    public static List<SeedMemory> loadByType(MemoryType type) {
        return loadAll().stream()
                .filter(m -> m.type() == type)
                .toList();
    }

    /**
     * Discovers all .md files in the e2e/memories classpath directory.
     */
    private static List<String> discoverMemoryFiles() throws IOException, URISyntaxException {
        List<String> filenames = new ArrayList<>();
        URL dirUrl = E2ESeedData.class.getClassLoader().getResource(MEMORIES_DIR);
        if (dirUrl == null) {
            throw new IOException("Resource directory not found: " + MEMORIES_DIR);
        }

        URI uri = dirUrl.toURI();
        if ("jar".equals(uri.getScheme())) {
            // Running from a JAR — use FileSystem to walk entries
            try (FileSystem fs = FileSystems.newFileSystem(uri, Collections.emptyMap())) {
                Path dirPath = fs.getPath(MEMORIES_DIR);
                try (Stream<Path> files = Files.list(dirPath)) {
                    files.filter(p -> p.toString().endsWith(".md"))
                         .forEach(p -> filenames.add(p.getFileName().toString()));
                }
            }
        } else {
            // Running from filesystem
            Path dirPath = Paths.get(uri);
            try (Stream<Path> files = Files.list(dirPath)) {
                files.filter(p -> p.toString().endsWith(".md"))
                     .forEach(p -> filenames.add(p.getFileName().toString()));
            }
        }

        return filenames;
    }

    /**
     * Parses a single markdown file into a list of seed memories.
     */
    private static List<SeedMemory> parseMarkdownFile(String resourcePath, String filename) throws IOException {
        List<SeedMemory> memories = new ArrayList<>();

        try (InputStream is = E2ESeedData.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            String[] blocks = content.split("(?m)^---\\s*$");

            // Process pairs: front-matter block + content block
            // blocks[0] is file header/comments (skip)
            // blocks[1] = first front-matter, blocks[2] = first content
            // blocks[3] = second front-matter, blocks[4] = second content, etc.
            for (int i = 1; i < blocks.length - 1; i += 2) {
                String frontMatter = blocks[i].trim();
                String text = (i + 1 < blocks.length) ? blocks[i + 1].trim() : "";

                if (frontMatter.isEmpty() || text.isEmpty()) continue;

                SeedMemory memory = parseFrontMatter(frontMatter, text, filename);
                if (memory != null) {
                    memories.add(memory);
                }
            }
        }

        log.debug("Parsed {} memories from {}", memories.size(), filename);
        return memories;
    }

    /**
     * Parses YAML-like front matter into a SeedMemory record.
     */
    private static SeedMemory parseFrontMatter(String frontMatter, String text, String filename) {
        String id = null;
        MemoryType type = MemoryType.EPISODIC;
        MemorySource source = MemorySource.OBSERVED;
        String[] tags = new String[0];
        byte valence = 0;

        for (String line : frontMatter.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            int colonIdx = line.indexOf(':');
            if (colonIdx < 0) continue;

            String key = line.substring(0, colonIdx).trim().toLowerCase();
            String value = line.substring(colonIdx + 1).trim();

            switch (key) {
                case "id" -> id = value;
                case "type" -> type = parseMemoryType(value);
                case "source" -> source = parseMemorySource(value);
                case "tags" -> tags = parseTags(value);
                case "valence" -> valence = parseValence(value);
            }
        }

        if (id == null || text.isEmpty()) {
            log.warn("Skipping memory block without id or text in {}", filename);
            return null;
        }

        return new SeedMemory(id, text, type, source, tags, valence, filename);
    }

    private static MemoryType parseMemoryType(String value) {
        try {
            return MemoryType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return MemoryType.EPISODIC;
        }
    }

    private static MemorySource parseMemorySource(String value) {
        try {
            return MemorySource.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return MemorySource.OBSERVED;
        }
    }

    private static String[] parseTags(String value) {
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    private static byte parseValence(String value) {
        try {
            int v = Integer.parseInt(value.trim());
            return (byte) Math.max(-128, Math.min(127, v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
