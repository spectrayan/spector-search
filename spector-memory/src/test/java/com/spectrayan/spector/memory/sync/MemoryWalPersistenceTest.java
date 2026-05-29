package com.spectrayan.spector.memory.sync;

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
 * Tests for file-backed WAL persistence, crash recovery, and chunk rolling.
 */
class MemoryWalPersistenceTest {

    @TempDir
    Path tempDir;

    private Path walDir;

    @BeforeEach
    void setUp() {
        walDir = tempDir.resolve("wal");
    }

    // ── Basic Persistence ──

    @Test
    void writeAndRecoverEvents() {
        // Write events to file-backed WAL
        try (MemoryWal wal = new MemoryWal(walDir)) {
            for (int i = 0; i < 100; i++) {
                wal.appendRemember("mem-" + i, ("payload-" + i).getBytes());
            }
            assertThat(wal.size()).isEqualTo(100);
            assertThat(wal.highWaterMark()).isEqualTo(100);
        }

        // Reopen — should recover all events from disk
        try (MemoryWal wal2 = new MemoryWal(walDir)) {
            assertThat(wal2.size()).isEqualTo(100);
            assertThat(wal2.highWaterMark()).isEqualTo(100);

            // Verify event content
            List<WalEvent> all = wal2.replay(0);
            assertThat(all).hasSize(100);
            assertThat(all.getFirst().memoryId()).isEqualTo("mem-0");
            assertThat(all.getFirst().type()).isEqualTo(WalEvent.EventType.REMEMBER);
            assertThat(all.getLast().memoryId()).isEqualTo("mem-99");
        }
    }

    @Test
    void recoverPreservesAllEventTypes() {
        try (MemoryWal wal = new MemoryWal(walDir)) {
            wal.appendRemember("r1", new byte[]{1, 2, 3});
            wal.appendForget("f1");
            wal.appendReinforce("r1", (byte) 64);
            wal.append(WalEvent.EventType.REFLECT, "system", null);
            wal.append(WalEvent.EventType.TAG_MERGE, "t1", new byte[]{10});
            wal.append(WalEvent.EventType.RECALL_HIT, "r1", null);
        }

        try (MemoryWal wal2 = new MemoryWal(walDir)) {
            List<WalEvent> all = wal2.replay(0);
            assertThat(all).hasSize(6);
            assertThat(all.get(0).type()).isEqualTo(WalEvent.EventType.REMEMBER);
            assertThat(all.get(0).payload()).containsExactly(1, 2, 3);
            assertThat(all.get(1).type()).isEqualTo(WalEvent.EventType.FORGET);
            assertThat(all.get(2).type()).isEqualTo(WalEvent.EventType.REINFORCE);
            assertThat(all.get(2).payload()).containsExactly(64);
            assertThat(all.get(3).type()).isEqualTo(WalEvent.EventType.REFLECT);
            assertThat(all.get(4).type()).isEqualTo(WalEvent.EventType.TAG_MERGE);
            assertThat(all.get(5).type()).isEqualTo(WalEvent.EventType.RECALL_HIT);
        }
    }

    @Test
    void appendAfterRecovery() {
        try (MemoryWal wal = new MemoryWal(walDir)) {
            wal.appendRemember("a", new byte[0]);
            wal.appendRemember("b", new byte[0]);
        }

        try (MemoryWal wal2 = new MemoryWal(walDir)) {
            assertThat(wal2.highWaterMark()).isEqualTo(2);

            // Append more events after recovery
            wal2.appendRemember("c", new byte[0]);
            wal2.appendRemember("d", new byte[0]);
            assertThat(wal2.highWaterMark()).isEqualTo(4);
            assertThat(wal2.size()).isEqualTo(4);
        }

        // Third open — should see all 4
        try (MemoryWal wal3 = new MemoryWal(walDir)) {
            assertThat(wal3.size()).isEqualTo(4);
            List<WalEvent> all = wal3.replay(0);
            assertThat(all.get(2).memoryId()).isEqualTo("c");
            assertThat(all.get(3).memoryId()).isEqualTo("d");
        }
    }

    // ── Replay Filtering ──

    @Test
    void replayAfterSequenceFilters() {
        try (MemoryWal wal = new MemoryWal(walDir)) {
            wal.appendRemember("a", new byte[0]); // seq 1
            wal.appendRemember("b", new byte[0]); // seq 2
            wal.appendRemember("c", new byte[0]); // seq 3
            wal.appendForget("a");                 // seq 4
            wal.appendRemember("d", new byte[0]); // seq 5

            List<WalEvent> afterTwo = wal.replay(2);
            assertThat(afterTwo).hasSize(3);
            assertThat(afterTwo.get(0).sequence()).isEqualTo(3);
            assertThat(afterTwo.get(0).memoryId()).isEqualTo("c");
        }
    }

