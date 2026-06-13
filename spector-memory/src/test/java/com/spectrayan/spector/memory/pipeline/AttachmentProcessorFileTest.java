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
import com.spectrayan.spector.memory.pipeline.AttachmentProcessor.AttachmentResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
 * Tests for {@link AttachmentProcessor} with real file operations.
 *
 * <p>Tests MIME type routing, multi-attachment processing, error handling,
 * and integration with SensoryExtractor and AssetStore SPIs using
 * actual temp files and mock extractors.</p>
 */
@DisplayName("AttachmentProcessor — File Integration")
class AttachmentProcessorFileTest {

    @TempDir
    Path tempDir;

    private Path imageFile;
    private Path textFile;
    private Path unknownFile;
    private Path emptyFile;

    @BeforeEach
    void setUp() throws IOException {
        // Create test files
        imageFile = tempDir.resolve("photo.png");
        Files.write(imageFile, new byte[]{(byte)0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
                0, 0, 0, 13, 'I', 'H', 'D', 'R'}); // PNG header

        textFile = tempDir.resolve("notes.txt");
        Files.writeString(textFile, "These are meeting notes about the Spector architecture.");

        unknownFile = tempDir.resolve("data.xyz");
        Files.writeString(unknownFile, "Unknown format data");

        emptyFile = tempDir.resolve("empty.txt");
        Files.createFile(emptyFile);
    }

    // ══════════════════════════════════════════════════════════════
    // MIME ROUTING
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("MIME Type Routing")
    class MimeRoutingTests {

        @Test
        @DisplayName("Image attachment routes to image extractor")
        void imageRoutesToImageExtractor() {
            var imageExtractor = new MockExtractor(
                    Set.of("image/png", "image/jpeg"),
                    List.of(new ExtractionChunk("img-0", "A photo of a dog in a park",
                            Map.of("modality", "IMAGE"))));

            var processor = new AttachmentProcessor(List.of(imageExtractor), null);

            var context = IngestionContext.builder()
                    .metadata(SourceModality.ATTACHMENTS_KEY, imageFile.toAbsolutePath().toString())
                    .build();

            List<AttachmentResult> results = processor.processAttachments("mem-1", context);

            assertFalse(results.isEmpty(), "Should produce attachment results");
            assertTrue(results.getFirst().text().contains("dog"),
                    "Should contain extracted text");
            assertTrue(results.getFirst().chunkId().startsWith("mem-1::attachment-0"),
                    "Chunk ID should be prefixed with parent ID");
        }

        @Test
        @DisplayName("Text attachment routes to text extractor")
        void textRoutesToTextExtractor() {
            var textExtractor = new MockExtractor(
                    Set.of("text/plain"),
                    List.of(new ExtractionChunk("txt-0", "Meeting notes about architecture",
                            Map.of("modality", "TEXT"))));

            var processor = new AttachmentProcessor(List.of(textExtractor), null);

            var context = IngestionContext.builder()
                    .metadata(SourceModality.ATTACHMENTS_KEY, textFile.toAbsolutePath().toString())
                    .build();

            List<AttachmentResult> results = processor.processAttachments("mem-2", context);
            assertFalse(results.isEmpty());
        }

