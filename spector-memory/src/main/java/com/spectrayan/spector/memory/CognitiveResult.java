package com.spectrayan.spector.memory;

import com.spectrayan.spector.memory.cortex.MemorySource;

/**
 * Immutable result record returned by {@link SpectorMemory#recall}.
 *
 * <p>Contains the memory text, cognitive scoring metadata, provenance information,
 * and biological state (recall count, valence, decay factor). Designed to give
 * the LLM maximum contextual grounding for reasoning about memory reliability.</p>
 *
 * @param id              unique memory identifier
 * @param text            the memory content text
 * @param score           final fused cognitive score (similarity × decay × importance)
 * @param importance      base importance weight (auto-set by Prediction Error engine)
 * @param ageDays         age of the memory in days
 * @param recallCount     number of times this memory has been recalled (LTP/reconsolidation)
 * @param valence         emotional valence (-128 to +127)
 * @param memoryType      cognitive memory tier (Working, Episodic, Semantic, Procedural)
 * @param source          provenance source (Observed, UserStated, Reflected, etc.)
 * @param synapticTags    decoded synaptic tag labels
 * @param decayFactor     raw decay multiplier (before reconsolidation adjustment)
 * @param ltpAdjustedDecay decay multiplier after reconsolidation adjustment
 * @param retrievalMode   how this result was retrieved (Standard, Lateral, Hyperfocus)
 */
public record CognitiveResult(
        String id,
        String text,
        float score,
        float importance,
        float ageDays,
        int recallCount,
        byte valence,
        MemoryType memoryType,
        MemorySource source,
        String[] synapticTags,
        float decayFactor,
        float ltpAdjustedDecay,
        RetrievalMode retrievalMode
) {

    /**
     * How a memory was retrieved — enables the LLM to reason about result provenance.
     *
     * <h3>Neurodivergent Cognitive Profiles</h3>
     * <ul>
     *   <li>{@code STANDARD} — normal similarity-based retrieval</li>
     *   <li>{@code LATERAL} — cross-domain retrieval via orthogonal tag matching
     *       (divergent thinking / ADHD profile)</li>
     *   <li>{@code HYPERFOCUS} — zero-decay retrieval for focus-matched memories
     *       (monotropism / autistic profile)</li>
     * </ul>
     */
    public enum RetrievalMode {
        /** Standard similarity-based retrieval. */
        STANDARD,
        /** Lateral/orthogonal retrieval — tag-matched but semantically distant. */
        LATERAL,
        /** Hyperfocus retrieval — zero time decay, strict tag matching. */
        HYPERFOCUS
    }

    /**
     * Compact constructor — defaults retrievalMode to STANDARD when not specified.
     */
    public CognitiveResult(String id, String text, float score, float importance,
                            float ageDays, int recallCount, byte valence,
                            MemoryType memoryType, MemorySource source,
                            String[] synapticTags, float decayFactor,
                            float ltpAdjustedDecay) {
        this(id, text, score, importance, ageDays, recallCount, valence,
                memoryType, source, synapticTags, decayFactor, ltpAdjustedDecay,
                RetrievalMode.STANDARD);
    }

    /**
     * Returns the confidence weight based on source monitoring.
     */
    public float confidenceWeight() {
        return source != null ? source.confidenceWeight() : 0.5f;
    }

    /**
     * Returns true if this memory has been positively reinforced (valence > 10).
     */
    public boolean isPositivelyReinforced() {
        return valence > 10;
    }

    /**
     * Returns true if this memory is associated with a negative outcome (valence < -10).
     */
    public boolean isNegativeOutcome() {
        return valence < -10;
    }

    /**
     * Returns true if this result was retrieved via lateral/divergent retrieval.
     */
    public boolean isLateral() {
        return retrievalMode == RetrievalMode.LATERAL;
    }

    /**
     * Returns true if this result was retrieved via hyperfocus/zero-decay mode.
     */
    public boolean isHyperfocused() {
        return retrievalMode == RetrievalMode.HYPERFOCUS;
    }
}

