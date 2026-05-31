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
package com.spectrayan.spector.memory.dopamine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fidelity escalation policy for extreme surprise events.
 *
 * <h3>Biological Analog: Flashbulb Memory Formation</h3>
 * <p>You remember exactly where you were during a life-changing moment, but not
 * what you had for lunch last Tuesday. The amygdala signals the hippocampus to
 * encode at maximum fidelity when dopamine exceeds a threshold.</p>
 *
 * <h3>Implementation</h3>
 * <p>For extreme surprise scores (z-score &gt; 3.0), this policy recommends:</p>
 * <ul>
 *   <li>Store full float32 vectors (not quantized) — zero reconstruction error</li>
 *   <li>Set the pinned flag — exempt from decay and pruning</li>
 *   <li>Set importance to maximum (10.0)</li>
 * </ul>
 */
public final class FlashbulbPolicy {

    private static final Logger log = LoggerFactory.getLogger(FlashbulbPolicy.class);

    /** Z-score threshold above which flashbulb mode activates. */
    private final double flashbulbThreshold;

    /**
     * Creates a flashbulb policy.
     *
     * @param flashbulbThreshold z-score threshold for activation (default: 3.0)
     */
    public FlashbulbPolicy(double flashbulbThreshold) {
        this.flashbulbThreshold = flashbulbThreshold;
    }

    /**
     * Creates a flashbulb policy with default threshold (3.0).
     */
    public FlashbulbPolicy() {
        this(3.0);
    }

    /**
     * Result of a flashbulb evaluation.
     *
     * @param isFlashbulb whether this memory should use full-fidelity storage
     * @param importance  the importance to assign (10.0 for flashbulb, original otherwise)
     * @param pinned      whether to set the pinned flag (exempt from pruning)
     */
    public record FlashbulbDecision(boolean isFlashbulb, float importance, boolean pinned) {

        /** Normal memory — no special treatment. */
        public static final FlashbulbDecision NORMAL =
                new FlashbulbDecision(false, -1f, false);
    }

    /**
     * Evaluates whether a memory should be stored with flashbulb fidelity.
     *
     * @param zScore the surprise z-score from the {@link SurpriseDetector}
     * @return decision with fidelity, importance, and pin recommendations
     */
    public FlashbulbDecision evaluate(double zScore) {
        if (zScore > flashbulbThreshold) {
            log.info("Flashbulb memory triggered! z-score={} (threshold={})",
                    zScore, flashbulbThreshold);
            return new FlashbulbDecision(true, 10.0f, true);
        }
        return FlashbulbDecision.NORMAL;
    }

    /**
     * Returns the flashbulb threshold.
     */
    public double flashbulbThreshold() {
        return flashbulbThreshold;
    }
}
