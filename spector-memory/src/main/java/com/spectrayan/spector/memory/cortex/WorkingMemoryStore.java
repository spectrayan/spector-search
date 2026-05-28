package com.spectrayan.spector.memory.cortex;

import com.spectrayan.spector.memory.MemoryType;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;
import com.spectrayan.spector.memory.synapse.SynapticHeaderConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;

/**
 * Volatile or persistent scratchpad for short-term Working Memory.
 *
 * <h3>Biological Analog: Prefrontal Cortex</h3>
 * <p>The prefrontal cortex holds a small number of items in active consciousness
 * (~7 ± 2 according to Miller's Law). Working memory is volatile — it exists
 * only while the session is active and is discarded when the session ends.</p>
 *
 * <h3>Persistence</h3>
 * <p>When file-backed ({@code filePath} constructor), the circular buffer and
 * its {@code writeIndex} are persisted via mmap. The {@code writeIndex} is
 * stored in the metadata header's {@code extra1} field (offset 24). On restart,
 * the agent resumes with its previous "train of thought."</p>
 *
 * <h3>Design</h3>
 * <ul>
 *   <li>Extends {@link AbstractTierStore} for common Arena/layout/segment lifecycle</li>
 *   <li>Fixed capacity (default: 100 records)</li>
 *   <li>FIFO eviction when full — oldest items are overwritten (circular buffer)</li>
 *   <li>Flat Panama scan — no index needed (working set is small)</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>Uses a shared Arena. Write access is synchronized; reads are lock-free
 * (scan over immutable segments).</p>
 */
public final class WorkingMemoryStore extends AbstractTierStore {

    private static final Logger log = LoggerFactory.getLogger(WorkingMemoryStore.class);

    private int writeIndex = 0;  // circular buffer index

    /**
     * Creates a volatile Working Memory store (in-memory only).
     *
     * @param quantizedVecBytes bytes per quantized vector
     * @param capacity          maximum number of records (default: 100)
     */
    public WorkingMemoryStore(int quantizedVecBytes, int capacity) {
        super(quantizedVecBytes, capacity,
                (long) new com.spectrayan.spector.memory.synapse.CognitiveRecordLayout(quantizedVecBytes).stride() * capacity);

        log.info("WorkingMemoryStore initialized: capacity={}, stride={}B, total={}KB, persistent=false",
                capacity, layout.stride(), (long) layout.stride() * capacity / 1024);
    }

    /**
     * Creates a persistent Working Memory store backed by an mmap file.
     *
     * <p>On restart, {@code count} and {@code writeIndex} are restored from
     * the metadata header, allowing the circular buffer to resume exactly
     * where it left off.</p>
     *
     * @param quantizedVecBytes bytes per quantized vector
     * @param capacity          maximum number of records
     * @param filePath          path to the backing mmap file
     */
    public WorkingMemoryStore(int quantizedVecBytes, int capacity, Path filePath) {
        super(quantizedVecBytes, capacity,
                (long) new com.spectrayan.spector.memory.synapse.CognitiveRecordLayout(quantizedVecBytes).stride() * capacity,
                filePath);

        // Restore writeIndex from metadata header extra1 field
        if (persistent && count > 0) {
            this.writeIndex = segment.get(ValueLayout.JAVA_INT, META_EXTRA1);
            log.info("WorkingMemoryStore restored: writeIndex={}, count={}", writeIndex, count);
        }

        log.info("WorkingMemoryStore initialized: capacity={}, stride={}B, persistent=true",
                capacity, layout.stride());
    }

    /**
     * Creates a volatile Working Memory store with default capacity (100).
     */
    public WorkingMemoryStore(int quantizedVecBytes) {
        this(quantizedVecBytes, 100);
    }

    @Override
    public MemoryType type() {
        return MemoryType.WORKING;
    }

    @Override
    public long write(CognitiveHeader header, byte[] quantizedVec) {
        long offset = dataOffset() + (long) writeIndex * layout.stride();
        put(header, quantizedVec);
        return offset;
    }

    /**
     * Appends a record to the working memory circular buffer.
     *
     * <p>If the buffer is full, the oldest record is overwritten (FIFO eviction).
     * The evicted record's tombstone flag is set before overwrite.</p>
     *
     * @param header       cognitive header for this memory
     * @param quantizedVec the quantized vector bytes
     */
    public synchronized void put(CognitiveHeader header, byte[] quantizedVec) {
        long offset = dataOffset() + (long) writeIndex * layout.stride();

        // If we're overwriting an existing record, mark it as evicted
        if (count >= capacity) {
            log.trace("Working memory full — evicting slot {}", writeIndex);
        }

        // Write header
        layout.writeHeader(segment, offset, header);

        // Write quantized vector payload
        MemorySegment.copy(
                MemorySegment.ofArray(quantizedVec), 0,
                segment, layout.vectorOffset(offset),
                quantizedVec.length
        );

        // Advance circular buffer
        writeIndex = (writeIndex + 1) % capacity;
        count = Math.min(count + 1, capacity);

        // Persist count and writeIndex to metadata header
        persistCount();
        if (persistent) {
            segment.set(ValueLayout.JAVA_INT, META_EXTRA1, writeIndex);
        }
    }

    /**
     * Flat scans all live records and returns matching results.
     *
     * <p>This is a linear scan over at most {@code capacity} records.
     * Since Working Memory is small (≤100 records), this is fast (~2-5µs).</p>
     *
     * @param queryTagMask synaptic tag filter (0 = match all)
     * @return array of offsets that passed the filter, for scoring
     */
    public long[] scan(long queryTagMask) {
        long[] matches = new long[count];
        int matchCount = 0;

        for (int i = 0; i < count; i++) {
            long offset = dataOffset() + (long) i * layout.stride();

            // Phase 1: Skip tombstones
            byte flags = layout.readFlags(segment, offset);
            if (SynapticHeaderConstants.isTombstoned(flags)) continue;

            // Phase 2: Synaptic tag gating
            if (queryTagMask != 0) {
                long recordTags = layout.readSynapticTags(segment, offset);
                if ((recordTags & queryTagMask) != queryTagMask) continue;
            }

            matches[matchCount++] = offset;
        }

        // Trim
        long[] result = new long[matchCount];
        System.arraycopy(matches, 0, result, 0, matchCount);
        return result;
    }
}

