package com.spectrayan.spector.memory.cortex;

import com.spectrayan.spector.memory.MemoryType;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;
import com.spectrayan.spector.memory.synapse.SynapticHeaderConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for mmap-backed EpisodicMemoryStore persistence.
 */
class EpisodicMmapPersistenceTest {

    private static final int VEC_BYTES = 16;
    private static final int CAPACITY = 100;

    @TempDir
    Path tempDir;

    private Path storePath;

    @BeforeEach
    void setUp() {
        storePath = tempDir.resolve("episodic");
    }

    // ── Basic Persistence ──

    @Test
    void appendAndRecoverAcrossRestart() {
        // Write records to the store
        try (EpisodicMemoryStore store = new EpisodicMemoryStore(storePath, VEC_BYTES, CAPACITY)) {
            for (int i = 0; i < 50; i++) {
                CognitiveHeader header = CognitiveHeader.create(
                        System.currentTimeMillis(), i * 7L, 1.0f,
                        (float) i / 10, (short) 0, MemoryType.EPISODIC);
                byte[] vec = makeVec(i);
                store.append(header, vec);
            }
            assertThat(store.totalRecords()).isEqualTo(50);
        }

        // Reopen — should recover all records from mmap files
        try (EpisodicMemoryStore store2 = new EpisodicMemoryStore(storePath, VEC_BYTES, CAPACITY)) {
            assertThat(store2.totalRecords()).isEqualTo(50);
            assertThat(store2.partitionCount()).isEqualTo(1);

            // Verify record content
            EpisodicMemoryStore.EpisodicPartition partition = store2.partitions().getFirst();
            CognitiveRecordLayout layout = partition.layout();
            var segment = partition.segment();

            // Check first record
            long offset0 = partition.recordOffset(0);
            assertThat(layout.readImportance(segment, offset0)).isEqualTo(0f);

            // Check last record
            long offset49 = partition.recordOffset(49);
            assertThat(layout.readImportance(segment, offset49)).isEqualTo(4.9f);
        }
    }

    @Test
    void metadataHeaderPreservesCountAndTombstones() {
        try (EpisodicMemoryStore store = new EpisodicMemoryStore(storePath, VEC_BYTES, CAPACITY)) {
            for (int i = 0; i < 20; i++) {
                CognitiveHeader header = CognitiveHeader.create(
                        System.currentTimeMillis(), 0L, 1.0f, 1.0f, (short) 0, MemoryType.EPISODIC);
                store.append(header, makeVec(i));
            }

            // Tombstone some records
            EpisodicMemoryStore.EpisodicPartition partition = store.partitions().getFirst();
            var segment = partition.segment();
            var layout = partition.layout();
            for (int i = 0; i < 5; i++) {
                layout.tombstone(segment, partition.recordOffset(i));
                partition.incrementTombstoneCount();
            }

            assertThat(partition.count()).isEqualTo(20);
            assertThat(partition.tombstoneCount()).isEqualTo(5);
        }

        // Reopen and verify metadata
        try (EpisodicMemoryStore store2 = new EpisodicMemoryStore(storePath, VEC_BYTES, CAPACITY)) {
            EpisodicMemoryStore.EpisodicPartition partition = store2.partitions().getFirst();
            assertThat(partition.count()).isEqualTo(20);
            assertThat(partition.tombstoneCount()).isEqualTo(5);
            assertThat(partition.tombstoneRatio()).isEqualTo(0.25f);
        }
    }

    @Test
    void appendAfterRecovery() {
        try (EpisodicMemoryStore store = new EpisodicMemoryStore(storePath, VEC_BYTES, CAPACITY)) {
            for (int i = 0; i < 10; i++) {
                store.append(CognitiveHeader.create(
                        System.currentTimeMillis(), 0L, 1.0f, 1.0f, (short) 0, MemoryType.EPISODIC), makeVec(i));
            }
        }

        try (EpisodicMemoryStore store2 = new EpisodicMemoryStore(storePath, VEC_BYTES, CAPACITY)) {
            assertThat(store2.totalRecords()).isEqualTo(10);

            // Append more records
            for (int i = 10; i < 20; i++) {
                store2.append(CognitiveHeader.create(
                        System.currentTimeMillis(), 0L, 1.0f, 2.0f, (short) 0, MemoryType.EPISODIC), makeVec(i));
            }
            assertThat(store2.totalRecords()).isEqualTo(20);
        }

        // Third open — verify all 20
        try (EpisodicMemoryStore store3 = new EpisodicMemoryStore(storePath, VEC_BYTES, CAPACITY)) {
            assertThat(store3.totalRecords()).isEqualTo(20);

            EpisodicMemoryStore.EpisodicPartition partition = store3.partitions().getFirst();
            var layout = partition.layout();
            var segment = partition.segment();

            // First 10 have importance 1.0, next 10 have importance 2.0
            assertThat(layout.readImportance(segment, partition.recordOffset(5))).isEqualTo(1.0f);
            assertThat(layout.readImportance(segment, partition.recordOffset(15))).isEqualTo(2.0f);
        }
    }

