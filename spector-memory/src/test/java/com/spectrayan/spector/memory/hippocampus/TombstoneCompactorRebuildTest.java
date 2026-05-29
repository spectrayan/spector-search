package com.spectrayan.spector.memory.hippocampus;

import com.spectrayan.spector.memory.MemoryType;
import com.spectrayan.spector.memory.cortex.EpisodicMemoryStore;
import com.spectrayan.spector.memory.cortex.EpisodicMemoryStore.EpisodicPartition;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;
import com.spectrayan.spector.memory.synapse.SynapticHeaderConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for TombstoneCompactor partition rebuild (V3.6).
 */
class TombstoneCompactorRebuildTest {

    private static final int VEC_BYTES = 16;
    private static final int CAPACITY = 200;
    private static final float TOMBSTONE_THRESHOLD = 0.30f;

    @TempDir
    Path tempDir;

    private Path storePath;
    private TombstoneCompactor compactor;

    @BeforeEach
    void setUp() {
        storePath = tempDir.resolve("episodic");
        compactor = new TombstoneCompactor(TOMBSTONE_THRESHOLD);
    }

    @Test
    void compactRemovesTombstonedRecords() {
        try (EpisodicMemoryStore store = new EpisodicMemoryStore(storePath, VEC_BYTES, CAPACITY)) {
            // Add 100 records
            for (int i = 0; i < 100; i++) {
                CognitiveHeader header = CognitiveHeader.create(
                        System.currentTimeMillis(), (long) i, 1.0f,
                        (float) i / 10, (short) 0, MemoryType.EPISODIC);
                store.append(header, makeVec(i));
            }

            EpisodicPartition partition = store.partitions().getFirst();
            CognitiveRecordLayout layout = partition.layout();
            MemorySegment segment = partition.segment();

            // Tombstone 40 records (indices 0-39)
            for (int i = 0; i < 40; i++) {
                layout.tombstone(segment, partition.recordOffset(i));
                partition.incrementTombstoneCount();
            }

            assertThat(partition.count()).isEqualTo(100);
            assertThat(partition.tombstoneCount()).isEqualTo(40);
            assertThat(partition.tombstoneRatio()).isEqualTo(0.40f);
            assertThat(compactor.shouldCompact(partition)).isTrue();

            // Compact
            String key = store.keyForPartition(partition);
            EpisodicPartition compacted = compactor.compact(partition, storePath, key);

            assertThat(compacted).isNotNull();
            assertThat(compacted.count()).isEqualTo(60); // 100 - 40 tombstoned
            assertThat(compacted.tombstoneCount()).isEqualTo(0);

            // Verify compacted partition has correct data (records 40-99 from original)
            CognitiveRecordLayout compactedLayout = compacted.layout();
            MemorySegment compactedSegment = compacted.segment();

            // First live record in compacted should have importance of index 40 → 4.0f
            float firstImportance = compactedLayout.readImportance(
                    compactedSegment, compacted.recordOffset(0));
            assertThat(firstImportance).isEqualTo(4.0f);

            // Last record should have importance of index 99 → 9.9f
            float lastImportance = compactedLayout.readImportance(
                    compactedSegment, compacted.recordOffset(59));
            assertThat(lastImportance).isEqualTo(9.9f);

            compacted.close();
        }
    }

    @Test
    void compactPreservesVectorPayload() {
        try (EpisodicMemoryStore store = new EpisodicMemoryStore(storePath, VEC_BYTES, CAPACITY)) {
            // Add 10 records with distinctive vectors
            for (int i = 0; i < 10; i++) {
                CognitiveHeader header = CognitiveHeader.create(
                        System.currentTimeMillis(), 0L, 1.0f, 1.0f, (short) 0, MemoryType.EPISODIC);
                store.append(header, makeVec(i * 100)); // distinctive seed
            }

            EpisodicPartition partition = store.partitions().getFirst();
            CognitiveRecordLayout layout = partition.layout();
            MemorySegment segment = partition.segment();

            // Tombstone records 0, 2, 4 (keep 1, 3, 5, 6, 7, 8, 9)
            for (int idx : new int[]{0, 2, 4}) {
                layout.tombstone(segment, partition.recordOffset(idx));
                partition.incrementTombstoneCount();
            }

            String key = store.keyForPartition(partition);
            EpisodicPartition compacted = compactor.compact(partition, storePath, key);

            assertThat(compacted).isNotNull();
            assertThat(compacted.count()).isEqualTo(7);

            // Verify vector of first record (was index 1 in original → seed = 100)
            byte[] expectedVec = makeVec(100);
            byte[] actualVec = new byte[VEC_BYTES];
            MemorySegment.copy(compacted.segment(),
                    compacted.layout().vectorOffset(compacted.recordOffset(0)),
                    MemorySegment.ofArray(actualVec), 0, VEC_BYTES);
            assertThat(actualVec).isEqualTo(expectedVec);

            compacted.close();
        }
    }

