package com.spectrayan.spector.memory.synapse;

/**
 * SIMD-friendly bucket-based temporal decay with reconsolidation support.
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
     * Adjusts the raw decay bucket for reconsolidation (Long-Term Potentiation).
     *
     * <p>Every 3 recalls shifts the bucket 1 position fresher (lower index).
     * A memory recalled 9 times behaves as if it's 3 buckets younger than it
     * actually is — frequently-used memories resist aging.</p>
     *
     * @param rawBucket   original bucket from {@link #ageToBucket}
     * @param recallCount number of times this memory has been recalled
     * @return adjusted bucket index (clamped to 0)
     */
    public static int adjustForReconsolidation(int rawBucket, int recallCount) {
        int shift = recallCount / 3;
        return Math.max(0, rawBucket - shift);
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
}
