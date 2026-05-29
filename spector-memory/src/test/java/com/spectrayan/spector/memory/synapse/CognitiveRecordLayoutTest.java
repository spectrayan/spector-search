package com.spectrayan.spector.memory.synapse;

import com.spectrayan.spector.memory.MemoryType;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CognitiveRecordLayout} — 32-byte header read/write.
 */
class CognitiveRecordLayoutTest {

    private static final int VECTOR_BYTES = 768; // 768 bytes for quantized vector
    private final CognitiveRecordLayout layout = new CognitiveRecordLayout(VECTOR_BYTES);

    @Test
    void strideIs32PlusVectorBytes() {
        assertThat(layout.stride()).isEqualTo(32 + VECTOR_BYTES);
    }

    @Test
    void vectorOffsetIs32() {
        assertThat(layout.vectorOffset(0)).isEqualTo(32);
        assertThat(layout.vectorOffset(800)).isEqualTo(832);
    }

    @Test
    void writeAndReadHeaderRoundtrip() {
        try (var arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(layout.stride());

            long timestamp = System.currentTimeMillis();
            long tags = SynapticTagEncoder.encode("java", "performance");
            var header = new CognitiveRecordLayout.CognitiveHeader(
                    timestamp, tags, 1.5f, 0.8f, 7,
                    (short) 42, (byte) -50, (byte) 0x12
            );

            layout.writeHeader(segment, 0, header);
            var readBack = layout.readHeader(segment, 0);

            assertThat(readBack.timestampMs()).isEqualTo(timestamp);
            assertThat(readBack.synapticTags()).isEqualTo(tags);
            assertThat(readBack.exactNorm()).isEqualTo(1.5f);
            assertThat(readBack.importance()).isEqualTo(0.8f);
            assertThat(readBack.centroidId()).isEqualTo((short) 42);
            assertThat(readBack.recallCount()).isEqualTo(7);
            assertThat(readBack.valence()).isEqualTo((byte) -50);
            assertThat(readBack.flags()).isEqualTo((byte) 0x12);
        }
    }

    @Test
    void fieldLevelAccessors() {
        try (var arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(layout.stride());

            long timestamp = 12345L;
            long tags = 0xDEAD_BEEF_CAFE_BABEL;
            var header = CognitiveRecordLayout.CognitiveHeader.create(
                    timestamp, tags, 2.0f, 5.0f, (short) 99, MemoryType.SEMANTIC
            );

            layout.writeHeader(segment, 0, header);

            assertThat(layout.readTimestamp(segment, 0)).isEqualTo(timestamp);
            assertThat(layout.readSynapticTags(segment, 0)).isEqualTo(tags);
            assertThat(layout.readImportance(segment, 0)).isEqualTo(5.0f);
            assertThat(layout.readRecallCount(segment, 0)).isZero();
            assertThat(layout.readValence(segment, 0)).isZero();
        }
    }

    @Test
    void incrementRecallCountIsAtomic() {
        try (var arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(layout.stride());

            var header = CognitiveRecordLayout.CognitiveHeader.create(
                    System.currentTimeMillis(), 0L, 1.0f, 1.0f, (short) 0, MemoryType.EPISODIC
            );
            layout.writeHeader(segment, 0, header);

            // Initial recall count = 0
            assertThat(layout.readRecallCount(segment, 0)).isZero();

            // Increment and check return value (old value)
            int old1 = layout.incrementRecallCount(segment, 0);
            assertThat(old1).isZero();
            assertThat(layout.readRecallCount(segment, 0)).isEqualTo(1);

            // Increment again
            int old2 = layout.incrementRecallCount(segment, 0);
            assertThat(old2).isEqualTo(1);
            assertThat(layout.readRecallCount(segment, 0)).isEqualTo(2);
        }
    }

    @Test
    void tombstoneSetsFlagBit() {
        try (var arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(layout.stride());

            var header = CognitiveRecordLayout.CognitiveHeader.create(
                    System.currentTimeMillis(), 0L, 1.0f, 1.0f, (short) 0, MemoryType.EPISODIC
            );
            layout.writeHeader(segment, 0, header);

            assertThat(SynapticHeaderConstants.isTombstoned(layout.readFlags(segment, 0))).isFalse();

            layout.tombstone(segment, 0);

            assertThat(SynapticHeaderConstants.isTombstoned(layout.readFlags(segment, 0))).isTrue();
        }
    }

    @Test
    void markConsolidatedSetsFlagBit() {
        try (var arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(layout.stride());

            var header = CognitiveRecordLayout.CognitiveHeader.create(
                    System.currentTimeMillis(), 0L, 1.0f, 1.0f, (short) 0, MemoryType.EPISODIC
            );
            layout.writeHeader(segment, 0, header);

            layout.markConsolidated(segment, 0);

            assertThat(SynapticHeaderConstants.isConsolidated(layout.readFlags(segment, 0))).isTrue();
        }
    }

    @Test
    void memoryTypeEncodedInFlags() {
        try (var arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(layout.stride());

            for (MemoryType type : MemoryType.values()) {
                var header = CognitiveRecordLayout.CognitiveHeader.create(
                        System.currentTimeMillis(), 0L, 1.0f, 1.0f, (short) 0, type
                );
                layout.writeHeader(segment, 0, header);

                byte flags = layout.readFlags(segment, 0);
                assertThat(SynapticHeaderConstants.memoryTypeOrdinal(flags))
                        .as("MemoryType %s", type)
                        .isEqualTo(type.ordinal());
            }
        }
    }

    @Test
    void mergeSynapticTagsORsExisting() {
        try (var arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(layout.stride());

            long initialTags = SynapticTagEncoder.encode("java");
            var header = CognitiveRecordLayout.CognitiveHeader.create(
                    System.currentTimeMillis(), initialTags, 1.0f, 1.0f, (short) 0, MemoryType.SEMANTIC
            );
            layout.writeHeader(segment, 0, header);

            long additionalTags = SynapticTagEncoder.encode("performance");
            layout.mergeSynapticTags(segment, 0, additionalTags);

            long merged = layout.readSynapticTags(segment, 0);
            assertThat(merged).isEqualTo(initialTags | additionalTags);
        }
    }
}
