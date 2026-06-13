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
package com.spectrayan.spector.memory.pipeline;

import com.spectrayan.spector.ingestion.sensory.AssetStore;
import com.spectrayan.spector.ingestion.sensory.SensoryExtractor;
import com.spectrayan.spector.ingestion.sensory.SensoryExtractor.ExtractionChunk;
import com.spectrayan.spector.memory.model.IngestionContext;
import com.spectrayan.spector.memory.model.SourceModality;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AttachmentProcessor}.
 */
@DisplayName("AttachmentProcessor")
class AttachmentProcessorTest {

    @TempDir
    Path tempDir;

    private Path testImageFile;
    private Path testTextFile;

    @BeforeEach
    void setUp() throws IOException {
        testImageFile = tempDir.resolve("photo.jpg");
        Files.write(testImageFile, "fake-image-bytes".getBytes());

        testTextFile = tempDir.resolve("doc.txt");
        Files.writeString(testTextFile, "Test document content about memory systems.");
    }

    // ══════════════════════════════════════════════════════════════
    // NO ATTACHMENTS
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("No Attachments")
    class NoAttachmentTests {

        @Test
        @DisplayName("Null context returns empty list")
        void nullContext() {
            var processor = new AttachmentProcessor(List.of(), null);
            List<AttachmentProcessor.AttachmentResult> results =
                    processor.processAttachments("mem-1", null);
            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("Empty context returns empty list")
        void emptyContext() {
            var processor = new AttachmentProcessor(List.of(), null);
            List<AttachmentProcessor.AttachmentResult> results =
                    processor.processAttachments("mem-1", IngestionContext.EMPTY);
            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("Context without attachments key returns empty list")
        void noAttachmentsKey() {
            var processor = new AttachmentProcessor(List.of(), null);
            var context = IngestionContext.builder()
                    .metadata("modality", "TEXT")
                    .build();
            List<AttachmentProcessor.AttachmentResult> results =
                    processor.processAttachments("mem-1", context);
            assertTrue(results.isEmpty());
        }
    }

    // ══════════════════════════════════════════════════════════════
    // SINGLE ATTACHMENT
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Single Attachment")
    class SingleAttachmentTests {

        @Test
        @DisplayName("Processes single text file attachment")
        void singleTextAttachment() {
            SensoryExtractor textExtractor = new MockTextExtractor();
            var processor = new AttachmentProcessor(List.of(textExtractor), null);

            var context = IngestionContext.builder()
                    .metadata(SourceModality.ATTACHMENTS_KEY, testTextFile.toString())
                    .build();

            List<AttachmentProcessor.AttachmentResult> results =
                    processor.processAttachments("mem-1", context);

            assertFalse(results.isEmpty());
            assertTrue(results.getFirst().chunkId().startsWith("mem-1::attachment-0"));
            assertNotNull(results.getFirst().text());
        }

        @Test
        @DisplayName("Sets correct modality from MIME type")
        void setsModalityFromMime() {
            SensoryExtractor imageExtractor = new MockImageExtractor();
            var processor = new AttachmentProcessor(List.of(imageExtractor), null);

            var context = IngestionContext.builder()
                    .metadata(SourceModality.ATTACHMENTS_KEY, testImageFile.toString())
                    .build();

            List<AttachmentProcessor.AttachmentResult> results =
                    processor.processAttachments("mem-1", context);

            assertFalse(results.isEmpty());
            assertEquals("IMAGE", results.getFirst().metadata().get(SourceModality.METADATA_KEY));
        }

        @Test
        @DisplayName("Includes original_path in metadata")
        void includesOriginalPath() {
            SensoryExtractor textExtractor = new MockTextExtractor();
            var processor = new AttachmentProcessor(List.of(textExtractor), null);

            var context = IngestionContext.builder()
                    .metadata(SourceModality.ATTACHMENTS_KEY, testTextFile.toString())
                    .build();

            List<AttachmentProcessor.AttachmentResult> results =
                    processor.processAttachments("mem-1", context);

            assertEquals(testTextFile.toString(), results.getFirst().metadata().get("original_path"));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // MULTIPLE ATTACHMENTS
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Multiple Attachments")
    class MultipleAttachmentTests {

        @Test
        @DisplayName("Processes comma-separated attachment list")
        void multipleAttachments() {
            SensoryExtractor textExtractor = new MockTextExtractor();
            SensoryExtractor imageExtractor = new MockImageExtractor();
            var processor = new AttachmentProcessor(
                    List.of(textExtractor, imageExtractor), null);

            var context = IngestionContext.builder()
                    .metadata(SourceModality.ATTACHMENTS_KEY,
                            testTextFile + "," + testImageFile)
                    .build();

            List<AttachmentProcessor.AttachmentResult> results =
                    processor.processAttachments("mem-1", context);

            assertTrue(results.size() >= 2, "Should process both attachments");

            // Verify attachment indices
            boolean hasIdx0 = results.stream().anyMatch(r -> "0".equals(r.metadata().get("attachment_index")));
            boolean hasIdx1 = results.stream().anyMatch(r -> "1".equals(r.metadata().get("attachment_index")));
            assertTrue(hasIdx0, "Should have attachment_index=0");
            assertTrue(hasIdx1, "Should have attachment_index=1");
        }

        @Test
        @DisplayName("Continues processing after one attachment fails")
        void continuesAfterFailure() {
            SensoryExtractor textExtractor = new MockTextExtractor();
            var processor = new AttachmentProcessor(List.of(textExtractor), null);

            // First path is non-existent, second is valid
            var context = IngestionContext.builder()
                    .metadata(SourceModality.ATTACHMENTS_KEY,
                            tempDir.resolve("nonexistent.txt") + "," + testTextFile)
                    .build();

            List<AttachmentProcessor.AttachmentResult> results =
                    processor.processAttachments("mem-1", context);

            // Should still process the valid second attachment
            assertFalse(results.isEmpty(), "Should process valid attachment even if first fails");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ASSET STORE INTEGRATION
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Asset Store")
    class AssetStoreTests {

        @Test
        @DisplayName("Stores asset via AssetStore when configured")
        void storesAsset() {
            MockAssetStore mockStore = new MockAssetStore(tempDir);
            SensoryExtractor textExtractor = new MockTextExtractor();
            var processor = new AttachmentProcessor(List.of(textExtractor), mockStore);

            var context = IngestionContext.builder()
                    .metadata(SourceModality.ATTACHMENTS_KEY, testTextFile.toString())
                    .build();

            processor.processAttachments("mem-1", context);

            assertTrue(mockStore.storeCallCount > 0, "AssetStore.store() should have been called");
        }

        @Test
        @DisplayName("Sets stored URI in result metadata")
        void setsStoredUri() {
            MockAssetStore mockStore = new MockAssetStore(tempDir);
            SensoryExtractor textExtractor = new MockTextExtractor();
            var processor = new AttachmentProcessor(List.of(textExtractor), mockStore);

            var context = IngestionContext.builder()
                    .metadata(SourceModality.ATTACHMENTS_KEY, testTextFile.toString())
                    .build();

            List<AttachmentProcessor.AttachmentResult> results =
                    processor.processAttachments("mem-1", context);

            assertFalse(results.isEmpty());
            assertNotNull(results.getFirst().storedUri());
            assertTrue(results.getFirst().storedUri().startsWith("file://"));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // EDGE CASES
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Blank attachments value returns empty list")
        void blankAttachments() {
            var processor = new AttachmentProcessor(List.of(), null);
            var context = IngestionContext.builder()
                    .metadata(SourceModality.ATTACHMENTS_KEY, "   ")
                    .build();

            assertTrue(processor.processAttachments("mem-1", context).isEmpty());
        }

        @Test
        @DisplayName("No matching extractor skips attachment")
        void noMatchingExtractor() {
            var processor = new AttachmentProcessor(List.of(), null); // no extractors

            var context = IngestionContext.builder()
                    .metadata(SourceModality.ATTACHMENTS_KEY, testTextFile.toString())
                    .build();

            List<AttachmentProcessor.AttachmentResult> results =
                    processor.processAttachments("mem-1", context);
            assertTrue(results.isEmpty(), "Should skip when no extractor matches");
        }

        @Test
        @DisplayName("Handles spaces in comma-separated list")
        void handlesSpacesInList() {
            SensoryExtractor textExtractor = new MockTextExtractor();
            var processor = new AttachmentProcessor(List.of(textExtractor), null);

            var context = IngestionContext.builder()
                    .metadata(SourceModality.ATTACHMENTS_KEY,
                            " " + testTextFile + " , " + testTextFile + " ")
                    .build();

            List<AttachmentProcessor.AttachmentResult> results =
                    processor.processAttachments("mem-1", context);
            assertTrue(results.size() >= 2, "Should handle spaces in attachment list");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // MOCK EXTRACTORS & ASSET STORE
    // ══════════════════════════════════════════════════════════════

    /** Mock extractor that supports text MIME types. */
    private static class MockTextExtractor implements SensoryExtractor {
        @Override
        public Stream<ExtractionChunk> extract(Path source, String mimeType) {
            String text = "Extracted text from " + source.getFileName();
            return Stream.of(new ExtractionChunk("chunk-0", text, Map.of()));
        }

        @Override
        public Set<String> supportedMimeTypes() {
            return Set.of("text/plain", "text/markdown", "text/html");
        }
    }

    /** Mock extractor that supports image MIME types. */
    private static class MockImageExtractor implements SensoryExtractor {
        @Override
        public Stream<ExtractionChunk> extract(Path source, String mimeType) {
            return Stream.of(new ExtractionChunk("chunk-0",
                    "A photo showing " + source.getFileName(),
                    Map.of("vlm_model", "mock-vlm")));
        }

        @Override
        public Set<String> supportedMimeTypes() {
            return Set.of("image/jpeg", "image/png", "image/gif", "image/webp");
        }
    }

    /** Mock AssetStore that tracks calls. */
    private static class MockAssetStore implements AssetStore {
        int storeCallCount = 0;
        private final Path baseDir;

        MockAssetStore(Path baseDir) { this.baseDir = baseDir; }

        @Override
        public URI store(Path source, String memoryId, String mimeType) throws IOException {
            storeCallCount++;
            String safeId = memoryId.replaceAll("[^a-zA-Z0-9_-]", "_");
            Path dest = baseDir.resolve("stored-" + safeId + "-" + source.getFileName());
            Files.copy(source, dest);
            return dest.toUri();
        }

        @Override
        public InputStream retrieve(URI assetUri) { return new ByteArrayInputStream(new byte[0]); }

        @Override
        public boolean exists(URI assetUri) { return false; }

        @Override
        public void delete(URI assetUri) {}
    }
}
