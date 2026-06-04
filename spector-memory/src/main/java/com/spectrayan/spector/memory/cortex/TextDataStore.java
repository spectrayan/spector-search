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
package com.spectrayan.spector.memory.cortex;

import com.spectrayan.spector.memory.MemoryType;
import com.spectrayan.spector.memory.StorageLayout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binary reader/writer for {@code text.dat} files within partition directories.
 *
 * <h3>Purpose</h3>
 * <p>Stores the raw text content for all memory tiers in a single partition.
 * On startup, the file is read sequentially to populate both
 * {@link com.spectrayan.spector.memory.index.MemoryIndex} texts and per-partition
 * BM25 indexes.</p>
 *
 * <h3>Binary Format</h3>
 * <pre>
 *   Header (16 bytes):
 *     [4B magic: 0x54585444 "TXTD"]
 *     [4B version: 1]
 *     [4B entry_count]
 *     [4B reserved]
 *
 *   For each entry:
 *     [1B tier_ordinal]              — 0=WORKING, 1=EPISODIC, 2=SEMANTIC, 3=PROCEDURAL
 *     [4B id_len] [N id_bytes]       — Memory ID (UTF-8)
 *     [4B text_len] [N text_bytes]   — Raw text content (UTF-8)
 * </pre>
 *
 * <h3>Performance</h3>
 * <p>Sequential SSD read at 3GB/s → 10K entries (~5MB of text) loads in ~1.7ms.
 * The format is intentionally flat with no indexing structure — it's read once
 * on startup and never random-accessed.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Not thread-safe. Callers must synchronize externally if concurrent writes
 * are needed (typically writes happen from a single ingestion thread per partition).</p>
 *
 * @see StorageLayout#FILE_TEXT
 * @see StorageLayout#TEXT_DAT_MAGIC
 */
public final class TextDataStore {

    private static final Logger log = LoggerFactory.getLogger(TextDataStore.class);

    /** Header size: magic(4) + version(4) + count(4) + reserved(4). */
    private static final int HEADER_BYTES = 16;

    private final Path file;
    private int entryCount;

    /**
     * Creates a TextDataStore for the given file path.
     *
     * @param file path to the text.dat file (may or may not exist yet)
     */
    public TextDataStore(Path file) {
        this.file = file;
        this.entryCount = 0;
    }

    /**
     * Creates a TextDataStore for a partition directory, using the standard file name.
     *
     * @param partitionDir the partition directory
     * @return a new TextDataStore instance
     */
    public static TextDataStore forPartition(Path partitionDir) {
        return new TextDataStore(StorageLayout.textDat(partitionDir));
    }

    /**
     * A single entry in the text.dat file.
     *
     * @param id   memory identifier
     * @param tier the cognitive tier this memory belongs to
     * @param text the raw text content
     */
    public record TextEntry(String id, MemoryType tier, String text) {}

