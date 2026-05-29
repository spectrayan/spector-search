package com.spectrayan.spector.memory.cortex;

import com.spectrayan.spector.memory.MemoryType;
import com.spectrayan.spector.memory.index.MemoryIndex;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests file-backed persistence for Working, Semantic, and Procedural
 * memory tier stores, plus MemoryIndex save/load round-trip.
 */
class MemoryPersistenceTest {

    private static final int VEC_BYTES = 32;
    private static final int CAPACITY = 50;

    @TempDir
    Path tmpDir;

    private CognitiveHeader createHeader(long timestamp, float importance) {
        return CognitiveHeader.create(timestamp, 0xCAFEL, 1.0f, importance, (short) 0, MemoryType.WORKING);
    }

    private byte[] dummyVec(int len, byte fill) {
        byte[] vec = new byte[len];
        java.util.Arrays.fill(vec, fill);
        return vec;
    }

    // ══════════════════════════════════════════════════════════════
    // WORKING MEMORY STORE — round-trip persistence
    // ══════════════════════════════════════════════════════════════

    @Test
    void workingStore_persistsAndRecoversCircularBuffer() {
        Path file = tmpDir.resolve("working.mem");

        // Write 5 records
        try (var store = new WorkingMemoryStore(VEC_BYTES, CAPACITY, file)) {
            for (int i = 0; i < 5; i++) {
                store.put(createHeader(1000L + i, 0.5f + i * 0.1f), dummyVec(VEC_BYTES, (byte) (i + 1)));
            }
            assertThat(store.size()).isEqualTo(5);
            assertThat(store.isPersistent()).isTrue();
        }

        // Reopen and verify count
        try (var store = new WorkingMemoryStore(VEC_BYTES, CAPACITY, file)) {
            assertThat(store.size()).isEqualTo(5);

            // Write 2 more and verify they stack correctly
            store.put(createHeader(2000L, 0.9f), dummyVec(VEC_BYTES, (byte) 99));
            store.put(createHeader(2001L, 0.95f), dummyVec(VEC_BYTES, (byte) 100));
            assertThat(store.size()).isEqualTo(7);
        }

        // Reopen again — count should be 7
        try (var store = new WorkingMemoryStore(VEC_BYTES, CAPACITY, file)) {
            assertThat(store.size()).isEqualTo(7);
        }
    }

