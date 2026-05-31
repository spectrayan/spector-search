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

/**
 * SIMD-friendly bucket-based temporal decay with reconsolidation and arousal modulation.
 *
 * <h3>Why Not {@code Math.exp()}?</h3>
 * <p>The naive exponential decay formula {@code e^(-λ·Age)} requires ~150 CPU cycles
 * per vector (scalar-only, no Java Vector API lane operation for exp). At 1M memories,
 * this adds 50–100ms of pure scalar overhead, destroying the SIMD advantage.</p>
 *
 * <h3>Solution: Precomputed Bucket Lookup</h3>
 * <p>Quantize time into 8 discrete buckets and precompute decay multipliers.
 * This turns the exponential into a single float multiply (~7 cycles).</p>
 *
 * <h3>Reconsolidation (LTP)</h3>
 * <p>The {@link #adjustForReconsolidation} method shifts the bucket index down
 * based on recall count, making frequently-recalled memories behave as if they
 * are younger — the biological equivalent of Long-Term Potentiation.</p>
 *
 * <h3>Arousal Modulation (Amygdala)</h3>
 * <p>Emotionally intense memories resist forgetting. The {@link #arousalModifier}
 * method uses a precomputed 4-entry lookup (from unsigned arousal byte) to slow
 * decay for high-arousal memories — up to 65% slower at extreme arousal.</p>
 */
public final class DecayStrategy {

    private DecayStrategy() {}

    // ── Bucket boundaries (milliseconds) ──
    private static final long HOUR_MS  = 3_600_000L;
    private static final long DAY_MS   = 86_400_000L;
    private static final long WEEK_MS  = 604_800_000L;
    private static final long MONTH_MS = 2_592_000_000L; // ~30 days

    /**
     * Precomputed decay multipliers for each time bucket.
     * Index 0 = freshest (1.0), index 7 = oldest (0.05).
     */
    public static final float[] DECAY_BUCKETS = {
            1.00f,  // Bucket 0: 0–1 hours ago
            0.95f,  // Bucket 1: 1–6 hours ago
            0.85f,  // Bucket 2: 6–24 hours ago
            0.70f,  // Bucket 3: 1–3 days ago
            0.50f,  // Bucket 4: 3–7 days ago
            0.30f,  // Bucket 5: 1–4 weeks ago
            0.15f,  // Bucket 6: 1–3 months ago
            0.05f   // Bucket 7: 3+ months ago
    };

    /** Maximum bucket index. */
    public static final int MAX_BUCKET = DECAY_BUCKETS.length - 1;

    /**
     * Maps a timestamp to a decay bucket index (0–7).
     *
     * @param timestampMs memory creation time (epoch millis)
     * @param nowMs       current time (epoch millis)
     * @return bucket index (0 = freshest, 7 = oldest)
     */
    public static int ageToBucket(long timestampMs, long nowMs) {
        long ageMs = nowMs - timestampMs;
        if (ageMs < 0) return 0; // future timestamp (clock skew) → treat as fresh

        if (ageMs < HOUR_MS)          return 0;  // < 1 hour
        if (ageMs < 6 * HOUR_MS)      return 1;  // 1–6 hours
        if (ageMs < DAY_MS)           return 2;  // 6–24 hours
        if (ageMs < 3 * DAY_MS)       return 3;  // 1–3 days
        if (ageMs < WEEK_MS)          return 4;  // 3–7 days
        if (ageMs < 4 * WEEK_MS)      return 5;  // 1–4 weeks
        if (ageMs < 3 * MONTH_MS)     return 6;  // 1–3 months
        return MAX_BUCKET;                         // 3+ months
    }

