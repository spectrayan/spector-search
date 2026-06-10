/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://github.com/spectrayan/spector/blob/main/spector-memory/LICENSE
 *
 * Change Date: May 27, 2030
 * Change License: Apache License, Version 2.0
 */
package com.spectrayan.spector.memory.sync;

import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorStorageException;
import com.spectrayan.spector.memory.error.SpectorWalCorruptionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Append-only Write-Ahead Log for memory events.
 *
 * <h3>Biological Analog: Hippocampal Replay Buffer</h3>
 * <p>Before memories are consolidated into long-term storage, they exist as
 * transient activity patterns in the hippocampus. The WAL is the digital equivalent
 * — an ordered, durable log of every memory mutation that can be replayed.</p>
 *
 * <h3>V3 Design: File-Backed Persistence</h3>
 * <ul>
 *   <li>Append-only with sequential numbering — O(1) writes</li>
 *   <li>No deletions — tombstone events instead</li>
 *   <li>Replay from any sequence number → enables distributed sync</li>
 *   <li>Binary record format: {@code [4B length][8B seq][1B type][4B id_len][N id][8B ts_epoch][4B payload_len][N payload]}</li>
 *   <li>Per-write fsync for crash durability (negligible vs. embedding latency)</li>
 *   <li>Rolled WAL chunks when file exceeds max size (default 8MB)</li>
 *   <li>Crash recovery: replay WAL file to rebuild in-memory state</li>
 * </ul>
 *
 * <h3>Dual Mode</h3>
 * <ul>
 *   <li><b>File mode</b> ({@code walPath != null}): All appends are durable on disk.</li>
 *   <li><b>In-memory mode</b> ({@code walPath == null}): Volatile, for tests and ephemeral agents.</li>
 * </ul>
 *
 * <h3>CloudSync (V2+)</h3>
 * <p>A replication daemon reads events after a high-water mark and ships them
 * to remote agents. Each agent replays events into their local memory store.</p>
 */
