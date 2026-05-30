package com.spectrayan.spector.memory.cortex;

import com.spectrayan.spector.memory.MemoryType;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;
import com.spectrayan.spector.memory.synapse.SynapticHeaderConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import com.spectrayan.spector.commons.error.SpectorServerException;
import com.spectrayan.spector.commons.error.SpectorMemoryTierFullException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Time-partitioned mmap store for Episodic memory.
 *
 * <h3>Biological Analog: Hippocampus</h3>
 * <p>The hippocampus encodes events as time-ordered episodic traces. New events are
 * appended rapidly (one-trial learning), and during sleep the hippocampus replays
 * sequences for consolidation into cortical (semantic) memory.</p>
 *
 * <h3>V3 Design: Persistent mmap via FileChannel.map()</h3>
 * <ul>
 *   <li>One partition per time window (default: 1 day)</li>
 *   <li>Append-only within each partition — O(1) inserts, no graph rewiring</li>
 *   <li>Uses {@link CognitiveRecordLayout} for each record</li>
 *   <li>Flat SIMD scan per partition via the scorer</li>
 *   <li>Persistent across JVM restarts via {@code FileChannel.map()}</li>
 *   <li>64-byte metadata header per partition file tracks count, state, capacity</li>
 *   <li>Lazy mmap for old partitions (only mapped when scanned)</li>
 * </ul>
 *
 * <h3>Partition Lifecycle</h3>
 * <pre>
 *   active → sealed → reflectable → tombstoned → compacted
 * </pre>
 */
public final class EpisodicMemoryStore implements TierStore {

    private static final Logger log = LoggerFactory.getLogger(EpisodicMemoryStore.class);
    private static final DateTimeFormatter PARTITION_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final Path basePath;
    private final CognitiveRecordLayout layout;
    private final int partitionCapacity;

    // Active partition state
    private final ConcurrentMap<String, EpisodicPartition> partitions = new ConcurrentHashMap<>();

    /**
     * Creates a new Episodic Memory store.
     *
     * <p>On construction, scans {@code basePath} for existing partition files and
     * loads them as sealed partitions (lazy mmap). New partitions are created
     * on demand when {@link #append} is called.</p>
     *
     * @param basePath           directory for partition files
     * @param quantizedVecBytes  bytes per quantized vector
     * @param partitionCapacity  max records per partition (default: 10_000)
     */
    public EpisodicMemoryStore(Path basePath, int quantizedVecBytes, int partitionCapacity) {
        this.basePath = basePath;
        this.layout = new CognitiveRecordLayout(quantizedVecBytes);
        this.partitionCapacity = partitionCapacity;

        try {
            Files.createDirectories(basePath);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create episodic store directory: " + basePath, e);
        }

        // Load existing partition files from disk
        loadExistingPartitions();

        log.info("EpisodicMemoryStore initialized: path={}, stride={}B, partitionCapacity={}, loaded={}",
                basePath, layout.stride(), partitionCapacity, partitions.size());
    }

    /**
     * Appends a new memory to the current day's partition.
     *
     * @param header       cognitive header
     * @param quantizedVec quantized vector bytes
     */
    public void append(CognitiveHeader header, byte[] quantizedVec) {
        String partitionKey = currentPartitionKey();
        EpisodicPartition partition = partitions.computeIfAbsent(partitionKey,
                k -> createPartition(k));
        partition.append(header, quantizedVec);
    }

    /**
     * Returns all partitions for scanning during recall.
     */
    public List<EpisodicPartition> partitions() {
        return new ArrayList<>(partitions.values());
    }

    /**
     * Returns the partition count.
     */
    public int partitionCount() {
        return partitions.size();
    }

    /**
     * Returns the total record count across all partitions.
     */
    public int totalRecords() {
        return partitions.values().stream().mapToInt(EpisodicPartition::count).sum();
    }

    /**
     * Returns the layout for this store.
     */
    public CognitiveRecordLayout layout() {
        return layout;
    }

    @Override
    public MemoryType type() {
        return MemoryType.EPISODIC;
    }

    @Override
    public int size() {
        return totalRecords();
    }

    @Override
    public java.lang.foreign.MemorySegment primarySegment() {
        var parts = partitions();
        return parts.isEmpty() ? null : parts.getLast().segment();
    }

    @Override
    public long write(CognitiveHeader header, byte[] quantizedVec) {
        append(header, quantizedVec);
        var parts = partitions();
        if (!parts.isEmpty()) {
            var lastPartition = parts.getLast();
            // Offset must include METADATA_HEADER_BYTES to match what CognitiveScorer
            // returns during recall scanning (baseOffset = METADATA_HEADER_BYTES)
            return lastPartition.recordOffset(lastPartition.count() - 1);
        }
        return 0L;
    }

