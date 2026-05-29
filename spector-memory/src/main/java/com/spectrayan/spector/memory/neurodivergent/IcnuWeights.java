package com.spectrayan.spector.memory.neurodivergent;

/**
 * Configurable fusion weights for the ICNU importance formula.
 *
 * <h3>Formula</h3>
 * <pre>
 *   importance = clamp(w_I·I + w_C·C + w_N·N_norm + w_U·U, 0.05, 10.0)
 * </pre>
 *
 * <p>Where I, C, U are LLM-provided via {@link IngestionHints} and N_norm is
 * Spector's native novelty score (z-score-based, normalized to [0, 1]).</p>
 *
 * <h3>Weight Invariant</h3>
 * <p>Weights are normalized to sum to 1.0 on construction. This guarantees that
 * when all inputs are at maximum (1.0), the raw fused value equals 1.0 before
 * final scaling.</p>
 *
 * @param interest  weight for LLM-provided interest signal
 * @param challenge weight for LLM-provided challenge signal
 * @param novelty   weight for Spector-native novelty signal
 * @param urgency   weight for LLM-provided urgency signal
 */
public record IcnuWeights(float interest, float challenge, float novelty, float urgency) {

    /**
     * Default: novelty-dominant, interest is strongest LLM signal.
     *
     * <p>Rationale:</p>
     * <ul>
     *   <li><b>Novelty (0.4)</b>: Strongest — objectively measurable, model-agnostic, impossible to game</li>
     *   <li><b>Interest (0.3)</b>: Second — the LLM knows what it's working on</li>
     *   <li><b>Urgency (0.2)</b>: Third — temporal priority matters but is often over-reported</li>
     *   <li><b>Challenge (0.1)</b>: Lowest — hardest for the LLM to assess honestly</li>
     * </ul>
     */
    public static final IcnuWeights DEFAULT = new IcnuWeights(0.30f, 0.10f, 0.40f, 0.20f);

    /**
     * Novelty-only mode — used when no LLM hints are provided (backward compatible).
     */
    public static final IcnuWeights NOVELTY_ONLY = new IcnuWeights(0f, 0f, 1.0f, 0f);

    /** Minimum fused importance (prevents memories from being completely invisible). */
    private static final float MIN_IMPORTANCE = 0.05f;

    /** Maximum fused importance (caps extreme spikes). */
    private static final float MAX_IMPORTANCE = 10.0f;

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
     * Computes the fused importance score from ICNU signals.
     *
     * @param interestVal   LLM-provided interest (0.0–1.0)
     * @param challengeVal  LLM-provided challenge (0.0–1.0)
     * @param noveltyNorm   Spector-computed novelty, normalized to 0.0–1.0
     * @param urgencyVal    LLM-provided urgency (0.0–1.0)
     * @return fused importance clamped to [0.05, 10.0]
     */
    public float fuse(float interestVal, float challengeVal,
                       float noveltyNorm, float urgencyVal) {
        float raw = interest * interestVal
                   + challenge * challengeVal
                   + novelty * noveltyNorm
                   + urgency * urgencyVal;

        // Scale from [0, 1] range to importance range [0.05, 10.0]
        // raw=0 → MIN_IMPORTANCE, raw=1 → MAX_IMPORTANCE
        float scaled = MIN_IMPORTANCE + raw * (MAX_IMPORTANCE - MIN_IMPORTANCE);
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
