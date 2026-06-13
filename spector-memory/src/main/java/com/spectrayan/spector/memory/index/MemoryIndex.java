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

import com.spectrayan.spector.memory.model.MemoryType;
import com.spectrayan.spector.memory.cortex.MemorySource;
import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorStorageException;

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
    private static final int INDEX_VERSION = 2;
    /** V1 format (no metadata) — still loadable for backward compatibility. */
    private static final int INDEX_VERSION_V1 = 1;

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

    // ── Multimodal metadata: id → metadata map  [lazy — only non-empty for multimodal memories] ──
    private final ConcurrentHashMap<String, Map<String, String>> metadataMap = new ConcurrentHashMap<>();

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
        register(id, location, text, source, tagArray, null);
    }

    /**
     * Registers a new memory in the index with optional multimodal metadata.
     *
     * @param id       unique memory identifier
     * @param location physical storage location
     * @param text     raw text content
     * @param source   provenance source
     * @param tagArray synaptic tag strings
     * @param metadata multimodal metadata (nullable — omitted for text-only memories)
     */
    public void register(String id, MemoryLocation location, String text,
                          MemorySource source, String[] tagArray,
                          Map<String, String> metadata) {
        locations.put(id, location);
        texts.put(id, text);
        sources.put(id, source);
        tags.put(id, tagArray);

        // Only store metadata if non-empty (zero cost for text-only memories)
        if (metadata != null && !metadata.isEmpty()) {
            metadataMap.put(id, Map.copyOf(metadata));
        }

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
        metadataMap.remove(id);

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

    /** Shared empty metadata map — avoids allocation on cache miss. */
    private static final Map<String, String> EMPTY_METADATA = Map.of();

    /**
     * Returns the multimodal metadata for a memory ID.
     *
     * <p>Returns an empty map for text-only memories (never null).</p>
     */
    public Map<String, String> metadata(String id) {
        return metadataMap.getOrDefault(id, EMPTY_METADATA);
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
     * Returns all registered memory IDs.
     *
     * <p>Used by WAL replay to iterate over all reconstructed memories.
     * Returns a snapshot of the key set — safe for concurrent modification.</p>
     *
     * @return unmodifiable set of all memory IDs
     */
    public java.util.Set<String> allIds() {
        return java.util.Collections.unmodifiableSet(new java.util.HashSet<>(locations.keySet()));
    }

    /**
     * Returns the raw location map (for iteration in decay, etc.).
     */
    public ConcurrentHashMap<String, MemoryLocation> locationMap() {
        return locations;
    }

    /**
     * Returns a map of memory ID → text for all memories in the specified partition.
     *
     * <p>Used on startup to rebuild per-partition BM25 indexes from text data.
     * Filters the global text map by partition index.</p>
     *
     * @param partitionIndex the partition index to filter by
     * @return map of id → text for that partition (may be empty)
     */
    public Map<String, String> textsByPartition(int partitionIndex) {
        Map<String, String> result = new java.util.HashMap<>();
        for (Map.Entry<String, MemoryLocation> entry : locations.entrySet()) {
            if (entry.getValue().partitionIndex() == partitionIndex) {
                String text = texts.get(entry.getKey());
                if (text != null) {
                    result.put(entry.getKey(), text);
                }
            }
        }
        return result;
    }

    /**
     * Returns the total number of indexed memories (alias for quota enforcement).
     */
    public int totalCount() {
        return locations.size();
    }

    // ══════════════════════════════════════════════════════════════
    // COMPACTION SUPPORT
    // ══════════════════════════════════════════════════════════════

    /**
     * Relocates a single memory to a new offset within the same tier.
     *
     * <p>Called during vacuum compaction when live records are copied to
     * a new segment. Updates both forward and reverse indexes atomically.</p>
     *
     * @param id        the memory ID
     * @param newOffset the new byte offset in the compacted segment
     */
    public void relocate(String id, long newOffset) {
        MemoryLocation oldLoc = locations.get(id);
        if (oldLoc == null) return;

        // Remove old reverse entry
        reverseIndex.remove(reverseKey(oldLoc.type(), oldLoc.offset()));

        // Update forward index with new offset
        MemoryLocation newLoc = new MemoryLocation(oldLoc.type(), newOffset, oldLoc.partitionIndex());
        locations.put(id, newLoc);

        // Add new reverse entry
        reverseIndex.put(reverseKey(newLoc.type(), newOffset), id);
    }

    /**
     * Batch-relocates multiple memories to new offsets.
     *
     * <p>More efficient than calling {@link #relocate(String, long)} individually
     * because it avoids repeated concurrent map overhead for large compactions.</p>
     *
     * @param relocations map of memory ID → new byte offset
     */
    public void relocateBatch(Map<String, Long> relocations) {
        for (Map.Entry<String, Long> entry : relocations.entrySet()) {
            relocate(entry.getKey(), entry.getValue());
        }
    }

    // ══════════════════════════════════════════════════════════════
    // PERSISTENCE: save / load
    // ══════════════════════════════════════════════════════════════

    /**
     * Saves the entire index to a binary file.
     *
     * <h3>File Format (V2)</h3>
     * <pre>
     *   [4B magic: "MIDX"]  [4B version: 2]  [4B entry_count]  [4B reserved]
     *   For each entry:
     *     [4B id_len] [N id_bytes]
     *     [4B type_ordinal] [8B offset] [4B partition_index]
     *     [4B text_len] [N text_bytes]
     *     [4B source_ordinal]
     *     [4B tag_count] { [4B tag_len] [N tag_bytes] }*
     *     [4B metadata_count] { [4B key_len] [N key_bytes] [4B val_len] [N val_bytes] }*
     * </pre>
     *
     * <p>V1 files (no metadata section) are still loadable for backward compatibility.</p>
     *
     * @param filePath path to write the index file
     */
    public void save(Path filePath) {
        Path parent = filePath.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new SpectorStorageException(ErrorCode.PARTITION_DIR_FAILED, e, parent);
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
                Map<String, String> meta = metadataMap.getOrDefault(id, Map.of());

                writeEntry(ch, id, loc, text, source, tagArray, meta);
            }

            ch.force(true);
            log.info("MemoryIndex saved: {} entries → {}", locations.size(), filePath);

        } catch (IOException e) {
            throw new SpectorStorageException(ErrorCode.DISK_IO_FAILED, e, "save MemoryIndex: " + filePath);
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
            if (version != INDEX_VERSION && version != INDEX_VERSION_V1) {
                log.warn("Unsupported MemoryIndex version: {} (expected {} or {}), starting fresh",
                        version, INDEX_VERSION, INDEX_VERSION_V1);
                return index;
            }

            boolean hasMetadata = (version >= INDEX_VERSION);

            // Read entries
            for (int i = 0; i < entryCount; i++) {
                readEntry(ch, index, hasMetadata);
            }

            log.info("MemoryIndex loaded: {} entries from {}", index.size(), filePath);

        } catch (IOException e) {
            log.error("Failed to load MemoryIndex from {}, starting fresh: {}", filePath, e.getMessage());
        }

        return index;
    }

    // ── Internal serialization helpers ──

    private void writeEntry(FileChannel ch, String id, MemoryLocation loc,
                             String text, MemorySource source, String[] tagArray,
                             Map<String, String> metadata) throws IOException {
        byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);

        // Calculate total size for this entry
        int size = 4 + idBytes.length      // id
                + 4 + 8 + 4               // location (type + offset + partitionIndex)
                + 4 + textBytes.length     // text
                + 4                        // source
                + 4;                       // tag count

        // Pre-compute tag byte arrays
        byte[][] tagBytesArray = new byte[tagArray.length][];
        for (int i = 0; i < tagArray.length; i++) {
            tagBytesArray[i] = tagArray[i].getBytes(StandardCharsets.UTF_8);
            size += 4 + tagBytesArray[i].length;
        }

        // V2: metadata map (4B count + entries)
        size += 4; // metadata count
        byte[][] metaKeyBytes = new byte[metadata.size()][];
        byte[][] metaValBytes = new byte[metadata.size()][];
        int mi = 0;
        for (Map.Entry<String, String> me : metadata.entrySet()) {
            metaKeyBytes[mi] = me.getKey().getBytes(StandardCharsets.UTF_8);
            metaValBytes[mi] = me.getValue().getBytes(StandardCharsets.UTF_8);
            size += 4 + metaKeyBytes[mi].length + 4 + metaValBytes[mi].length;
            mi++;
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
        for (byte[] tagBytes : tagBytesArray) {
            buf.putInt(tagBytes.length);
            buf.put(tagBytes);
        }

        // V2: Metadata map
        buf.putInt(metadata.size());
        for (int j = 0; j < metaKeyBytes.length; j++) {
            buf.putInt(metaKeyBytes[j].length);
            buf.put(metaKeyBytes[j]);
            buf.putInt(metaValBytes[j].length);
            buf.put(metaValBytes[j]);
        }

        buf.flip();
        ch.write(buf);
    }

    private static void readEntry(FileChannel ch, MemoryIndex index, boolean hasMetadata) throws IOException {
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

        // V2: Metadata map
        Map<String, String> metadata = null;
        if (hasMetadata) {
            ByteBuffer metaCountBuf = ByteBuffer.allocate(4);
            ch.read(metaCountBuf);
            metaCountBuf.flip();
            int metaCount = metaCountBuf.getInt();
            if (metaCount > 0) {
                metadata = new java.util.HashMap<>(metaCount);
                for (int m = 0; m < metaCount; m++) {
                    String key = readString(ch);
                    String value = readString(ch);
                    metadata.put(key, value);
                }
            }
        }

        index.register(id, loc, text, source, tagArray, metadata);
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