        @Test
        @DisplayName("Unsupported MIME type produces no results")
        void unsupportedMimeTypeEmpty() {
            var imageExtractor = new MockExtractor(Set.of("image/png"), List.of());

            var processor = new AttachmentProcessor(List.of(imageExtractor), null);

            var context = IngestionContext.builder()
                    .metadata(SourceModality.ATTACHMENTS_KEY, unknownFile.toAbsolutePath().toString())
                    .build();

            List<AttachmentResult> results = processor.processAttachments("mem-3", context);
            assertTrue(results.isEmpty(), "Unknown format should produce no results");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // MULTI-ATTACHMENT
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Multi-Attachment Processing")
    class MultiAttachmentTests {

        @Test
        @DisplayName("Processes comma-separated attachments")
        void commaReparatedAttachments() {
            var imageExtractor = new MockExtractor(
                    Set.of("image/png"),
                    List.of(new ExtractionChunk("img-0", "Image caption", Map.of())));
            var textExtractor = new MockExtractor(
                    Set.of("text/plain"),
                    List.of(new ExtractionChunk("txt-0", "Text content", Map.of())));

            var processor = new AttachmentProcessor(
                    List.of(imageExtractor, textExtractor), null);

            String attachments = imageFile.toAbsolutePath() + "," + textFile.toAbsolutePath();
            var context = IngestionContext.builder()
                    .metadata(SourceModality.ATTACHMENTS_KEY, attachments)
                    .build();

            List<AttachmentResult> results = processor.processAttachments("mem-4", context);
            assertEquals(2, results.size(), "Should process both attachments");

            // Check different attachment indexes
            assertTrue(results.get(0).chunkId().contains("attachment-0"));
            assertTrue(results.get(1).chunkId().contains("attachment-1"));
        }

        @Test
        @DisplayName("Multi-chunk extractor produces multiple results per attachment")
        void multiChunkExtraction() {
            var multiExtractor = new MockExtractor(
                    Set.of("text/plain"),
                    List.of(
                            new ExtractionChunk("chunk-0", "First paragraph", Map.of()),
                            new ExtractionChunk("chunk-1", "Second paragraph", Map.of()),
                            new ExtractionChunk("chunk-2", "Third paragraph", Map.of())
                    ));

            var processor = new AttachmentProcessor(List.of(multiExtractor), null);

            var context = IngestionContext.builder()
                    .metadata(SourceModality.ATTACHMENTS_KEY, textFile.toAbsolutePath().toString())
                    .build();

            List<AttachmentResult> results = processor.processAttachments("mem-5", context);
            assertEquals(3, results.size(), "Should produce 3 chunks from one attachment");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ASSET STORE
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Asset Store Integration")
    class AssetStoreTests {

        @Test
        @DisplayName("AssetStore stores file and URI appears in metadata")
        void assetStoreStoresFile() {
            var imageExtractor = new MockExtractor(
                    Set.of("image/png"),
                    List.of(new ExtractionChunk("img-0", "Caption", Map.of())));

            Path storageDir = tempDir.resolve("assets");
            var mockStore = new MockAssetStore(storageDir);

            var processor = new AttachmentProcessor(List.of(imageExtractor), mockStore);

            var context = IngestionContext.builder()
                    .metadata(SourceModality.ATTACHMENTS_KEY, imageFile.toAbsolutePath().toString())
                    .build();

            List<AttachmentResult> results = processor.processAttachments("mem-6", context);
            assertFalse(results.isEmpty());

            // The stored URI should appear in metadata
            String storedUri = results.getFirst().storedUri();
            assertNotNull(storedUri);
            assertTrue(storedUri.contains("asset") || storedUri.contains("mem-6"),
                    "Stored URI should reference stored asset: " + storedUri);
        }

        @Test
        @DisplayName("Processor continues if AssetStore fails")
        void assetStoreFailureContinues() {
            var imageExtractor = new MockExtractor(
                    Set.of("image/png"),
                    List.of(new ExtractionChunk("img-0", "Caption despite store failure", Map.of())));

            var failingStore = new FailingAssetStore();

            var processor = new AttachmentProcessor(List.of(imageExtractor), failingStore);

            var context = IngestionContext.builder()
                    .metadata(SourceModality.ATTACHMENTS_KEY, imageFile.toAbsolutePath().toString())
                    .build();

            // Should NOT throw — AssetStore failure is graceful
            List<AttachmentResult> results = processor.processAttachments("mem-7", context);
            assertFalse(results.isEmpty(), "Should still produce results even if store fails");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ERROR HANDLING
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Error Handling")
    class ErrorTests {

        @Test
        @DisplayName("Missing file produces empty results")
        void missingFileEmpty() {
            var extractor = new MockExtractor(Set.of("text/plain"), List.of());
            var processor = new AttachmentProcessor(List.of(extractor), null);

            var context = IngestionContext.builder()
                    .metadata(SourceModality.ATTACHMENTS_KEY, "/nonexistent/file.txt")
                    .build();

            List<AttachmentResult> results = processor.processAttachments("mem-8", context);
            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("Extractor IOException is caught, batch continues")
        void extractorIoExceptionCaught() {
            var failingExtractor = new FailingExtractor();
            var goodExtractor = new MockExtractor(
                    Set.of("text/plain"),
                    List.of(new ExtractionChunk("txt-0", "Good content", Map.of())));

            var processor = new AttachmentProcessor(
                    List.of(failingExtractor, goodExtractor), null);

            // Image will use failingExtractor, text will use goodExtractor
            String attachments = imageFile.toAbsolutePath() + "," + textFile.toAbsolutePath();
            var context = IngestionContext.builder()
                    .metadata(SourceModality.ATTACHMENTS_KEY, attachments)
                    .build();

            // Should not throw — failing attachment is skipped
            List<AttachmentResult> results = processor.processAttachments("mem-9", context);
            // At least the text one should succeed
            assertTrue(results.size() >= 1, "At least one attachment should succeed");
        }

        @Test
        @DisplayName("Null context returns empty list")
        void nullContextEmpty() {
            var processor = new AttachmentProcessor(List.of(), null);
            assertTrue(processor.processAttachments("id", null).isEmpty());
        }

        @Test
        @DisplayName("Empty attachments key returns empty list")
        void emptyAttachmentsKeyEmpty() {
            var processor = new AttachmentProcessor(List.of(), null);
            var context = IngestionContext.builder()
                    .metadata(SourceModality.ATTACHMENTS_KEY, "")
                    .build();
            assertTrue(processor.processAttachments("id", context).isEmpty());
        }
    }

    // ══════════════════════════════════════════════════════════════
    // METADATA PROPAGATION
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Metadata Propagation")
    class MetadataTests {

        @Test
        @DisplayName("Chunk metadata merged with attachment context")
        void metadataMerged() {
            var extractor = new MockExtractor(
                    Set.of("image/png"),
                    List.of(new ExtractionChunk("img-0", "Caption",
                            Map.of("vlm_model", "llava", "latency_ms", "500"))));

            var processor = new AttachmentProcessor(List.of(extractor), null);

            var context = IngestionContext.builder()
                    .metadata(SourceModality.ATTACHMENTS_KEY, imageFile.toAbsolutePath().toString())
                    .build();

            List<AttachmentResult> results = processor.processAttachments("mem-10", context);
            assertFalse(results.isEmpty());

            Map<String, String> meta = results.getFirst().metadata();
            // Original extractor metadata preserved
            assertEquals("llava", meta.get("vlm_model"));
            assertEquals("500", meta.get("latency_ms"));
            // AttachmentProcessor adds extra keys
            assertEquals("0", meta.get("attachment_index"));
            assertNotNull(meta.get("original_path"));
        }

        @Test
        @DisplayName("Modality set from MIME type")
        void modalityFromMime() {
            var extractor = new MockExtractor(
                    Set.of("image/png"),
                    List.of(new ExtractionChunk("img-0", "Caption", Map.of())));

            var processor = new AttachmentProcessor(List.of(extractor), null);

            var context = IngestionContext.builder()
                    .metadata(SourceModality.ATTACHMENTS_KEY, imageFile.toAbsolutePath().toString())
                    .build();

            List<AttachmentResult> results = processor.processAttachments("mem-11", context);
            assertFalse(results.isEmpty());
            assertEquals("IMAGE", results.getFirst().metadata().get(SourceModality.METADATA_KEY));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // MOCK IMPLEMENTATIONS
    // ══════════════════════════════════════════════════════════════

    /** Mock extractor that returns pre-configured chunks for supported MIME types. */
    private static class MockExtractor implements SensoryExtractor {
        private final Set<String> mimeTypes;
        private final List<ExtractionChunk> chunks;

        MockExtractor(Set<String> mimeTypes, List<ExtractionChunk> chunks) {
            this.mimeTypes = mimeTypes;
            this.chunks = chunks;
        }

        @Override
        public Stream<ExtractionChunk> extract(Path source, String mimeType) {
            return chunks.stream();
        }

        @Override
        public Set<String> supportedMimeTypes() { return mimeTypes; }

        @Override
        public boolean isAvailable() { return true; }
    }

    /** Mock extractor that always throws IOException. */
    private static class FailingExtractor implements SensoryExtractor {
        @Override
        public Stream<ExtractionChunk> extract(Path source, String mimeType) throws IOException {
            throw new IOException("Simulated extraction failure");
        }

        @Override
        public Set<String> supportedMimeTypes() { return Set.of("image/png"); }

        @Override
        public boolean isAvailable() { return true; }
    }

    /** Mock asset store that stores files in a temp directory. */
    private static class MockAssetStore implements AssetStore {
        private final Path baseDir;
        MockAssetStore(Path baseDir) { this.baseDir = baseDir; }

        @Override
        public URI store(Path source, String id, String mimeType) throws IOException {
            Files.createDirectories(baseDir);
            Path target = baseDir.resolve("asset-" + id.replace("::", "_") + getSuffix(source));
            Files.copy(source, target);
            return target.toUri();
        }

        @Override
        public InputStream retrieve(URI uri) throws IOException {
            return Files.newInputStream(Path.of(uri));
        }

        @Override
        public void delete(URI uri) throws IOException {
            Files.deleteIfExists(Path.of(uri));
        }

        @Override
        public boolean exists(URI uri) {
            try { return Files.exists(Path.of(uri)); } catch (Exception e) { return false; }
        }

        private String getSuffix(Path file) {
            String name = file.getFileName().toString();
            int dot = name.lastIndexOf('.');
            return dot >= 0 ? name.substring(dot) : ".dat";
        }
    }

    /** Asset store that always fails. */
    private static class FailingAssetStore implements AssetStore {
        @Override
        public URI store(Path source, String id, String mimeType) throws IOException {
            throw new IOException("Simulated store failure");
        }

        @Override
        public InputStream retrieve(URI uri) throws IOException {
            throw new IOException("Simulated retrieve failure");
        }

        @Override
        public void delete(URI uri) throws IOException {
            throw new IOException("Simulated delete failure");
        }

        @Override
        public boolean exists(URI uri) {
            return false;
        }
    }
}