    /**
     * Atomically replaces a partition in the map.
     *
     * <p>Used by {@code TombstoneCompactor} after rebuilding a compacted partition.
     * The old partition is closed after replacement.</p>
     *
     * @param key          the partition key
     * @param oldPartition the partition being replaced
     * @param newPartition the compacted replacement
     * @return true if the swap was successful
     */
    public boolean replacePartition(String key, EpisodicPartition oldPartition,
                                     EpisodicPartition newPartition) {
        boolean replaced = partitions.replace(key, oldPartition, newPartition);
        if (replaced) {
            oldPartition.close();
            log.info("Partition '{}' replaced: {} → {} records",
                    key, oldPartition.count(), newPartition.count());
        }
        return replaced;
    }

    /**
     * Returns the partition key for a given partition, or null if not found.
     */
    public String keyForPartition(EpisodicPartition partition) {
        for (var entry : partitions.entrySet()) {
            if (entry.getValue() == partition) {
                return entry.getKey();
            }
        }
        return null;
    }

    private String currentPartitionKey() {
        return LocalDate.now().format(PARTITION_FORMAT);
    }

    private EpisodicPartition createPartition(String key) {
        Path partitionPath = basePath.resolve("episodic-" + key + ".mem");
        log.info("Creating new episodic partition: {}", partitionPath);
        return new EpisodicPartition(partitionPath, layout, partitionCapacity, true);
    }

