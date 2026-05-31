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
package com.spectrayan.spector.memory.amygdala;

/**
 * Valence constants and utility methods.
 *
 * <h3>Biological Analog: Amygdala</h3>
 * <p>The amygdala processes emotions and tags memories with emotional significance.
 * Positive valence (joy, reward) and negative valence (fear, punishment) influence
 * which memories are recalled and how they are weighted.</p>
 *
 * <p>Valence is stored as a signed byte (-128 to +127) in the synaptic header
 * at offset 30. It is learned from <em>outcomes</em>, not guessed at encoding time.</p>
 */
public final class Valence {

    private Valence() {}

    /** Strong positive outcome (e.g., agent's response solved the problem). */
    public static final byte STRONGLY_POSITIVE = 100;

    /** Mild positive outcome. */
    public static final byte POSITIVE = 50;

    /** Neutral / unknown outcome (default for new memories). */
    public static final byte NEUTRAL = 0;

    /** Mild negative outcome (e.g., response was unhelpful). */
    public static final byte NEGATIVE = -50;

    /** Strong negative outcome (e.g., response caused an error / data loss). */
    public static final byte STRONGLY_NEGATIVE = -100;

    /**
     * Clamps a valence value to the valid range (-128 to +127).
     */
    public static byte clamp(int value) {
        return (byte) Math.max(Byte.MIN_VALUE, Math.min(Byte.MAX_VALUE, value));
    }

    /**
     * Returns true if the valence indicates a positive outcome.
     */
    public static boolean isPositive(byte valence) {
        return valence > 10;
    }

    /**
     * Returns true if the valence indicates a negative outcome.
     */
    public static boolean isNegative(byte valence) {
        return valence < -10;
    }

    /**
     * Blends two valence values with exponential moving average.
     * New observations have more weight than old ones.
     *
     * @param existing current valence
     * @param newValue  new outcome valence
     * @param alpha    learning rate (0.0–1.0, default: 0.3)
     * @return blended valence
     */
    public static byte blend(byte existing, byte newValue, float alpha) {
        float blended = existing * (1.0f - alpha) + newValue * alpha;
        return clamp(Math.round(blended));
    }
}
