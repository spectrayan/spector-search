package com.spectrayan.spector.memory.cortex;

import com.spectrayan.spector.memory.MemoryType;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;
import com.spectrayan.spector.memory.synapse.SynapticHeaderConstants;
import com.spectrayan.spector.memory.synapse.SynapticTagEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link WorkingMemoryStore} — volatile circular buffer.
 */
class WorkingMemoryStoreTest {

    private static final int VEC_BYTES = 32; // small vectors for testing
    private WorkingMemoryStore store;

    @BeforeEach
    void setUp() {
        store = new WorkingMemoryStore(VEC_BYTES, 5); // capacity of 5 for easy testing
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void putAndSize() {
        assertThat(store.size()).isZero();

        store.put(createHeader("java"), new byte[VEC_BYTES]);
        assertThat(store.size()).isEqualTo(1);

        store.put(createHeader("python"), new byte[VEC_BYTES]);
        assertThat(store.size()).isEqualTo(2);
    }

    @Test
    void fifoEvictionWhenFull() {
        // Fill to capacity
        for (int i = 0; i < 5; i++) {
            store.put(createHeader("tag-" + i), new byte[VEC_BYTES]);
        }
        assertThat(store.size()).isEqualTo(5);

        // One more should evict oldest (FIFO, size stays at capacity)
        store.put(createHeader("tag-overflow"), new byte[VEC_BYTES]);
        assertThat(store.size()).isEqualTo(5); // stays at capacity
    }

    @Test
    void scanReturnsMatchingOffsets() {
        long javaTag = SynapticTagEncoder.encode("java");
        long pythonTag = SynapticTagEncoder.encode("python");

        store.put(createHeader("java"), new byte[VEC_BYTES]);
        store.put(createHeader("python"), new byte[VEC_BYTES]);
        store.put(createHeader("java", "performance"), new byte[VEC_BYTES]);

        // Scan for "java" tag
        long[] matches = store.scan(javaTag);
        assertThat(matches.length).isGreaterThanOrEqualTo(2); // at least 2 java-tagged

        // Scan with no filter (0 mask matches everything)
        long[] all = store.scan(0L);
        assertThat(all.length).isEqualTo(3);
    }

    @Test
    void scanSkipsTombstones() {
        store.put(createHeader("java"), new byte[VEC_BYTES]);
        store.put(createHeader("python"), new byte[VEC_BYTES]);

        // Tombstone the first record
        store.layout().tombstone(store.segment(), 0);

        long[] all = store.scan(0L);
        assertThat(all.length).isEqualTo(1); // only the non-tombstoned one
    }

    @Test
    void capacityIsCorrect() {
        assertThat(store.capacity()).isEqualTo(5);
    }

    private CognitiveHeader createHeader(String... tags) {
        return CognitiveHeader.create(
                System.currentTimeMillis(),
                SynapticTagEncoder.encode(tags),
                1.0f,
                1.0f,
                (short) 0,
                MemoryType.WORKING
        );
    }
}
