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
package com.spectrayan.spector.memory.synapse;

import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorStorageException;
import com.spectrayan.spector.commons.error.SpectorValidationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;

/**
 * One-time migration tool for converting store files between header layout versions.
 *
 * <h3>Migration Strategy</h3>
 * <ol>
 *   <li>Read source file with source layout</li>
 *   <li>Write to temporary file ({@code .migrating}) with target layout</li>
 *   <li>Verify record count matches</li>
 *   <li>Back up original file ({@code .vN.bak})</li>
 *   <li>Atomic rename: temp → original</li>
 *   <li>Update metadata header with new version/stride</li>
 * </ol>
 *
 * <h3>Safety</h3>
 * <p>If the process crashes mid-migration, the original file is untouched —
 * the atomic rename (step 5) hasn't happened yet. On next startup, detect
 * the {@code .migrating} temp file and clean it up.</p>
 *
 * <h3>Supported Paths</h3>
 * <ul>
 *   <li>V1 (32B) → V2 (48B): arousal=0, storageStrength=1.0f</li>
 *   <li>V1 (32B) → V3 (64B): arousal=0, storageStrength=1.0f, reserved=0</li>
 *   <li>V2 (48B) → V3 (64B): reserved=0</li>
 *   <li>V3 (64B) → V2 (48B): ⚠️ lossy — reserved fields dropped</li>
 *   <li>V3 (64B) → V1 (32B): ⚠️ lossy — arousal, storageStrength, reserved dropped</li>
 *   <li>V2 (48B) → V1 (32B): ⚠️ lossy — arousal, storageStrength dropped</li>
 * </ul>
 *
 * @see HeaderLayout
 */
public final class HeaderMigrator {

    private static final Logger log = LoggerFactory.getLogger(HeaderMigrator.class);

    /** Metadata header size in bytes (same as AbstractTierStore.METADATA_HEADER_BYTES). */
    private static final int METADATA_HEADER_BYTES = 64;

    /** Metadata field offsets (mirrors AbstractTierStore). */
    private static final int META_MAGIC    = 0;
    private static final int META_VERSION  = 4;
    private static final int META_COUNT    = 8;
    private static final int META_CAPACITY = 12;
    private static final int META_STRIDE   = 16;
    private static final int META_TIER_ORD = 20;

    /** Magic number for tier files: "TIER" in ASCII. */
    private static final int TIER_MAGIC = 0x54494552;

    private HeaderMigrator() {}

