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
 * Configuration for the power-law decay strategy.
 *
 * <h3>Power Law of Forgetting</h3>
 * <p>Research since Wixted (2004) has established that forgetting follows a
 * <b>power law</b> rather than the exponential curve originally proposed by
 * Ebbinghaus (1885):</p>
 *
 * <pre>
 *   R(t) = a · t^{-d}
 * </pre>
 *
 * <p>Where {@code d} is the decay exponent — empirically validated in the
 * range [0.1, 0.5] depending on material type and retention interval.</p>
 *
 * <h3>Decay Exponent Guidelines</h3>
 * <table>
 *   <tr><th>Exponent</th><th>Character</th><th>Use Case</th></tr>
 *   <tr><td>d=0.08</td><td>Very slow forgetting</td><td>Digital legacy, legal, medical</td></tr>
 *   <tr><td>d=0.15</td><td>Moderate forgetting</td><td>General-purpose agent memory</td></tr>
 *   <tr><td>d=0.30</td><td>Aggressive forgetting</td><td>Chat assistants, fast-moving contexts</td></tr>
 * </table>
 *
 * <h3>Permastore Floor</h3>
 * <p>The {@code floor} parameter sets a minimum decay multiplier — no memory
 * decays below this value. This reflects Bahrick's (1984) "permastore" finding
 * that some memories stabilize and resist further forgetting after extended
 * retention intervals.</p>
 *
 * <h3>References</h3>
 * <ul>
 *   <li>Wixted, J.T. (2004). The psychology and neuroscience of forgetting.
 *       <i>Annual Review of Psychology</i>, 55, 235-269.</li>
 *   <li>Bahrick, H.P. (1984). Semantic memory content in permastore.
 *       <i>JEP: General</i>, 113(1), 1-29.</li>
 * </ul>
 *
 * @param exponent power-law decay exponent d (default: 0.15, range: 0.05-1.0)
 * @param floor    minimum decay multiplier — permastore floor (default: 0.10)
 * @param buckets  precomputed bucket values; pass {@code null} to auto-generate
 *                 from exponent and floor using {@link #computeBuckets}
 */
public record DecayConfig(
        float exponent,
        float floor,
        float[] buckets
) {

    /** Number of decay buckets in the 12-bucket power-law system. */
    public static final int BUCKET_COUNT = 12;

    /** Default: moderate forgetting (d=0.15), 10% permastore floor. */
    public static final DecayConfig DEFAULT = new DecayConfig(0.15f, 0.10f, null);

    /** Slow forgetting: for digital legacy, long-term knowledge bases. */
    public static final DecayConfig SLOW_FORGET = new DecayConfig(0.08f, 0.15f, null);

    /** Fast forgetting: for chat assistants, ephemeral contexts. */
    public static final DecayConfig FAST_FORGET = new DecayConfig(0.30f, 0.05f, null);

    /**
     * Compact constructor with validation and auto-generation of buckets.
     */
    public DecayConfig {
        if (exponent < 0.05f || exponent > 1.0f)
            throw new IllegalArgumentException("exponent must be in [0.05, 1.0]: " + exponent);
        if (floor < 0.0f || floor > 0.5f)
            throw new IllegalArgumentException("floor must be in [0.0, 0.5]: " + floor);
        if (buckets == null) {
            buckets = computeBuckets(exponent, floor);
        }
        if (buckets.length != BUCKET_COUNT) {
            throw new IllegalArgumentException(
                    "buckets must have exactly " + BUCKET_COUNT + " entries, got " + buckets.length);
        }
    }

    /**
     * Computes power-law decay bucket values from the given exponent and floor.
     *
     * <p>Each bucket value is computed at the midpoint of the bucket's time range,
     * using the power law {@code R(t) = t^{-d}}, normalized so that bucket 0 = 1.0
     * and clamped to the permastore floor.</p>
     *
     * <p>The midpoint hours used for computation:</p>
     * <pre>
     *   Bucket  0: midpoint =    0.5 hours (0–1h)
     *   Bucket  1: midpoint =    3.5 hours (1–6h)
     *   Bucket  2: midpoint =   15.0 hours (6–24h)
     *   Bucket  3: midpoint =   48.0 hours (1–3 days)
     *   Bucket  4: midpoint =  120.0 hours (3–7 days)
     *   Bucket  5: midpoint =  420.0 hours (1–4 weeks)
     *   Bucket  6: midpoint = 1440.0 hours (1–3 months)
     *   Bucket  7: midpoint = 3240.0 hours (3–6 months)
     *   Bucket  8: midpoint = 6480.0 hours (6–12 months)
     *   Bucket  9: midpoint = 13140.0 hours (1–2 years)
     *   Bucket 10: midpoint = 30660.0 hours (2–5 years)
     *   Bucket 11: midpoint = 61320.0 hours (5+ years, using 5–9 year midpoint)
     * </pre>
     *
     * @param d     power-law decay exponent
     * @param floor minimum value (permastore floor)
     * @return array of 12 decay multipliers, monotonically decreasing
     */
    public static float[] computeBuckets(float d, float floor) {
        // Midpoint hours for each bucket's time range
        final double[] midpointHours = {
                0.5,      // 0–1 hours
                3.5,      // 1–6 hours
                15.0,     // 6–24 hours
                48.0,     // 1–3 days
                120.0,    // 3–7 days
                420.0,    // 1–4 weeks
                1440.0,   // 1–3 months
                3240.0,   // 3–6 months
                6480.0,   // 6–12 months
                13140.0,  // 1–2 years
                30660.0,  // 2–5 years
                61320.0   // 5+ years
        };

        float[] result = new float[BUCKET_COUNT];
        // Normalize: bucket 0 = 1.0
        double base = Math.pow(midpointHours[0], -d);
        for (int i = 0; i < BUCKET_COUNT; i++) {
            double raw = Math.pow(midpointHours[i], -d) / base;
            result[i] = Math.max(floor, (float) raw);
        }
        result[0] = 1.00f; // Always exactly 1.0 for fresh memories
        return result;
    }
}