    // ── Chunk Rolling ──

    @Test
    void chunkRollingCreatesMultipleFiles() throws IOException {
        // Use a tiny max chunk size to force rolling
        long tinyChunkSize = 256; // 256 bytes

        try (MemoryWal wal = new MemoryWal(walDir, tinyChunkSize)) {
            for (int i = 0; i < 50; i++) {
                wal.appendRemember("mem-" + i, ("large-payload-data-" + i).getBytes());
            }
        }

        // Should have created multiple chunk files
        long chunkCount;
        try (var stream = Files.list(walDir)) {
            chunkCount = stream
                    .filter(p -> p.getFileName().toString().startsWith("wal-") &&
                                 p.getFileName().toString().endsWith(".bin"))
                    .count();
        }
        assertThat(chunkCount).isGreaterThan(1);

        // Reopen — should recover all events across all chunks
        try (MemoryWal wal2 = new MemoryWal(walDir, tinyChunkSize)) {
            assertThat(wal2.size()).isEqualTo(50);
            assertThat(wal2.highWaterMark()).isEqualTo(50);
        }
    }

    // ── Disk Replay ──

    @Test
    void replayFromDiskMatchesInMemory() {
        try (MemoryWal wal = new MemoryWal(walDir)) {
            wal.appendRemember("x", new byte[]{1});
            wal.appendForget("y");
            wal.appendReinforce("x", (byte) -10);
        }

        try (MemoryWal wal2 = new MemoryWal(walDir)) {
            List<WalEvent> fromDisk = wal2.replayFromDisk();
            List<WalEvent> fromMemory = wal2.replay(0);

            assertThat(fromDisk).hasSize(fromMemory.size());
            for (int i = 0; i < fromDisk.size(); i++) {
                assertThat(fromDisk.get(i).sequence()).isEqualTo(fromMemory.get(i).sequence());
                assertThat(fromDisk.get(i).type()).isEqualTo(fromMemory.get(i).type());
                assertThat(fromDisk.get(i).memoryId()).isEqualTo(fromMemory.get(i).memoryId());
            }
        }
    }

    // ── In-Memory Mode ──

    @Test
    void inMemoryModeWorksWithoutFiles() {
        try (MemoryWal wal = new MemoryWal()) {
            assertThat(wal.isPersistent()).isFalse();
            assertThat(wal.path()).isNull();

            wal.appendRemember("a", new byte[0]);
            wal.appendRemember("b", new byte[0]);

            assertThat(wal.size()).isEqualTo(2);
            assertThat(wal.replay(0)).hasSize(2);
            assertThat(wal.replayFromDisk()).isEmpty();
        }
    }

    // ── Edge Cases ──

    @Test
    void emptyWalRecovery() {
        // Create and immediately close
        try (MemoryWal wal = new MemoryWal(walDir)) {
            assertThat(wal.size()).isEqualTo(0);
        }

        // Reopen empty WAL
        try (MemoryWal wal2 = new MemoryWal(walDir)) {
            assertThat(wal2.size()).isEqualTo(0);
            assertThat(wal2.highWaterMark()).isEqualTo(0);
        }
    }