    @Test
    void buildOffsetRemapProducesCorrectMapping() {
        try (EpisodicMemoryStore store = new EpisodicMemoryStore(storePath, VEC_BYTES, CAPACITY)) {
            for (int i = 0; i < 10; i++) {
                CognitiveHeader header = CognitiveHeader.create(
                        System.currentTimeMillis(), 0L, 1.0f, 1.0f, (short) 0, MemoryType.EPISODIC);
                store.append(header, makeVec(i));
            }

            EpisodicPartition partition = store.partitions().getFirst();
            CognitiveRecordLayout layout = partition.layout();

            // Tombstone records 3, 5, 7
            for (int idx : new int[]{3, 5, 7}) {
                layout.tombstone(partition.segment(), partition.recordOffset(idx));
                partition.incrementTombstoneCount();
            }

            String key = store.keyForPartition(partition);
            EpisodicPartition compacted = compactor.compact(partition, storePath, key);

            Map<Long, Long> remap = compactor.buildOffsetRemap(partition, compacted);

            // Should have 7 entries (10 - 3 tombstoned)
            assertThat(remap).hasSize(7);

            // Record 0 → should map to compacted record 0
            assertThat(remap).containsKey(partition.recordOffset(0));
            assertThat(remap.get(partition.recordOffset(0))).isEqualTo(compacted.recordOffset(0));

            // Record 3 → should NOT be in remap (tombstoned)
            assertThat(remap).doesNotContainKey(partition.recordOffset(3));

            // Record 4 → should map to compacted record 3 (shifted after tombstone at 3)
            assertThat(remap).containsKey(partition.recordOffset(4));
            assertThat(remap.get(partition.recordOffset(4))).isEqualTo(compacted.recordOffset(3));

            compacted.close();
        }
    }

    @Test
    void replacePartitionSwapsInStore() {
        try (EpisodicMemoryStore store = new EpisodicMemoryStore(storePath, VEC_BYTES, CAPACITY)) {
            for (int i = 0; i < 20; i++) {
                CognitiveHeader header = CognitiveHeader.create(
                        System.currentTimeMillis(), 0L, 1.0f, 1.0f, (short) 0, MemoryType.EPISODIC);
                store.append(header, makeVec(i));
            }

            EpisodicPartition partition = store.partitions().getFirst();
            CognitiveRecordLayout layout = partition.layout();

            // Tombstone 10 records
            for (int i = 0; i < 10; i++) {
                layout.tombstone(partition.segment(), partition.recordOffset(i));
                partition.incrementTombstoneCount();
            }

            String key = store.keyForPartition(partition);
            assertThat(store.totalRecords()).isEqualTo(20);

            // Compact
            EpisodicPartition compacted = compactor.compact(partition, storePath, key);
            assertThat(compacted).isNotNull();

            // Swap
            boolean swapped = store.replacePartition(key, partition, compacted);
            assertThat(swapped).isTrue();
            assertThat(store.totalRecords()).isEqualTo(10);
            assertThat(store.partitionCount()).isEqualTo(1);
        }
    }

    @Test
    void compactWithAllTombstonedReturnsNull() {
        try (EpisodicMemoryStore store = new EpisodicMemoryStore(storePath, VEC_BYTES, CAPACITY)) {
            for (int i = 0; i < 5; i++) {
                store.append(CognitiveHeader.create(
                        System.currentTimeMillis(), 0L, 1.0f, 1.0f, (short) 0, MemoryType.EPISODIC), makeVec(i));
            }

            EpisodicPartition partition = store.partitions().getFirst();
            for (int i = 0; i < 5; i++) {
                partition.layout().tombstone(partition.segment(), partition.recordOffset(i));
                partition.incrementTombstoneCount();
            }

            String key = store.keyForPartition(partition);
            EpisodicPartition compacted = compactor.compact(partition, storePath, key);
            assertThat(compacted).isNull();
        }
    }

    @Test
    void shouldCompactRespectThreshold() {
        try (EpisodicMemoryStore store = new EpisodicMemoryStore(storePath, VEC_BYTES, CAPACITY)) {
            for (int i = 0; i < 10; i++) {
                store.append(CognitiveHeader.create(
                        System.currentTimeMillis(), 0L, 1.0f, 1.0f, (short) 0, MemoryType.EPISODIC), makeVec(i));
            }

            EpisodicPartition partition = store.partitions().getFirst();

            // 2/10 = 20% < 30% threshold — should NOT compact
            for (int i = 0; i < 2; i++) {
                partition.layout().tombstone(partition.segment(), partition.recordOffset(i));
                partition.incrementTombstoneCount();
            }
            assertThat(compactor.shouldCompact(partition)).isFalse();

            // Tombstone 2 more → 4/10 = 40% > 30% — should compact
            for (int i = 2; i < 4; i++) {
                partition.layout().tombstone(partition.segment(), partition.recordOffset(i));
                partition.incrementTombstoneCount();
            }
            assertThat(compactor.shouldCompact(partition)).isTrue();
        }
    }

    // ── Helpers ──

    private byte[] makeVec(int seed) {
        byte[] vec = new byte[VEC_BYTES];
        for (int i = 0; i < VEC_BYTES; i++) {
            vec[i] = (byte) ((seed + i) % 127);
        }
        return vec;
    }
}
