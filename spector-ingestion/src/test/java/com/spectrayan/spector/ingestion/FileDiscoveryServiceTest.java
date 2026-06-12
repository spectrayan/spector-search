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

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link FileDiscoveryService} — file pattern matching,
 * directory skip, and edge cases.
 */
@DisplayName("FileDiscoveryService")
class FileDiscoveryServiceTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        // Create test directory structure:
        // tempDir/
        //   doc1.md
        //   doc2.md
        //   readme.txt
        //   src/
        //     code.java
        //     notes.md
        //   .git/
        //     config
        //   target/
        //     output.md
        Files.writeString(tempDir.resolve("doc1.md"), "# Document 1\nContent here.");
        Files.writeString(tempDir.resolve("doc2.md"), "# Document 2\nMore content.");
        Files.writeString(tempDir.resolve("readme.txt"), "Plain text readme.");

        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/code.java"), "public class Foo {}");
        Files.writeString(tempDir.resolve("src/notes.md"), "# Notes\nImportant notes.");

        Files.createDirectories(tempDir.resolve(".git"));
        Files.writeString(tempDir.resolve(".git/config"), "[core] autocrlf=true");

        Files.createDirectories(tempDir.resolve("target"));
        Files.writeString(tempDir.resolve("target/output.md"), "# Output\nGenerated.");
    }

    // ══════════════════════════════════════════════════════════════
    // Pattern Matching
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("pattern matching")
    class PatternMatching {

        @Test
        @DisplayName("discovers .md files with default pattern")
        void discoversMdFiles() throws IOException {
            var service = FileDiscoveryService.builder()
                    .rootDirectory(tempDir)
                    .filePattern("**/*.md")
                    .skipDirs(".git", "target")
                    .build();

            List<Path> files = service.discover();

            assertThat(files).hasSize(3); // doc1.md, doc2.md, src/notes.md
            assertThat(files).allMatch(p -> p.getFileName().toString().endsWith(".md"));
        }

        @Test
        @DisplayName("discovers .txt files")
        void discoversTxtFiles() throws IOException {
            var service = FileDiscoveryService.builder()
                    .rootDirectory(tempDir)
                    .filePattern("**/*.txt")
                    .skipDirs(".git", "target")
                    .build();

            List<Path> files = service.discover();
            assertThat(files).hasSize(1);
            assertThat(files.get(0).getFileName().toString()).isEqualTo("readme.txt");
        }

        @Test
        @DisplayName("discovers multiple extensions")
        void discoversMultipleExtensions() throws IOException {
            var service = FileDiscoveryService.builder()
                    .rootDirectory(tempDir)
                    .filePattern("**/*.md,**/*.txt")
                    .skipDirs(".git", "target")
                    .build();

            List<Path> files = service.discover();
            assertThat(files).hasSize(4); // 3 md + 1 txt
        }

        @Test
        @DisplayName("discovers .java files")
        void discoversJavaFiles() throws IOException {
            var service = FileDiscoveryService.builder()
                    .rootDirectory(tempDir)
                    .filePattern("**/*.java")
                    .skipDirs(".git", "target")
                    .build();

            List<Path> files = service.discover();
            assertThat(files).hasSize(1);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Directory Skipping
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("directory skipping")
    class DirectorySkipping {

        @Test
        @DisplayName("skips .git directory")
        void skipsGitDir() throws IOException {
            var service = FileDiscoveryService.builder()
                    .rootDirectory(tempDir)
                    .filePattern("**/*")
                    .skipDirs(".git")
                    .build();

            List<Path> files = service.discover();
            assertThat(files).noneMatch(p -> p.toString().contains(".git"));
        }

        @Test
        @DisplayName("skips target directory")
        void skipsTargetDir() throws IOException {
            var service = FileDiscoveryService.builder()
                    .rootDirectory(tempDir)
                    .filePattern("**/*.md")
                    .skipDirs("target")
                    .build();

            List<Path> files = service.discover();
            // Should include all md files except target/output.md
            assertThat(files).noneMatch(p -> p.toString().contains("target"));
        }

        @Test
        @DisplayName("no skip dirs includes everything")
        void noSkipDirs() throws IOException {
            var service = FileDiscoveryService.builder()
                    .rootDirectory(tempDir)
                    .filePattern("**/*.md")
                    .skipDirs() // empty
                    .build();

            List<Path> files = service.discover();
            assertThat(files).hasSize(4); // includes target/output.md
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Edge Cases
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        @DisplayName("empty directory returns empty list")
        void emptyDirectory() throws IOException {
            Path emptyDir = Files.createTempDirectory(tempDir, "empty");
            var service = FileDiscoveryService.builder()
                    .rootDirectory(emptyDir)
                    .filePattern("**/*.md")
                    .build();

            List<Path> files = service.discover();
            assertThat(files).isEmpty();
        }

        @Test
        @DisplayName("results are sorted by path")
        void resultsSorted() throws IOException {
            var service = FileDiscoveryService.builder()
                    .rootDirectory(tempDir)
                    .filePattern("**/*.md")
                    .skipDirs(".git", "target")
                    .build();

            List<Path> files = service.discover();
            assertThat(files).isSorted();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Builder Accessors
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("accessors")
    class Accessors {

        @Test
        @DisplayName("accessor methods return configured values")
        void accessorsReturnConfigured() {
            var service = FileDiscoveryService.builder()
                    .rootDirectory(tempDir)
                    .filePattern("**/*.txt")
                    .chunkSize(1000)
                    .chunkOverlap(200)
                    .build();

            assertThat(service.rootDirectory()).isEqualTo(tempDir.toAbsolutePath().normalize());
            assertThat(service.filePattern()).isEqualTo("**/*.txt");
            assertThat(service.chunkSize()).isEqualTo(1000);
            assertThat(service.chunkOverlap()).isEqualTo(200);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // extractTitle utility
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("extractTitle")
    class ExtractTitle {

        @Test
        @DisplayName("extracts title from markdown heading")
        void extractsFromHeading() {
            String content = "# My Document Title\nContent follows.";
            assertThat(FileDiscoveryService.extractTitle(content, "fallback.md"))
                    .isEqualTo("My Document Title");
        }

        @Test
        @DisplayName("uses fallback when no heading found")
        void usesFallbackWhenNoHeading() {
            String content = "No heading here. Just text.";
            assertThat(FileDiscoveryService.extractTitle(content, "readme.md"))
                    .isEqualTo("readme");
        }

        @Test
        @DisplayName("strips file extension from fallback")
        void stripsExtensionFromFallback() {
            assertThat(FileDiscoveryService.extractTitle("no heading", "document.txt"))
                    .isEqualTo("document");
        }

        @Test
        @DisplayName("replaces path separators in fallback")
        void replacesPathSeparators() {
            assertThat(FileDiscoveryService.extractTitle("no heading", "path/to/doc"))
                    .isEqualTo("path to doc");
        }

        @Test
        @DisplayName("handles fallback with no extension")
        void fallbackNoExtension() {
            assertThat(FileDiscoveryService.extractTitle("no heading", "README"))
                    .isEqualTo("README");
        }

        @Test
        @DisplayName("extracts first heading even with multiple")
        void extractsFirstHeading() {
            String content = "Intro text\n# First Title\n# Second Title\nMore content.";
            assertThat(FileDiscoveryService.extractTitle(content, "fallback"))
                    .isEqualTo("First Title");
        }
    }
}
