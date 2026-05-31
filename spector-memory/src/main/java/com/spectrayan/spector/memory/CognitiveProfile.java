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
 * Preset cognitive scoring profiles for thalamic modulation.
 *
 * <h3>Biological Analog: Thalamic Gating</h3>
 * <p>The thalamus modulates which sensory information reaches the cortex based on
 * the brain's current cognitive state. During focused debugging, the thalamus
 * amplifies error-related signals and suppresses unrelated memories. During
 * creative brainstorming, it broadens the gate to allow more associative
 * connections.</p>
 *
 * <h3>Usage</h3>
 * <p>Profiles preset the {@code alpha} (similarity weight), {@code beta}
 * (importance × decay weight), and valence range so the agent doesn't need
 * to manually tune these parameters:</p>
 * <pre>
 *   // Explicit profile
 *   memory.recall("database error", CognitiveProfile.DEBUGGING);
 *
 *   // Profile with overrides
 *   RecallOptions opts = RecallOptions.builder()
 *       .profile(CognitiveProfile.EXPLORING)
 *       .topK(20)   // override just topK, keep profile's alpha/beta
 *       .build();
 * </pre>
 *
 * <h3>Auto-Detection</h3>
 * <p>Use {@link #detect(String...)} to automatically select a profile based on
 * synaptic tags. Tags containing error-related keywords trigger {@code DEBUGGING},
 * positive keywords trigger {@code RECALLING}, etc.</p>
 */
public enum CognitiveProfile {

    /**
     * Balanced scoring — equal weight to similarity and importance.
     * Default profile for general-purpose recall.
     */
    BALANCED(0.6f, 0.4f, Byte.MIN_VALUE, Byte.MAX_VALUE),

    /**
     * Exploration mode — similarity-dominated scoring for creative, associative recall.
     * Finds memories that are semantically close to the query, regardless of age or importance.
     * Use when brainstorming, exploring new ideas, or looking for tangential connections.
     */
    EXPLORING(0.8f, 0.2f, Byte.MIN_VALUE, Byte.MAX_VALUE),

    /**
     * Debugging mode — importance-dominated scoring, biased toward negative valence.
     * Surfaces recent errors, bugs, and failures. Deprioritizes old successes.
     * Use when investigating bugs, crashes, or production issues.
     */
    DEBUGGING(0.3f, 0.7f, Byte.MIN_VALUE, (byte) -10),

    /**
     * Recalling mode — importance-dominated, biased toward positive valence.
     * Surfaces proven solutions and past successes. Filters out negative outcomes.
     * Use when looking for known-good patterns, templates, or prior art.
     */
    RECALLING(0.4f, 0.6f, (byte) 10, Byte.MAX_VALUE),

    /**
     * Critical mode — heavily importance-dominated, full valence range.
     * Surfaces the most important memories regardless of similarity.
     * Use for high-stakes decisions where correctness matters more than relevance.
     */
    CRITICAL(0.2f, 0.8f, Byte.MIN_VALUE, Byte.MAX_VALUE),

    // ══ Neurodivergent Profiles ══

    /**
     * Hyperfocus mode — pure similarity scoring, zero time decay.
     *
     * <p>Biological analog: Monotropism. The neurodivergent brain focuses all
     * attention on a narrow topic with absolute depth. Time ceases to exist
     * for the focused topic — a 3-month-old memory scores as if fresh.</p>
     *
     * <p>Must be combined with a {@code hyperfocusMask} in RecallOptions to be
     * effective. Without a mask, behaves like EXPLORING.</p>
     *
     * <p>Scoring: α=1.0 (pure similarity), β=0.0 (no importance×decay).
     * Decay is clamped to 1.0 for focus-matched memories.
     * Post-score hyperfocusBoost=1.5 applied after normalized base score.</p>
     */
    HYPERFOCUS(1.0f, 0.0f, Byte.MIN_VALUE, Byte.MAX_VALUE),

    /**
     * Systematizer mode — importance-dominated, lossless consolidation.
     *
     * <p>Biological analog: Bottom-up processing. The autistic brain absorbs massive
     * amounts of raw, unfiltered details, meticulously holding them until a perfect
     * systemic pattern emerges. Source episodes are pinned during consolidation
     * instead of being eligible for pruning.</p>
     *
     * <p>Great for Senior AI Software Engineers, medical diagnosis, log analysis,
     * and deep-research agents that need encyclopedic detail retention.</p>
     */
    SYSTEMATIZER(0.3f, 0.7f, Byte.MIN_VALUE, Byte.MAX_VALUE),

    /**
     * Divergent thinking mode — enables lateral/orthogonal retrieval.
     *
     * <p>Biological analog: Reduced Latent Inhibition. The ADHD brain processes
     * peripheral data that neurotypical brains filter out, causing thoughts to
     * jump between seemingly unrelated concepts based on shared structural tags.
     * This is the engine of cross-disciplinary innovation.</p>
     *
     * <p>Enables {@code lateralMode} with default thresholds. Lateral candidates
     * are tag-matched but semantically distant — blended with standard results.</p>
     */
    DIVERGENT(0.8f, 0.2f, Byte.MIN_VALUE, Byte.MAX_VALUE),

    // ══ Enhanced Profiles (feature-flagged for licensing) ══

    /**
     * Paranoid Sentinel mode — SRE / Cyber Auditor.
     *
     * <p>Biological analog: Threat-detection circuitry in the amygdala.
     * Only surfaces memories associated with negative outcomes (errors,
     * failures, security incidents). Uses valence alignment to amplify
     * mood-congruent threat recall.</p>
     *
     * <p>Scoring: α=0.2 (minimal similarity), β=0.8 (importance-dominated),
     * valence range [-128, -1] (only negative memories).
     * Valence alignment set to queryValence=-128 (maximum threat).</p>
     *
     * <p><b>Note:</b> This profile is functionally equivalent to the proposed
     * "Anxious / Hyper-vigilant" cognitive profile from neuroscience literature.
     * Threat-association weighting, negative valence bias, and amygdala modulation
     * are all implemented via the valence range and alignment parameters.
     * Users seeking "Anxious" or "Hyper-vigilant" behavior should use this profile.</p>
     */
    PARANOID_SENTINEL(0.2f, 0.8f, Byte.MIN_VALUE, (byte) -1),

    /**
     * The Executor mode — Devin-style agentic task runner.
     *
     * <p>Biological analog: Prefrontal cortex in "executive function" mode.
     * Strict matching via Heaviside Cliff (only near-exact matches surface).
     * Lateral retrieval is disabled to prevent tangential exploration.
     * Combined with Zeigarnik Effect for task tracking.</p>
     *
     * <p>Scoring: α=0.3 (moderate similarity), β=0.7 (importance-dominated),
     * strictnessCoefficient=10.0 (cliff function).</p>
     */
    THE_EXECUTOR(0.3f, 0.7f, Byte.MIN_VALUE, Byte.MAX_VALUE),

    /**
     * Highly Sensitive mode — Sensory Processing Sensitivity.
     *
     * <p>Biological analog: Enhanced sensory processing depth. The highly
     * sensitive brain processes stimuli more deeply, captures finer details,
     * and has a lower threshold for emotional activation. Memories are
     * ingested at a lower flashbulb threshold and retained with stronger
     * lateral inhibition to prevent interference.</p>
     *
     * <p>Scoring: α=0.7 (similarity-leaning), β=0.3 (importance secondary).
     * Overrides: flashbulbThreshold=2.0 (lower than default 3.0),
     * inhibitionFloor=0.3 (stronger than default).</p>
     *
     * <p>Users who previously tuned flashbulbThreshold and inhibition parameters
     * manually can use this profile instead for a curated experience.</p>
     */
    HIGHLY_SENSITIVE(0.7f, 0.3f, Byte.MIN_VALUE, Byte.MAX_VALUE),

    /**
     * Default Mode Network — "Shower Thoughts" / mind-wandering.
     *
     * <p>Biological analog: The brain's default mode network activates during
     * rest, surfacing deep, consolidated knowledge from long-term memory.
     * Skips Working and Episodic tiers to focus on Semantic and Procedural
     * memories.</p>
     *
     * <p>Scoring: α=0.2 (low similarity), β=0.8 (importance-dominated).
     * memoryTypes restricted to SEMANTIC + PROCEDURAL.</p>
     */
    DEFAULT_MODE_NETWORK(0.2f, 0.8f, Byte.MIN_VALUE, Byte.MAX_VALUE);

    private final float alpha;
    private final float beta;
    private final byte minValence;
    private final byte maxValence;

    CognitiveProfile(float alpha, float beta, byte minValence, byte maxValence) {
        this.alpha = alpha;
        this.beta = beta;
        this.minValence = minValence;
        this.maxValence = maxValence;
    }

    /** Similarity weight (higher = more similarity-driven). */
    public float alpha() { return alpha; }

    /** Importance × decay weight (higher = more importance-driven). */
    public float beta() { return beta; }

    /** Minimum valence filter. */
    public byte minValence() { return minValence; }

    /** Maximum valence filter. */
    public byte maxValence() { return maxValence; }

    /**
     * Applies this profile's settings to a {@link RecallOptions.Builder}.
     *
     * <p>Sets alpha, beta, minValence, and maxValence. The caller can override
     * individual fields after applying the profile:</p>
     * <pre>
     *   RecallOptions opts = RecallOptions.builder()
     *       .profile(CognitiveProfile.DEBUGGING)
     *       .topK(20)  // profile sets alpha/beta/valence; topK is independent
     *       .build();
     * </pre>
     *
     * @param builder the builder to configure
     * @return the same builder for chaining
     */
    public RecallOptions.Builder applyTo(RecallOptions.Builder builder) {
        builder.alpha(alpha)
               .beta(beta)
               .minValence(minValence)
               .maxValence(maxValence);

        // Neurodivergent profile-specific overrides
        return switch (this) {
            case HYPERFOCUS -> builder.hyperfocusBoost(1.5f);
            case SYSTEMATIZER -> builder.strictnessCoefficient(10.0f);
            case DIVERGENT  -> builder.lateralMode(true);
            case PARANOID_SENTINEL -> builder.queryValence(Byte.MIN_VALUE)
                                            .enableValenceAlignment(true);
            case THE_EXECUTOR -> builder.lateralMode(false)
                                        .strictnessCoefficient(10.0f);
            case HIGHLY_SENSITIVE -> builder.minImportance(0.01f);
            case DEFAULT_MODE_NETWORK -> builder.memoryTypes(
                    MemoryType.SEMANTIC, MemoryType.PROCEDURAL);
            default         -> builder;
        };
    }

    /**
     * Whether this profile pins source episodes during consolidation
     * (lossless consolidation mode).
     *
     * @return true for SYSTEMATIZER, false for all others
     */
    public boolean pinSourceEpisodes() {
        return this == SYSTEMATIZER;
    }

    // ══════════════════════════════════════════════════════════════
    // AUTO-DETECTION — select profile from synaptic tags
    // ══════════════════════════════════════════════════════════════

    /** Keywords that trigger DEBUGGING profile. */
    private static final String[] DEBUG_KEYWORDS = {
            "error", "bug", "crash", "fail", "exception", "timeout", "broken", "fix"
    };

    /** Keywords that trigger RECALLING profile. */
    private static final String[] RECALL_KEYWORDS = {
            "solution", "success", "working", "resolved", "pattern", "template", "best-practice"
    };

    /** Keywords that trigger CRITICAL profile. */
    private static final String[] CRITICAL_KEYWORDS = {
            "critical", "urgent", "security", "production", "outage", "data-loss"
    };

    /**
     * Auto-detects the most appropriate cognitive profile from synaptic tags.
     *
     * <p>Scans tags for keyword matches. If multiple profiles match, the most
     * specific one wins (CRITICAL > DEBUGGING > RECALLING > BALANCED).</p>
     *
     * @param tags synaptic tag strings from the query
     * @return the detected profile, or {@link #BALANCED} if no keywords match
     */
    public static CognitiveProfile detect(String... tags) {
        if (tags == null || tags.length == 0) return BALANCED;

        boolean hasCritical = false, hasDebug = false, hasRecall = false;

        for (String tag : tags) {
            if (tag == null) continue;
            String lower = tag.toLowerCase();
            for (String kw : CRITICAL_KEYWORDS) {
                if (lower.contains(kw)) { hasCritical = true; break; }
            }
            for (String kw : DEBUG_KEYWORDS) {
                if (lower.contains(kw)) { hasDebug = true; break; }
            }
            for (String kw : RECALL_KEYWORDS) {
                if (lower.contains(kw)) { hasRecall = true; break; }
            }
        }

        // Priority: CRITICAL > DEBUGGING > RECALLING > BALANCED
        if (hasCritical) return CRITICAL;
        if (hasDebug) return DEBUGGING;
        if (hasRecall) return RECALLING;
        return BALANCED;
    }
}
