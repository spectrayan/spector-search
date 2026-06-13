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

import com.spectrayan.spector.memory.cortex.MemorySource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for multimodal fields in {@link CognitiveResult}.
 */
@DisplayName("CognitiveResult Multimodal")
class CognitiveResultMultimodalTest {

    // ══════════════════════════════════════════════════════════════
    // BACKWARD COMPATIBILITY
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Legacy constructor defaults to TEXT modality and empty metadata")
    void legacyConstructorDefaults() {
        var result = new CognitiveResult(
                "mem-1", "hello", 0.95f, 5.0f, 1.0f,
                (short) 2, (byte) 10, MemoryType.EPISODIC, MemorySource.USER_STATED,
                new String[]{"tag1"}, 0.8f, 0.9f);

        assertEquals(SourceModality.TEXT, result.sourceModality());
        assertTrue(result.metadata().isEmpty());
        assertFalse(result.isMultimodal());
        assertNull(result.sourceUri());
    }

    // ══════════════════════════════════════════════════════════════
    // MULTIMODAL FIELDS
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Full constructor with modality and metadata")
    void fullConstructorWithModality() {
        Map<String, String> metadata = Map.of(
                "modality", "IMAGE",
                "source_uri", "file:///photos/cat.jpg",
                "vlm_model", "moondream"
        );

        var result = new CognitiveResult(
                "mem-42", "A cat sitting on a windowsill", 0.85f, 7.0f, 0.5f,
                1, (byte) 20, MemoryType.EPISODIC, MemorySource.OBSERVED,
                new String[]{"cat"}, 0.9f, 0.95f,
                CognitiveResult.RetrievalMode.STANDARD, null, null,
                SourceModality.IMAGE, metadata);

        assertEquals(SourceModality.IMAGE, result.sourceModality());
        assertTrue(result.isMultimodal());
        assertEquals("file:///photos/cat.jpg", result.sourceUri());
        assertEquals("moondream", result.metadata().get("vlm_model"));
    }

    @Test
    @DisplayName("withModality creates copy with new modality and metadata")
    void withModalityCreatesNewCopy() {
        var original = new CognitiveResult(
                "mem-1", "text", 0.9f, 5.0f, 1.0f,
                (short) 0, (byte) 0, MemoryType.EPISODIC, MemorySource.OBSERVED,
                new String[0], 1.0f, 1.0f);

        assertFalse(original.isMultimodal());

        var withImage = original.withModality(SourceModality.IMAGE,
                Map.of("source_uri", "file:///img.png"));

        assertTrue(withImage.isMultimodal());
        assertEquals(SourceModality.IMAGE, withImage.sourceModality());
        assertEquals("file:///img.png", withImage.sourceUri());

        // Original should be unchanged (immutability)
        assertFalse(original.isMultimodal());
        assertEquals(SourceModality.TEXT, original.sourceModality());
    }

    @Test
    @DisplayName("Null modality defaults to TEXT in compact constructor")
    void nullModalityDefaultsToText() {
        var result = new CognitiveResult(
                "mem-1", "text", 0.9f, 5.0f, 1.0f,
                (short) 0, (byte) 0, MemoryType.EPISODIC, MemorySource.OBSERVED,
                new String[0], 1.0f, 1.0f,
                CognitiveResult.RetrievalMode.STANDARD, null, null,
                null, null);

        assertEquals(SourceModality.TEXT, result.sourceModality());
        assertTrue(result.metadata().isEmpty());
    }

    @Test
    @DisplayName("TEXT modality is not multimodal")
    void textIsNotMultimodal() {
        var result = new CognitiveResult(
                "mem-1", "text", 0.9f, 5.0f, 1.0f,
                (short) 0, (byte) 0, MemoryType.EPISODIC, MemorySource.OBSERVED,
                new String[0], 1.0f, 1.0f,
                CognitiveResult.RetrievalMode.STANDARD, null, null,
                SourceModality.TEXT, Map.of());

        assertFalse(result.isMultimodal());
    }

    @Test
    @DisplayName("sourceUri returns null when source_uri key is missing")
    void sourceUriMissingFromMetadata() {
        var result = new CognitiveResult(
                "mem-1", "text", 0.9f, 5.0f, 1.0f,
                (short) 0, (byte) 0, MemoryType.EPISODIC, MemorySource.OBSERVED,
                new String[0], 1.0f, 1.0f,
                CognitiveResult.RetrievalMode.STANDARD, null, null,
                SourceModality.IMAGE, Map.of("vlm_model", "moondream"));

        assertTrue(result.isMultimodal());
        assertNull(result.sourceUri());
    }
}
