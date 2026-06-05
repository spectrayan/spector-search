/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.spectrayan.spector.storage;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.storage.error.SpectorStoreFullException;
import com.spectrayan.spector.storage.error.SpectorSegmentClosedException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Partitioned memory-mapped vector store using rolling partition files.
 *
 * <h3>Design</h3>
 * <p>Instead of pre-allocating a single monolithic file (which could reach 700 MB+),
 * vectors are distributed across fixed-capacity partition files. When the active
 * (write) partition fills up, a new one is created automatically — identical to
 * the rolling partition model used by
 * the directory-level partition model used by Spector Memory.</p>
 *
 * <h3>Partition Layout</h3>
 * <p>Each partition is a memory-mapped file named {@code partition-NNN.mmap}
 * with the following layout:</p>
 * <pre>
 *   [64B PartitionMetadata header]
 *   [vector_0: float × D]
 *   [vector_1: float × D]
 *   ...
 *   [vector_N: float × D]
 * </pre>
 *
 * <p>The metadata header is self-describing (magic, version, localCount,
 * localCapacity, dimensions, partitionIndex) — enabling recovery on restart
 * without a separate ID-mappings file.</p>
 *
 * <h3>Concurrency</h3>
 * <ul>
 *   <li>Reads acquire no lock and scan the partition list (CopyOnWriteArrayList)</li>
 *   <li>Writes are serialized via {@link ReentrantLock}; rolling to a new
 *       partition happens under the write lock</li>
 * </ul>
 *
 * <h3>Index Alignment</h3>
 * <p>Shard resolution is trivial: {@code partitionIdx = vectorIndex / nodesPerPartition},
 * {@code localIdx = vectorIndex % nodesPerPartition}. This matches the HNSW index
 * sharding boundary so index shard N can correspond to the same vectors in
 * partition N.</p>
 *
 * @see PartitionMetadata
 * @deprecated Since V4. Sub-file partition rolling is replaced by directory-level
 * partitioning (see {@code memory-storage-architecture.md}). Engine will also
 * migrate to dir-based partitions. Use per-partition {@code semantic.mem} files instead.
 */
