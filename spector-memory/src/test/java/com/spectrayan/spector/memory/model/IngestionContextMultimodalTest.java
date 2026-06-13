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
package com.spectrayan.spector.memory.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for multimodal-related methods in {@link IngestionContext}.
 */
@DisplayName("IngestionContext — Multimodal")
class IngestionContextMultimodalTest {

    // ══════════════════════════════════════════════════════════════
    // SOURCE MODALITY
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Source Modality")
    class SourceModalityTests {

        @Test
        @DisplayName("Returns null when no modality set")
        void nullWhenNotSet() {
            var ctx = IngestionContext.EMPTY;
            assertNull(ctx.sourceModality());
        }

        @Test
        @DisplayName("Returns IMAGE when modality set via builder")
        void imageModalityFromBuilder() {
            var ctx = IngestionContext.builder()
                    .sourceModality(SourceModality.IMAGE)
                    .build();
            assertEquals(SourceModality.IMAGE, ctx.sourceModality());
        }

        @Test
        @DisplayName("Returns AUDIO when modality set via metadata key")
        void audioModalityFromMetadata() {
            var ctx = IngestionContext.builder()
                    .metadata(SourceModality.METADATA_KEY, "AUDIO")
                    .build();
            assertEquals(SourceModality.AUDIO, ctx.sourceModality());
        }

        @Test
        @DisplayName("Case-insensitive modality parsing")
        void caseInsensitive() {
            var ctx = IngestionContext.builder()
                    .metadata(SourceModality.METADATA_KEY, "video")
                    .build();
            assertEquals(SourceModality.VIDEO, ctx.sourceModality());
        }

        @Test
        @DisplayName("Unknown modality defaults to TEXT")
        void unknownDefaultsToText() {
            var ctx = IngestionContext.builder()
                    .metadata(SourceModality.METADATA_KEY, "hologram")
                    .build();
            assertEquals(SourceModality.TEXT, ctx.sourceModality());
        }
    }

    // ══════════════════════════════════════════════════════════════
    // SOURCE URI
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Source URI")
    class SourceUriTests {

        @Test
        @DisplayName("Returns null when no URI set")
        void nullWhenNotSet() {
            assertNull(IngestionContext.EMPTY.sourceUri());
        }

        @Test
        @DisplayName("Returns URI from builder")
        void uriFromBuilder() {
            var ctx = IngestionContext.builder()
                    .sourceUri("file:///photos/cat.jpg")
                    .build();
            assertEquals("file:///photos/cat.jpg", ctx.sourceUri());
        }

