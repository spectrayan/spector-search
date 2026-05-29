package com.spectrayan.spector.memory.neurodivergent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lateral retrieval evaluation engine — measures utility, suppression, and
 * hallucination rates for neurodivergent lateral/orthogonal retrieval.
 *
 * <h3>Biological Analog: Reduced Latent Inhibition Feedback</h3>
 * <p>The ADHD brain's reduced latent inhibition produces lateral associations
 * (cross-domain leaps). Some are brilliant insights; others are noise. This
 * evaluator tracks which lateral results the agent actually uses vs rejects,
 * providing the feedback loop to auto-tune lateral retrieval aggressiveness.</p>
 *
 * <h3>Metrics</h3>
 * <ul>
 *   <li><b>LUR (Lateral Utility Rate)</b>: reinforced / returned — "are lateral results useful?"</li>
 *   <li><b>LSR (Lateral Suppression Rate)</b>: suppressed / returned — "are lateral results rejected?"</li>
 *   <li><b>LHI (Lateral Hallucination Index)</b>: (1-LUR) × LSR — composite safety metric</li>
 * </ul>
 *
 * <h3>Auto-Tuning</h3>
 * <p>Every {@link #evaluationWindow} lateral results, the evaluator checks LUR:</p>
 * <ul>
 *   <li>LUR &lt; 0.05 → auto-disable lateral mode + WARN log</li>
 *   <li>LUR &lt; 0.10 → tighten distance threshold by 10%</li>
 *   <li>LUR &gt; 0.10 → keep current thresholds</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>Fully concurrent via {@link AtomicInteger} counters.</p>
 */
public final class LateralEvaluator {

    private static final Logger log = LoggerFactory.getLogger(LateralEvaluator.class);

    private final AtomicInteger lateralReturned = new AtomicInteger();
    private final AtomicInteger lateralReinforced = new AtomicInteger();
    private final AtomicInteger lateralSuppressed = new AtomicInteger();

    /** Whether lateral mode is currently enabled (can be auto-disabled). */
    private final AtomicBoolean lateralEnabled = new AtomicBoolean(true);

    /** Current lateral distance threshold (can be auto-tightened). */
    private final AtomicReference<Float> lateralDistanceThreshold;

    /** Number of lateral results per evaluation window. */
    private final int evaluationWindow;

    /** LUR threshold below which lateral mode is auto-disabled. */
    private static final float AUTO_DISABLE_LUR = 0.05f;

    /** LUR threshold below which the distance threshold is tightened. */
    private static final float TIGHTEN_LUR = 0.10f;

    /** Factor by which to tighten the distance threshold. */
    private static final float TIGHTEN_FACTOR = 1.1f;

    /**
     * Creates a lateral evaluator.
     *
     * @param initialDistanceThreshold starting lateral distance threshold (e.g., 1.2)
     * @param evaluationWindow         number of lateral results per evaluation cycle (default: 100)
     */
    public LateralEvaluator(float initialDistanceThreshold, int evaluationWindow) {
        this.lateralDistanceThreshold = new AtomicReference<>(initialDistanceThreshold);
        this.evaluationWindow = evaluationWindow;
    }

    /**
     * Creates a lateral evaluator with default evaluation window (100).
     */
    public LateralEvaluator(float initialDistanceThreshold) {
        this(initialDistanceThreshold, 100);
    }

    /**
     * Creates a lateral evaluator with all defaults (threshold=1.2, window=100).
     */
    public LateralEvaluator() {
        this(1.2f, 100);
    }

    /**
     * Records that a lateral result was returned to the agent.
     * Called by the recall pipeline when a result has {@code retrievalMode == LATERAL}.
     */
    public void recordLateralReturn() {
        lateralReturned.incrementAndGet();
    }

    /**
     * Records that the agent reinforced a lateral result (found it useful).
     * Called by {@code SpectorMemory.reinforce()} when the reinforced memory
     * was originally retrieved via lateral mode.
     */
    public void recordLateralReinforcement() {
        lateralReinforced.incrementAndGet();
        checkAndTune();
    }

    /**
     * Records that the agent suppressed a lateral result (rejected it as noise).
     * Called by {@code SpectorMemory.suppress()} when the suppressed memory
     * was originally retrieved via lateral mode.
     */
    public void recordLateralSuppression() {
        lateralSuppressed.incrementAndGet();
        checkAndTune();
    }

    /**
     * Checks if the evaluation window is complete and auto-tunes if needed.
     */
    private void checkAndTune() {
        int returned = lateralReturned.get();
        if (returned < evaluationWindow) return;

        float lur = (float) lateralReinforced.get() / returned;
        float lsr = (float) lateralSuppressed.get() / returned;
        float lhi = (1.0f - lur) * lsr;

        if (lur < AUTO_DISABLE_LUR) {
            log.warn("Lateral auto-disable: LUR={}, LSR={}, LHI={} over {} results — " +
                     "lateral retrieval producing noise", lur, lsr, lhi, returned);
            lateralEnabled.set(false);
        } else if (lur < TIGHTEN_LUR) {
            float oldThreshold = lateralDistanceThreshold.get();
            float newThreshold = oldThreshold * TIGHTEN_FACTOR;
            lateralDistanceThreshold.set(newThreshold);
            log.info("Lateral threshold tightened: LUR={}, threshold {} → {}",
                     lur, oldThreshold, newThreshold);
        } else {
            log.debug("Lateral evaluation: LUR={}, LSR={}, LHI={} — healthy",
                      lur, lsr, lhi);
        }

        // Reset window
        lateralReturned.set(0);
        lateralReinforced.set(0);
        lateralSuppressed.set(0);
    }

    /**
     * Returns whether lateral mode is currently enabled.
     * Auto-disabled when LUR drops below 5%.
     */
    public boolean isLateralEnabled() {
        return lateralEnabled.get();
    }

    /**
     * Re-enables lateral mode (after auto-disable, or for manual override).
     */
    public void enableLateral() {
        lateralEnabled.set(true);
        log.info("Lateral mode re-enabled manually");
    }

    /**
     * Returns the current (possibly auto-tuned) lateral distance threshold.
     */
    public float currentDistanceThreshold() {
        return lateralDistanceThreshold.get();
    }

    /**
     * Returns a snapshot of the current lateral evaluation metrics.
     */
    public LateralMetrics metrics() {
        int returned = lateralReturned.get();
        if (returned == 0) return LateralMetrics.EMPTY;

        float lur = (float) lateralReinforced.get() / returned;
        float lsr = (float) lateralSuppressed.get() / returned;
        float lhi = (1.0f - lur) * lsr;
        return new LateralMetrics(lur, lsr, lhi, returned);
    }

    /**
     * Resets all counters and re-enables lateral mode.
     */
    public void reset() {
        lateralReturned.set(0);
        lateralReinforced.set(0);
        lateralSuppressed.set(0);
        lateralEnabled.set(true);
    }

    /**
     * Snapshot of lateral evaluation metrics.
     *
     * @param utilityRate        fraction of lateral results reinforced by the agent
     * @param suppressionRate    fraction of lateral results suppressed by the agent
     * @param hallucinationIndex composite safety metric: (1-LUR) × LSR
     * @param sampleSize         number of lateral results in this evaluation window
     */
    public record LateralMetrics(float utilityRate, float suppressionRate,
                                  float hallucinationIndex, int sampleSize) {
        /** Empty metrics — no lateral results have been returned yet. */
        public static final LateralMetrics EMPTY = new LateralMetrics(0f, 0f, 0f, 0);
    }
}
