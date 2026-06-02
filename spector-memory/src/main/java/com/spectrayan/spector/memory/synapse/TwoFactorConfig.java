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
 * Configuration for the Two-Factor Memory model (Bjork &amp; Bjork, 1992).
 *
 * <h3>Two-Factor Model</h3>
 * <p>Each memory has two independent strengths:</p>
 * <ul>
 *   <li><b>Retrieval Strength R(t)</b> — how easily the memory can be accessed right now.
 *       Mapped to the existing {@code decay(t)} function. High R(t) = easy recall.</li>
 *   <li><b>Storage Strength S(t)</b> — how deeply the memory is encoded.
 *       Stored in the {@code storage_strength} header field (V2+ layouts). High S(t) = durable.</li>
 * </ul>
 *
 * <h3>Key Insight: Desirable Difficulty</h3>
 * <p>When retrieval is hard (low R(t)), successful recall causes the largest S(t) boost.
 * This is the "desirable difficulty" effect: struggling to retrieve a memory makes it
 * stick better. Conversely, re-retrieving something that's already easily accessible
 * provides little storage benefit.</p>
 *
 * <h3>Scoring Integration</h3>
 * <p>The final score modifier is {@code S(t)^sExponent}. The default exponent of 0.3
 * provides a gentle boost: a memory with S(t)=5.0 gets a 1.62× multiplier, while
 * the default S(t)=1.0 has no effect (1.0^0.3 = 1.0).</p>
 *
 * <h3>Storage Strength Update</h3>
 * <pre>
 *   ΔS = sGain × (1 - R(t))    // max boost when retrieval is hard
 *   S' = min(S + ΔS, sMax)     // bounded growth
 * </pre>
 *
 * @param sGain    learning rate for storage strength increment (default: 0.1)
 * @param sMax     maximum storage strength (default: 5.0)
 * @param sExponent exponent applied to S(t) in scoring (default: 0.3)
 * @param enabled  whether Two-Factor scoring is active (default: true)
 */
public record TwoFactorConfig(
        float sGain,
        float sMax,
        float sExponent,
        boolean enabled
) {

    /** Default configuration with gentle S(t) influence. */
    public static final TwoFactorConfig DEFAULT = new TwoFactorConfig(0.1f, 5.0f, 0.3f, true);

    /** Disabled configuration — S(t) has no effect on scoring. */
    public static final TwoFactorConfig DISABLED = new TwoFactorConfig(0.1f, 5.0f, 0.3f, false);

    /**
     * Compact constructor with validation.
     */
    public TwoFactorConfig {
        if (sGain < 0 || sGain > 1.0f)
            throw new IllegalArgumentException("sGain must be in [0, 1.0]: " + sGain);
        if (sMax < 1.0f || sMax > 100.0f)
            throw new IllegalArgumentException("sMax must be in [1.0, 100.0]: " + sMax);
        if (sExponent < 0 || sExponent > 2.0f)
            throw new IllegalArgumentException("sExponent must be in [0, 2.0]: " + sExponent);
    }
}
