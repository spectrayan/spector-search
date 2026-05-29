package com.spectrayan.spector.memory.neurodivergent;

import com.spectrayan.spector.memory.synapse.SynapticTagEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages hyperfocus state — zero-decay, strict tag gating, TTL with agent self-extension.
 *
 * <h3>Biological Analog: Monotropism &amp; Hyperfocus</h3>
 * <p>Monotropism is the tendency of neurodivergent minds to focus their attention on a
 * small number of interests with absolute, unrelenting depth. When in "Hyperfocus,"
 * the brain experiences <strong>Time Blindness</strong> — hours feel like minutes,
 * and the brain ignores all outside stimuli.</p>
 *
 * <h3>Effect on Scoring</h3>
 * <ul>
 *   <li>Phase 2: Strict tag equality gate — only memories matching ALL focus tags pass</li>
 *   <li>Phase 4: Decay clamped to 1.0 — time ceases to exist for focused-topic memories</li>
 *   <li>Phase 6: Post-score {@code hyperfocusBoost} multiplier applied</li>
 *   <li>Scoring weights: α=1.0, β=0.0 (pure similarity, no importance×decay)</li>
 * </ul>
 *
 * <h3>TTL &amp; Self-Extension</h3>
 * <p>Hyperfocus activates with a configurable TTL (default: 30 minutes). The agent
 * can self-extend via {@link #extend(long)} when deeply engaged. When the TTL
 * expires, the mask returns {@code 0L} and scoring reverts to normal.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Uses {@code volatile} fields for lock-free reads from the scoring hot loop.
 * Write operations (activate/extend/deactivate) are not synchronized — intended
 * for single-agent usage. Multi-agent setups should use external coordination.</p>
 */
public final class HyperfocusState {

    private static final Logger log = LoggerFactory.getLogger(HyperfocusState.class);

    /** Default TTL: 30 minutes. */
    public static final long DEFAULT_TTL_MS = 30 * 60 * 1000L;

    private volatile long hyperfocusMask = 0L;
    private volatile long expiresAtMs = 0L;
    private final long defaultTtlMs;

    /**
     * Creates a hyperfocus state with a configurable default TTL.
     *
     * @param defaultTtlMs default time-to-live in milliseconds
     */
    public HyperfocusState(long defaultTtlMs) {
        this.defaultTtlMs = defaultTtlMs;
    }

    /**
     * Creates a hyperfocus state with the default TTL (30 minutes).
     */
    public HyperfocusState() {
        this(DEFAULT_TTL_MS);
    }

    /**
     * Activates hyperfocus on the given topic tags with a custom TTL.
     *
     * @param mask  Bloom filter mask encoding the focus topic tags
     * @param ttlMs time-to-live in milliseconds for this hyperfocus session
     */
    public void activate(long mask, long ttlMs) {
        this.hyperfocusMask = mask;
        this.expiresAtMs = System.currentTimeMillis() + ttlMs;
        log.info("Hyperfocus activated: mask=0x{}, TTL={}ms", Long.toHexString(mask), ttlMs);
    }

    /**
     * Activates hyperfocus with the default TTL.
     *
     * @param mask Bloom filter mask encoding the focus topic tags
     */
    public void activate(long mask) {
        activate(mask, defaultTtlMs);
    }

    /**
     * Activates hyperfocus from string tags with a custom TTL.
     *
     * @param ttlMs time-to-live in milliseconds
     * @param tags  focus topic tags to encode into a Bloom filter mask
     */
    public void activate(long ttlMs, String... tags) {
        activate(SynapticTagEncoder.encode(tags), ttlMs);
    }

    /**
     * Activates hyperfocus from string tags with the default TTL.
     *
     * @param tags focus topic tags to encode into a Bloom filter mask
     */
    public void activateFromTags(String... tags) {
        activate(SynapticTagEncoder.encode(tags), defaultTtlMs);
    }

    /**
     * Agent self-extends the current hyperfocus session.
     *
     * <p>Only effective if hyperfocus is currently active. Adds the specified
     * duration to the current expiration time.</p>
     *
     * @param additionalMs additional time in milliseconds
     */
    public void extend(long additionalMs) {
        if (isActive()) {
            this.expiresAtMs += additionalMs;
            log.info("Hyperfocus extended by {}ms, new expiry in {}ms",
                     additionalMs, expiresAtMs - System.currentTimeMillis());
        }
    }

    /**
     * Extends hyperfocus by the default TTL duration.
     */
    public void extend() {
        extend(defaultTtlMs);
    }

    /**
     * Returns whether hyperfocus is currently active (mask != 0 and TTL not expired).
     */
    public boolean isActive() {
        return hyperfocusMask != 0L && System.currentTimeMillis() < expiresAtMs;
    }

    /**
     * Returns the current hyperfocus mask, or {@code 0L} if expired or inactive.
     *
     * <p>Called from the scoring hot loop — must be fast (volatile read).</p>
     */
    public long mask() {
        return isActive() ? hyperfocusMask : 0L;
    }

    /**
     * Returns the remaining time in milliseconds, or 0 if inactive.
     */
    public long remainingMs() {
        if (!isActive()) return 0L;
        return Math.max(0L, expiresAtMs - System.currentTimeMillis());
    }

    /**
     * Deactivates hyperfocus immediately.
     */
    public void deactivate() {
        long oldMask = this.hyperfocusMask;
        this.hyperfocusMask = 0L;
        this.expiresAtMs = 0L;
        if (oldMask != 0L) {
            log.info("Hyperfocus deactivated (was mask=0x{})", Long.toHexString(oldMask));
        }
    }

    /**
     * Returns the configured default TTL in milliseconds.
     */
    public long defaultTtlMs() {
        return defaultTtlMs;
    }
}
