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

import com.spectrayan.spector.memory.synapse.SynapticTagEncoder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
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
 * <h3>Off-Heap Architecture</h3>
 * <ul>
 *   <li><b>Co-activations</b>: off-heap open-addressing hash table (32B per slot)</li>
 *   <li><b>STDP edges</b>: off-heap open-addressing hash table (40B per slot)</li>
 *   <li><b>Tag names</b>: on-heap {@link ConcurrentHashMap} for hash↔name resolution</li>
 *   <li>Persistence via {@link #save(Path)} / {@link #load(Path, int, int)}</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>Write-side methods use {@code synchronized} blocks. Read-side methods
 * are lock-free and may see slightly stale data — acceptable for soft-scoring.</p>
 *
 * @see HebbianCoActivationListener
 */
public final class CoActivationTracker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CoActivationTracker.class);

    // ══════════════════════════════════════════════════════════════
    // STDP Constants
    // ══════════════════════════════════════════════════════════════

    /** A+ (LTP amplitude): maximum weight increase for causal pairings. */
    private static final float A_PLUS = 0.1f;

    /** A- (LTD amplitude): maximum weight decrease for anti-causal pairings. */
    private static final float A_MINUS = 0.05f;

    /** τ+ (LTP time constant): causal window in milliseconds. */
    private static final float TAU_PLUS = 30_000f;  // 30 seconds

    /** τ- (LTD time constant): anti-causal window in milliseconds. */
    private static final float TAU_MINUS = 30_000f;  // 30 seconds

    /** Minimum weight (prevent complete erasure). */
    private static final float MIN_WEIGHT = 0.0f;

    /** Maximum weight (prevent runaway potentiation). */
    private static final float MAX_WEIGHT = 1.0f;

    // ══════════════════════════════════════════════════════════════
    // Off-Heap Layout: Co-Activation Pair Table
    // ══════════════════════════════════════════════════════════════

    /**
     * Slot layout (32 bytes, 8-byte aligned):
     * <pre>
     *   [hashA:8B][hashB:8B][count:4B][flags:4B][pad:8B]
     * </pre>
     */
    private static final int PAIR_SLOT_BYTES = 32;
    private static final long PAIR_OFF_HASH_A = 0;
    private static final long PAIR_OFF_HASH_B = 8;
    private static final long PAIR_OFF_COUNT = 16;
    private static final long PAIR_OFF_FLAGS = 20;
    // pad: 8B to reach 32B total

    // ══════════════════════════════════════════════════════════════
    // Off-Heap Layout: STDP Directed Edge Table
    // ══════════════════════════════════════════════════════════════

    /**
     * Slot layout (40 bytes, 8-byte aligned):
     * <pre>
     *   [srcHash:8B][tgtHash:8B][weight:4B][pad:4B][lastActivatedMs:8B][activationCount:4B][flags:4B]
     * </pre>
     */
    private static final int EDGE_SLOT_BYTES = 40;
    private static final long EDGE_OFF_SRC = 0;
    private static final long EDGE_OFF_TGT = 8;
    private static final long EDGE_OFF_WEIGHT = 16;
    // pad: 4B at offset 20 for alignment
    private static final long EDGE_OFF_LAST_MS = 24;
    private static final long EDGE_OFF_ACT_COUNT = 32;
    private static final long EDGE_OFF_FLAGS = 36;

    /** Flag: slot is occupied. */
    private static final int FLAG_OCCUPIED = 1;

    // ══════════════════════════════════════════════════════════════
    // Persistence
    // ══════════════════════════════════════════════════════════════

    /** File magic: "COAX" in ASCII. */
    private static final int FILE_MAGIC = 0x434F4158;
    private static final int FILE_VERSION = 1;
    /** Header: magic(4) + version(4) + pairCap(4) + edgeCap(4) + pairCount(4) + edgeCount(4) = 24B. */
    private static final int FILE_HEADER_BYTES = 24;

    // ══════════════════════════════════════════════════════════════
    // State
    // ══════════════════════════════════════════════════════════════

    private final Arena arena;
    private final MemorySegment pairSegment;
    private final MemorySegment edgeSegment;
    private final int pairCapacity;   // number of hash table slots (power of 2)
    private final int edgeCapacity;
    private volatile int pairCount;
    private volatile int edgeCount;

    /** On-heap name↔hash resolution (small — only unique tag strings). */
    private final ConcurrentHashMap<Long, String> hashToTag = new ConcurrentHashMap<>();

    // ══════════════════════════════════════════════════════════════
    // STDP Records (kept for API compatibility)
    // ══════════════════════════════════════════════════════════════

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
        this.pairCapacity = nextPowerOf2(Math.max(64, maxPairs * 2)); // ~50% load factor
        this.edgeCapacity = nextPowerOf2(Math.max(64, maxEdges * 2));
        this.arena = Arena.ofShared();
        this.pairSegment = arena.allocate((long) PAIR_SLOT_BYTES * pairCapacity);
        this.edgeSegment = arena.allocate((long) EDGE_SLOT_BYTES * edgeCapacity);
        pairSegment.fill((byte) 0);
        edgeSegment.fill((byte) 0);
        this.pairCount = 0;
        this.edgeCount = 0;

        log.info("CoActivationTracker initialized (off-heap): pairSlots={}, edgeSlots={}, memory={}KB",
                pairCapacity, edgeCapacity,
                ((long) PAIR_SLOT_BYTES * pairCapacity + (long) EDGE_SLOT_BYTES * edgeCapacity) / 1024);
    }

    /** Private constructor for loading from pre-existing segments. */
    private CoActivationTracker(int pairCapacity, int edgeCapacity, int pairCount, int edgeCount,
                                 Arena arena, MemorySegment pairSegment, MemorySegment edgeSegment,
                                 ConcurrentHashMap<Long, String> hashToTag) {
        this.pairCapacity = pairCapacity;
        this.edgeCapacity = edgeCapacity;
        this.pairCount = pairCount;
        this.edgeCount = edgeCount;
        this.arena = arena;
        this.pairSegment = pairSegment;
        this.edgeSegment = edgeSegment;
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

                synchronized (this) {
                    incrementPair(keyA, keyB);
                }
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

        int slot = findPairSlot(keyA, keyB);
        if (slot < 0) return 0;

        long offset = (long) slot * PAIR_SLOT_BYTES;
        return pairSegment.get(ValueLayout.JAVA_INT, offset + PAIR_OFF_COUNT);
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

        // Scan all pair slots for matches containing this tag's hash
        record TagCount(String name, int count) {}
        List<TagCount> matches = new ArrayList<>();

        for (int i = 0; i < pairCapacity; i++) {
            long offset = (long) i * PAIR_SLOT_BYTES;
            int flags = pairSegment.get(ValueLayout.JAVA_INT, offset + PAIR_OFF_FLAGS);
            if ((flags & FLAG_OCCUPIED) == 0) continue;

            long hA = pairSegment.get(ValueLayout.JAVA_LONG, offset + PAIR_OFF_HASH_A);
            long hB = pairSegment.get(ValueLayout.JAVA_LONG, offset + PAIR_OFF_HASH_B);

            if (hA == tagHash || hB == tagHash) {
                long otherHash = (hA == tagHash) ? hB : hA;
                String otherName = hashToTag.get(otherHash);
                if (otherName != null) {
                    int count = pairSegment.get(ValueLayout.JAVA_INT, offset + PAIR_OFF_COUNT);
                    matches.add(new TagCount(otherName, count));
                }
            }
        }

        return matches.stream()
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
        synchronized (this) {
            updateEdge(hashBefore, hashAfter, dW_causal, timeAfter);
        }

        // Anti-causal: B→A (weaken)
        float dW_anti = -A_MINUS * (float) Math.exp(-dt / TAU_MINUS);
        synchronized (this) {
            updateEdge(hashAfter, hashBefore, dW_anti, timeAfter);
        }

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
                int slot = findEdgeSlot(srcHash, tgtHash);
                if (slot >= 0) {
                    long offset = (long) slot * EDGE_SLOT_BYTES;
                    float weight = edgeSegment.get(ValueLayout.JAVA_FLOAT, offset + EDGE_OFF_WEIGHT);
                    if (weight > maxStrength) maxStrength = weight;
                }
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
        int count = 0;
        for (String qTag : queryTags) {
            long srcHash = hashTag(qTag);
            for (String rTag : resultTags) {
                long tgtHash = hashTag(rTag);
                int slot = findEdgeSlot(srcHash, tgtHash);
                if (slot >= 0) {
                    long offset = (long) slot * EDGE_SLOT_BYTES;
                    float weight = edgeSegment.get(ValueLayout.JAVA_FLOAT, offset + EDGE_OFF_WEIGHT);
                    if (weight > 0) {
                        sum += weight;
                        count++;
                    }
                }
            }
        }
        return count > 0 ? sum / count : 0.0f;
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
        int slot = findEdgeSlot(srcHash, tgtHash);
        if (slot < 0) return null;

        long offset = (long) slot * EDGE_SLOT_BYTES;
        float weight = edgeSegment.get(ValueLayout.JAVA_FLOAT, offset + EDGE_OFF_WEIGHT);
        long lastMs = edgeSegment.get(ValueLayout.JAVA_LONG, offset + EDGE_OFF_LAST_MS);
        int actCount = edgeSegment.get(ValueLayout.JAVA_INT, offset + EDGE_OFF_ACT_COUNT);
        return new EdgeWeight(weight, lastMs, actCount);
    }

    /**
     * Returns the number of STDP directed edges.
     */
    public int edgeCount() {
        return edgeCount;
    }

    /**
     * Returns the number of tracked undirected tag pairs.
     */
    public int pairCount() {
        return pairCount;
    }

    /**
     * Resets all co-activation and STDP data.
     */
    public synchronized void reset() {
        pairSegment.fill((byte) 0);
        edgeSegment.fill((byte) 0);
        hashToTag.clear();
        pairCount = 0;
        edgeCount = 0;
    }

    @Override
    public void close() {
        log.info("CoActivationTracker closing (pairs={}, edges={})", pairCount, edgeCount);
        arena.close();
    }

    // ══════════════════════════════════════════════════════════════
    // Hash Table Internals
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

    /**
     * Finds a co-activation pair slot by hash keys.
     *
     * @return slot index if found, or negative value if not found
     */
    private int findPairSlot(long hashA, long hashB) {
        int mask = pairCapacity - 1;
        int idx = (int) ((hashA * 0x9E3779B97F4A7C15L + hashB) & mask);

        for (int probe = 0; probe < pairCapacity; probe++) {
            int slot = (idx + probe) & mask;
            long offset = (long) slot * PAIR_SLOT_BYTES;
            int flags = pairSegment.get(ValueLayout.JAVA_INT, offset + PAIR_OFF_FLAGS);

            if ((flags & FLAG_OCCUPIED) == 0) return ~slot; // empty = not found, return insertion point
            long a = pairSegment.get(ValueLayout.JAVA_LONG, offset + PAIR_OFF_HASH_A);
            long b = pairSegment.get(ValueLayout.JAVA_LONG, offset + PAIR_OFF_HASH_B);
            if (a == hashA && b == hashB) return slot; // found
        }
        return -1; // table full
    }

    /**
     * Increments (or inserts) a co-activation pair. MUST be called under synchronized.
     */
    private void incrementPair(long hashA, long hashB) {
        int slot = findPairSlot(hashA, hashB);

        if (slot >= 0) {
            // Exists — increment count
            long offset = (long) slot * PAIR_SLOT_BYTES;
            int count = pairSegment.get(ValueLayout.JAVA_INT, offset + PAIR_OFF_COUNT);
            pairSegment.set(ValueLayout.JAVA_INT, offset + PAIR_OFF_COUNT, count + 1);
        } else {
            // Not found — insert at insertion point
            int insertSlot = ~slot;
            if (insertSlot < 0 || pairCount >= pairCapacity / 2) {
                // Too full — prune weakest 10%
                pruneWeakestPairs();
                // Retry
                slot = findPairSlot(hashA, hashB);
                insertSlot = slot >= 0 ? slot : ~slot;
                if (insertSlot < 0) return; // still full, give up
            }

            long offset = (long) insertSlot * PAIR_SLOT_BYTES;
            pairSegment.set(ValueLayout.JAVA_LONG, offset + PAIR_OFF_HASH_A, hashA);
            pairSegment.set(ValueLayout.JAVA_LONG, offset + PAIR_OFF_HASH_B, hashB);
            pairSegment.set(ValueLayout.JAVA_INT, offset + PAIR_OFF_COUNT, 1);
            pairSegment.set(ValueLayout.JAVA_INT, offset + PAIR_OFF_FLAGS, FLAG_OCCUPIED);
            pairCount++;
        }
    }

    /**
     * Finds a STDP edge slot by source and target hashes.
     */
    private int findEdgeSlot(long srcHash, long tgtHash) {
        int mask = edgeCapacity - 1;
        int idx = (int) ((srcHash * 0x517CC1B727220A95L + tgtHash) & mask);

        for (int probe = 0; probe < edgeCapacity; probe++) {
            int slot = (idx + probe) & mask;
            long offset = (long) slot * EDGE_SLOT_BYTES;
            int flags = edgeSegment.get(ValueLayout.JAVA_INT, offset + EDGE_OFF_FLAGS);

            if ((flags & FLAG_OCCUPIED) == 0) return ~slot;
            long s = edgeSegment.get(ValueLayout.JAVA_LONG, offset + EDGE_OFF_SRC);
            long t = edgeSegment.get(ValueLayout.JAVA_LONG, offset + EDGE_OFF_TGT);
            if (s == srcHash && t == tgtHash) return slot;
        }
        return -1;
    }

    /**
     * Updates or inserts a STDP edge. MUST be called under synchronized.
     */
    private void updateEdge(long srcHash, long tgtHash, float deltaWeight, long nowMs) {
        int slot = findEdgeSlot(srcHash, tgtHash);

        if (slot >= 0) {
            // Exists — update
            long offset = (long) slot * EDGE_SLOT_BYTES;
            float weight = edgeSegment.get(ValueLayout.JAVA_FLOAT, offset + EDGE_OFF_WEIGHT);
            float newWeight = Math.clamp(weight + deltaWeight, MIN_WEIGHT, MAX_WEIGHT);
            int actCount = edgeSegment.get(ValueLayout.JAVA_INT, offset + EDGE_OFF_ACT_COUNT);

            edgeSegment.set(ValueLayout.JAVA_FLOAT, offset + EDGE_OFF_WEIGHT, newWeight);
            edgeSegment.set(ValueLayout.JAVA_LONG, offset + EDGE_OFF_LAST_MS, nowMs);
            edgeSegment.set(ValueLayout.JAVA_INT, offset + EDGE_OFF_ACT_COUNT, actCount + 1);
        } else {
            // Insert
            int insertSlot = ~slot;
            if (insertSlot < 0 || edgeCount >= edgeCapacity / 2) {
                pruneWeakestEdges();
                slot = findEdgeSlot(srcHash, tgtHash);
                insertSlot = slot >= 0 ? slot : ~slot;
                if (insertSlot < 0) return;
            }

            long offset = (long) insertSlot * EDGE_SLOT_BYTES;
            float initialWeight = Math.max(MIN_WEIGHT, deltaWeight);
            edgeSegment.set(ValueLayout.JAVA_LONG, offset + EDGE_OFF_SRC, srcHash);
            edgeSegment.set(ValueLayout.JAVA_LONG, offset + EDGE_OFF_TGT, tgtHash);
            edgeSegment.set(ValueLayout.JAVA_FLOAT, offset + EDGE_OFF_WEIGHT, initialWeight);
            edgeSegment.set(ValueLayout.JAVA_LONG, offset + EDGE_OFF_LAST_MS, nowMs);
            edgeSegment.set(ValueLayout.JAVA_INT, offset + EDGE_OFF_ACT_COUNT, 1);
            edgeSegment.set(ValueLayout.JAVA_INT, offset + EDGE_OFF_FLAGS, FLAG_OCCUPIED);
            edgeCount++;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Pruning
    // ══════════════════════════════════════════════════════════════

    /**
     * Prunes the weakest 10% of co-activation pairs (by count).
     */
    private void pruneWeakestPairs() {
        if (pairCount == 0) return;
        int toPrune = Math.max(1, pairCount / 10);

        // Find the threshold count: collect all counts, sort, take toPrune-th value
        int[] counts = new int[pairCount];
        int idx = 0;
        for (int i = 0; i < pairCapacity && idx < pairCount; i++) {
            long offset = (long) i * PAIR_SLOT_BYTES;
            int flags = pairSegment.get(ValueLayout.JAVA_INT, offset + PAIR_OFF_FLAGS);
            if ((flags & FLAG_OCCUPIED) != 0) {
                counts[idx++] = pairSegment.get(ValueLayout.JAVA_INT, offset + PAIR_OFF_COUNT);
            }
        }
        java.util.Arrays.sort(counts, 0, idx);
        int threshold = idx > toPrune ? counts[toPrune] : counts[0];

        // Remove entries at or below threshold
        int removed = 0;
        for (int i = 0; i < pairCapacity && removed < toPrune; i++) {
            long offset = (long) i * PAIR_SLOT_BYTES;
            int flags = pairSegment.get(ValueLayout.JAVA_INT, offset + PAIR_OFF_FLAGS);
            if ((flags & FLAG_OCCUPIED) != 0) {
                int count = pairSegment.get(ValueLayout.JAVA_INT, offset + PAIR_OFF_COUNT);
                if (count <= threshold) {
                    pairSegment.set(ValueLayout.JAVA_INT, offset + PAIR_OFF_FLAGS, 0);
                    pairSegment.set(ValueLayout.JAVA_LONG, offset + PAIR_OFF_HASH_A, 0L);
                    pairSegment.set(ValueLayout.JAVA_LONG, offset + PAIR_OFF_HASH_B, 0L);
                    pairSegment.set(ValueLayout.JAVA_INT, offset + PAIR_OFF_COUNT, 0);
                    removed++;
                    pairCount--;
                }
            }
        }

        log.debug("Pruned {} weak co-activation pairs (remaining={})", removed, pairCount);
    }

    /**
     * Prunes the weakest 10% of STDP directed edges (by weight).
     */
    private void pruneWeakestEdges() {
        if (edgeCount == 0) return;
        int toPrune = Math.max(1, edgeCount / 10);

        float[] weights = new float[edgeCount];
        int idx = 0;
        for (int i = 0; i < edgeCapacity && idx < edgeCount; i++) {
            long offset = (long) i * EDGE_SLOT_BYTES;
            int flags = edgeSegment.get(ValueLayout.JAVA_INT, offset + EDGE_OFF_FLAGS);
            if ((flags & FLAG_OCCUPIED) != 0) {
                weights[idx++] = edgeSegment.get(ValueLayout.JAVA_FLOAT, offset + EDGE_OFF_WEIGHT);
            }
        }
        java.util.Arrays.sort(weights, 0, idx);
        float threshold = idx > toPrune ? weights[toPrune] : weights[0];

        int removed = 0;
        for (int i = 0; i < edgeCapacity && removed < toPrune; i++) {
            long offset = (long) i * EDGE_SLOT_BYTES;
            int flags = edgeSegment.get(ValueLayout.JAVA_INT, offset + EDGE_OFF_FLAGS);
            if ((flags & FLAG_OCCUPIED) != 0) {
                float weight = edgeSegment.get(ValueLayout.JAVA_FLOAT, offset + EDGE_OFF_WEIGHT);
                if (weight <= threshold) {
                    // Zero-out the slot
                    for (int b = 0; b < EDGE_SLOT_BYTES; b += 4) {
                        edgeSegment.set(ValueLayout.JAVA_INT, offset + b, 0);
                    }
                    removed++;
                    edgeCount--;
                }
            }
        }

        log.debug("Pruned {} weak STDP edges (remaining={})", removed, edgeCount);
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
            header.putInt(pairCapacity);
            header.putInt(edgeCapacity);
            header.putInt(pairCount);
            header.putInt(edgeCount);
            header.flip();
            ch.write(header);

            // Pair segment
            writeSegment(ch, pairSegment, (long) PAIR_SLOT_BYTES * pairCapacity);

            // Edge segment
            writeSegment(ch, edgeSegment, (long) EDGE_SLOT_BYTES * edgeCapacity);

            // Tag name index (on-heap → serialized)
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

            ch.force(true);
            log.info("CoActivationTracker saved: pairs={}, edges={}, tags={} → {}",
                    pairCount, edgeCount, hashToTag.size(), filePath);

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

            // Read pair segment
            long pairBytes = (long) PAIR_SLOT_BYTES * pairCap;
            MemorySegment pairSeg = arena.allocate(pairBytes);
            readSegment(ch, pairSeg, pairBytes);

            // Read edge segment
            long edgeBytes = (long) EDGE_SLOT_BYTES * edgeCap;
            MemorySegment edgeSeg = arena.allocate(edgeBytes);
            readSegment(ch, edgeSeg, edgeBytes);

            // Read tag name index
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

            CoActivationTracker tracker = new CoActivationTracker(
                    pairCap, edgeCap, pairs, edges, arena, pairSeg, edgeSeg, names);
            log.info("CoActivationTracker loaded: pairs={}, edges={}, tags={} from {}",
                    pairs, edges, names.size(), filePath);
            return tracker;

        } catch (IOException e) {
            log.error("Failed to load CoActivationTracker, creating fresh: {}", e.getMessage());
            return new CoActivationTracker(defaultPairs, defaultEdges);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Utility
    // ══════════════════════════════════════════════════════════════

    private static int nextPowerOf2(int n) {
        int p = 1;
        while (p < n) p <<= 1;
        return p;
    }

    private static void writeSegment(FileChannel ch, MemorySegment seg, long totalBytes)
            throws IOException {
        long written = 0;
        int chunkSize = 64 * 1024;
        while (written < totalBytes) {
            int toWrite = (int) Math.min(chunkSize, totalBytes - written);
            ByteBuffer buf = seg.asSlice(written, toWrite).asByteBuffer().asReadOnlyBuffer();
            ch.write(buf);
            written += toWrite;
        }
    }

    private static void readSegment(FileChannel ch, MemorySegment seg, long totalBytes)
            throws IOException {
        long read = 0;
        int chunkSize = 64 * 1024;
        while (read < totalBytes) {
            int toRead = (int) Math.min(chunkSize, totalBytes - read);
            ByteBuffer buf = ByteBuffer.allocate(toRead);
            ch.read(buf);
            buf.flip();
            MemorySegment.copy(MemorySegment.ofBuffer(buf), 0, seg, read, toRead);
            read += toRead;
        }
    }
}
