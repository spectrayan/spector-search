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
 * Configurable fusion weights for the ICNU importance formula with sigmoid gating.
 *
 * <h3>Formula (Sigmoid-Gated)</h3>
 * <pre>
 *   stimulus = w_I·(I×N) + w_C·C + w_U·U
 *   gated    = σ(k · (stimulus - θ))
 *   importance = clamp(MIN + gated × (MAX - MIN), 0.05, 10.0)
 * </pre>
 *
 * <p>The sigmoid gate creates a <b>hard threshold</b>: stimuli below θ produce
 * near-zero importance, while stimuli above θ quickly saturate. This prevents
 * low-relevance memories from accumulating in the store.</p>
 *
 * <h3>Biological Analog: Dopaminergic Gating</h3>
 * <p>In ADHD neurobiology, the dopamine system requires both interest AND novelty
 * to fire simultaneously. The multiplicative I×N interaction models this —
 * something interesting but familiar (low N) or novel but boring (low I) won't
 * cross the dopamine threshold. This is the engine of hyperfocus: only
 * sufficiently stimulating inputs get through.</p>
 *
 * <h3>Weight Invariant</h3>
 * <p>Weights are normalized to sum to 1.0 on construction. The stimulus function
 * uses the I×N multiplicative interaction (biologically correct) rather than
 * additive I + N (which allows low-stimulus pass-through).</p>
 *
 * @param interest   weight for LLM-provided interest signal
 * @param challenge  weight for LLM-provided challenge signal
 * @param novelty    weight for Spector-native novelty signal
 * @param urgency    weight for LLM-provided urgency signal
 * @param threshold  sigmoid threshold θ — stimuli below this produce near-zero importance
 * @param steepness  sigmoid steepness k — higher = sharper cutoff at threshold
 */
