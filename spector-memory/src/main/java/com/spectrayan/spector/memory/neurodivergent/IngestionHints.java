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
package com.spectrayan.spector.memory.neurodivergent;

/**
 * Optional LLM-provided cognitive hints for ingestion-time importance tuning.
 *
 * <h3>Biological Analog: ICNU (Interest-Challenge-Novelty-Urgency)</h3>
 * <p>The ADHD brain processes motivation not by priority or rules, but by
 * <strong>Interest, Challenge, Novelty, and Urgency</strong>. These hints allow
 * the LLM to provide the subjective signals (I, C, U) while Spector computes
 * the objective signal (N = novelty via L2 distance) natively.</p>
 *
 * <h3>Valence &amp; Arousal</h3>
 * <p>Optionally, the LLM can provide emotional valence (negative↔positive)
 * and arousal (calm→intense). If not provided, both default to 0 (neutral).
 * When valence is provided but arousal is not, arousal is automatically derived
 * from the absolute value of valence at ingestion time.</p>
 *
 * <h3>Usage</h3>
 * <p>Provided as part of the {@code remember()} call:</p>
 * <pre>{@code
 *   memory.remember("mem-123", "The deadlock was caused by...",
 *       MemoryType.EPISODIC, MemorySource.OBSERVED,
 *       new IngestionHints(0.8f, 0.6f, 0.3f),  // I=0.8, C=0.6, U=0.3
 *       "database", "deadlock");
 *
 *   // With emotional context:
 *   memory.remember("mem-456", "Critical production outage!",
 *       MemoryType.EPISODIC, MemorySource.OBSERVED,
 *       new IngestionHints(1.0f, 0.9f, 1.0f, (byte) -100, (byte) 200),
 *       "incident", "outage");
 * }</pre>
 *
 * <h3>Clamping</h3>
 * <p>ICNU values are clamped to {@code [0.0, 1.0]} on construction to prevent
 * gaming via out-of-range values. Valence is signed byte (-128 to 127).
 * Arousal is unsigned byte (0 to 255, stored as signed Java byte).</p>
 *
 * @param interest  how relevant this memory is to the agent's current task (0.0–1.0)
 * @param challenge how complex or difficult the problem is (0.0–1.0)
 * @param urgency   how time-critical this information is (0.0–1.0)
 * @param valence   emotional valence: -128 (extremely negative) to +127 (extremely positive), 0 = neutral
 * @param arousal   emotional intensity: 0 (calm) to 255 (extreme), stored as unsigned byte. 0 = neutral
 */
public record IngestionHints(float interest, float challenge, float urgency,
                              byte valence, byte arousal) {

    /**
     * Compact constructor — clamps ICNU values to [0.0, 1.0].
     */
    public IngestionHints {
        interest = Math.clamp(interest, 0f, 1f);
        challenge = Math.clamp(challenge, 0f, 1f);
        urgency = Math.clamp(urgency, 0f, 1f);
    }

    /**
     * ICNU-only constructor — no emotional context (valence=0, arousal=0).
     */
    public IngestionHints(float interest, float challenge, float urgency) {
        this(interest, challenge, urgency, (byte) 0, (byte) 0);
    }

    /** Empty hints — triggers novelty-only importance computation. */
    public static final IngestionHints NONE = new IngestionHints(0f, 0f, 0f);

    /**
     * Returns true if no hints were actually provided.
     * When empty, the ingestion pipeline falls back to novelty-only importance.
     */
    public boolean isEmpty() {
        return interest == 0f && challenge == 0f && urgency == 0f;
    }

    /**
     * Returns true if valence or arousal have been set.
     */
    public boolean hasEmotionalContext() {
        return valence != 0 || arousal != 0;
    }

    /**
     * Derives arousal from valence if arousal was not explicitly set.
     *
     * <p>Biological basis: emotional intensity (arousal) correlates with
     * the absolute magnitude of valence. A memory that's extremely negative
     * (-100) or extremely positive (+100) is equally arousing.</p>
     *
     * <p>The mapping: {@code arousal = |valence| * 2}, clamped to [0, 255].</p>
     *
     * @return the effective arousal byte (unsigned 0-255)
     */
    public byte effectiveArousal() {
        if (Byte.toUnsignedInt(arousal) > 0) {
            return arousal;  // explicitly set, use as-is
        }
        if (valence == 0) {
            return 0;  // neutral valence → neutral arousal
        }
        // Derive from |valence|: range [-128, 127] → |val| = [0, 128] → ×2 = [0, 256] → clamp
        int absValence = Math.abs((int) valence);
        int derived = Math.min(255, absValence * 2);
        return (byte) derived;
    }
}
