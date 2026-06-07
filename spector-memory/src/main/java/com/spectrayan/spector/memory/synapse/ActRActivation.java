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

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Full ACT-R base-level activation using a compact recall-timestamp ring buffer.
 *
 * <h3>ACT-R Base-Level Activation</h3>
 * <p>Anderson's (1993) ACT-R architecture computes base-level activation as:</p>
 * <pre>
 *   B_i = ln(Σ_{j=1}^{n} t_j^{-d})
 * </pre>
 * <p>Where t_j are the times since each past recall. This captures both
 * <b>recency</b> and <b>frequency</b>, and models the <b>spacing effect</b>:
 * recalls spaced over time produce stronger activation than massed practice.</p>
 *
 * <h3>Storage: 3-Slot Ring Buffer in Reserved Fields</h3>
 * <p>Instead of storing every recall timestamp (variable-length), we store the
 * <b>last 3</b> as 32-bit relative offsets from the memory's creation time.
 * This gives ~136 years of range with second-level granularity, fitting in the
 * 12 bytes of reserved space in the 64-byte header:</p>
 *
 * <pre>
 *   Offset 44 (reserved_f1): recall_ts[0] as int (seconds since creation)
 *   Offset 56 (reserved_l1): recall_ts[1] as int (lower 32 bits)
 *   Offset 60 (reserved_l1): recall_ts[2] as int (upper 32 bits)
 * </pre>
 *
 * <p>A slot value of 0 means "no recall recorded at this slot."
 * The ring buffer overwrites the oldest slot when full.</p>
 *
 * <h3>Fallback</h3>
 * <p>When the ring buffer is empty,
 * falls back to the simplified reconsolidation model (bucket bit-shift based
 * on agentRecallCount).</p>
 *
 * <h3>Performance</h3>
 * <p>Zero {@code Math.pow}, zero {@code Math.log}, zero {@code Math.exp} at query time.
 * Each recall timestamp is mapped to a decay bucket via
 * {@link DecayStrategy#ageToBucket} → array lookup (~7 cycles per slot).
 * The sigmoid normalization uses the algebraic identity
 * {@code σ(ln(x)) = x / (x + 1)} — a single float division.
 * Total: ~30 CPU cycles for 4 recall slots.</p>
 *
 * @see DecayStrategy
 * @see DecayConfig
 * @see HeaderLayout64
 */
public final class ActRActivation {

    private ActRActivation() {}

    /** Number of recall timestamp slots in the ring buffer. */
    public static final int RING_BUFFER_SLOTS = 3;

    // ── Reserved field offsets repurposed as recall timestamp ring buffer ──
    private static final long OFFSET_RECALL_TS_0 = SynapticHeaderConstants.OFFSET_RESERVED_F1;  // 44
    private static final long OFFSET_RECALL_TS_1 = SynapticHeaderConstants.OFFSET_RESERVED_L1;  // 56
    private static final long OFFSET_RECALL_TS_2 = SynapticHeaderConstants.OFFSET_RESERVED_L1 + 4; // 60

    private static final long[] OFFSETS = {
            OFFSET_RECALL_TS_0, OFFSET_RECALL_TS_1, OFFSET_RECALL_TS_2
    };

    /**
     * Records a recall timestamp in the ring buffer.
     *
     * <p>Finds the oldest (smallest) slot and overwrites it with the new timestamp.
     * If any slot is 0 (empty), fills that first.</p>
     *
     * @param seg          off-heap memory segment
     * @param recordOffset byte offset of the record header
     * @param creationMs   memory creation timestamp (epoch millis)
     * @param recallMs     current recall timestamp (epoch millis)
     */
    public static void recordRecall(MemorySegment seg, long recordOffset,
                                    long creationMs, long recallMs) {
        int relativeSeconds = (int) ((recallMs - creationMs) / 1000L);
        if (relativeSeconds <= 0) relativeSeconds = 1; // guard against same-millisecond or clock skew

        // Find the first empty slot, or the oldest slot
        int oldestSlot = 0;
        int oldestValue = Integer.MAX_VALUE;
        for (int i = 0; i < RING_BUFFER_SLOTS; i++) {
            int ts = seg.get(ValueLayout.JAVA_INT, recordOffset + OFFSETS[i]);
            if (ts == 0) {
                // Empty slot — use it
                seg.set(ValueLayout.JAVA_INT, recordOffset + OFFSETS[i], relativeSeconds);
                return;
            }
            if (ts < oldestValue) {
                oldestValue = ts;
                oldestSlot = i;
            }
        }
        // All slots full — overwrite oldest
        seg.set(ValueLayout.JAVA_INT, recordOffset + OFFSETS[oldestSlot], relativeSeconds);
    }

    /**
     * Reads all recall timestamps from the ring buffer.
     *
     * @param seg          off-heap memory segment
     * @param recordOffset byte offset of the record header
     * @return array of 4 relative-second values (0 = empty slot)
     */
    public static int[] readRecallTimestamps(MemorySegment seg, long recordOffset) {
        int[] result = new int[RING_BUFFER_SLOTS];
        for (int i = 0; i < RING_BUFFER_SLOTS; i++) {
            result[i] = seg.get(ValueLayout.JAVA_INT, recordOffset + OFFSETS[i]);
        }
        return result;
    }

    /**
     * Computes the full ACT-R base-level activation using bucket lookups.
     *
     * <h4>The Math</h4>
     * <pre>
     *   B_i = ln(Σ_{j=1}^{n} t_j^{-d})
     *   σ(B_i) = 1 / (1 + e^{-ln(sum)}) = sum / (sum + 1)   ← algebraic identity!
     * </pre>
     *
     * <h4>Why No Math.pow / Math.log / Math.exp?</h4>
     * <p>Each {@code t_j^{-d}} is approximated by looking up the precomputed decay
     * bucket value from {@link DecayStrategy#DECAY_BUCKETS}. These values were
     * derived from {@code Math.pow(t, -d)} at class-load time. At query time,
     * we reuse them via {@link DecayStrategy#ageToBucket} → array lookup.</p>
     *
     * <p>The sigmoid normalization {@code σ(ln(x)) = x / (x + 1)} is an algebraic
     * identity that eliminates both {@code Math.log} and {@code Math.exp}.</p>
     *
     * <h4>Cost</h4>
     * <p>~30 CPU cycles total: 4 bucket lookups + 4 adds + 1 division.
     * Compare to the previous scalar path: 4× Math.pow + Math.log + Math.exp = ~60 cycles.</p>
     *
     * @param seg           off-heap memory segment
     * @param recordOffset  byte offset of the record header
     * @param creationMs    memory creation timestamp (epoch millis)
     * @param nowMs         current time (epoch millis)
     * @param decayExponent unused (kept for API compatibility; bucket values come from DecayConfig)
     * @return normalized base-level activation in [0, 1], or -1 if no recall data
     */
    public static float computeBaseLevelActivation(MemorySegment seg, long recordOffset,
                                                    long creationMs, long nowMs,
                                                    float decayExponent) {
        // Sum decay(bucket(t_j)) over all non-empty recall slots
        float sum = 0.0f;
        int validSlots = 0;
        for (int i = 0; i < RING_BUFFER_SLOTS; i++) {
            int relativeSeconds = seg.get(ValueLayout.JAVA_INT, recordOffset + OFFSETS[i]);
            if (relativeSeconds == 0) continue;

            // t_j = time since this recall (millis) = (total_age_ms) - (recall_offset_ms)
            long recallAgeMs = (nowMs - creationMs) - (relativeSeconds * 1000L);
            if (recallAgeMs <= 0) recallAgeMs = 1000L; // guard: at least 1 second

            // Reuse the decay bucket lookup: DECAY_BUCKETS[bucket] ≈ t^{-d}
            long recallTimestampMs = nowMs - recallAgeMs;
            int bucket = DecayStrategy.ageToBucket(recallTimestampMs, nowMs);
            sum += DecayStrategy.decay(bucket);
            validSlots++;
        }

        if (validSlots == 0) {
            return -1.0f; // no recall data — caller should use simplified fallback
        }

        // Include the initial encoding as a "recall" at t = full age
        int encodingBucket = DecayStrategy.ageToBucket(creationMs, nowMs);
        sum += DecayStrategy.decay(encodingBucket);

        // σ(ln(sum)) = sum / (sum + 1) — algebraic identity, no Math.log or Math.exp!
        return sum / (sum + 1.0f);
    }


    /**
     * Computes the decay multiplier using the full ACT-R model when recall
     * timestamps are available, otherwise falls back to bucket-based decay.
     *
     * <p>This method is designed to be a drop-in replacement for
     * {@link DecayStrategy#computeDecay(long, long, int)} in contexts where
     * the full ACT-R model is desired.</p>
     *
     * @param seg           off-heap memory segment (or null for fallback)
     * @param recordOffset  byte offset of the record header
     * @param creationMs    memory creation timestamp (epoch millis)
     * @param nowMs         current time (epoch millis)
     * @param agentRecallCount   simplified recall count (for fallback)
     * @param decayExponent power-law decay exponent
     * @return decay multiplier in [0, 1]
     */
    public static float computeDecayWithActR(MemorySegment seg, long recordOffset,
                                              long creationMs, long nowMs,
                                              int agentRecallCount, float decayExponent) {
        if (seg != null) {
            float actr = computeBaseLevelActivation(seg, recordOffset, creationMs, nowMs, decayExponent);
            if (actr >= 0) {
                return actr; // Full ACT-R result
            }
        }
        // Fallback: simplified bucket-based decay
        return DecayStrategy.computeDecay(creationMs, nowMs, agentRecallCount);
    }
}
