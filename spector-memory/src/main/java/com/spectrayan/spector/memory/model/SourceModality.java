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

/**
 * Source modality — what the memory originally was before ingestion.
 *
 * <h3>Distinction from {@link MemoryType}</h3>
 * <ul>
 *   <li>{@link MemoryType} determines <em>where</em> a memory is stored (tier routing)</li>
 *   <li>{@code SourceModality} records <em>what</em> the memory originally was</li>
 * </ul>
 *
 * <p>An image caption is text stored in the Episodic tier whose source modality
 * is {@code IMAGE}. A video transcript can consolidate into the Semantic tier
 * while retaining its {@code VIDEO} modality. These are orthogonal axes.</p>
 *
 * <h3>Binary Encoding</h3>
 * <p>Encoded as 2 bits (bits 6-7) in the flags byte of the
 * {@link com.spectrayan.spector.memory.synapse.SynapticHeaderConstants synaptic header}.
 * This enables SIMD hardware to instantly filter by modality (e.g., "search
 * only text memories") without touching the metadata payload.</p>
 *
 * <h3>Metadata Convention</h3>
 * <p>When provided via the {@code metadata} map in
 * {@link com.spectrayan.spector.memory.model.IngestionContext IngestionContext},
 * use the key {@value #METADATA_KEY}. The ingestion pipeline extracts it and
 * encodes it into the binary header automatically.</p>
 */
public enum SourceModality {

    /** Default: plain text content. */
    TEXT,

    /** Image content (caption/description stored as text, URI points to asset). */
    IMAGE,

    /** Audio content (transcript stored as text, URI points to asset). */
    AUDIO,

    /** Video content (frame captions/transcripts stored as text, URI points to asset). */
    VIDEO;

    /** Metadata map key for source modality. */
    public static final String METADATA_KEY = "modality";

    /** Metadata map key for the source asset URI. */
    public static final String URI_KEY = "source_uri";

    /**
     * Metadata map key for attachment file paths/URIs.
     *
     * <p>Comma-separated list of local paths or URIs (file://, s3://, https://) that
     * should be processed by the appropriate {@code SensoryExtractor} and linked
     * to the parent memory as sub-memories.</p>
     *
     * <p>Example: {@code "/photos/cat.jpg,s3://bucket/video.mp4"}</p>
     */
    public static final String ATTACHMENTS_KEY = "attachments";

    /**
     * Converts a 2-bit ordinal (0-3) back to a {@code SourceModality}.
     *
     * <p>Unknown ordinals default to {@code TEXT} for forward compatibility
     * (future header versions may redefine these bits).</p>
     *
     * @param ordinal the 2-bit ordinal from the flags byte
     * @return the corresponding modality
     */
    public static SourceModality fromOrdinal(int ordinal) {
        return switch (ordinal) {
            case 0 -> TEXT;
            case 1 -> IMAGE;
            case 2 -> AUDIO;
            case 3 -> VIDEO;
            default -> TEXT;
        };
    }

    /**
     * Parses a modality from a string name (case-insensitive).
     *
     * <p>Returns {@code TEXT} for null, blank, or unrecognized values.</p>
     *
     * @param name the modality name (e.g., "IMAGE", "image")
     * @return the corresponding modality
     */
    public static SourceModality fromName(String name) {
        if (name == null || name.isBlank()) return TEXT;
        try {
            return valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return TEXT;
        }
    }
}
