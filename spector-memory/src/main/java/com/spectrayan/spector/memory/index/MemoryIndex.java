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
package com.spectrayan.spector.memory.index;

import com.spectrayan.spector.memory.MemoryType;
import com.spectrayan.spector.memory.cortex.MemorySource;

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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized ID → metadata index for cognitive memories.
 *
 * <h3>Responsibility</h3>
 * <p>Owns the concurrent maps that track memory locations, raw text,
 * provenance sources, and synaptic tag strings. Provides O(1) lookup by ID
 * and O(1) reverse-lookup by offset (via dedicated reverse index).</p>
 *
 * <h3>Persistence</h3>
 * <p>Supports binary serialization via {@link #save(Path)} and {@link #load(Path)}.
 * The file format uses a "MIDX" magic header followed by variable-length records.
 * On startup, the index can be rebuilt from disk without re-ingestion.</p>
 *
 * <h3>Performance: O(1) Reverse Index</h3>
 * <p>A dedicated {@code reverseIndex} maps {@code (type, offset) → id} for
 * constant-time reverse lookups during recall result assembly. The key is
 * computed as {@code (type.ordinal() << 48) | offset}, packing both into
 * a single {@code long} to avoid String concatenation.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>All maps are {@link ConcurrentHashMap} — safe for concurrent ingestion
 * (Virtual Threads) and recall (parallel scans).</p>
 */
public final class MemoryIndex {

    private static final Logger log = LoggerFactory.getLogger(MemoryIndex.class);

    /** File magic: "MIDX" in ASCII. */
    private static final int INDEX_MAGIC = 0x4D494458;

    /** File format version. */
    private static final int INDEX_VERSION = 1;

    /** File header: 4B magic + 4B version + 4B count + 4B reserved = 16 bytes. */
    private static final int FILE_HEADER_BYTES = 16;

    /**
     * Tracks where a memory is physically stored.
     *
     * @param type            cognitive tier
     * @param offset          byte offset within the tier's segment
     * @param partitionIndex  partition index (episodic only, -1 otherwise)
     */
    public record MemoryLocation(MemoryType type, long offset, int partitionIndex) {}

    // ── Forward index: id → metadata ──
    private final ConcurrentHashMap<String, MemoryLocation> locations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> texts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MemorySource> sources = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String[]> tags = new ConcurrentHashMap<>();

    // ── Reverse index: (type, offset) → id  [O(1) lookup for recall result assembly] ──
    private final ConcurrentHashMap<Long, String> reverseIndex = new ConcurrentHashMap<>();

    /**
     * Computes the reverse-index key from a memory type and byte offset.
     *
     * <p>Packs type ordinal into the upper 16 bits and offset into the lower 48 bits.
     * This supports offsets up to 256 TB per tier — far beyond any practical limit.</p>
     */
    private static long reverseKey(MemoryType type, long offset) {
        return ((long) type.ordinal() << 48) | (offset & 0x0000_FFFF_FFFF_FFFFL);
    }

    /**
     * Registers a new memory in the index.
     *
     * <p>Maintains both forward (id → metadata) and reverse ((type, offset) → id)
     * indexes for O(1) lookups in both directions.</p>
     *
     * @param id       unique memory identifier
     * @param location physical storage location
     * @param text     raw text content
     * @param source   provenance source
     * @param tagArray synaptic tag strings
     */
    public void register(String id, MemoryLocation location, String text,
                          MemorySource source, String[] tagArray) {
        locations.put(id, location);
        texts.put(id, text);
        sources.put(id, source);
        tags.put(id, tagArray);

        // O(1) reverse index
        reverseIndex.put(reverseKey(location.type(), location.offset()), id);
    }

    /**
     * Removes a memory from both forward and reverse indexes.
     */
    public void remove(String id) {
        MemoryLocation loc = locations.remove(id);
        texts.remove(id);
        sources.remove(id);
        tags.remove(id);

        // Clean reverse index
        if (loc != null) {
            reverseIndex.remove(reverseKey(loc.type(), loc.offset()));
        }
    }

    /**
     * Returns the physical location for a memory ID, or null if not found.
     * O(1) via ConcurrentHashMap.
     */
    public MemoryLocation locate(String id) {
        return locations.get(id);
    }

    /**
     * Returns the raw text for a memory ID, or empty string if not found.
     */
    public String text(String id) {
        return texts.getOrDefault(id, "");
    }

    /**
     * Returns the provenance source for a memory ID.
     */
    public MemorySource source(String id) {
        return sources.getOrDefault(id, MemorySource.OBSERVED);
    }

    /** Shared empty tags array — avoids heap allocation on every cache miss. */
    private static final String[] EMPTY_TAGS = new String[0];

    /**
     * Returns the synaptic tag strings for a memory ID.
     */
    public String[] tags(String id) {
        return tags.getOrDefault(id, EMPTY_TAGS);
    }

    /**
     * O(1) reverse-lookup: finds the memory ID stored at a given offset in a given tier.
     *
     * <p>Uses a dedicated reverse index ({@code ConcurrentHashMap<Long, String>})
     * instead of the previous O(n) linear scan over the location map.</p>
     *
     * @param type   memory tier to search
     * @param offset byte offset to match
     * @return the memory ID, or null if not found
     */
    public String findIdByOffset(MemoryType type, long offset) {
        return reverseIndex.get(reverseKey(type, offset));
    }

    /**
     * Returns the text for a memory stored at a given offset.
     * O(1) via reverse index.
     */
    public String findTextByOffset(MemoryType type, long offset) {
        String id = findIdByOffset(type, offset);
        return id != null ? texts.get(id) : null;
    }

    /**
     * Returns the total number of indexed memories.
     */
    public int size() {
        return locations.size();
    }

    /**
     * Returns the raw location map (for iteration in decay, etc.).
     */
    public ConcurrentHashMap<String, MemoryLocation> locationMap() {
        return locations;
    }

    // ══════════════════════════════════════════════════════════════
    // PERSISTENCE: save / load
    // ══════════════════════════════════════════════════════════════

    /**
     * Saves the entire index to a binary file.
     *
     * <h3>File Format</h3>
     * <pre>
     *   [4B magic: "MIDX"]  [4B version: 1]  [4B entry_count]  [4B reserved]
     *   For each entry:
     *     [4B id_len] [N id_bytes]
     *     [4B type_ordinal] [8B offset] [4B partition_index]
     *     [4B text_len] [N text_bytes]
     *     [4B source_ordinal]
     *     [4B tag_count] { [4B tag_len] [N tag_bytes] }*
     * </pre>
     *
     * @param filePath path to write the index file
     */
    public void save(Path filePath) {
        Path parent = filePath.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot create index directory: " + parent, e);
            }
        }

        try (FileChannel ch = FileChannel.open(filePath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            // Write file header
            ByteBuffer header = ByteBuffer.allocate(FILE_HEADER_BYTES);
            header.putInt(INDEX_MAGIC);
            header.putInt(INDEX_VERSION);
            header.putInt(locations.size());
            header.putInt(0); // reserved
            header.flip();
            ch.write(header);

            // Write each entry
            for (Map.Entry<String, MemoryLocation> entry : locations.entrySet()) {
                String id = entry.getKey();
                MemoryLocation loc = entry.getValue();
                String text = texts.getOrDefault(id, "");
                MemorySource source = sources.getOrDefault(id, MemorySource.OBSERVED);
                String[] tagArray = tags.getOrDefault(id, new String[0]);

                writeEntry(ch, id, loc, text, source, tagArray);
            }

            ch.force(true);
            log.info("MemoryIndex saved: {} entries → {}", locations.size(), filePath);

        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save MemoryIndex: " + filePath, e);
        }
    }

    /**
     * Loads an index from a binary file, or returns a new empty index
     * if the file doesn't exist.
     *
     * @param filePath path to the index file
     * @return a populated MemoryIndex (or empty if file missing)
     */
    public static MemoryIndex load(Path filePath) {
        MemoryIndex index = new MemoryIndex();

        if (filePath == null || !Files.exists(filePath)) {
            log.info("MemoryIndex file not found, starting fresh: {}", filePath);
            return index;
        }

        try (FileChannel ch = FileChannel.open(filePath, StandardOpenOption.READ)) {
            long fileSize = ch.size();
            if (fileSize < FILE_HEADER_BYTES) {
                log.warn("MemoryIndex file too small ({}B), starting fresh", fileSize);
                return index;
            }

            // Read file header
            ByteBuffer header = ByteBuffer.allocate(FILE_HEADER_BYTES);
            ch.read(header);
            header.flip();

            int magic = header.getInt();
            int version = header.getInt();
            int entryCount = header.getInt();
            header.getInt(); // reserved

            if (magic != INDEX_MAGIC) {
                log.warn("Invalid MemoryIndex magic: 0x{} (expected 0x{}), starting fresh",
                        Integer.toHexString(magic), Integer.toHexString(INDEX_MAGIC));
                return index;
            }
            if (version != INDEX_VERSION) {
                log.warn("Unsupported MemoryIndex version: {} (expected {}), starting fresh",
                        version, INDEX_VERSION);
                return index;
            }

            // Read entries
            for (int i = 0; i < entryCount; i++) {
                readEntry(ch, index);
            }

            log.info("MemoryIndex loaded: {} entries from {}", index.size(), filePath);

        } catch (IOException e) {
            log.error("Failed to load MemoryIndex from {}, starting fresh: {}", filePath, e.getMessage());
        }

        return index;
    }

    // ── Internal serialization helpers ──

    private void writeEntry(FileChannel ch, String id, MemoryLocation loc,
                             String text, MemorySource source, String[] tagArray) throws IOException {
        byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);

        // Calculate total size for this entry
        int size = 4 + idBytes.length      // id
                + 4 + 8 + 4               // location (type + offset + partitionIndex)
                + 4 + textBytes.length     // text
                + 4                        // source
                + 4;                       // tag count

        for (String tag : tagArray) {
            size += 4 + tag.getBytes(StandardCharsets.UTF_8).length;
        }

        ByteBuffer buf = ByteBuffer.allocate(size);

        // ID
        buf.putInt(idBytes.length);
        buf.put(idBytes);

        // Location
        buf.putInt(loc.type().ordinal());
        buf.putLong(loc.offset());
        buf.putInt(loc.partitionIndex());

        // Text
        buf.putInt(textBytes.length);
        buf.put(textBytes);

        // Source
        buf.putInt(source.ordinal());

        // Tags
        buf.putInt(tagArray.length);
        for (String tag : tagArray) {
            byte[] tagBytes = tag.getBytes(StandardCharsets.UTF_8);
            buf.putInt(tagBytes.length);
            buf.put(tagBytes);
        }

        buf.flip();
        ch.write(buf);
    }

    private static void readEntry(FileChannel ch, MemoryIndex index) throws IOException {
        // ID
        String id = readString(ch);

        // Location
        ByteBuffer locBuf = ByteBuffer.allocate(4 + 8 + 4);
        ch.read(locBuf);
        locBuf.flip();
        int typeOrd = locBuf.getInt();
        long offset = locBuf.getLong();
        int partitionIndex = locBuf.getInt();
        MemoryType type = MemoryType.values()[typeOrd];
        MemoryLocation loc = new MemoryLocation(type, offset, partitionIndex);

        // Text
        String text = readString(ch);

        // Source
        ByteBuffer srcBuf = ByteBuffer.allocate(4);
        ch.read(srcBuf);
        srcBuf.flip();
        int sourceOrd = srcBuf.getInt();
        MemorySource source = MemorySource.values()[sourceOrd];

        // Tags
        ByteBuffer tagCountBuf = ByteBuffer.allocate(4);
        ch.read(tagCountBuf);
        tagCountBuf.flip();
        int tagCount = tagCountBuf.getInt();
        String[] tagArray = new String[tagCount];
        for (int t = 0; t < tagCount; t++) {
            tagArray[t] = readString(ch);
        }

        index.register(id, loc, text, source, tagArray);
    }

    private static String readString(FileChannel ch) throws IOException {
        ByteBuffer lenBuf = ByteBuffer.allocate(4);
        ch.read(lenBuf);
        lenBuf.flip();
        int len = lenBuf.getInt();

        if (len == 0) return "";

        ByteBuffer strBuf = ByteBuffer.allocate(len);
        ch.read(strBuf);
        strBuf.flip();
        return new String(strBuf.array(), 0, len, StandardCharsets.UTF_8);
    }
}