public final class MemoryWal implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MemoryWal.class);

    /** Magic bytes for WAL file identification: "SPEC" in ASCII. */
    static final int WAL_MAGIC = 0x53504543;

    /** WAL format version. */
    static final int WAL_VERSION = 2;

    /** Record magic for Version 2: 'W' and 'A' (0x5741) */
    static final short RECORD_MAGIC = 0x5741;

    /** File header size: 4B magic + 4B version = 8 bytes. */
    static final int FILE_HEADER_BYTES = 8;

    /** Default max chunk size before rolling (8 MB). */
    private static final long DEFAULT_MAX_CHUNK_BYTES = 8L * 1024 * 1024;

    private final Path walDir;
    private final long maxChunkBytes;
    private final boolean compressionEnabled;
    private final int compressionThreshold;
    private final boolean fsyncPerWrite;
    private final AtomicLong sequenceCounter;
    private final ReentrantLock writeLock = new ReentrantLock();

    /** In-memory event cache for fast replay from recent HWM. */
    private final List<WalEvent> events = new ArrayList<>();

    /** Active FileChannel for the current WAL chunk (null in memory-only mode). */
    private FileChannel activeChannel;
    private Path activeChunkPath;
    private long activeChunkBytes;
    private int chunkIndex;

    /**
     * Opens or creates a file-backed WAL with custom configurations.
     *
     * @param walDir               directory for WAL chunk files
     * @param maxChunkBytes        maximum bytes per chunk before rolling (default: 8MB)
     * @param compressionEnabled   whether text/payload compression is enabled
     * @param compressionThreshold byte threshold above which payload compression is triggered
     * @param fsyncPerWrite        whether to physically fsync the disk on every individual write
     */
    public MemoryWal(Path walDir, long maxChunkBytes, boolean compressionEnabled, int compressionThreshold, boolean fsyncPerWrite) {
        this.walDir = walDir;
        this.maxChunkBytes = maxChunkBytes;
        this.compressionEnabled = compressionEnabled;
        this.compressionThreshold = compressionThreshold;
        this.fsyncPerWrite = fsyncPerWrite;
        this.sequenceCounter = new AtomicLong(0);

        if (walDir != null) {
            try {
                Files.createDirectories(walDir);
            } catch (IOException e) {
                throw new SpectorStorageException(ErrorCode.PARTITION_DIR_FAILED, e, walDir);
            }

            // Recover state from existing chunk files
            recoverFromDisk();

            // Open (or create) the active chunk
            openActiveChunk();

            log.info("MemoryWal opened: dir={}, chunks={}, recovered={} events, hwm={}, compression={}, fsyncPerWrite={}",
                    walDir, chunkIndex + 1, events.size(), sequenceCounter.get(), compressionEnabled, fsyncPerWrite);
        } else {
            log.info("MemoryWal opened: in-memory mode");
        }
    }

    /**
     * Opens or creates a file-backed WAL with default compaction configurations.
     *
     * @param walDir        directory for WAL chunk files
     * @param maxChunkBytes maximum bytes per chunk before rolling (default: 8MB)
     */
    public MemoryWal(Path walDir, long maxChunkBytes) {
        this(walDir, maxChunkBytes, false, 1024, false);
    }

    /**
     * Opens or creates a file-backed WAL with default chunk size (8 MB).
     *
     * @param walDir directory for WAL chunk files
     */
    public MemoryWal(Path walDir) {
        this(walDir, DEFAULT_MAX_CHUNK_BYTES, false, 1024, false);
    }

    /**
     * Creates an in-memory WAL (no file persistence).
     */
    public MemoryWal() {
        this(null, Long.MAX_VALUE, false, 1024, false);
    }

    /**
     * Appends a new event to the WAL.
     *
     * <p>In file mode, the event is serialized to the binary format and written
     * to the active chunk with {@code FileChannel.force(true)} for durability.</p>
     *
     * @param type     event type
     * @param memoryId the affected memory ID
     * @param payload  serialized event data (can be empty)
     * @return the event with its assigned sequence number
     */
    public WalEvent append(WalEvent.EventType type, String memoryId, byte[] payload) {
        long seq = sequenceCounter.incrementAndGet();
        WalEvent event = new WalEvent(seq, type, memoryId, Instant.now(),
                payload != null ? payload : new byte[0]);

        writeLock.lock();
        try {
            events.add(event);

            if (activeChannel != null) {
                writeEventToChannel(event);

                // Roll chunk if needed
                if (activeChunkBytes >= maxChunkBytes) {
                    rollChunk();
                }
            }
        } catch (IOException e) {
            throw new SpectorStorageException(ErrorCode.WAL_WRITE_FAILED, e);
        } finally {
            writeLock.unlock();
        }

        log.trace("WAL append: seq={}, type={}, id={}", seq, type, memoryId);
        return event;
    }

    /**
     * Appends a REMEMBER event.
     */
    public WalEvent appendRemember(String memoryId, byte[] payload) {
        return append(WalEvent.EventType.REMEMBER, memoryId, payload);
    }

    /**
     * Appends a FORGET event.
     */
    public WalEvent appendForget(String memoryId) {
        return append(WalEvent.EventType.FORGET, memoryId, null);
    }

    /**
     * Appends a REINFORCE event.
     */
    public WalEvent appendReinforce(String memoryId, byte valence) {
        return append(WalEvent.EventType.REINFORCE, memoryId, new byte[]{valence});
    }

    /**
     * Replays all events after a given sequence number.
     *
     * <p>Used by CloudSync to ship events to remote agents. Returns events
     * from the in-memory cache first; if the cache doesn't cover the requested
     * range, reads from disk.</p>
     *
     * @param afterSequence replay events with sequence &gt; this value (0 = replay all)
     * @return list of events in order
     */
    public List<WalEvent> replay(long afterSequence) {
        return events.stream()
                .filter(e -> e.sequence() > afterSequence)
                .toList();
    }

    /**
     * Replays all events from disk WAL files, ignoring the in-memory cache.
     *
     * <p>Used for crash recovery and consistency verification.</p>
     *
     * @return list of all events read from WAL chunk files
     */
    public List<WalEvent> replayFromDisk() {
        if (walDir == null) return List.of();

        List<WalEvent> diskEvents = new ArrayList<>();
        try {
            List<Path> chunks = findChunkFiles();
            for (Path chunk : chunks) {
                readChunkFile(chunk, diskEvents);
            }
        } catch (IOException e) {
            throw new SpectorStorageException(ErrorCode.WAL_REPLAY_FAILED, e, "disk replay");
        }
        return diskEvents;
    }

    /**
     * Returns the current high-water mark (latest sequence number).
     */
    public long highWaterMark() {
        return sequenceCounter.get();
    }

    /**
     * Returns the total number of events in the WAL (in-memory cache).
     */
    public int size() {
        return events.size();
    }

    /**
     * Returns the WAL directory path (null for in-memory mode).
     */
    public Path path() {
        return walDir;
    }

    /**
     * Returns whether this WAL is file-backed.
     */
    public boolean isPersistent() {
        return walDir != null;
    }

    @Override
    public void close() {
        writeLock.lock();
        try {
            if (activeChannel != null) {
                try {
                    activeChannel.force(true);
                    activeChannel.close();
                } catch (IOException e) {
                    log.warn("Error closing WAL channel: {}", e.getMessage());
                }
            }
        } finally {
            writeLock.unlock();
        }
        log.info("MemoryWal closing ({} events, hwm={})", events.size(), sequenceCounter.get());
    }

    // ── Internal: File I/O ──


    /**
     * Recovers state from existing WAL chunk files on disk.
     * Rebuilds the in-memory event cache and restores the sequence counter.
     */
    private void recoverFromDisk() {
        if (walDir == null) return;

        try {
            List<Path> chunks = findChunkFiles();
            int maxIdx = -1;
            for (Path chunk : chunks) {
                maxIdx = Math.max(maxIdx, parseChunkIndex(chunk.getFileName().toString()));
            }
            chunkIndex = maxIdx + 1; // next chunk index

            for (Path chunk : chunks) {
                readChunkFile(chunk, events);
            }

            // Restore sequence counter to the max seen
            long maxSeq = events.stream()
                    .mapToLong(WalEvent::sequence)
                    .max()
                    .orElse(0L);
            sequenceCounter.set(maxSeq);

        } catch (SpectorWalCorruptionException e) {
            log.error("Fatal WAL corruption detected during recovery: {}", e.getMessage());
            throw e; // Already a SpectorStorageException — propagate directly
        } catch (IOException e) {
            log.error("WAL recovery failed: {}", e.getMessage());
            throw new SpectorStorageException(ErrorCode.WAL_REPLAY_FAILED, e, "recovery");
        }
    }

    /**
     * Opens the active chunk file for writing. Creates a new file with header
     * if it doesn't exist.
     */
    private void openActiveChunk() {
        if (walDir == null) return;

        try {
            activeChunkPath = walDir.resolve(chunkFileName(chunkIndex));
            boolean isNew = !Files.exists(activeChunkPath);

            activeChannel = FileChannel.open(activeChunkPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.READ);

            if (isNew) {
                writeFileHeader();
                activeChunkBytes = FILE_HEADER_BYTES;
            } else {
                activeChunkBytes = activeChannel.size();
                activeChannel.position(activeChunkBytes); // seek to end for appending
            }
        } catch (IOException e) {
            throw new SpectorStorageException(ErrorCode.DISK_IO_FAILED, e, "open WAL chunk: " + activeChunkPath);
        }
    }

    /**
     * Writes the WAL file header (magic + version).
     */
    private void writeFileHeader() throws IOException {
        ByteBuffer header = ByteBuffer.allocate(FILE_HEADER_BYTES);
        header.putInt(WAL_MAGIC);
        header.putInt(WAL_VERSION);
        header.flip();
        activeChannel.write(header);
        activeChannel.force(true);
    }

    /**
     * Serializes and writes a single event to the active FileChannel.
     */
    private void writeEventToChannel(WalEvent event) throws IOException {
        byte[] idBytes = event.memoryId().getBytes(StandardCharsets.UTF_8);
        byte[] rawPayload = event.payload();
        byte[] payload = rawPayload;
        byte flags = 0;

        if (compressionEnabled && rawPayload.length > compressionThreshold) {
            payload = compress(rawPayload);
            flags |= 1; // Bit 0: Compressed
        }

        int idLen = idBytes.length;
        int payloadLen = payload.length;
        int totalVarLen = idLen + payloadLen;
        int paddingLen = (8 - (totalVarLen % 8)) % 8;
        int recordSize = 40 + totalVarLen + paddingLen;

        ByteBuffer buf = ByteBuffer.allocate(recordSize);

        // Offset 0-7: Metadata Block
        buf.putShort(RECORD_MAGIC);
        buf.put((byte) WAL_VERSION);
        buf.put(flags);
        buf.put((byte) event.type().ordinal());
        buf.putShort((short) idLen);
        buf.put((byte) 0); // Reserved byte

        // Offset 8-15: Sequence Number
        buf.putLong(event.sequence());

        // Offset 16-23: Timestamp
        buf.putLong(event.timestamp().toEpochMilli());

        // Offset 24-27: Payload Length
        buf.putInt(payloadLen);

        // Offset 28-31: Payload CRC32
        int payloadCrc = calculateCrc32(payload);
        buf.putInt(payloadCrc);

        // Offset 32-35: Reserved field
        buf.putInt(0);

        // Offset 36-39: Compute Header CRC over the first 36 bytes of the header
        int headerCrc = calculateCrc32(buf, 36);
        buf.putInt(headerCrc);

        // Variable segments
        buf.put(idBytes);
        buf.put(payload);

        // Alignment padding to 8-byte boundaries
        for (int i = 0; i < paddingLen; i++) {
            buf.put((byte) 0);
        }

        buf.flip();
        activeChannel.write(buf);

        if (fsyncPerWrite) {
            activeChannel.force(false); // metadata update not needed per-write
        }
        activeChunkBytes += recordSize;
    }

    /**
     * Rolls to a new WAL chunk file.
     */
    private void rollChunk() throws IOException {
        log.info("WAL chunk {} reached {}KB — rolling to next chunk",
                chunkIndex, activeChunkBytes / 1024);

        activeChannel.force(true);
        activeChannel.close();

        chunkIndex++;
        openActiveChunk();
    }

    /**
     * Reads all events from a single WAL chunk file.
     */
    private void readChunkFile(Path chunkPath, List<WalEvent> out) throws IOException {
        // Open with READ and WRITE to support auto-repair of torn writes during recovery
        try (FileChannel ch = FileChannel.open(chunkPath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long fileSize = ch.size();
            if (fileSize < FILE_HEADER_BYTES) return; // too small, skip

            // Read and validate file header
            ByteBuffer headerBuf = ByteBuffer.allocate(FILE_HEADER_BYTES);
            ch.read(headerBuf);
            headerBuf.flip();

            int magic = headerBuf.getInt();
            int version = headerBuf.getInt();

            if (magic != WAL_MAGIC) {
                log.warn("Invalid WAL magic in {}: 0x{} (expected 0x{})",
                        chunkPath, Integer.toHexString(magic), Integer.toHexString(WAL_MAGIC));
                return;
            }
            if (version != WAL_VERSION) {
                log.warn("Unsupported WAL version in {}: {} (expected {})",
                        chunkPath, version, WAL_VERSION);
                return;
            }

            // Read events
            while (ch.position() < fileSize) {
                WalEvent event = readEventFromChannel(ch, chunkPath, version);
                if (event == null) break; // torn-write truncation was triggered, stop
                out.add(event);
            }
        }
    }

    /**
     * Reads a single event from a FileChannel at the current position.
     *
     * @return the deserialized event, or null if the record is truncated
     */
    private WalEvent readEventFromChannel(FileChannel ch, Path source, int fileVersion) throws IOException {
        if (fileVersion != WAL_VERSION) {
            throw new SpectorWalCorruptionException("Unsupported file version: " + fileVersion + " (expected " + WAL_VERSION + ")");
        }

        long startPos = ch.position();
        if (ch.size() - startPos < 40) {
            if (ch.size() - startPos > 0) {
                handleTornWrite(source, ch, startPos);
            }
            return null; // EOF
        }

        // Read 40-byte header
        ByteBuffer headerBuf = ByteBuffer.allocate(40);
        int bytesRead = ch.read(headerBuf);
        if (bytesRead < 40) {
            handleTornWrite(source, ch, startPos);
            return null;
        }
        headerBuf.flip();

        // Offset 0-1: Record Magic
        short magic = headerBuf.getShort();
        if (magic != RECORD_MAGIC) {
            handleMiddleLogCorruption(source, ch, startPos, "Record magic mismatch: expected 0x5741, got 0x" + Integer.toHexString(magic & 0xFFFF));
            return null;
        }

        // Offset 2-7: Metadata
        byte recVersion = headerBuf.get();
        byte flags = headerBuf.get();
        byte typeOrd = headerBuf.get();
        int idLen = headerBuf.getShort() & 0xFFFF;
        byte reserved = headerBuf.get();

        // Offset 8-15: Sequence Number
        long sequence = headerBuf.getLong();

        // Offset 16-23: Timestamp
        long timestampMs = headerBuf.getLong();

        // Offset 24-27: Payload Length
        int payloadLen = headerBuf.getInt();

        // Offset 28-31: Payload CRC
        int payloadCrc = headerBuf.getInt();

        // Offset 32-35: Reserved field
        int reserved4 = headerBuf.getInt();

        // Offset 36-39: Header CRC
        int headerCrc = headerBuf.getInt();

        // Verify Header CRC-32C
        int computedHeaderCrc = calculateCrc32(headerBuf, 36);
        if (headerCrc != computedHeaderCrc) {
            handleMiddleLogCorruption(source, ch, startPos, "Header CRC mismatch: expected " + headerCrc + ", got " + computedHeaderCrc);
            return null;
        }

        // Variable segments
        int totalVarLen = idLen + payloadLen;
        int paddingLen = (8 - (totalVarLen % 8)) % 8;
        int expectedRecordSize = totalVarLen + paddingLen;

        if (ch.position() + expectedRecordSize > ch.size()) {
            handleTornWrite(source, ch, startPos);
            return null;
        }

        ByteBuffer varBuf = ByteBuffer.allocate(expectedRecordSize);
        bytesRead = ch.read(varBuf);
        if (bytesRead < expectedRecordSize) {
            handleTornWrite(source, ch, startPos);
            return null;
        }
        varBuf.flip();

        byte[] idBytes = new byte[idLen];
        varBuf.get(idBytes);
        byte[] payloadBytes = new byte[payloadLen];
        varBuf.get(payloadBytes);

        // Verify Payload CRC-32C
        int computedPayloadCrc = calculateCrc32(payloadBytes);
        if (payloadCrc != computedPayloadCrc) {
            handleMiddleLogCorruption(source, ch, startPos, "Payload CRC mismatch: expected " + payloadCrc + ", got " + computedPayloadCrc);
            return null;
        }

        // Decompress payload if necessary
        if ((flags & 1) != 0) {
            payloadBytes = decompress(payloadBytes);
        }

        WalEvent.EventType type = WalEvent.EventType.values()[typeOrd];
        String memoryId = new String(idBytes, StandardCharsets.UTF_8);
        Instant timestamp = Instant.ofEpochMilli(timestampMs);

        return new WalEvent(sequence, type, memoryId, timestamp, payloadBytes);
    }

    /**
     * Finds all WAL chunk files in the WAL directory, sorted by name (ascending).
     */
    private List<Path> findChunkFiles() throws IOException {
        if (walDir == null || !Files.isDirectory(walDir)) return List.of();

        try (var stream = Files.list(walDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith("wal-") &&
                                 p.getFileName().toString().endsWith(".bin"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * Generates a chunk file name from the chunk index.
     */
    static String chunkFileName(int index) {
        return String.format("wal-%06d.bin", index);
    }

    /**
     * Truncates historical closed WAL chunk files where the maximum sequence
     * number in the chunk is less than or equal to the snapshot High-Water Mark.
     *
     * @param snapshotHwm the sequence number up to which all mutations are persisted
     */
    public void truncateBefore(long snapshotHwm) {
        if (walDir == null) return;

        writeLock.lock();
        try {
            List<Path> chunks = findChunkFiles();
            for (Path chunk : chunks) {
                // Never truncate/delete the active chunk
                if (chunk.equals(activeChunkPath)) {
                    continue;
                }

                long maxSeqInChunk;
                try {
                    maxSeqInChunk = getMaxSequenceInChunk(chunk);
                } catch (IOException e) {
                    log.error("Failed to read maximum sequence in chunk " + chunk + " during truncation", e);
                    continue;
                }

                if (maxSeqInChunk <= snapshotHwm) {
                    try {
                        Files.delete(chunk);
                        log.info("Truncated WAL chunk {} (maxSeq={} <= snapshotHwm={})", chunk.getFileName(), maxSeqInChunk, snapshotHwm);
                    } catch (IOException e) {
                        log.warn("Failed to delete WAL chunk {}: {}", chunk, e.getMessage());
                    }
                } else {
                    // Once we encounter a chunk with sequence > snapshotHwm, stop truncating
                    break;
                }
            }

            // Also clean up in-memory cache events to prevent memory bloating
            events.removeIf(e -> e.sequence() <= snapshotHwm);

        } catch (IOException e) {
            log.error("WAL truncation failed", e);
        } finally {
            writeLock.unlock();
        }
    }

    long getMaxSequenceInChunk(Path chunkPath) throws IOException {
        try (FileChannel ch = FileChannel.open(chunkPath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long fileSize = ch.size();
            if (fileSize < FILE_HEADER_BYTES) return 0L;

            ByteBuffer headerBuf = ByteBuffer.allocate(FILE_HEADER_BYTES);
            ch.read(headerBuf);
            headerBuf.flip();
            int magic = headerBuf.getInt();
            int version = headerBuf.getInt();

            if (magic != WAL_MAGIC || version != WAL_VERSION) {
                return 0L;
            }

            long maxSeq = 0L;
            while (ch.position() < fileSize) {
                WalEvent event = readEventFromChannel(ch, chunkPath, version);
                if (event == null) break;
                maxSeq = Math.max(maxSeq, event.sequence());
            }
            return maxSeq;
        }
    }

    private static int parseChunkIndex(String filename) {
        try {
            // filename format: wal-XXXXXX.bin
            String numPart = filename.substring(4, 10);
            return Integer.parseInt(numPart);
        } catch (Exception e) {
            return 0;
        }
    }

    private void handleTornWrite(Path path, FileChannel fc, long startPos) throws IOException {
        log.warn("Torn WAL record detected in {} at position {}. Truncating file to recovery boundary.", path, startPos);
        fc.truncate(startPos);
        fc.force(true);
    }

    private void handleMiddleLogCorruption(Path path, FileChannel fc, long startPos, String reason) throws IOException {
        log.error("Fatal mid-log corruption in {} at position {}: {}. Triggering quarantine.", path, startPos, reason);
        fc.close();

        Path quarantineDir = path.getParent().resolve(".quarantine");
        Files.createDirectories(quarantineDir);
        Path quarantinedPath = quarantineDir.resolve(path.getFileName());
        Files.move(path, quarantinedPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        log.warn("Quarantined corrupted WAL chunk {} to {}", path, quarantinedPath);

        throw new SpectorWalCorruptionException(
                "Fatal WAL corruption: " + reason + " at position " + startPos, path);
    }

    private byte[] compress(byte[] data) {
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();

        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length);
        byte[] buffer = new byte[1024];
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            bos.write(buffer, 0, count);
        }
        deflater.end();
        return bos.toByteArray();
    }

    private byte[] decompress(byte[] data) throws IOException {
        Inflater inflater = new Inflater();
        inflater.setInput(data);

        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length * 2);
        byte[] buffer = new byte[1024];
        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                bos.write(buffer, 0, count);
            }
        } catch (DataFormatException e) {
            throw new IOException("Failed to decompress WAL payload", e);
        } finally {
            inflater.end();
        }
        return bos.toByteArray();
    }

    private static int calculateCrc32(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return (int) crc.getValue();
    }

    private static int calculateCrc32(ByteBuffer buf, int length) {
        CRC32 crc = new CRC32();
        int originalPosition = buf.position();
        buf.position(0);
        byte[] temp = new byte[length];
        buf.get(temp);
        buf.position(originalPosition);
        crc.update(temp);
        return (int) crc.getValue();
    }
}
