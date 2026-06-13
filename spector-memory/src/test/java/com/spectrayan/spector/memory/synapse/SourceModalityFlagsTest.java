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
package com.spectrayan.spector.memory.synapse;

import com.spectrayan.spector.memory.model.SourceModality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for source modality bit encoding/decoding in the flags byte.
 *
 * <p>Verifies that bits 6-7 of the flags byte correctly round-trip all 4
 * modalities without corrupting other flag bits.</p>
 */
@DisplayName("Source Modality Flags Encoding")
class SourceModalityFlagsTest {

    // ══════════════════════════════════════════════════════════════
    // ROUND-TRIP: All modalities
    // ══════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "modality {0} round-trips through flags byte")
    @EnumSource(SourceModality.class)
    void modalityRoundTrip(SourceModality modality) {
        byte flags = SynapticHeaderConstants.withSourceModality((byte) 0, modality.ordinal());
        int readBack = SynapticHeaderConstants.sourceModalityOrdinal(flags);
        SourceModality decoded = SourceModality.fromOrdinal(readBack);

        assertEquals(modality, decoded,
                "Modality should round-trip: wrote " + modality + " (ordinal=" + modality.ordinal()
                + "), read ordinal=" + readBack);
    }

    // ══════════════════════════════════════════════════════════════
    // ISOLATION: Modality bits don't clobber other flags
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Modality bits don't clobber tombstone flag (bit 0)")
    void modalityDoesNotClobberTombstone() {
        byte flags = SynapticHeaderConstants.FLAG_TOMBSTONE; // bit 0 set
        flags = SynapticHeaderConstants.withSourceModality(flags, SourceModality.IMAGE.ordinal());

        assertTrue(SynapticHeaderConstants.isTombstoned(flags),
                "Tombstone flag should survive modality encoding");
        assertEquals(SourceModality.IMAGE,
                SourceModality.fromOrdinal(SynapticHeaderConstants.sourceModalityOrdinal(flags)));
    }

    @Test
    @DisplayName("Modality bits don't clobber memory type bits (bits 1-2)")
    void modalityDoesNotClobberMemoryType() {
        // Set memory type to PROCEDURAL (ordinal 3, bits 1-2)
        byte flags = SynapticHeaderConstants.withMemoryType((byte) 0, 3);
        int originalType = SynapticHeaderConstants.memoryTypeOrdinal(flags);

        // Now set modality to VIDEO (ordinal 3, bits 6-7)
        flags = SynapticHeaderConstants.withSourceModality(flags, SourceModality.VIDEO.ordinal());

        assertEquals(originalType, SynapticHeaderConstants.memoryTypeOrdinal(flags),
                "Memory type bits should survive modality encoding");
        assertEquals(SourceModality.VIDEO,
                SourceModality.fromOrdinal(SynapticHeaderConstants.sourceModalityOrdinal(flags)));
    }

    @Test
    @DisplayName("Modality bits don't clobber consolidated flag (bit 3)")
    void modalityDoesNotClobberConsolidated() {
        byte flags = SynapticHeaderConstants.FLAG_CONSOLIDATED;
        flags = SynapticHeaderConstants.withSourceModality(flags, SourceModality.AUDIO.ordinal());

        assertTrue(SynapticHeaderConstants.isConsolidated(flags),
                "Consolidated flag should survive modality encoding");
        assertEquals(SourceModality.AUDIO,
                SourceModality.fromOrdinal(SynapticHeaderConstants.sourceModalityOrdinal(flags)));
    }

    @Test
    @DisplayName("Modality bits don't clobber pinned flag (bit 4)")
    void modalityDoesNotClobberPinned() {
        byte flags = SynapticHeaderConstants.FLAG_PINNED;
        flags = SynapticHeaderConstants.withSourceModality(flags, SourceModality.IMAGE.ordinal());

        assertTrue(SynapticHeaderConstants.isPinned(flags),
                "Pinned flag should survive modality encoding");
    }

    @Test
    @DisplayName("Modality bits don't clobber resolved flag (bit 5)")
    void modalityDoesNotClobberResolved() {
        byte flags = SynapticHeaderConstants.FLAG_RESOLVED;
        flags = SynapticHeaderConstants.withSourceModality(flags, SourceModality.VIDEO.ordinal());

        assertTrue(SynapticHeaderConstants.isResolved(flags),
                "Resolved flag should survive modality encoding");
    }

    // ══════════════════════════════════════════════════════════════
    // COMBINED: All flags set simultaneously
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("All flags can coexist with modality bits")
    void allFlagsCoexist() {
        byte flags = (byte) 0;
        flags = (byte) (flags | SynapticHeaderConstants.FLAG_TOMBSTONE);    // bit 0
        flags = SynapticHeaderConstants.withMemoryType(flags, 3);            // bits 1-2 = PROCEDURAL (0b11)
        flags = (byte) (flags | SynapticHeaderConstants.FLAG_CONSOLIDATED); // bit 3
        flags = (byte) (flags | SynapticHeaderConstants.FLAG_PINNED);       // bit 4
        flags = (byte) (flags | SynapticHeaderConstants.FLAG_RESOLVED);     // bit 5
        flags = SynapticHeaderConstants.withSourceModality(flags, SourceModality.VIDEO.ordinal()); // bits 6-7

        assertTrue(SynapticHeaderConstants.isTombstoned(flags));
        assertEquals(3, SynapticHeaderConstants.memoryTypeOrdinal(flags));
        assertTrue(SynapticHeaderConstants.isConsolidated(flags));
        assertTrue(SynapticHeaderConstants.isPinned(flags));
        assertTrue(SynapticHeaderConstants.isResolved(flags));
        assertEquals(SourceModality.VIDEO,
                SourceModality.fromOrdinal(SynapticHeaderConstants.sourceModalityOrdinal(flags)));

        // All bits should be set: 0xFF
        assertEquals((byte) 0xFF, flags, "All 8 bits should be set");
    }