    /**
     * Scans the base directory for existing partition files and loads them.
     * Existing partitions are loaded in SEALED state (read-only until today's
     * partition is accessed).
     */
    private void loadExistingPartitions() {
        try {
            if (!Files.isDirectory(basePath)) return;

            try (var stream = Files.list(basePath)) {
                stream.filter(p -> {
                            String name = p.getFileName().toString();
                            return name.startsWith("episodic-") && name.endsWith(".mem");
                        })
                        .forEach(p -> {
                            String name = p.getFileName().toString();
                            // Extract key: "episodic-{key}.mem" or "episodic-{key}-compacted.mem"
                            String key = name.replace("episodic-", "")
                                    .replace("-compacted.mem", "")
                                    .replace(".mem", "");
                            try {
                                EpisodicPartition partition = new EpisodicPartition(p, layout, partitionCapacity, false);
                                partitions.putIfAbsent(key, partition);
                                log.debug("Loaded existing partition: {} ({} records, state={})",
                                        key, partition.count(), partition.state());
                            } catch (Exception e) {
                                log.warn("Failed to load partition {}: {}", p, e.getMessage());
                            }
                        });
            }
        } catch (IOException e) {
            log.warn("Error scanning for existing partitions: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        log.info("EpisodicMemoryStore closing ({} partitions, {} records)",
                partitions.size(), totalRecords());
        partitions.values().forEach(EpisodicPartition::close);
        partitions.clear();
    }

    // ── Inner class: single partition ──

    /**
     * Partition lifecycle states.
     */
    public enum PartitionState {
        /** Currently accepting writes. */
        ACTIVE,
        /** No more writes; available for scanning. */
        SEALED,
        /** Eligible for sleep consolidation (ReflectDaemon). */
        REFLECTABLE,
        /** Tombstone ratio exceeds threshold; queued for compaction. */
        TOMBSTONED,
        /** Has been rebuilt by TombstoneCompactor. */
        COMPACTED
    }

    /**
     * A single time-partitioned episodic memory file.
     *
     * <h3>V3: Persistent mmap via FileChannel.map()</h3>
     * <p>Each partition is backed by an mmap'd file. The first {@value #METADATA_HEADER_BYTES}
     * bytes contain a metadata header tracking record count, tombstone count, capacity,
     * and partition state. Records begin at offset {@value #METADATA_HEADER_BYTES}.</p>
     *
     * <h3>Metadata Header Layout (64 bytes)</h3>
     * <pre>
     *   [4B magic]          Offset 0  — 0x45504943 ("EPIC")
     *   [4B version]        Offset 4  — format version (1)
     *   [4B count]          Offset 8  — number of live records
     *   [4B tombstoneCount] Offset 12 — number of tombstoned records
     *   [4B capacity]       Offset 16 — max records in this partition
     *   [4B state]          Offset 20 — PartitionState ordinal
     *   [4B stride]         Offset 24 — record stride in bytes
     *   [36B reserved]      Offset 28 — reserved for future use
     * </pre>
     */
    public static final class EpisodicPartition {

        /** Partition file magic: "EPIC" in ASCII. */
        static final int PARTITION_MAGIC = 0x45504943;

        /** Partition format version. */
        static final int PARTITION_VERSION = 1;

        /** Size of the metadata header in bytes. */
        public static final int METADATA_HEADER_BYTES = 64;

        // Metadata field offsets
        private static final int META_MAGIC           = 0;
        private static final int META_VERSION         = 4;
        private static final int META_COUNT           = 8;
        private static final int META_TOMBSTONE_COUNT = 12;
        private static final int META_CAPACITY        = 16;
        private static final int META_STATE           = 20;
        private static final int META_STRIDE          = 24;

        private final Path path;
        private final CognitiveRecordLayout layout;
        private final Arena arena;
        private MemorySegment segment;
        private final int capacity;
        private int count;
        private int tombstoneCount;
        private PartitionState state;

        private FileChannel fileChannel;

        /**
         * Creates or opens an episodic partition.
         *
         * @param path     path to the partition file
         * @param layout   the cognitive record layout
         * @param capacity max records for this partition
         * @param isNew    true to create a new partition, false to load existing
         */
        public EpisodicPartition(Path path, CognitiveRecordLayout layout, int capacity, boolean isNew) {
            this.path = path;
            this.layout = layout;
            this.capacity = capacity;
            this.arena = Arena.ofShared();

            if (isNew) {
                createNewPartition();
            } else {
                loadExistingPartition();
            }
        }

        /**
         * Creates a new partition file with metadata header and mmap'd segment.
         */
        private void createNewPartition() {
            try {
                long totalBytes = METADATA_HEADER_BYTES + (long) layout.stride() * capacity;

                fileChannel = FileChannel.open(path,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE);

                // Extend the file to full size
                fileChannel.position(totalBytes - 1);
                fileChannel.write(ByteBuffer.wrap(new byte[]{0}));

                // Map the entire file
                segment = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, totalBytes, arena);

                // Write metadata header
                this.count = 0;
                this.tombstoneCount = 0;
                this.state = PartitionState.ACTIVE;
                writeMetadata();

            } catch (IOException e) {
                throw new UncheckedIOException("Cannot create partition: " + path, e);
            }
        }

        /**
         * Loads an existing partition file — reads metadata header, then mmaps.
         */
        private void loadExistingPartition() {
            try {
                if (!Files.exists(path)) {
                    // Fallback: create as new if file doesn't exist
                    createNewPartition();
                    return;
                }

                fileChannel = FileChannel.open(path,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE);

                long fileSize = fileChannel.size();
                if (fileSize < METADATA_HEADER_BYTES) {
                    log.warn("Partition file too small ({}B), creating fresh: {}", fileSize, path);
                    fileChannel.close();
                    createNewPartition();
                    return;
                }

                // Map the entire file
                segment = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, fileSize, arena);

                // Read metadata header
                readMetadata();

                // If loaded partition is today's date, keep it ACTIVE
                String fileName = path.getFileName().toString();
                String today = LocalDate.now().format(PARTITION_FORMAT);
                if (fileName.contains(today) && state == PartitionState.ACTIVE) {
                    // Keep ACTIVE — it's today's partition
                } else if (state == PartitionState.ACTIVE) {
                    // Older partitions default to SEALED on load
                    this.state = PartitionState.SEALED;
                    writeMetadata();
                }

            } catch (IOException e) {
                throw new UncheckedIOException("Cannot load partition: " + path, e);
            }
        }

        /**
         * Writes the metadata header to the mapped segment.
         */
        private void writeMetadata() {
            segment.set(java.lang.foreign.ValueLayout.JAVA_INT, META_MAGIC, PARTITION_MAGIC);
            segment.set(java.lang.foreign.ValueLayout.JAVA_INT, META_VERSION, PARTITION_VERSION);
            segment.set(java.lang.foreign.ValueLayout.JAVA_INT, META_COUNT, count);
            segment.set(java.lang.foreign.ValueLayout.JAVA_INT, META_TOMBSTONE_COUNT, tombstoneCount);
            segment.set(java.lang.foreign.ValueLayout.JAVA_INT, META_CAPACITY, capacity);
            segment.set(java.lang.foreign.ValueLayout.JAVA_INT, META_STATE, state.ordinal());
            segment.set(java.lang.foreign.ValueLayout.JAVA_INT, META_STRIDE, layout.stride());
        }

