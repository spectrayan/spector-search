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
package com.spectrayan.spector.memory;

/**
 * Decomposed scoring trace for a single recall result.
 *
 * <h3>Turning the Black Box into a Glass Box</h3>
 * <p>Spector Memory's fused cognitive scoring pipeline computes a single float
 * {@code score} that blends similarity, importance, decay, tag relevance,
 * and optionally valence alignment. This record decomposes that score into
 * named components so developers can understand <b>why</b> a memory ranked
 * where it did.</p>
 *
 * <h3>Score Formula</h3>
 * <pre>
 *   baseScore = α·similarity + β·importance·decay·storageBoost
 *   baseScore *= valenceAlignment   (if enabled)
 *   finalScore = baseScore × (1 + tagOverlap × tagBoost)
 *   finalScore *= habituationPenalty (from pipeline Step 5)
 *   finalScore *= graphBoost         (from pipeline Step 5c-e)
 * </pre>
 *
 * @param similarity        raw vector similarity score (1/(1 + L2·strictness))
 * @param importanceDecay   importance × decay × storageBoost component
 * @param tagBoostFactor    tag relevance multiplier (1.0 = no boost, &gt;1.0 = tag match)
 * @param habituationPenalty combined habituation + IOR + satiation penalty (1.0 = no penalty)
 * @param graphBoost        combined Hebbian + temporal + entity graph boost (1.0 = no graph)
 * @param valenceAlignment  valence alignment factor (1.0 = disabled or neutral)
 * @param finalScore        the actual returned score after all factors
 */
public record ScoreBreakdown(
        float similarity,
        float importanceDecay,
        float tagBoostFactor,
        float habituationPenalty,
        float graphBoost,
        float valenceAlignment,
        float finalScore
) {

    /** No breakdown available — used when breakdown cannot be computed. */
    public static final ScoreBreakdown NONE = new ScoreBreakdown(0, 0, 1, 1, 1, 1, 0);

    /**
     * Returns a human-readable trace string showing how the score was computed.
     *
     * <p>Example output:</p>
     * <pre>
     * Score Trace:
     *   similarity:    0.8200 (vector match)
     *   imp×decay:     0.4500 (importance × time decay × storage boost)
     *   tag_boost:     1.30×  (tag relevance multiplier)
     *   habituation:   0.85×  (anti-fixation penalty)
     *   graph_boost:   1.12×  (Hebbian/temporal/entity)
     *   valence_align: 1.00×  (mood-congruent factor)
     *   → final:       0.4231
     * </pre>
     */
    public String trace() {
        return String.format(
                "Score Trace:%n"
                + "  similarity:    %.4f (vector match)%n"
                + "  imp×decay:     %.4f (importance × time decay × storage boost)%n"
                + "  tag_boost:     %.2f× (tag relevance multiplier)%n"
                + "  habituation:   %.2f× (anti-fixation penalty)%n"
                + "  graph_boost:   %.2f× (Hebbian/temporal/entity)%n"
                + "  valence_align: %.2f× (mood-congruent factor)%n"
                + "  → final:       %.4f",
                similarity, importanceDecay, tagBoostFactor,
                habituationPenalty, graphBoost, valenceAlignment, finalScore);
    }

    /**
     * Returns the dominant scoring factor — the component with the highest
     * absolute contribution. Useful for quick "why did this rank high/low?"
     *
     * @return the name of the dominant factor
     */
    public String dominantFactor() {
        float simContribution = similarity;
        float idContribution = importanceDecay;
        if (simContribution >= idContribution) {
            return "similarity";
        }
        return "importance_decay";
    }

    /**
     * Returns the weakest multiplier — the factor that penalized this result
     * the most (lowest value among the multipliers). Useful for "why did this
     * rank low?" diagnostics.
     *
     * @return the name of the weakest multiplier
     */
    public String weakestMultiplier() {
        float weakest = Math.min(Math.min(tagBoostFactor, habituationPenalty),
                Math.min(graphBoost, valenceAlignment));
        if (weakest == habituationPenalty) return "habituation";
        if (weakest == valenceAlignment) return "valence_alignment";
        if (weakest == graphBoost) return "graph_boost";
        return "tag_boost";
    }
}
