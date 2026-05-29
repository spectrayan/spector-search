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
 * <h3>Usage</h3>
 * <p>Provided as part of the {@code remember()} call:</p>
 * <pre>{@code
 *   memory.remember("mem-123", "The deadlock was caused by...",
 *       MemoryType.EPISODIC, MemorySource.OBSERVED,
 *       new IngestionHints(0.8f, 0.6f, 0.3f),  // I=0.8, C=0.6, U=0.3
 *       "database", "deadlock");
 * }</pre>
 *
 * <h3>Clamping</h3>
 * <p>All values are clamped to {@code [0.0, 1.0]} on construction to prevent
 * gaming via out-of-range values.</p>
 *
 * @param interest  how relevant this memory is to the agent's current task (0.0–1.0)
 * @param challenge how complex or difficult the problem is (0.0–1.0)
 * @param urgency   how time-critical this information is (0.0–1.0)
 */
public record IngestionHints(float interest, float challenge, float urgency) {

    /**
     * Compact constructor — clamps all values to [0.0, 1.0].
     */
    public IngestionHints {
        interest = Math.clamp(interest, 0f, 1f);
        challenge = Math.clamp(challenge, 0f, 1f);
        urgency = Math.clamp(urgency, 0f, 1f);
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
}
