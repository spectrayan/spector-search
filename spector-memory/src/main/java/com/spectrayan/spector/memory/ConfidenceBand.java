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

import java.util.List;

/**
 * Confidence classification for a recall result set.
 *
 * <h3>The Trust Signal</h3>
 * <p>When all recall results have similar scores, the system is uncertain —
 * any of them could be "the right one." When the top result dramatically
 * outscores the others, the system is confident. This enum makes that
 * uncertainty explicit so the LLM can reason about it.</p>
 *
 * <h3>How It Works</h3>
 * <p>Computed from the ratio of the top result's score to the second result's
 * score after sort+topK in the pipeline:</p>
 * <pre>
 *   ratio = results[0].score / results[1].score
 *
 *   ratio ≥ 2.0  → HIGH   (strong winner, clear best match)
 *   ratio ≥ 1.2  → MEDIUM (reasonable pick, but alternatives exist)
 *   ratio &lt; 1.2  → LOW    (results are clustered, uncertain)
 * </pre>
 *
 * @see CognitiveResult
 */
public enum ConfidenceBand {

    /**
     * Top result is ≥2× the score of #2 — strong winner.
     * The system has a clear, confident answer.
     */
    HIGH,

    /**
     * Top result is 1.2×–2× #2 — reasonable but not definitive.
     * The LLM should consider alternatives.
     */
    MEDIUM,

    /**
     * Top result is &lt;1.2× #2 — results are clustered, uncertain.
     * The LLM should weight all results roughly equally.
     */
    LOW;

    /** Threshold: score ratio ≥ this means HIGH. */
    private static final float HIGH_THRESHOLD = 2.0f;

    /** Threshold: score ratio ≥ this means MEDIUM. */
    private static final float MEDIUM_THRESHOLD = 1.2f;

    /**
     * Classifies a result set's confidence based on score spread.
     *
     * @param results the recall results (sorted by score descending)
     * @return the confidence band
     */
    public static ConfidenceBand classify(List<CognitiveResult> results) {
        if (results == null || results.isEmpty()) {
            return LOW;
        }
        if (results.size() == 1) {
            // Single result — HIGH if score is meaningful, LOW if near-zero
            return results.get(0).score() > 0.1f ? HIGH : MEDIUM;
        }

        float topScore = results.get(0).score();
        float secondScore = results.get(1).score();

        if (secondScore <= 0f) {
            // Second result has zero score — top result wins by default
            return topScore > 0f ? HIGH : LOW;
        }

        float ratio = topScore / secondScore;
        if (ratio >= HIGH_THRESHOLD) return HIGH;
        if (ratio >= MEDIUM_THRESHOLD) return MEDIUM;
        return LOW;
    }
}