        @Test
        @DisplayName("Returns URI from metadata key")
        void uriFromMetadata() {
            var ctx = IngestionContext.builder()
                    .metadata(SourceModality.URI_KEY, "s3://bucket/image.png")
                    .build();
            assertEquals("s3://bucket/image.png", ctx.sourceUri());
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ATTACHMENTS
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Attachments")
    class AttachmentTests {

        @Test
        @DisplayName("hasAttachments false when not set")
        void falseWhenNotSet() {
            assertFalse(IngestionContext.EMPTY.hasAttachments());
        }

        @Test
        @DisplayName("hasAttachments false for blank value")
        void falseForBlank() {
            var ctx = IngestionContext.builder()
                    .metadata(SourceModality.ATTACHMENTS_KEY, "   ")
                    .build();
            assertFalse(ctx.hasAttachments());
        }

        @Test
        @DisplayName("hasAttachments true when set")
        void trueWhenSet() {
            var ctx = IngestionContext.builder()
                    .metadata(SourceModality.ATTACHMENTS_KEY, "/photos/cat.jpg")
                    .build();
            assertTrue(ctx.hasAttachments());
        }

        @Test
        @DisplayName("attachmentList parses single path")
        void singlePath() {
            var ctx = IngestionContext.builder()
                    .metadata(SourceModality.ATTACHMENTS_KEY, "/photos/cat.jpg")
                    .build();
            assertEquals(List.of("/photos/cat.jpg"), ctx.attachmentList());
        }

        @Test
        @DisplayName("attachmentList parses comma-separated paths")
        void multiplePaths() {
            var ctx = IngestionContext.builder()
                    .metadata(SourceModality.ATTACHMENTS_KEY,
                            "/photos/cat.jpg,/photos/dog.png,/videos/trip.mp4")
                    .build();
            assertEquals(List.of("/photos/cat.jpg", "/photos/dog.png", "/videos/trip.mp4"),
                    ctx.attachmentList());
        }

        @Test
        @DisplayName("attachmentList trims whitespace around paths")
        void trimsWhitespace() {
            var ctx = IngestionContext.builder()
                    .metadata(SourceModality.ATTACHMENTS_KEY,
                            "  /photos/cat.jpg , /photos/dog.png  ")
                    .build();
            assertEquals(List.of("/photos/cat.jpg", "/photos/dog.png"), ctx.attachmentList());
        }

        @Test
        @DisplayName("attachmentList skips empty entries")
        void skipsEmpty() {
            var ctx = IngestionContext.builder()
                    .metadata(SourceModality.ATTACHMENTS_KEY, "/photos/cat.jpg,,, ,/photos/dog.png")
                    .build();
            assertEquals(List.of("/photos/cat.jpg", "/photos/dog.png"), ctx.attachmentList());
        }

        @Test
        @DisplayName("attachmentList returns empty list for no attachments")
        void emptyListForNoAttachments() {
            assertEquals(List.of(), IngestionContext.EMPTY.attachmentList());
        }

        @Test
        @DisplayName("attachmentList handles mixed URI schemes")
        void mixedUriSchemes() {
            var ctx = IngestionContext.builder()
                    .metadata(SourceModality.ATTACHMENTS_KEY,
                            "/local/file.jpg,s3://bucket/key.png,https://cdn.example.com/img.webp")
                    .build();
            assertEquals(
                    List.of("/local/file.jpg", "s3://bucket/key.png", "https://cdn.example.com/img.webp"),
                    ctx.attachmentList());
        }
    }

    // ══════════════════════════════════════════════════════════════
    // BUILDER — MULTIMODAL CONVENIENCE
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Builder Convenience Methods")
    class BuilderTests {

        @Test
        @DisplayName("sourceModality() sets metadata key correctly")
        void sourceModalityBuilder() {
            var ctx = IngestionContext.builder()
                    .sourceModality(SourceModality.VIDEO)
                    .build();
            assertEquals("VIDEO", ctx.metadata().get(SourceModality.METADATA_KEY));
            assertEquals(SourceModality.VIDEO, ctx.sourceModality());
        }

        @Test
        @DisplayName("sourceUri() sets metadata key correctly")
        void sourceUriBuilder() {
            var ctx = IngestionContext.builder()
                    .sourceUri("gs://bucket/audio.mp3")
                    .build();
            assertEquals("gs://bucket/audio.mp3", ctx.metadata().get(SourceModality.URI_KEY));
        }

        @Test
        @DisplayName("Chained multimodal builder")
        void chainedBuilder() {
            var ctx = IngestionContext.builder()
                    .sourceModality(SourceModality.IMAGE)
                    .sourceUri("file:///photos/sunset.jpg")
                    .metadata("vlm_model", "llava")
                    .metadata("original_filename", "sunset.jpg")
                    .build();

            assertEquals(SourceModality.IMAGE, ctx.sourceModality());
            assertEquals("file:///photos/sunset.jpg", ctx.sourceUri());
            assertEquals("llava", ctx.metadata().get("vlm_model"));
            assertEquals("sunset.jpg", ctx.metadata().get("original_filename"));
        }

        @Test
        @DisplayName("Empty builder produces EMPTY-like context")
        void emptyBuilder() {
            var ctx = IngestionContext.builder().build();
            assertNull(ctx.sourceModality());
            assertNull(ctx.sourceUri());
            assertFalse(ctx.hasAttachments());
            assertFalse(ctx.hasHints());
        }
    }
}