        /**
         * Reads the metadata header from the mapped segment.
         */
        private void readMetadata() {
            int magic = segment.get(java.lang.foreign.ValueLayout.JAVA_INT, META_MAGIC);
            if (magic != PARTITION_MAGIC) {
                log.warn("Invalid partition magic in {}: 0x{} (expected 0x{})",
                        path, Integer.toHexString(magic), Integer.toHexString(PARTITION_MAGIC));
                // Treat as empty
                this.count = 0;
                this.tombstoneCount = 0;
                this.state = PartitionState.ACTIVE;
                return;
            }

            this.count = segment.get(java.lang.foreign.ValueLayout.JAVA_INT, META_COUNT);
            this.tombstoneCount = segment.get(java.lang.foreign.ValueLayout.JAVA_INT, META_TOMBSTONE_COUNT);

            int stateOrd = segment.get(java.lang.foreign.ValueLayout.JAVA_INT, META_STATE);
            if (stateOrd >= 0 && stateOrd < PartitionState.values().length) {
                this.state = PartitionState.values()[stateOrd];
            } else {
                this.state = PartitionState.ACTIVE;
            }
        }

        /**
         * Appends a record to this partition.
         *
         * <p>Records are stored after the metadata header. The offset for record
         * {@code i} is {@code METADATA_HEADER_BYTES + i * stride}.</p>
         */
        public synchronized void append(CognitiveHeader header, byte[] quantizedVec) {
            if (count >= capacity) {
                throw new SpectorMemoryTierFullException("EPISODIC", capacity);
            }

            long offset = recordOffset(count);
            layout.writeHeader(segment, offset, header);
            MemorySegment.copy(
                    MemorySegment.ofArray(quantizedVec), 0,
                    segment, layout.vectorOffset(offset),
                    quantizedVec.length
            );
            count++;

            // Update count in metadata header
            segment.set(java.lang.foreign.ValueLayout.JAVA_INT, META_COUNT, count);
        }

        /**
         * Computes the byte offset for record at logical index {@code i}.
         *
         * <p>Offset includes the metadata header:
         * {@code METADATA_HEADER_BYTES + i * stride}</p>
         *
         * @param recordIndex logical record index (0-based)
         * @return byte offset in the mapped segment
         */
        public long recordOffset(int recordIndex) {
            return METADATA_HEADER_BYTES + (long) recordIndex * layout.stride();
        }

        /**
         * Returns the number of records in this partition.
         */
        public int count() {
            return count;
        }

        /**
         * Returns the tombstone count.
         */
        public int tombstoneCount() {
            return tombstoneCount;
        }

        /**
         * Returns the tombstone ratio (0.0 to 1.0).
         */
        public float tombstoneRatio() {
            return count == 0 ? 0f : (float) tombstoneCount / count;
        }

        /**
         * Increments the tombstone counter and persists to metadata.
         */
        public void incrementTombstoneCount() {
            tombstoneCount++;
            segment.set(java.lang.foreign.ValueLayout.JAVA_INT, META_TOMBSTONE_COUNT, tombstoneCount);
        }

        /**
         * Returns the backing segment for scanning.
         */
        public MemorySegment segment() {
            return segment;
        }

        /**
         * Returns the partition file path.
         */
        public Path path() {
            return path;
        }

        /**
         * Returns the layout.
         */
        public CognitiveRecordLayout layout() {
            return layout;
        }

        /**
         * Returns the capacity.
         */
        public int capacity() {
            return capacity;
        }

        /**
         * Returns the current partition state.
         */
        public PartitionState state() {
            return state;
        }

        /**
         * Seals this partition — prevents further writes.
         */
        public synchronized void seal() {
            this.state = PartitionState.SEALED;
            writeMetadata();
            log.debug("Partition sealed: {} ({} records)", path, count);
        }

        /**
         * Sets the partition state.
         */
        public synchronized void setState(PartitionState newState) {
            this.state = newState;
            writeMetadata();
        }

        /**
         * Forces the mapped segment to be written to the underlying file.
         */
        public void force() {
            if (segment != null) {
                segment.force();
            }
        }

        public void close() {
            try {
                if (segment != null) {
                    segment.force();
                }
            } catch (Exception e) {
                log.debug("Error forcing segment: {}", e.getMessage());
            }
            arena.close();
            try {
                if (fileChannel != null) {
                    fileChannel.close();
                }
            } catch (IOException e) {
                log.debug("Error closing file channel: {}", e.getMessage());
            }
        }
    }
}
