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
package com.spectrayan.spector.memory.hebbian;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Off-heap synaptic tag co-occurrence and STDP tracking for Hebbian learning.
 *
 * <h3>Biological Analog: Hebbian Learning + STDP</h3>
 * <p>"Cells that fire together wire together" (Hebb, 1949). When two neurons
 * fire simultaneously, the synapse between them strengthens. Over time,
 * activating one neuron automatically activates the other — this is the
 * basis of associative memory.</p>
 *
 * <h3>Spike-Timing-Dependent Plasticity (STDP)</h3>
 * <p>STDP extends basic Hebbian learning with <em>temporal direction</em>.
 * If neuron A fires <b>before</b> neuron B (causal), the A→B synapse is
 * <b>strengthened</b> (Long-Term Potentiation). If A fires <b>after</b> B
 * (anti-causal), the B→A synapse is <b>weakened</b> (Long-Term Depression).
 * This produces directed, predictive associations — "tag A predicts tag B."</p>
 *
 * <h3>Architecture</h3>
 * <p>This class coordinates two independent off-heap hash tables:</p>
 * <ul>
 *   <li>{@link OffHeapPairTable} — undirected co-activation pairs (32B/slot)</li>
 *   <li>{@link OffHeapEdgeTable} — directed STDP edges (40B/slot)</li>
 * </ul>
 * <p>Each table has its own {@code ReentrantLock}, so pair writes never
 * block edge writes and vice versa.</p>
 *
 * @see OffHeapPairTable
 * @see OffHeapEdgeTable
 * @see HebbianCoActivationListener
 */