    // ── Partition State ──

    @Test
    void partitionStatesLifecycle() {
        try (EpisodicMemoryStore store = new EpisodicMemoryStore(storePath, VEC_BYTES, CAPACITY)) {
            store.append(CognitiveHeader.create(
                    System.currentTimeMillis(), 0L, 1.0f, 1.0f, (short) 0, MemoryType.EPISODIC), makeVec(0));

            EpisodicMemoryStore.EpisodicPartition partition = store.partitions().getFirst();
            assertThat(partition.state()).isEqualTo(EpisodicMemoryStore.PartitionState.ACTIVE);

            partition.seal();
            assertThat(partition.state()).isEqualTo(EpisodicMemoryStore.PartitionState.SEALED);

            partition.setState(EpisodicMemoryStore.PartitionState.REFLECTABLE);
            assertThat(partition.state()).isEqualTo(EpisodicMemoryStore.PartitionState.REFLECTABLE);
        }
    }

    // ── Partition File Structure ──

    @Test
    void partitionFileCreatedOnDisk() throws IOException {
        try (EpisodicMemoryStore store = new EpisodicMemoryStore(storePath, VEC_BYTES, CAPACITY)) {
            store.append(CognitiveHeader.create(
                    System.currentTimeMillis(), 0L, 1.0f, 1.0f, (short) 0, MemoryType.EPISODIC), makeVec(0));
        }

        // Verify partition file exists
        try (var files = Files.list(storePath)) {
            long memFiles = files.filter(p -> p.getFileName().toString().endsWith(".mem")).count();
            assertThat(memFiles).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void recordOffsetAccountsForMetadataHeader() {
        try (EpisodicMemoryStore store = new EpisodicMemoryStore(storePath, VEC_BYTES, CAPACITY)) {
            store.append(CognitiveHeader.create(
                    System.currentTimeMillis(), 0L, 1.0f, 1.0f, (short) 0, MemoryType.EPISODIC), makeVec(0));

            EpisodicMemoryStore.EpisodicPartition partition = store.partitions().getFirst();

            // First record should be at offset 64 (METADATA_HEADER_BYTES)
            long offset0 = partition.recordOffset(0);
            assertThat(offset0).isEqualTo(EpisodicMemoryStore.EpisodicPartition.METADATA_HEADER_BYTES);

            // Second record should be at offset 64 + stride
            long offset1 = partition.recordOffset(1);
            assertThat(offset1).isEqualTo(
                    EpisodicMemoryStore.EpisodicPartition.METADATA_HEADER_BYTES + partition.layout().stride());
        }
    }

    // ── Replace Partition (for compaction) ──

    @Test
    void replacePartitionSwapsAtomically() {
        try (EpisodicMemoryStore store = new EpisodicMemoryStore(storePath, VEC_BYTES, CAPACITY)) {
            // Create partition with 10 records
            for (int i = 0; i < 10; i++) {
                store.append(CognitiveHeader.create(
                        System.currentTimeMillis(), 0L, 1.0f, (float) i, (short) 0, MemoryType.EPISODIC), makeVec(i));
            }

            EpisodicMemoryStore.EpisodicPartition old = store.partitions().getFirst();
            String key = store.keyForPartition(old);
            assertThat(key).isNotNull();

            // Create a "compacted" replacement
            Path compactedPath = storePath.resolve("episodic-" + key + "-compacted.mem");
            EpisodicMemoryStore.EpisodicPartition replacement =
                    new EpisodicMemoryStore.EpisodicPartition(compactedPath, store.layout(), 50, true);

            // Copy only 5 records to the replacement
            for (int i = 0; i < 5; i++) {
                CognitiveHeader header = old.layout().readHeader(old.segment(), old.recordOffset(i));
                replacement.append(header, makeVec(i));
            }

            boolean swapped = store.replacePartition(key, old, replacement);
            assertThat(swapped).isTrue();
            assertThat(store.totalRecords()).isEqualTo(5);
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