    @Test
    void largePayloadRoundTrips() {
        byte[] largePayload = new byte[4096];
        for (int i = 0; i < largePayload.length; i++) {
            largePayload[i] = (byte) (i % 256);
        }

        try (MemoryWal wal = new MemoryWal(walDir)) {
            wal.appendRemember("large", largePayload);
        }

        try (MemoryWal wal2 = new MemoryWal(walDir)) {
            List<WalEvent> events = wal2.replay(0);
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().payload()).isEqualTo(largePayload);
        }
    }

    @Test
    void unicodeMemoryIdRoundTrips() {
        try (MemoryWal wal = new MemoryWal(walDir)) {
            wal.appendRemember("日本語テスト-🧠", new byte[]{42});
        }

        try (MemoryWal wal2 = new MemoryWal(walDir)) {
            List<WalEvent> events = wal2.replay(0);
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().memoryId()).isEqualTo("日本語テスト-🧠");
        }
    }

    // ── V2 Upgrades: Compression, Checksums, Auto-Repair & Compaction ──

    @Test
    void payloadCompressionRoundTrips() {
        int threshold = 100;
        try (MemoryWal wal = new MemoryWal(walDir, 8L * 1024 * 1024, true, threshold, false)) {
            byte[] smallPayload = "small".getBytes();
            byte[] largePayload = "large-payload-string-that-definitely-exceeds-the-hundred-bytes-threshold-for-compression-testing".repeat(3).getBytes();

            wal.appendRemember("small-id", smallPayload);
            wal.appendRemember("large-id", largePayload);
        }

        try (MemoryWal wal2 = new MemoryWal(walDir, 8L * 1024 * 1024, true, threshold, false)) {
            List<WalEvent> recovered = wal2.replay(0);
            assertThat(recovered).hasSize(2);
            
            WalEvent smallEvent = recovered.get(0);
            assertThat(smallEvent.memoryId()).isEqualTo("small-id");
            assertThat(smallEvent.payload()).isEqualTo("small".getBytes());

            WalEvent largeEvent = recovered.get(1);
            assertThat(largeEvent.memoryId()).isEqualTo("large-id");
            assertThat(largeEvent.payload()).isEqualTo("large-payload-string-that-definitely-exceeds-the-hundred-bytes-threshold-for-compression-testing".repeat(3).getBytes());
        }
    }

    @Test
    void fsyncConfigurationRespected() {
        try (MemoryWal wal = new MemoryWal(walDir, 8L * 1024 * 1024, false, 1024, true)) {
            wal.appendRemember("id-fsync", new byte[]{1, 2, 3});
        }
        try (MemoryWal wal2 = new MemoryWal(walDir)) {
            List<WalEvent> events = wal2.replay(0);
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().memoryId()).isEqualTo("id-fsync");
        }
    }

    @Test
    void tornWriteAutoRepair() throws IOException {
        try (MemoryWal wal = new MemoryWal(walDir)) {
            wal.appendRemember("m1", new byte[]{10});
            wal.appendRemember("m2", new byte[]{20});
            wal.appendRemember("m3", new byte[]{30});
        }

        Path activeChunk = walDir.resolve(MemoryWal.chunkFileName(0));
        long fileSize = Files.size(activeChunk);
        
        try (var out = Files.newOutputStream(activeChunk, java.nio.file.StandardOpenOption.APPEND)) {
            out.write(new byte[]{0x57, 0x41, 0, 0, 1, 0, 0, 0, 9, 9, 9, 9, 9, 9, 9});
        }

        try (MemoryWal wal2 = new MemoryWal(walDir)) {
            assertThat(wal2.size()).isEqualTo(3);
            List<WalEvent> events = wal2.replay(0);
            assertThat(events.get(0).memoryId()).isEqualTo("m1");
            assertThat(events.get(1).memoryId()).isEqualTo("m2");
            assertThat(events.get(2).memoryId()).isEqualTo("m3");
            
            assertThat(Files.size(activeChunk)).isEqualTo(fileSize);
            
            wal2.appendRemember("m4", new byte[]{40});
            assertThat(wal2.size()).isEqualTo(4);
        }
    }

    @Test
    void middleOfLogCorruptionQuarantine() throws IOException {
        try (MemoryWal wal = new MemoryWal(walDir)) {
            wal.appendRemember("m1", new byte[]{10});
            wal.appendRemember("m2", new byte[]{20});
            wal.appendRemember("m3", new byte[]{30});
            wal.appendRemember("m4", new byte[]{40});
            wal.appendRemember("m5", new byte[]{50});
        }

        Path activeChunk = walDir.resolve(MemoryWal.chunkFileName(0));
        byte[] bytes = Files.readAllBytes(activeChunk);
        
        bytes[60] ^= (byte) 0xFF;
        Files.write(activeChunk, bytes);

        org.junit.jupiter.api.Assertions.assertThrows(java.io.UncheckedIOException.class, () -> {
            new MemoryWal(walDir);
        });

        Path quarantinedPath = walDir.resolve(".quarantine").resolve(activeChunk.getFileName());
        assertThat(Files.exists(quarantinedPath)).isTrue();
        assertThat(Files.exists(activeChunk)).isFalse();
    }

    @Test
    void snapshotDrivenLogTruncation() throws IOException {
        long tinyChunkSize = 256; 
        try (MemoryWal wal = new MemoryWal(walDir, tinyChunkSize)) {
            for (int i = 0; i < 30; i++) {
                wal.appendRemember("mem-" + i, ("payload-string-to-exceed-chunk-boundary-" + i).getBytes());
            }
        }

        List<Path> initialChunks;
        try (var stream = Files.list(walDir)) {
            initialChunks = stream
                    .filter(p -> p.getFileName().toString().startsWith("wal-") &&
                                 p.getFileName().toString().endsWith(".bin"))
                    .sorted()
                    .toList();
        }
        assertThat(initialChunks.size()).isGreaterThan(2);

        long maxSeqInChunk0;
        try (MemoryWal wal2 = new MemoryWal(walDir, tinyChunkSize)) {
            maxSeqInChunk0 = wal2.getMaxSequenceInChunk(initialChunks.get(0));
            assertThat(maxSeqInChunk0).isGreaterThan(0);

            wal2.truncateBefore(maxSeqInChunk0);
        }

        assertThat(Files.exists(initialChunks.get(0))).isFalse();
        assertThat(Files.exists(initialChunks.get(initialChunks.size() - 1))).isTrue();
    }
}