public final class CoActivationTracker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CoActivationTracker.class);

    // ── STDP Constants ──

    /** A+ (LTP amplitude): maximum weight increase for causal pairings. */
    private static final float A_PLUS = 0.1f;

    /** A- (LTD amplitude): maximum weight decrease for anti-causal pairings. */
    private static final float A_MINUS = 0.05f;

    /** τ+ (LTP time constant): causal window in milliseconds. */
    private static final float TAU_PLUS = 30_000f;  // 30 seconds

    /** τ- (LTD time constant): anti-causal window in milliseconds. */
    private static final float TAU_MINUS = 30_000f;  // 30 seconds

    /** Minimum weight (prevent complete erasure). */
    static final float MIN_WEIGHT = 0.0f;

    /** Maximum weight (prevent runaway potentiation). */
    static final float MAX_WEIGHT = 1.0f;

    // ── Persistence ──

    /** File magic: "COAX" in ASCII. */
    private static final int FILE_MAGIC = 0x434F4158;
    private static final int FILE_VERSION = 1;
    /** Header: magic(4) + version(4) + pairCap(4) + edgeCap(4) + pairCount(4) + edgeCount(4) = 24B. */
    private static final int FILE_HEADER_BYTES = 24;

    // ── State ──

    private final Arena arena;
    private final OffHeapPairTable pairTable;
    private final OffHeapEdgeTable edgeTable;

    /** On-heap name↔hash resolution (small — only unique tag strings). */
    private final ConcurrentHashMap<Long, String> hashToTag = new ConcurrentHashMap<>();

    // ── Records ──

    /**
     * A directed edge between two synaptic tags.
     */
    public record DirectedEdge(String sourceTag, String targetTag) {
        @Override
        public String toString() {
            return sourceTag + "→" + targetTag;
        }
    }

    /**
     * STDP edge weight with temporal metadata.
     *
     * @param weight           current STDP weight (0.0 to 1.0)
     * @param lastActivatedMs  epoch millis of last activation
     * @param activationCount  total number of sequential activations
     */
    public record EdgeWeight(float weight, long lastActivatedMs, int activationCount) {
        /** Returns a new EdgeWeight with updated weight and timestamp. */
        public EdgeWeight withUpdate(float deltaWeight, long nowMs) {
            float newWeight = Math.clamp(weight + deltaWeight, MIN_WEIGHT, MAX_WEIGHT);
            return new EdgeWeight(newWeight, nowMs, activationCount + 1);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Constructors
    // ══════════════════════════════════════════════════════════════

    /**
     * Creates a co-activation tracker with default capacities (10_000 pairs, 20_000 edges).
     */
    public CoActivationTracker() {
        this(10_000);
    }

    /**
     * Creates a co-activation tracker.
     *
     * @param maxPairs maximum tracked undirected pairs
     */
    public CoActivationTracker(int maxPairs) {
        this(maxPairs, maxPairs * 2);
    }

    /**
     * Creates a co-activation tracker with custom limits.
     *
     * @param maxPairs maximum tracked undirected pairs
     * @param maxEdges maximum tracked STDP directed edges
     */
    public CoActivationTracker(int maxPairs, int maxEdges) {
        int pairCap = nextPowerOf2(Math.max(64, maxPairs * 2));
        int edgeCap = nextPowerOf2(Math.max(64, maxEdges * 2));
        this.arena = Arena.ofShared();
        this.pairTable = new OffHeapPairTable(pairCap, arena);
        this.edgeTable = new OffHeapEdgeTable(edgeCap, arena);

        log.info("CoActivationTracker initialized (off-heap): pairSlots={}, edgeSlots={}, memory={}KB",
                pairCap, edgeCap,
                ((long) OffHeapPairTable.SLOT_BYTES * pairCap
                        + (long) OffHeapEdgeTable.SLOT_BYTES * edgeCap) / 1024);
    }

    /** Private constructor for loading from pre-existing tables. */
    private CoActivationTracker(Arena arena, OffHeapPairTable pairTable, OffHeapEdgeTable edgeTable,
                                 ConcurrentHashMap<Long, String> hashToTag) {
        this.arena = arena;
        this.pairTable = pairTable;
        this.edgeTable = edgeTable;
        this.hashToTag.putAll(hashToTag);
    }

    // ══════════════════════════════════════════════════════════════
    // Undirected Co-Activation (Original Hebbian)
    // ══════════════════════════════════════════════════════════════

    /**
     * Records co-activation of tags that appeared together in a recall result set.
     *
     * @param tags array of tag strings that appeared together in recall results
     */
    public void recordCoActivation(String... tags) {
        if (tags.length < 2) return;

        for (int i = 0; i < tags.length; i++) {
            for (int j = i + 1; j < tags.length; j++) {
                long hashA = hashTag(tags[i]);
                long hashB = hashTag(tags[j]);
                registerTag(tags[i], hashA);
                registerTag(tags[j], hashB);

                // Ensure canonical order: smaller hash first
                long keyA = Math.min(hashA, hashB);
                long keyB = Math.max(hashA, hashB);

                pairTable.increment(keyA, keyB);
            }
        }
    }

    /**
     * Returns the co-activation count for a tag pair.
     *
     * @param tagA first tag
     * @param tagB second tag
     * @return co-activation count (0 if never co-activated)
     */
    public int getCoActivation(String tagA, String tagB) {
        long hashA = hashTag(tagA);
        long hashB = hashTag(tagB);
        long keyA = Math.min(hashA, hashB);
        long keyB = Math.max(hashA, hashB);
        return pairTable.get(keyA, keyB);
    }

    /**
     * Returns the top-N most co-activated tags for a given tag.
     *
     * @param tag   the source tag
     * @param topN  maximum number of associated tags to return
     * @return list of associated tag names sorted by co-activation strength
     */
    public List<String> getAssociatedTags(String tag, int topN) {
        long tagHash = hashTag(tag);

        record TagCount(String name, int count) {}

        return pairTable.findAssociations(tagHash).stream()
                .map(arr -> {
                    String name = hashToTag.get(arr[0]);
                    return name != null ? new TagCount(name, (int) arr[1]) : null;
                })
                .filter(tc -> tc != null)
                .sorted((a, b) -> Integer.compare(b.count(), a.count()))
                .limit(topN)
                .map(TagCount::name)
                .toList();
    }

    // ══════════════════════════════════════════════════════════════
    // STDP — Spike-Timing-Dependent Plasticity
    // ══════════════════════════════════════════════════════════════

    /**
     * Records a sequential activation pair for STDP weight update.
     *
     * @param tagBefore  the tag that was activated first
     * @param tagAfter   the tag that was activated second
     * @param timeBefore epoch millis when tagBefore was activated
     * @param timeAfter  epoch millis when tagAfter was activated
     */
    public void recordSequentialActivation(String tagBefore, String tagAfter,
                                            long timeBefore, long timeAfter) {
        if (tagBefore.equals(tagAfter)) return;
        if (timeAfter < timeBefore) return;

        long dt = timeAfter - timeBefore;
        long hashBefore = hashTag(tagBefore);
        long hashAfter = hashTag(tagAfter);
        registerTag(tagBefore, hashBefore);
        registerTag(tagAfter, hashAfter);

        // Causal: A→B (strengthen)
        float dW_causal = A_PLUS * (float) Math.exp(-dt / TAU_PLUS);
        edgeTable.update(hashBefore, hashAfter, dW_causal, timeAfter);

        // Anti-causal: B→A (weaken)
        float dW_anti = -A_MINUS * (float) Math.exp(-dt / TAU_MINUS);
        edgeTable.update(hashAfter, hashBefore, dW_anti, timeAfter);

        log.trace("STDP: {}→{} Δt={}ms, causal ΔW={}, anti-causal ΔW={}",
                tagBefore, tagAfter, dt,
                String.format("%.4f", dW_causal), String.format("%.4f", dW_anti));
    }

    /**
     * Records sequential activations from an ordered list of tags with timestamps.
     *
     * @param orderedTags tags in temporal order (first = earliest)
     * @param timestamps  corresponding epoch millis for each tag
     */
    public void recordSequentialActivations(List<String> orderedTags, List<Long> timestamps) {
        if (orderedTags.size() < 2) return;
        if (orderedTags.size() != timestamps.size()) return;

        for (int i = 0; i < orderedTags.size() - 1; i++) {
            recordSequentialActivation(
                    orderedTags.get(i), orderedTags.get(i + 1),
                    timestamps.get(i), timestamps.get(i + 1));
        }
    }

    /**
     * Returns the STDP predictive strength from query tags to a result's tags.
     *
     * @param queryTags  tags from the query context
     * @param resultTags tags from a candidate result
     * @return maximum predictive strength (0.0 if no causal link exists)
     */
    public float getPredictiveStrength(List<String> queryTags, String[] resultTags) {
        if (queryTags == null || queryTags.isEmpty() || resultTags == null || resultTags.length == 0) {
            return 0.0f;
        }

        float maxStrength = 0.0f;
        for (String qTag : queryTags) {
            long srcHash = hashTag(qTag);
            for (String rTag : resultTags) {
                long tgtHash = hashTag(rTag);
                float weight = edgeTable.getWeight(srcHash, tgtHash);
                if (weight > maxStrength) maxStrength = weight;
            }
        }
        return maxStrength;
    }

    /**
     * Returns the average predictive strength (mean of all matching edges).
     *
     * @param queryTags  tags from the query context
     * @param resultTags tags from a candidate result
     * @return average predictive strength across all matching edges
     */
    public float getAveragePredictiveStrength(List<String> queryTags, String[] resultTags) {
        if (queryTags == null || queryTags.isEmpty() || resultTags == null || resultTags.length == 0) {
            return 0.0f;
        }

        float sum = 0.0f;
        int matchCount = 0;
        for (String qTag : queryTags) {
            long srcHash = hashTag(qTag);
            for (String rTag : resultTags) {
                long tgtHash = hashTag(rTag);
                float weight = edgeTable.getWeight(srcHash, tgtHash);
                if (weight > 0) {
                    sum += weight;
                    matchCount++;
                }
            }
        }
        return matchCount > 0 ? sum / matchCount : 0.0f;
    }

    /**
     * Returns the STDP edge weight for a specific directed edge.
     *
     * @param sourceTag the source tag
     * @param targetTag the target tag
     * @return the edge weight, or null if no edge exists
     */
    public EdgeWeight getEdge(String sourceTag, String targetTag) {
        long srcHash = hashTag(sourceTag);
        long tgtHash = hashTag(targetTag);
        return edgeTable.getEdge(srcHash, tgtHash);
    }

    // ══════════════════════════════════════════════════════════════
    // Counts / Reset / Close
    // ══════════════════════════════════════════════════════════════

    /** Returns the number of STDP directed edges. */
    public int edgeCount() { return edgeTable.count(); }

    /** Returns the number of tracked undirected tag pairs. */
    public int pairCount() { return pairTable.count(); }

    /** Resets all co-activation and STDP data. */
    public void reset() {
        pairTable.reset();
        edgeTable.reset();
        hashToTag.clear();
    }

    @Override
    public void close() {
        log.info("CoActivationTracker closing (pairs={}, edges={})", pairCount(), edgeCount());
        arena.close();
    }

    // ══════════════════════════════════════════════════════════════
    // Tag Hashing
    // ══════════════════════════════════════════════════════════════

    /**
     * FNV-1a 64-bit hash of a tag string.
     */
    static long hashTag(String tag) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < tag.length(); i++) {
            hash ^= tag.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash == 0 ? 1 : hash; // avoid 0 which means empty slot
    }

    private void registerTag(String tag, long hash) {
        hashToTag.putIfAbsent(hash, tag);
    }

    // ══════════════════════════════════════════════════════════════
    // PERSISTENCE: save / load
    // ══════════════════════════════════════════════════════════════

    /**
     * Saves the tracker state to a binary file.
     *
     * @param filePath path to write
     */
    public void save(Path filePath) {
        Path parent = filePath.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot create tracker directory: " + parent, e);
            }
        }

        try (FileChannel ch = FileChannel.open(filePath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            // Header
            ByteBuffer header = ByteBuffer.allocate(FILE_HEADER_BYTES);
            header.putInt(FILE_MAGIC);
            header.putInt(FILE_VERSION);
            header.putInt(pairTable.capacity());
            header.putInt(edgeTable.capacity());
            header.putInt(pairTable.count());
            header.putInt(edgeTable.count());
            header.flip();
            ch.write(header);

            // Delegate segment I/O to tables
            pairTable.writeTo(ch);
            edgeTable.writeTo(ch);

            // Tag name index
            writeTagIndex(ch);

            ch.force(true);
            log.info("CoActivationTracker saved: pairs={}, edges={}, tags={} → {}",
                    pairCount(), edgeCount(), hashToTag.size(), filePath);

        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save CoActivationTracker: " + filePath, e);
        }
    }

    /**
     * Loads a tracker from a binary file, or returns a new empty tracker.
     *
     * @param filePath       path to the tracker file
     * @param defaultPairs   default pair capacity if file doesn't exist
     * @param defaultEdges   default edge capacity if file doesn't exist
     * @return a CoActivationTracker (loaded or new)
     */
    public static CoActivationTracker load(Path filePath, int defaultPairs, int defaultEdges) {
        if (filePath == null || !Files.exists(filePath)) {
            log.info("CoActivationTracker file not found, creating fresh: {}", filePath);
            return new CoActivationTracker(defaultPairs, defaultEdges);
        }

        try (FileChannel ch = FileChannel.open(filePath, StandardOpenOption.READ)) {
            if (ch.size() < FILE_HEADER_BYTES) {
                log.warn("CoActivationTracker file too small, creating fresh");
                return new CoActivationTracker(defaultPairs, defaultEdges);
            }

            ByteBuffer header = ByteBuffer.allocate(FILE_HEADER_BYTES);
            ch.read(header);
            header.flip();

            int magic = header.getInt();
            int version = header.getInt();
            int pairCap = header.getInt();
            int edgeCap = header.getInt();
            int pairs = header.getInt();
            int edges = header.getInt();

            if (magic != FILE_MAGIC || version != FILE_VERSION) {
                log.warn("Invalid CoActivationTracker file, creating fresh");
                return new CoActivationTracker(defaultPairs, defaultEdges);
            }

            Arena arena = Arena.ofShared();

            // Delegate segment I/O to tables
            OffHeapPairTable pairTable = OffHeapPairTable.readFrom(ch, pairCap, pairs, arena);
            OffHeapEdgeTable edgeTable = OffHeapEdgeTable.readFrom(ch, edgeCap, edges, arena);

            // Tag name index
            ConcurrentHashMap<Long, String> names = readTagIndex(ch);

            CoActivationTracker tracker = new CoActivationTracker(arena, pairTable, edgeTable, names);
            log.info("CoActivationTracker loaded: pairs={}, edges={}, tags={} from {}",
                    pairs, edges, names.size(), filePath);
            return tracker;

        } catch (IOException e) {
            log.error("Failed to load CoActivationTracker, creating fresh: {}", e.getMessage());
            return new CoActivationTracker(defaultPairs, defaultEdges);
        }
    }

    // ── Tag Index I/O ──

    private void writeTagIndex(FileChannel ch) throws IOException {
        ByteBuffer countBuf = ByteBuffer.allocate(4);
        countBuf.putInt(hashToTag.size());
        countBuf.flip();
        ch.write(countBuf);

        for (Map.Entry<Long, String> entry : hashToTag.entrySet()) {
            byte[] nameBytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
            ByteBuffer entryBuf = ByteBuffer.allocate(8 + 4 + nameBytes.length);
            entryBuf.putLong(entry.getKey());
            entryBuf.putInt(nameBytes.length);
            entryBuf.put(nameBytes);
            entryBuf.flip();
            ch.write(entryBuf);
        }
    }

    private static ConcurrentHashMap<Long, String> readTagIndex(FileChannel ch) throws IOException {
        ConcurrentHashMap<Long, String> names = new ConcurrentHashMap<>();

        ByteBuffer countBuf = ByteBuffer.allocate(4);
        ch.read(countBuf);
        countBuf.flip();
        int nameCount = countBuf.getInt();

        for (int i = 0; i < nameCount; i++) {
            ByteBuffer hashBuf = ByteBuffer.allocate(8);
            ch.read(hashBuf);
            hashBuf.flip();
            long hash = hashBuf.getLong();

            ByteBuffer lenBuf = ByteBuffer.allocate(4);
            ch.read(lenBuf);
            lenBuf.flip();
            int len = lenBuf.getInt();

            ByteBuffer nameBuf = ByteBuffer.allocate(len);
            ch.read(nameBuf);
            nameBuf.flip();
            String name = new String(nameBuf.array(), 0, len, StandardCharsets.UTF_8);

            names.put(hash, name);
        }

        return names;
    }

    // ── Utility ──

    private static int nextPowerOf2(int n) {
        int p = 1;
        while (p < n) p <<= 1;
        return p;
    }
}