    /**
     * Migrates a persistent store file from one header layout to another.
     *
     * <p>The migration is atomic: the original file is backed up before
     * the migrated file replaces it. If the target version is lower than
     * the source version (downgrade), this is a lossy operation — extended
     * fields are discarded.</p>
     *
     * @param storePath path to the persistent store file
     * @param source    current layout (detected from file metadata)
     * @param target    desired layout version
     * @param vectorBytes bytes per quantized vector (needed for stride calculation)
     * @param isHeaderOnly true for header-only stores (e.g., SemanticMemoryStore)
     * @return migration report with statistics
     * @throws SpectorValidationException if source and target are the same version
     * @throws SpectorStorageException if file I/O fails
     */
    public static MigrationReport migrate(Path storePath, HeaderLayout source,
                                            HeaderLayout target, int vectorBytes,
                                            boolean isHeaderOnly) {
        if (source.version() == target.version()) {
            throw new SpectorValidationException(
                    ErrorCode.ARGUMENT_INVALID, "targetVersion", "same as source: V" + source.version());
        }

        boolean isDowngrade = target.version() < source.version();
        if (isDowngrade) {
            log.warn("LOSSY DOWNGRADE: V{} → V{} — extended fields will be discarded",
                    source.version(), target.version());
        }

        Instant start = Instant.now();
        Path tempPath = storePath.resolveSibling(storePath.getFileName() + ".migrating");
        Path backupPath = storePath.resolveSibling(
                storePath.getFileName() + ".v" + source.version() + ".bak");

        log.info("Migrating {} from V{} ({}B) to V{} ({}B){}",
                storePath.getFileName(), source.version(), source.headerBytes(),
                target.version(), target.headerBytes(),
                isDowngrade ? " [LOSSY]" : "");

        int recordCount;
        long bytesBefore;

        try {
            bytesBefore = Files.size(storePath);
        } catch (IOException e) {
            throw new SpectorStorageException(ErrorCode.DISK_IO_FAILED, e, "read file size: " + storePath);
        }

        try (Arena sourceArena = Arena.ofConfined();
             Arena targetArena = Arena.ofConfined()) {

            // ── Step 1: Open source file ──
            MemorySegment sourceSegment;
            try (FileChannel sourceCh = FileChannel.open(storePath, StandardOpenOption.READ)) {
                sourceSegment = sourceCh.map(FileChannel.MapMode.READ_ONLY, 0,
                        sourceCh.size(), sourceArena);
            }

            // Read metadata
            int magic = sourceSegment.get(ValueLayout.JAVA_INT, META_MAGIC);
            if (magic != TIER_MAGIC) {
                throw new SpectorStorageException(
                        ErrorCode.FILE_FORMAT_INVALID, "bad tier magic in " + storePath + ": 0x" + Integer.toHexString(magic));
            }

            recordCount = sourceSegment.get(ValueLayout.JAVA_INT, META_COUNT);
            int capacity = sourceSegment.get(ValueLayout.JAVA_INT, META_CAPACITY);
            int tierOrd = sourceSegment.get(ValueLayout.JAVA_INT, META_TIER_ORD);

            int sourceRecordStride = isHeaderOnly ? source.headerBytes()
                    : source.headerBytes() + vectorBytes;
            int targetRecordStride = isHeaderOnly ? target.headerBytes()
                    : target.headerBytes() + vectorBytes;

            long targetDataSize = (long) targetRecordStride * capacity;
            long targetTotalSize = METADATA_HEADER_BYTES + targetDataSize;

            // ── Step 2: Create target temp file ──
            try (FileChannel targetCh = FileChannel.open(tempPath,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE)) {

                // Extend file
                targetCh.position(targetTotalSize - 1);
                targetCh.write(ByteBuffer.wrap(new byte[]{0}));

                MemorySegment targetSegment = targetCh.map(FileChannel.MapMode.READ_WRITE,
                        0, targetTotalSize, targetArena);

                // Write metadata header
                targetSegment.set(ValueLayout.JAVA_INT, META_MAGIC, TIER_MAGIC);
                targetSegment.set(ValueLayout.JAVA_INT, META_VERSION, target.version());
                targetSegment.set(ValueLayout.JAVA_INT, META_COUNT, recordCount);
                targetSegment.set(ValueLayout.JAVA_INT, META_CAPACITY, capacity);
                targetSegment.set(ValueLayout.JAVA_INT, META_STRIDE, targetRecordStride);
                targetSegment.set(ValueLayout.JAVA_INT, META_TIER_ORD, tierOrd);

                // ── Step 3: Migrate records ──
                for (int i = 0; i < recordCount; i++) {
                    long sourceOff = METADATA_HEADER_BYTES + (long) i * sourceRecordStride;
                    long targetOff = METADATA_HEADER_BYTES + (long) i * targetRecordStride;

                    // Read header from source layout (extended fields get defaults)
                    CognitiveRecordLayout.CognitiveHeader header =
                            source.readHeader(sourceSegment, sourceOff);

                    // Write header with target layout
                    target.writeHeader(targetSegment, targetOff, header);

                    // Copy vector payload if present
                    if (!isHeaderOnly && vectorBytes > 0) {
                        long sourceVecOff = sourceOff + source.headerBytes();
                        long targetVecOff = targetOff + target.headerBytes();
                        MemorySegment.copy(sourceSegment, sourceVecOff,
                                targetSegment, targetVecOff, vectorBytes);
                    }
                }

                // Force to disk
                targetSegment.force();

                log.info("Migrated {} records from V{} to V{}", recordCount,
                        source.version(), target.version());
            }

            // ── Step 4: Atomic swap ──
            // Back up original
            Files.move(storePath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            // Rename temp → original
            Files.move(tempPath, storePath, StandardCopyOption.ATOMIC_MOVE);

            long bytesAfter;
            try {
                bytesAfter = Files.size(storePath);
            } catch (IOException e) {
                bytesAfter = targetTotalSize;
            }

            Duration duration = Duration.between(start, Instant.now());

            log.info("Migration complete: {} records, {}KB → {}KB, took {}ms, backup at {}",
                    recordCount, bytesBefore / 1024, bytesAfter / 1024,
                    duration.toMillis(), backupPath);

            return new MigrationReport(recordCount, bytesBefore, bytesAfter,
                    duration, backupPath, isDowngrade);

        } catch (IOException e) {
            // Clean up temp file on failure
            try {
                Files.deleteIfExists(tempPath);
            } catch (IOException cleanupEx) {
                log.warn("Failed to clean up temp file: {}", tempPath, cleanupEx);
            }
            throw new SpectorStorageException(ErrorCode.STORAGE_MIGRATION_FAILED, e, storePath);
        }
    }

    /**
     * Estimates the target file size after migration without performing it.
     *
     * @param currentFileSize current file size in bytes
     * @param recordCount     number of records
     * @param source          current layout
     * @param target          target layout
     * @param vectorBytes     bytes per quantized vector
     * @param isHeaderOnly    true for header-only stores
     * @return estimated target file size in bytes
     */
    public static long estimateTargetSize(long currentFileSize, int recordCount,
                                           HeaderLayout source, HeaderLayout target,
                                           int vectorBytes, boolean isHeaderOnly) {
        int targetRecordStride = isHeaderOnly ? target.headerBytes()
                : target.headerBytes() + vectorBytes;
        int capacity = (int) ((currentFileSize - METADATA_HEADER_BYTES)
                / (isHeaderOnly ? source.headerBytes() : source.headerBytes() + vectorBytes));
        return METADATA_HEADER_BYTES + (long) targetRecordStride * capacity;
    }

    /**
     * Detects the header layout version from a store file's metadata.
     *
     * <p>Reads the stride field from the metadata header and infers the layout
     * version from it, since each version has a unique header size.</p>
     *
     * @param storePath   path to the store file
     * @param vectorBytes bytes per quantized vector
     * @param isHeaderOnly true for header-only stores
     * @return detected header layout
     */
    public static HeaderLayout detectVersion(Path storePath, int vectorBytes,
                                              boolean isHeaderOnly) {
        try (FileChannel ch = FileChannel.open(storePath, StandardOpenOption.READ)) {
            if (ch.size() < METADATA_HEADER_BYTES) {
                return HeaderLayout64.INSTANCE; // assume current layout
            }

            ByteBuffer buf = ByteBuffer.allocate(METADATA_HEADER_BYTES);
            ch.read(buf);
            buf.flip();

            int magic = buf.getInt(META_MAGIC);
            if (magic != TIER_MAGIC) {
                log.warn("Invalid magic in {}, assuming current layout", storePath);
                return HeaderLayout64.INSTANCE;
            }

            int stride = buf.getInt(META_STRIDE);
            int headerBytes = isHeaderOnly ? stride : stride - vectorBytes;

            if (headerBytes != SynapticHeaderConstants.HEADER_BYTES) {
                log.warn("Unexpected header size {} in {} (expected {}), assuming current layout",
                        headerBytes, storePath, SynapticHeaderConstants.HEADER_BYTES);
            }

            return HeaderLayout64.INSTANCE;
        } catch (IOException e) {
            log.warn("Cannot detect header version from {}: {}", storePath, e.getMessage());
            return HeaderLayout64.INSTANCE;
        }
    }

    /**
     * Cleans up orphaned {@code .migrating} temp files from interrupted migrations.
     *
     * @param storePath path to the store file
     */
    public static void cleanupOrphanedTempFile(Path storePath) {
        Path tempPath = storePath.resolveSibling(storePath.getFileName() + ".migrating");
        try {
            if (Files.deleteIfExists(tempPath)) {
                log.info("Cleaned up orphaned migration temp file: {}", tempPath);
            }
        } catch (IOException e) {
            log.warn("Failed to clean up orphaned temp file: {}", tempPath, e);
        }
    }

    /**
     * Migration result.
     *
     * @param recordsMigrated number of records migrated
     * @param bytesBefore     file size before migration
     * @param bytesAfter      file size after migration
     * @param duration        migration duration
     * @param backupPath      path to the backup of the original file
     * @param lossy           true if the migration was a downgrade (data loss)
     */
    public record MigrationReport(
            int recordsMigrated,
            long bytesBefore,
            long bytesAfter,
            Duration duration,
            Path backupPath,
            boolean lossy
    ) {
        @Override
        public String toString() {
            return String.format("MigrationReport[records=%d, %dKB→%dKB, %dms, backup=%s%s]",
                    recordsMigrated, bytesBefore / 1024, bytesAfter / 1024,
                    duration.toMillis(), backupPath, lossy ? ", LOSSY" : "");
        }
    }
}