    // ══════════════════════════════════════════════════════════════
    // BACKWARD COMPATIBILITY: Existing records default to TEXT
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Zeroed flags byte reads as TEXT modality (backward compat)")
    void zeroFlagsDefaultToText() {
        assertEquals(SourceModality.TEXT,
                SourceModality.fromOrdinal(SynapticHeaderConstants.sourceModalityOrdinal((byte) 0)),
                "Existing records with zeroed bits should map to TEXT");
    }

    @Test
    @DisplayName("Flags with only memory type set still read as TEXT modality")
    void memoryTypeOnlyFlagsDefaultToText() {
        // Simulate an existing record: memory type set, modality bits zeroed
        byte flags = SynapticHeaderConstants.withMemoryType((byte) 0, 1); // EPISODIC
        assertEquals(SourceModality.TEXT,
                SourceModality.fromOrdinal(SynapticHeaderConstants.sourceModalityOrdinal(flags)),
                "Existing records with only memory type should default to TEXT");
    }

    // ══════════════════════════════════════════════════════════════
    // EDGE CASES: SourceModality.fromOrdinal / fromName
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("fromOrdinal handles out-of-range values gracefully")
    void fromOrdinalOutOfRange() {
        assertEquals(SourceModality.TEXT, SourceModality.fromOrdinal(-1));
        assertEquals(SourceModality.TEXT, SourceModality.fromOrdinal(4));
        assertEquals(SourceModality.TEXT, SourceModality.fromOrdinal(99));
        assertEquals(SourceModality.TEXT, SourceModality.fromOrdinal(Integer.MAX_VALUE));
    }

    @Test
    @DisplayName("fromName handles null, blank, and invalid names")
    void fromNameEdgeCases() {
        assertEquals(SourceModality.TEXT, SourceModality.fromName(null));
        assertEquals(SourceModality.TEXT, SourceModality.fromName(""));
        assertEquals(SourceModality.TEXT, SourceModality.fromName("   "));
        assertEquals(SourceModality.TEXT, SourceModality.fromName("UNKNOWN"));
        assertEquals(SourceModality.TEXT, SourceModality.fromName("jpg"));
    }

    @Test
    @DisplayName("fromName is case-insensitive")
    void fromNameCaseInsensitive() {
        assertEquals(SourceModality.IMAGE, SourceModality.fromName("image"));
        assertEquals(SourceModality.IMAGE, SourceModality.fromName("IMAGE"));
        assertEquals(SourceModality.IMAGE, SourceModality.fromName("Image"));
        assertEquals(SourceModality.AUDIO, SourceModality.fromName("audio"));
        assertEquals(SourceModality.VIDEO, SourceModality.fromName("Video"));
    }

    // ══════════════════════════════════════════════════════════════
    // OVERWRITE: Changing modality preserves other bits
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Overwriting modality from IMAGE to AUDIO preserves other flags")
    void overwriteModality() {
        byte flags = SynapticHeaderConstants.withMemoryType((byte) 0, 1); // EPISODIC
        flags = (byte) (flags | SynapticHeaderConstants.FLAG_PINNED);
        flags = SynapticHeaderConstants.withSourceModality(flags, SourceModality.IMAGE.ordinal());

        // Verify initial state
        assertEquals(SourceModality.IMAGE,
                SourceModality.fromOrdinal(SynapticHeaderConstants.sourceModalityOrdinal(flags)));

        // Overwrite to AUDIO
        flags = SynapticHeaderConstants.withSourceModality(flags, SourceModality.AUDIO.ordinal());

        assertEquals(SourceModality.AUDIO,
                SourceModality.fromOrdinal(SynapticHeaderConstants.sourceModalityOrdinal(flags)));
        assertEquals(1, SynapticHeaderConstants.memoryTypeOrdinal(flags), "Memory type should survive");
        assertTrue(SynapticHeaderConstants.isPinned(flags), "Pinned flag should survive");
    }

    @Test
    @DisplayName("Overwriting modality back to TEXT zeroes bits 6-7")
    void overwriteModalityBackToText() {
        byte flags = SynapticHeaderConstants.withSourceModality((byte) 0, SourceModality.VIDEO.ordinal());
        assertEquals(SourceModality.VIDEO,
                SourceModality.fromOrdinal(SynapticHeaderConstants.sourceModalityOrdinal(flags)));

        flags = SynapticHeaderConstants.withSourceModality(flags, SourceModality.TEXT.ordinal());
        assertEquals(SourceModality.TEXT,
                SourceModality.fromOrdinal(SynapticHeaderConstants.sourceModalityOrdinal(flags)));
        assertEquals(0, flags & 0xFF, "Flags should be zero after setting TEXT modality on clean byte");
    }
}