    /**
     * Appends a single text entry to the file.
     *
     * <p>If the file doesn't exist, creates it with a fresh header.
     * If the file exists, appends the entry and updates the header count.</p>
     *
     * @param id   memory identifier
     * @param tier the cognitive tier
     * @param text the raw text content
     */
    public void write(String id, MemoryType tier, String text) {
        try {
            boolean isNew = !Files.exists(file);

            try (FileChannel ch = FileChannel.open(file,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    isNew ? StandardOpenOption.CREATE_NEW : StandardOpenOption.APPEND)) {

                if (isNew) {
                    writeHeader(ch, 0);
                }

                // Encode strings
                byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
                byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);

                // Entry: tier(1) + idLen(4) + id(N) + textLen(4) + text(N)
                int entrySize = 1 + 4 + idBytes.length + 4 + textBytes.length;
                ByteBuffer buf = ByteBuffer.allocate(entrySize);
                buf.put((byte) tier.ordinal());
                buf.putInt(idBytes.length);
                buf.put(idBytes);
                buf.putInt(textBytes.length);
                buf.put(textBytes);
                buf.flip();

                // Position at end for append
                ch.position(ch.size());
                ch.write(buf);

                entryCount++;
            }

            // Update count in header
            updateHeaderCount();

        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write text entry: " + id, e);
        }
    }

    /**
     * Reads all entries from the file.
     *
     * <p>Returns a linked map preserving insertion order. Used on startup to
     * populate MemoryIndex texts and rebuild BM25 indexes.</p>
     *
     * @return map of memory ID → TextEntry, empty map if file doesn't exist
     */
    public Map<String, TextEntry> readAll() {
        Map<String, TextEntry> entries = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return entries;
        }

        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long fileSize = ch.size();
            if (fileSize < HEADER_BYTES) {
                log.warn("text.dat too small ({} bytes), skipping: {}", fileSize, file);
                return entries;
            }

            // Read header
            ByteBuffer headerBuf = ByteBuffer.allocate(HEADER_BYTES);
            ch.read(headerBuf);
            headerBuf.flip();

            int magic = headerBuf.getInt();
            if (magic != StorageLayout.TEXT_DAT_MAGIC) {
                log.error("Invalid text.dat magic: 0x{} (expected 0x{}), file: {}",
                        Integer.toHexString(magic),
                        Integer.toHexString(StorageLayout.TEXT_DAT_MAGIC), file);
                return entries;
            }

            int version = headerBuf.getInt();
            if (version != StorageLayout.TEXT_DAT_VERSION) {
                log.error("Unsupported text.dat version: {} (expected {}), file: {}",
                        version, StorageLayout.TEXT_DAT_VERSION, file);
                return entries;
            }

            int count = headerBuf.getInt();
            // reserved (4 bytes, ignored)

            // Read entries
            // Use a large buffer for sequential read performance
            int remaining = (int) (fileSize - HEADER_BYTES);
            if (remaining <= 0) {
                return entries;
            }

            ByteBuffer dataBuf = ByteBuffer.allocate(Math.min(remaining, 8 * 1024 * 1024));
            ch.position(HEADER_BYTES);

            int readSoFar = 0;
            while (readSoFar < remaining) {
                dataBuf.clear();
                int bytesRead = ch.read(dataBuf);
                if (bytesRead <= 0) break;
                dataBuf.flip();
                readSoFar += bytesRead;

                while (dataBuf.remaining() >= 9) { // minimum entry: 1 + 4 + 0 + 4 + 0
                    int posBeforeEntry = dataBuf.position();

                    byte tierOrd = dataBuf.get();
                    if (tierOrd < 0 || tierOrd >= MemoryType.values().length) {
                        log.warn("Invalid tier ordinal {} at position {}, stopping read", tierOrd, posBeforeEntry);
                        return entries;
                    }

                    if (dataBuf.remaining() < 4) {
                        dataBuf.position(posBeforeEntry);
                        break;
                    }
                    int idLen = dataBuf.getInt();
                    if (idLen < 0 || idLen > 10_000 || dataBuf.remaining() < idLen + 4) {
                        dataBuf.position(posBeforeEntry);
                        break;
                    }

                    byte[] idBytes = new byte[idLen];
                    dataBuf.get(idBytes);

                    int textLen = dataBuf.getInt();
                    if (textLen < 0 || textLen > 10_000_000 || dataBuf.remaining() < textLen) {
                        dataBuf.position(posBeforeEntry);
                        break;
                    }

                    byte[] textBytes = new byte[textLen];
                    dataBuf.get(textBytes);

                    MemoryType tier = MemoryType.values()[tierOrd];
                    String id = new String(idBytes, StandardCharsets.UTF_8);
                    String text = new String(textBytes, StandardCharsets.UTF_8);

                    entries.put(id, new TextEntry(id, tier, text));
                }
            }

            this.entryCount = entries.size();

            if (entries.size() != count) {
                log.warn("text.dat header count ({}) != actual entries read ({}): {}",
                        count, entries.size(), file);
            }

        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read text.dat: " + file, e);
        }

        log.debug("Loaded {} text entries from {}", entries.size(), file);
        return entries;
    }

    /**
     * Rebuilds the file from the given entries (compaction).
     *
     * <p>Rewrites the entire file with only the provided entries.
     * Used after partition compaction to remove tombstoned entries.</p>
     *
     * @param entries the surviving entries to write
     */
    public void rebuild(Map<String, TextEntry> entries) {
        try {
            // Write to temp file, then atomic rename
            Path tempFile = file.resolveSibling(file.getFileName() + ".tmp");

            try (FileChannel ch = FileChannel.open(tempFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {

                writeHeader(ch, entries.size());

                for (TextEntry entry : entries.values()) {
                    byte[] idBytes = entry.id().getBytes(StandardCharsets.UTF_8);
                    byte[] textBytes = entry.text().getBytes(StandardCharsets.UTF_8);

                    int entrySize = 1 + 4 + idBytes.length + 4 + textBytes.length;
                    ByteBuffer buf = ByteBuffer.allocate(entrySize);
                    buf.put((byte) entry.tier().ordinal());
                    buf.putInt(idBytes.length);
                    buf.put(idBytes);
                    buf.putInt(textBytes.length);
                    buf.put(textBytes);
                    buf.flip();
                    ch.write(buf);
                }
            }

            // Atomic rename
            Files.move(tempFile, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);

            this.entryCount = entries.size();
            log.debug("Rebuilt text.dat with {} entries: {}", entries.size(), file);

        } catch (IOException e) {
            throw new UncheckedIOException("Failed to rebuild text.dat: " + file, e);
        }
    }

    /** Returns the number of entries in this store. */
    public int size() {
        return entryCount;
    }

    /** Returns the file path. */
    public Path path() {
        return file;
    }

    // ── Internal helpers ──

    private void writeHeader(FileChannel ch, int count) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        header.putInt(StorageLayout.TEXT_DAT_MAGIC);
        header.putInt(StorageLayout.TEXT_DAT_VERSION);
        header.putInt(count);
        header.putInt(0); // reserved
        header.flip();
        ch.position(0);
        ch.write(header);
    }

    private void updateHeaderCount() throws IOException {
        try (FileChannel ch = FileChannel.open(file,
                StandardOpenOption.WRITE)) {
            ByteBuffer countBuf = ByteBuffer.allocate(4);
            countBuf.putInt(entryCount);
            countBuf.flip();
            ch.position(8); // offset of count field: magic(4) + version(4)
            ch.write(countBuf);
        }
    }
}