@Deprecated(since = "4.0", forRemoval = true)
public class ShardedMappedVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(ShardedMappedVectorStore.class);

    /** Partition file name format: partition-000.mmap */
    private static final String PARTITION_PREFIX = "partition-";
    private static final String PARTITION_SUFFIX = ".mmap";

    private final VectorStoreLayout layout;
    private final int capacity;
    private final int nodesPerPartition;
    private final Path partitionDir;
    private final Map<String, Integer> idToIndex;
    private final AtomicInteger count;
    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile boolean closed;
    private volatile long lastAccessed;

    /**
     * Per-partition mmap context.
     */
    private static final class VectorPartition {
        final Path filePath;
        final int partitionIndex;
        final int localCapacity;
        final Arena arena;
        final MemorySegment segment;
        final RandomAccessFile raf;
        final FileChannel channel;
        int localCount;

        VectorPartition(Path filePath, int partitionIndex, int localCapacity,
                        Arena arena, MemorySegment segment,
                        RandomAccessFile raf, FileChannel channel, int localCount) {
            this.filePath = filePath;
            this.partitionIndex = partitionIndex;
            this.localCapacity = localCapacity;
            this.arena = arena;
            this.segment = segment;
            this.raf = raf;
            this.channel = channel;
            this.localCount = localCount;
        }

        boolean isFull() {
            return localCount >= localCapacity;
        }

        int globalOffset() {
            // Not used for index calculation — we use partitionIndex * nodesPerPartition
            // But kept for clarity
            return -1;
        }

        void close() throws IOException {
            if (segment.isMapped()) {
                segment.force();
                com.spectrayan.spector.commons.concurrent.MemoryPinning.unlock(segment);
                segment.unload();
            }
            arena.close();
            channel.close();
            raf.close();
        }
    }

    /** All partitions (oldest first). Thread-safe for concurrent reads. */
    private final CopyOnWriteArrayList<VectorPartition> partitions = new CopyOnWriteArrayList<>();

    /**
     * Creates a partitioned vector store.
     *
     * @param partitionDir     directory for partition files (created if absent)
     * @param dimensions       number of float elements per vector
     * @param capacity         maximum total number of vectors
     * @param nodesPerPartition maximum vectors per partition file
     * @throws IOException if directory creation fails
     */
    public ShardedMappedVectorStore(Path partitionDir, int dimensions, int capacity,
                                     int nodesPerPartition) throws IOException {
        if (capacity <= 0) throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "capacity", 1, Integer.MAX_VALUE, capacity);
        if (nodesPerPartition <= 0) throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "nodesPerPartition", 1, Integer.MAX_VALUE, nodesPerPartition);

        this.layout = new VectorStoreLayout(dimensions);
        this.capacity = capacity;
        this.nodesPerPartition = nodesPerPartition;
        this.partitionDir = partitionDir;
        this.idToIndex = new ConcurrentHashMap<>(Math.min(capacity, 65536));
        this.count = new AtomicInteger(0);
        this.closed = false;
        this.lastAccessed = System.currentTimeMillis();

        Files.createDirectories(partitionDir);

        // Load existing partition files from disk
        loadPartitions();

        // If no partitions exist, create the first one
        if (partitions.isEmpty()) {
            rollNewPartition();
        }

        log.info("ShardedMappedVectorStore created: dir={}, dims={}, capacity={}, nodesPerPartition={}, " +
                 "partitions={}, totalRecords={}",
                partitionDir, dimensions, capacity, nodesPerPartition,
                partitions.size(), count.get());
    }

    @Override
    public int put(String id, float[] vector) {
        writeLock.lock();
        try {
            ensureOpen();
            this.lastAccessed = System.currentTimeMillis();
            if (vector.length != layout.dimensions()) {
                throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, "Expected " + layout.dimensions() + " dimensions, got " + vector.length);
            }

            // Update in-place if ID exists
            Integer existingIndex = idToIndex.get(id);
            if (existingIndex != null) {
                writeVectorAt(existingIndex, vector);
                return existingIndex;
            }

            // Allocate new slot
            int index = count.getAndIncrement();
            if (index >= capacity) {
                count.decrementAndGet();
                throw new SpectorStoreFullException(capacity);
            }

            // Ensure the target partition exists and isn't full
            int partIdx = index / nodesPerPartition;
            ensurePartitionReady(partIdx);

            writeVectorAt(index, vector);

            // Update the partition's local count + metadata header
            VectorPartition partition = partitions.get(partIdx);
            partition.localCount++;
            PartitionMetadata.writeLocalCount(partition.segment, partition.localCount);

            idToIndex.put(id, index);
            return index;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Failed to open vector partition", e);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public float[] get(String id) {
        ensureOpen();
        this.lastAccessed = System.currentTimeMillis();
        Integer index = idToIndex.get(id);
        return index == null ? null : readVectorAt(index);
    }

    @Override
    public float[] getByIndex(int index) {
        ensureOpen();
        validateIndex(index);
        this.lastAccessed = System.currentTimeMillis();
        return readVectorAt(index);
    }

    @Override
    public void getByIndex(int index, float[] dst, int dstOffset) {
        ensureOpen();
        validateIndex(index);
        this.lastAccessed = System.currentTimeMillis();
        int partIdx = index / nodesPerPartition;
        int localIdx = index % nodesPerPartition;
        readVectorFromSegment(partitions.get(partIdx).segment, localIdx, dst, dstOffset);
    }

    @Override
    public int indexOf(String id) {
        this.lastAccessed = System.currentTimeMillis();
        Integer index = idToIndex.get(id);
        return index == null ? -1 : index;
    }

    @Override
    public int size() { return count.get(); }

    @Override
    public int dimensions() { return layout.dimensions(); }

    @Override
    public int capacity() { return capacity; }

    @Override
    public boolean isClosed() { return closed; }

    /** Returns the path to the partition directory. */
    public Path shardDir() { return partitionDir; }

    /** Returns the nodes-per-partition configuration. */
    public int nodesPerShard() { return nodesPerPartition; }

    /** Returns the number of active (open) partition files. */
    public int activeShardCount() { return partitions.size(); }

    @Override
    public void close() {
        writeLock.lock();
        try {
            if (!closed) {
                closed = true;
                for (VectorPartition partition : partitions) {
                    try {
                        partition.close();
                    } catch (IOException e) {
                        log.warn("Error closing vector partition {}", partition.filePath, e);
                    }
                }
                log.info("ShardedMappedVectorStore closed: {} vectors across {} partitions, dir={}",
                        count.get(), partitions.size(), partitionDir);
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Pre-touches all partition segments on virtual threads for warm page cache.
     */
    public void warmup() {
        for (VectorPartition partition : partitions) {
            if (partition.segment.isMapped()) {
                Thread.startVirtualThread(() -> {
                    long start = System.nanoTime();
                    try {
                        partition.segment.load();
                        boolean pinned = com.spectrayan.spector.commons.concurrent.MemoryPinning.lock(partition.segment);
                        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                        log.debug("Vector partition warmed up (pinned={}) in {} ms: {}",
                                pinned, elapsedMs, partition.filePath);
                    } catch (Exception e) {
                        log.warn("Failed to warm up vector partition {}: {}", partition.filePath, e.getMessage());
                    }
                });
            }
        }
    }

    /**
     * Evicts idle partition pages from physical memory.
     *
     * @param gracePeriodMs threshold of inactivity in milliseconds
     * @return true if any partitions were evicted
     */
    public boolean unloadIdle(long gracePeriodMs) {
        writeLock.lock();
        try {
            if (closed) return false;
            long idleMs = System.currentTimeMillis() - lastAccessed;
            if (idleMs < gracePeriodMs) return false;

            boolean evicted = false;
            for (VectorPartition partition : partitions) {
                if (partition.segment.isMapped()) {
                    com.spectrayan.spector.commons.concurrent.MemoryPinning.unlock(partition.segment);
                    partition.segment.unload();
                    evicted = true;
                }
            }
            if (evicted) {
                log.info("ShardedMappedVectorStore idle-evicted all partitions (idle for {} ms)", idleMs);
            }
            return evicted;
        } finally {
            writeLock.unlock();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ID MAPPING PERSISTENCE
    // ══════════════════════════════════════════════════════════════

    /** File magic: "VIDS" in ASCII. */
    private static final int VIDS_MAGIC = 0x56494453;
    private static final int VIDS_VERSION = 1;
    private static final int VIDS_HEADER_BYTES = 16;

    /**
     * Saves the id→index mapping to a binary file.
     *
     * @param mappingPath path to write the ID mapping file
     */
    public void saveIdMappings(Path mappingPath) {
        Path parent = mappingPath.getParent();
        if (parent != null) {
            try { Files.createDirectories(parent); } catch (IOException e) {
                log.warn("Cannot create id-mappings directory: {}", e.getMessage());
                return;
            }
        }

        try (var ch = FileChannel.open(mappingPath,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {

            java.nio.ByteBuffer header = java.nio.ByteBuffer.allocate(VIDS_HEADER_BYTES);
            header.putInt(VIDS_MAGIC);
            header.putInt(VIDS_VERSION);
            header.putInt(idToIndex.size());
            header.putInt(0);
            header.flip();
            ch.write(header);

            for (var entry : idToIndex.entrySet()) {
                byte[] idBytes = entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(4 + idBytes.length + 4);
                buf.putInt(idBytes.length);
                buf.put(idBytes);
                buf.putInt(entry.getValue());
                buf.flip();
                ch.write(buf);
            }

            ch.force(true);
            log.info("ShardedMappedVectorStore ID mappings saved: {} entries → {}", idToIndex.size(), mappingPath);
        } catch (IOException e) {
            log.error("Failed to save ID mappings: {}", e.getMessage());
        }
    }

    /**
     * Loads id→index mappings from a binary file.
     *
     * @param mappingPath path to read the ID mapping file
     */
    public void loadIdMappings(Path mappingPath) {
        if (mappingPath == null || !Files.exists(mappingPath)) {
            log.info("ID mappings file not found: {}", mappingPath);
            return;
        }

        try (var ch = FileChannel.open(mappingPath, java.nio.file.StandardOpenOption.READ)) {
            if (ch.size() < VIDS_HEADER_BYTES) return;

            java.nio.ByteBuffer header = java.nio.ByteBuffer.allocate(VIDS_HEADER_BYTES);
            ch.read(header);
            header.flip();

            int magic = header.getInt();
            int version = header.getInt();
            int entryCount = header.getInt();
            header.getInt(); // reserved

            if (magic != VIDS_MAGIC || version != VIDS_VERSION) {
                log.warn("Invalid ID mappings file header, skipping");
                return;
            }

            int maxIdx = -1;
            for (int i = 0; i < entryCount; i++) {
                java.nio.ByteBuffer lenBuf = java.nio.ByteBuffer.allocate(4);
                ch.read(lenBuf);
                lenBuf.flip();
                int idLen = lenBuf.getInt();

                java.nio.ByteBuffer idBuf = java.nio.ByteBuffer.allocate(idLen);
                ch.read(idBuf);
                idBuf.flip();
                String id = new String(idBuf.array(), 0, idLen, java.nio.charset.StandardCharsets.UTF_8);

                java.nio.ByteBuffer idxBuf = java.nio.ByteBuffer.allocate(4);
                ch.read(idxBuf);
                idxBuf.flip();
                int idx = idxBuf.getInt();

                idToIndex.put(id, idx);
                if (idx > maxIdx) maxIdx = idx;
            }

            if (maxIdx >= 0) {
                count.set(maxIdx + 1);
            }

            log.info("ShardedMappedVectorStore ID mappings loaded: {} entries from {}", idToIndex.size(), mappingPath);
        } catch (IOException e) {
            log.error("Failed to load ID mappings: {}", e.getMessage());
        }
    }

    // ─────────────── Internal: Vector I/O ───────────────

    private void writeVectorAt(int globalIndex, float[] vector) {
        int partIdx = globalIndex / nodesPerPartition;
        int localIdx = globalIndex % nodesPerPartition;
        VectorPartition partition = partitions.get(partIdx);
        long offset = PartitionMetadata.HEADER_BYTES + layout.vectorOffset(localIdx);
        MemorySegment.copy(vector, 0, partition.segment,
                java.lang.foreign.ValueLayout.JAVA_FLOAT, offset, layout.dimensions());
    }

    private float[] readVectorAt(int globalIndex) {
        int partIdx = globalIndex / nodesPerPartition;
        int localIdx = globalIndex % nodesPerPartition;
        VectorPartition partition = partitions.get(partIdx);
        float[] result = new float[layout.dimensions()];
        long offset = PartitionMetadata.HEADER_BYTES + layout.vectorOffset(localIdx);
        MemorySegment.copy(partition.segment,
                java.lang.foreign.ValueLayout.JAVA_FLOAT, offset, result, 0, layout.dimensions());
        return result;
    }

    private void readVectorFromSegment(MemorySegment segment, int localIdx,
                                        float[] dst, int dstOffset) {
        long offset = PartitionMetadata.HEADER_BYTES + layout.vectorOffset(localIdx);
        MemorySegment.copy(segment,
                java.lang.foreign.ValueLayout.JAVA_FLOAT, offset, dst, dstOffset, layout.dimensions());
    }

    // ─────────────── Internal: Partition Lifecycle ───────────────

    /**
     * Loads existing partition files from the partition directory.
     * Restores localCount from each partition's metadata header.
     */
    private void loadPartitions() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(partitionDir,
                PARTITION_PREFIX + "*" + PARTITION_SUFFIX)) {

            List<Path> files = new ArrayList<>();
            for (Path path : stream) {
                files.add(path);
            }
            files.sort(Comparator.comparing(Path::getFileName));

            int totalRecovered = 0;
            for (int i = 0; i < files.size(); i++) {
                Path file = files.get(i);
                try {
                    VectorPartition partition = openExistingPartition(file, i);
                    partitions.add(partition);
                    totalRecovered += partition.localCount;
                    log.debug("Loaded partition {}: {} records from {}",
                            i, partition.localCount, file.getFileName());
                } catch (IOException e) {
                    log.warn("Failed to load partition {}: {}", file, e.getMessage());
                }
            }

            if (totalRecovered > 0) {
                count.set(totalRecovered);
                // Correct: compute count from partition offsets + localCounts
                // The last partition determines the max global index
                if (!partitions.isEmpty()) {
                    VectorPartition last = partitions.getLast();
                    int maxGlobalIndex = last.partitionIndex * nodesPerPartition + last.localCount;
                    count.set(maxGlobalIndex);
                }
            }
        } catch (IOException e) {
            log.warn("Error loading partitions from {}: {}", partitionDir, e.getMessage());
        }
    }

    /**
     * Opens an existing partition file, validating its metadata header.
     */
    private VectorPartition openExistingPartition(Path file, int expectedIndex) throws IOException {
        var raf = new RandomAccessFile(file.toFile(), "rw");
        var channel = raf.getChannel();
        long fileSize = raf.length();

        var arena = Arena.ofShared();
        var segment = channel.map(FileChannel.MapMode.READ_WRITE, 0, fileSize, arena);

        if (!PartitionMetadata.isValid(segment)) {
            arena.close();
            channel.close();
            raf.close();
            throw new IOException("Invalid partition metadata in " + file);
        }

        int localCount = PartitionMetadata.readLocalCount(segment);
        int localCapacity = PartitionMetadata.readLocalCapacity(segment);

        return new VectorPartition(file, expectedIndex, localCapacity,
                arena, segment, raf, channel, localCount);
    }

    /**
     * Ensures the partition at the given index exists and is ready for writes.
     * Creates new partitions up to and including partIdx if needed.
     */
    private void ensurePartitionReady(int partIdx) throws IOException {
        while (partitions.size() <= partIdx) {
            rollNewPartition();
        }
    }

    /**
     * Creates a new partition and adds it to the partition list.
     */
    private void rollNewPartition() throws IOException {
        int seqNo = partitions.size();
        String fileName = String.format("%s%03d%s", PARTITION_PREFIX, seqNo, PARTITION_SUFFIX);
        Path file = partitionDir.resolve(fileName);

        int localCapacity = Math.min(nodesPerPartition,
                capacity - seqNo * nodesPerPartition);
        if (localCapacity <= 0) {
            throw new SpectorStoreFullException(capacity);
        }

        long totalBytes = layout.partitionByteSize(localCapacity);

        var raf = new RandomAccessFile(file.toFile(), "rw");
        raf.setLength(totalBytes);
        var channel = raf.getChannel();
        var arena = Arena.ofShared();
        var segment = channel.map(FileChannel.MapMode.READ_WRITE, 0, totalBytes, arena);

        // Write fresh metadata header
        PartitionMetadata.writeHeader(segment, localCapacity, layout.dimensions(), seqNo);

        VectorPartition partition = new VectorPartition(file, seqNo, localCapacity,
                arena, segment, raf, channel, 0);
        partitions.add(partition);

        log.info("Rolled new partition {}: {} (capacity={})", seqNo, file.getFileName(), localCapacity);
    }

    private static String partitionFileName(int seqNo) {
        return String.format("%s%03d%s", PARTITION_PREFIX, seqNo, PARTITION_SUFFIX);
    }

    private void ensureOpen() {
        if (closed) throw new SpectorSegmentClosedException();
    }

    private void validateIndex(int index) {
        if (index < 0 || index >= count.get()) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "index", 0, count.get() - 1, index);
        }
    }
}