    /**
     * Adjusts the raw decay bucket for reconsolidation (Long-Term Potentiation)
     * using exponential half-life doubling via bit-shift.
     *
     * <p>Each recall effectively halves the memory's perceived age by shifting
     * the bucket index right. This mirrors biological spaced repetition where
     * each successful retrieval doubles the memory's half-life.</p>
     *
     * <table>
     *   <tr><th>Recall Count</th><th>Shift</th><th>Effect</th></tr>
     *   <tr><td>0</td><td>0</td><td>No change</td></tr>
     *   <tr><td>1</td><td>÷2</td><td>bucket 6 → 3</td></tr>
     *   <tr><td>2</td><td>÷4</td><td>bucket 6 → 1</td></tr>
     *   <tr><td>3</td><td>÷8</td><td>bucket 7 → 0</td></tr>
     *   <tr><td>5+</td><td>÷32</td><td>effectively fresh</td></tr>
     * </table>
     *
     * @param rawBucket   original bucket from {@link #ageToBucket}
     * @param recallCount number of times this memory has been recalled
     * @return adjusted bucket index (clamped to 0)
     */
    public static int adjustForReconsolidation(int rawBucket, int recallCount) {
        int shift = Math.min(recallCount, 5);
        return rawBucket >> shift;
    }

    /**
     * Returns the decay multiplier for the given (possibly adjusted) bucket.
     *
     * @param bucket bucket index (0–7)
     * @return decay multiplier (1.0 = no decay, 0.05 = heavy decay)
     */
    public static float decay(int bucket) {
        return DECAY_BUCKETS[Math.min(bucket, MAX_BUCKET)];
    }

    /**
     * Convenience: computes the full decay multiplier for a memory, including
     * reconsolidation adjustment.
     *
     * @param timestampMs memory creation time
     * @param nowMs       current time
     * @param recallCount number of recalls
     * @return decay multiplier
     */
    public static float computeDecay(long timestampMs, long nowMs, int recallCount) {
        int rawBucket = ageToBucket(timestampMs, nowMs);
        int adjusted = adjustForReconsolidation(rawBucket, recallCount);
        return decay(adjusted);
    }

    // ══════════════════════════════════════════════════════════════
    // AROUSAL MODULATION — Amygdala-driven decay resistance
    // ══════════════════════════════════════════════════════════════

    /**
     * Precomputed arousal-based decay modifiers.
     *
     * <p>Higher arousal = slower decay. The arousal byte is unsigned (0-255),
     * divided into 4 quartile buckets. The modifier is multiplied with the
     * base decay to produce the final effective decay.</p>
     *
     * <p>At arousal=0 (neutral), the modifier is 1.0 — no effect.
     * At arousal=255 (extreme), memories decay 65% slower.</p>
     */
    public static final float[] AROUSAL_DECAY_MODIFIERS = {
            1.00f,  // arousal 0-63:    neutral     → no change
            1.15f,  // arousal 64-127:  mild        → 15% slower decay
            1.35f,  // arousal 128-191: moderate    → 35% slower decay
            1.65f   // arousal 192-255: extreme     → 65% slower decay
    };

    /**
     * Returns the decay modifier based on arousal intensity.
     *
     * <p>Uses an unsigned interpretation of the arousal byte.
     * At arousal=0, returns 1.0 (no effect). At arousal=255,
     * returns 1.65 (65% slower decay).</p>
     *
     * @param arousal unsigned arousal byte (0-255)
     * @return decay modifier (≥ 1.0)
     */
    public static float arousalModifier(byte arousal) {
        int unsigned = Byte.toUnsignedInt(arousal);
        int bucket = Math.min(3, unsigned / 64);
        return AROUSAL_DECAY_MODIFIERS[bucket];
    }

    /**
     * Computes the full decay multiplier including arousal modulation.
     *
     * <p>The arousal modifier scales the base decay upward (toward 1.0),
     * making emotionally intense memories resist forgetting. The result
     * is clamped to [0.0, 1.0] to prevent inverted decay.</p>
     *
     * @param timestampMs memory creation time
     * @param nowMs       current time
     * @param recallCount number of recalls
     * @param arousal     emotional intensity (unsigned byte 0-255)
     * @return arousal-modulated decay multiplier
     */
    public static float computeDecayWithArousal(long timestampMs, long nowMs,
                                                  int recallCount, byte arousal) {
        float baseDecay = computeDecay(timestampMs, nowMs, recallCount);
        float modifier = arousalModifier(arousal);
        return Math.min(1.0f, baseDecay * modifier);
    }
}
