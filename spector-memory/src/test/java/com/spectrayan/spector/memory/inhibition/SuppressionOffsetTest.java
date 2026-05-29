package com.spectrayan.spector.memory.inhibition;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for offset-indexed suppression in {@link SuppressionSet}.
 */
class SuppressionOffsetTest {

    @Test
    void registerOffset_enablesOffsetLookup() {
        SuppressionSet set = new SuppressionSet();

        set.registerOffset(1, 1024L);
        assertThat(set.isSuppressedByOffset(1, 1024L)).isTrue();
        assertThat(set.isSuppressedByOffset(1, 2048L)).isFalse();
    }

    @Test
    void differentTypes_atSameOffset_trackedSeparately() {
        SuppressionSet set = new SuppressionSet();

        set.registerOffset(0, 512L); // e.g., WORKING type
        set.registerOffset(1, 512L); // e.g., EPISODIC type at same offset

        assertThat(set.isSuppressedByOffset(0, 512L)).isTrue();
        assertThat(set.isSuppressedByOffset(1, 512L)).isTrue();
        assertThat(set.isSuppressedByOffset(2, 512L)).isFalse(); // different type
    }

    @Test
    void clear_removesOffsets() {
        SuppressionSet set = new SuppressionSet();

        set.suppress("mem-1");
        set.registerOffset(1, 1024L);

        set.clear();

        assertThat(set.isSuppressed("mem-1")).isFalse();
        assertThat(set.isSuppressedByOffset(1, 1024L)).isFalse();
    }

    @Test
    void largeOffsets_packedCorrectly() {
        SuppressionSet set = new SuppressionSet();

        // Test with a large offset that uses many bits
        long largeOffset = 0x0000_ABCD_1234_5678L;
        set.registerOffset(3, largeOffset);

        assertThat(set.isSuppressedByOffset(3, largeOffset)).isTrue();
        assertThat(set.isSuppressedByOffset(3, largeOffset + 1)).isFalse();
    }

    @Test
    void multipleOffsets_trackedIndependently() {
        SuppressionSet set = new SuppressionSet();

        set.registerOffset(1, 0L);
        set.registerOffset(1, 64L);
        set.registerOffset(1, 128L);

        assertThat(set.isSuppressedByOffset(1, 0L)).isTrue();
        assertThat(set.isSuppressedByOffset(1, 64L)).isTrue();
        assertThat(set.isSuppressedByOffset(1, 128L)).isTrue();
        assertThat(set.isSuppressedByOffset(1, 192L)).isFalse();
    }

    @Test
    void stringAndOffsetSuppression_independent() {
        SuppressionSet set = new SuppressionSet();

        // String-based suppression
        set.suppress("mem-1");
        assertThat(set.isSuppressed("mem-1")).isTrue();

        // Offset-based — not yet registered
        assertThat(set.isSuppressedByOffset(1, 1024L)).isFalse();

        // Register offset
        set.registerOffset(1, 1024L);
        assertThat(set.isSuppressedByOffset(1, 1024L)).isTrue();
    }
}