public record IcnuWeights(float interest, float challenge, float novelty, float urgency,
                           float threshold, float steepness) {

    /** Minimum fused importance (prevents memories from being completely invisible). */
    private static final float MIN_IMPORTANCE = 0.05f;

    /** Maximum fused importance (caps extreme spikes). */
    private static final float MAX_IMPORTANCE = 10.0f;

    /** Default sigmoid threshold — stimuli below 0.2 are nearly invisible. */
    private static final float DEFAULT_THRESHOLD = 0.2f;

    /** Default sigmoid steepness — moderate cutoff sharpness. */
    private static final float DEFAULT_STEEPNESS = 8.0f;

    /**
     * Default: novelty-dominant, interest is strongest LLM signal.
     * Sigmoid threshold=0.2, steepness=8 (moderate gating).
     *
     * <p>Rationale:</p>
     * <ul>
     *   <li><b>Novelty (0.4)</b>: Strongest — objectively measurable, model-agnostic, impossible to game</li>
     *   <li><b>Interest (0.3)</b>: Second — the LLM knows what it's working on</li>
     *   <li><b>Urgency (0.2)</b>: Third — temporal priority matters but is often over-reported</li>
     *   <li><b>Challenge (0.1)</b>: Lowest — hardest for the LLM to assess honestly</li>
     * </ul>
     */
    public static final IcnuWeights DEFAULT = new IcnuWeights(0.30f, 0.10f, 0.40f, 0.20f,
            DEFAULT_THRESHOLD, DEFAULT_STEEPNESS);

    /**
     * Novelty-only mode — used when no LLM hints are provided (backward compatible).
     */
    public static final IcnuWeights NOVELTY_ONLY = new IcnuWeights(0f, 0f, 1.0f, 0f,
            DEFAULT_THRESHOLD, DEFAULT_STEEPNESS);

    /**
     * Linear mode — no sigmoid gating (backward compatible with pre-sigmoid behavior).
     * Uses steepness=0 to disable the sigmoid, falling back to linear fusion.
     */
    public static final IcnuWeights LINEAR = new IcnuWeights(0.30f, 0.10f, 0.40f, 0.20f,
            0f, 0f);

    /**
     * Backward-compatible constructor — uses default threshold and steepness.
     */
    public IcnuWeights(float interest, float challenge, float novelty, float urgency) {
        this(interest, challenge, novelty, urgency, DEFAULT_THRESHOLD, DEFAULT_STEEPNESS);
    }

    /**
     * Compact constructor — normalizes weights to sum to 1.0.
     */
    public IcnuWeights {
        float sum = interest + challenge + novelty + urgency;
        if (sum > 0f && Math.abs(sum - 1.0f) > 0.001f) {
            interest /= sum;
            challenge /= sum;
            novelty /= sum;
            urgency /= sum;
        }
    }

    /**
     * Computes the fused importance score from ICNU signals using sigmoid gating.
     *
     * <h3>Sigmoid Mode (steepness &gt; 0)</h3>
     * <pre>
     *   stimulus = w_I·(I×N) + w_C·C + w_U·U
     *   gated    = 1 / (1 + exp(-k · (stimulus - θ)))
     *   result   = MIN + gated × (MAX - MIN)
     * </pre>
     *
     * <p>The I×N multiplicative interaction is biologically correct: in ADHD,
     * interest AND novelty must both be high for dopamine release. If something
     * is interesting but familiar (low N), or novel but boring (low I), it
     * doesn't cross the threshold.</p>
     *
     * <h3>Linear Mode (steepness = 0)</h3>
     * <p>Falls back to the original linear fusion for backward compatibility.</p>
     *
     * @param interestVal   LLM-provided interest (0.0–1.0)
     * @param challengeVal  LLM-provided challenge (0.0–1.0)
     * @param noveltyNorm   Spector-computed novelty, normalized to 0.0–1.0
     * @param urgencyVal    LLM-provided urgency (0.0–1.0)
     * @return fused importance clamped to [0.05, 10.0]
     */
    public float fuse(float interestVal, float challengeVal,
                       float noveltyNorm, float urgencyVal) {
        if (steepness <= 0f) {
            // Linear fallback (pre-sigmoid behavior)
            float raw = interest * interestVal
                       + challenge * challengeVal
                       + novelty * noveltyNorm
                       + urgency * urgencyVal;
            float scaled = MIN_IMPORTANCE + raw * (MAX_IMPORTANCE - MIN_IMPORTANCE);
            return Math.clamp(scaled, MIN_IMPORTANCE, MAX_IMPORTANCE);
        }

        // Sigmoid-gated fusion with I×N multiplicative interaction
        // Interest and novelty must BOTH be high (dopaminergic gating)
        float stimulus = interest * (interestVal * noveltyNorm)
                       + challenge * challengeVal
                       + urgency * urgencyVal;

        // Sigmoid: σ(k · (stimulus - θ))
        float gated = 1.0f / (1.0f + (float) Math.exp(-steepness * (stimulus - threshold)));

        // Scale to importance range
        float scaled = MIN_IMPORTANCE + gated * (MAX_IMPORTANCE - MIN_IMPORTANCE);
        return Math.clamp(scaled, MIN_IMPORTANCE, MAX_IMPORTANCE);
    }

    /**
     * Fuses importance using {@link IngestionHints} and a normalized novelty score.
     *
     * <p>If hints are empty, falls back to novelty-only weighting.</p>
     *
     * @param hints       LLM-provided hints (may be {@link IngestionHints#NONE})
     * @param noveltyNorm normalized novelty score (0.0–1.0)
     * @return fused importance
     */
    public float fuse(IngestionHints hints, float noveltyNorm) {
        if (hints == null || hints.isEmpty()) {
            return NOVELTY_ONLY.fuse(0f, 0f, noveltyNorm, 0f);
        }
        return fuse(hints.interest(), hints.challenge(), noveltyNorm, hints.urgency());
    }
}