    @Test
    void workingStore_circularBufferWraparound_survivesPersistence() {
        Path file = tmpDir.resolve("working_wrap.mem");
        int smallCap = 5;

        // Fill and wrap — write 8 records into a 5-slot buffer
        try (var store = new WorkingMemoryStore(VEC_BYTES, smallCap, file)) {
            for (int i = 0; i < 8; i++) {
                store.put(createHeader(1000L + i, 0.5f), dummyVec(VEC_BYTES, (byte) i));
            }
            assertThat(store.size()).isEqualTo(smallCap); // capped at capacity
        }

        // Reopen — count should still be 5 (capacity)
        try (var store = new WorkingMemoryStore(VEC_BYTES, smallCap, file)) {
            assertThat(store.size()).isEqualTo(smallCap);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // SEMANTIC MEMORY STORE — round-trip persistence
    // ══════════════════════════════════════════════════════════════

    @Test
    void semanticStore_persistsAndRecoversHeaders() {
        Path file = tmpDir.resolve("semantic.mem");

        // Write 3 headers
        try (var store = new SemanticMemoryStore(VEC_BYTES, CAPACITY, file)) {
            for (int i = 0; i < 3; i++) {
                var header = CognitiveHeader.create(
                        System.currentTimeMillis(), 0xBEEFL, 1.0f, 0.7f + i * 0.1f, (short) i, MemoryType.SEMANTIC);
                store.store(header);
            }
            assertThat(store.size()).isEqualTo(3);
        }

        // Reopen and verify
        try (var store = new SemanticMemoryStore(VEC_BYTES, CAPACITY, file)) {
            assertThat(store.size()).isEqualTo(3);

            // Read back first header and verify importance
            var h0 = store.readHeader(0);
            assertThat(h0.importance()).isCloseTo(0.7f, org.assertj.core.data.Offset.offset(0.01f));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // PROCEDURAL MEMORY STORE — round-trip persistence
    // ══════════════════════════════════════════════════════════════

    @Test
    void proceduralStore_persistsAndRecoversRecords() {
        Path file = tmpDir.resolve("procedural.mem");

        try (var store = new ProceduralMemoryStore(VEC_BYTES, CAPACITY, file)) {
            for (int i = 0; i < 4; i++) {
                store.append(createHeader(3000L + i, 1.0f), dummyVec(VEC_BYTES, (byte) (i + 10)));
            }
            assertThat(store.size()).isEqualTo(4);
        }

        try (var store = new ProceduralMemoryStore(VEC_BYTES, CAPACITY, file)) {
            assertThat(store.size()).isEqualTo(4);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // MEMORY INDEX — save / load round-trip
    // ══════════════════════════════════════════════════════════════

    @Test
    void memoryIndex_saveAndLoad_preservesAllMaps() {
        Path file = tmpDir.resolve("memory-index.mem");

        MemoryIndex original = new MemoryIndex();

        // Register 3 entries with different types and tags
        original.register("mem-1",
                new MemoryIndex.MemoryLocation(MemoryType.EPISODIC, 64L, 0),
                "The cat sat on the mat", MemorySource.OBSERVED, new String[]{"animal", "location"});

        original.register("mem-2",
                new MemoryIndex.MemoryLocation(MemoryType.SEMANTIC, 128L, -1),
                "Java 25 supports Panama FFM API", MemorySource.USER_STATED, new String[]{"java", "panama"});

        original.register("mem-3",
                new MemoryIndex.MemoryLocation(MemoryType.PROCEDURAL, 0L, -1),
                "Use ScalarQuantizer for 8-bit encoding", MemorySource.PROCEDURAL, new String[]{});

        // Save
        original.save(file);

        // Load
        MemoryIndex loaded = MemoryIndex.load(file);

        // Verify sizes
        assertThat(loaded.size()).isEqualTo(3);

        // Verify forward index
        assertThat(loaded.locate("mem-1")).isNotNull();
        assertThat(loaded.locate("mem-1").type()).isEqualTo(MemoryType.EPISODIC);
        assertThat(loaded.locate("mem-1").offset()).isEqualTo(64L);
        assertThat(loaded.locate("mem-1").partitionIndex()).isEqualTo(0);
        assertThat(loaded.text("mem-1")).isEqualTo("The cat sat on the mat");
        assertThat(loaded.source("mem-1")).isEqualTo(MemorySource.OBSERVED);
        assertThat(loaded.tags("mem-1")).containsExactly("animal", "location");

        assertThat(loaded.locate("mem-2").type()).isEqualTo(MemoryType.SEMANTIC);
        assertThat(loaded.text("mem-2")).isEqualTo("Java 25 supports Panama FFM API");
        assertThat(loaded.source("mem-2")).isEqualTo(MemorySource.USER_STATED);

        assertThat(loaded.text("mem-3")).isEqualTo("Use ScalarQuantizer for 8-bit encoding");
        assertThat(loaded.tags("mem-3")).isEmpty();

        // Verify reverse index
        assertThat(loaded.findIdByOffset(MemoryType.EPISODIC, 64L)).isEqualTo("mem-1");
        assertThat(loaded.findIdByOffset(MemoryType.SEMANTIC, 128L)).isEqualTo("mem-2");
        assertThat(loaded.findIdByOffset(MemoryType.PROCEDURAL, 0L)).isEqualTo("mem-3");
    }

    @Test
    void memoryIndex_load_missingFile_returnsEmpty() {
        MemoryIndex loaded = MemoryIndex.load(tmpDir.resolve("nonexistent.mem"));
        assertThat(loaded.size()).isEqualTo(0);
    }

    @Test
    void memoryIndex_load_nullPath_returnsEmpty() {
        MemoryIndex loaded = MemoryIndex.load(null);
        assertThat(loaded.size()).isEqualTo(0);
    }
}
