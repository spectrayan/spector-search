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

import com.spectrayan.spector.memory.cortex.TierRouter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Performs periodic checkpoints of tier store segments and WAL truncation.
 *
 * <h3>Biological Analog: Sleep-Consolidation Flush</h3>
 * <p>During slow-wave sleep, the hippocampus replays recent events and
 * consolidates them into cortical storage. The checkpoint daemon is the
 * digital equivalent — periodically flushing dirty pages to disk and
 * pruning the replay buffer (WAL).</p>
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Force all persistent tier store segments ({@code MemorySegment.force()})</li>
 *   <li>Read the WAL high-water mark ({@code wal.highWaterMark()})</li>
 *   <li>Write the HWM to {@code checkpoint.meta} (atomic via temp+rename)</li>
 *   <li>Truncate WAL events ≤ HWM ({@code wal.truncateBefore(hwm)})</li>
 * </ol>
 *
 * <h3>checkpoint.meta Format (16 bytes)</h3>
 * <pre>
 *   [4B magic]   0x434B5054 ("CKPT")
 *   [4B version] 1
 *   [8B hwm]     WAL sequence number (long)
 * </pre>
 *
 * <h3>Threading</h3>
 * <p>This class is <b>not</b> responsible for its own thread lifecycle.
 * The {@link com.spectrayan.spector.commons.concurrent.DaemonSupervisor}
 * schedules periodic calls to {@link #checkpoint()} and handles restart,
 * watchdog, and shutdown. The {@code checkpoint()} method is safe to call
 * from any thread.</p>
 *
 * @see MemoryWal#truncateBefore(long)
 * @see com.spectrayan.spector.commons.concurrent.DaemonSupervisor
 */
public final class CheckpointDaemon {

    private static final Logger log = LoggerFactory.getLogger(CheckpointDaemon.class);

    /** checkpoint.meta magic: "CKPT" in ASCII. */
    static final int CKPT_MAGIC = 0x434B5054;

    /** checkpoint.meta format version. */
    static final int CKPT_VERSION = 1;

    /** Size of checkpoint.meta in bytes. */
    static final int CKPT_SIZE = 16;

    private final TierRouter tierRouter;
    private final MemoryWal wal;
    private final Path checkpointMetaPath;

    /**
     * Creates a checkpoint daemon.
     *
     * <p>Does <b>not</b> start any threads. Use
     * {@link com.spectrayan.spector.commons.concurrent.DaemonSupervisor#schedule}
     * to drive periodic checkpoint calls.</p>
     *
     * @param tierRouter         the tier router (for forcing persistent segments)
     * @param wal                the write-ahead log
     * @param checkpointMetaPath path to the checkpoint.meta file
     */
    public CheckpointDaemon(TierRouter tierRouter, MemoryWal wal,
                            Path checkpointMetaPath) {
        this.tierRouter = tierRouter;
        this.wal = wal;
        this.checkpointMetaPath = checkpointMetaPath;
    }

    /**
     * Performs a single checkpoint cycle.
     *
     * <p>Thread-safe. Called periodically by the {@code DaemonSupervisor},
     * and also called manually during shutdown for a final flush.</p>
     */
    public void checkpoint() {
        long start = System.nanoTime();

        // Step 1: Force all persistent tier store segments
        tierRouter.forceAll();

        // Step 2: Read the WAL high-water mark
        long hwm = wal.highWaterMark();
        if (hwm <= 0) {
            log.trace("Checkpoint skipped: no WAL events");
            return;
        }

        // Step 3: Write HWM to checkpoint.meta (atomic via temp+rename)
        writeCheckpointMeta(hwm);

        // Step 4: Truncate WAL events ≤ HWM
        wal.truncateBefore(hwm);

        long elapsed = (System.nanoTime() - start) / 1_000_000;
        log.info("Checkpoint complete: hwm={}, elapsed={}ms", hwm, elapsed);
    }

    /**
     * Atomically writes the checkpoint metadata file.
     * Uses temp file + rename for crash safety.
     */
    private void writeCheckpointMeta(long hwm) {
        Path tempPath = checkpointMetaPath.resolveSibling(
                checkpointMetaPath.getFileName() + ".tmp");
        try {
            // Ensure parent directory exists
            Path parent = checkpointMetaPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            // Write to temp file
            ByteBuffer buf = ByteBuffer.allocate(CKPT_SIZE);
            buf.putInt(CKPT_MAGIC);
            buf.putInt(CKPT_VERSION);
            buf.putLong(hwm);
            buf.flip();

            try (FileChannel ch = FileChannel.open(tempPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ch.write(buf);
                ch.force(true);
            }

            // Atomic rename
            Files.move(tempPath, checkpointMetaPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);

        } catch (IOException e) {
            log.error("Failed to write checkpoint.meta: {}", e.getMessage(), e);
            // Clean up temp file on failure
            try { Files.deleteIfExists(tempPath); } catch (IOException ignored) {}
        }
    }

    /**
     * Reads the checkpoint HWM from an existing checkpoint.meta file.
     *
     * @param path path to the checkpoint.meta file
     * @return the high-water mark, or -1 if the file doesn't exist or is invalid
     */
    public static long readCheckpointHwm(Path path) {
        if (!Files.exists(path)) return -1;

        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            if (ch.size() < CKPT_SIZE) return -1;

            ByteBuffer buf = ByteBuffer.allocate(CKPT_SIZE);
            ch.read(buf);
            buf.flip();

            int magic = buf.getInt();
            if (magic != CKPT_MAGIC) {
                log.warn("Invalid checkpoint.meta magic: 0x{}", Integer.toHexString(magic));
                return -1;
            }

            int version = buf.getInt();
            if (version != CKPT_VERSION) {
                log.warn("Unsupported checkpoint.meta version: {}", version);
                return -1;
            }

            return buf.getLong();
        } catch (IOException e) {
            log.warn("Failed to read checkpoint.meta: {}", e.getMessage());
            return -1;
        }
    }
}
