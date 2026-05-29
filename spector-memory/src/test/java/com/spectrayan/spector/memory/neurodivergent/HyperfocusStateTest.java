package com.spectrayan.spector.memory.neurodivergent;

import com.spectrayan.spector.memory.synapse.SynapticTagEncoder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link HyperfocusState} — TTL management and agent self-extension.
 */
class HyperfocusStateTest {

    @Test
    void inactive_byDefault() {
        var state = new HyperfocusState();
        assertThat(state.isActive()).isFalse();
        assertThat(state.mask()).isZero();
        assertThat(state.remainingMs()).isZero();
    }

    @Test
    void activate_becomesActive() {
        var state = new HyperfocusState();
        long mask = SynapticTagEncoder.encode("database", "deadlock");
        state.activate(mask);
        assertThat(state.isActive()).isTrue();
        assertThat(state.mask()).isEqualTo(mask);
        assertThat(state.remainingMs()).isGreaterThan(0L);
    }

    @Test
    void activate_fromTagStrings() {
        var state = new HyperfocusState();
        state.activateFromTags("java", "concurrency");
        assertThat(state.isActive()).isTrue();
        long expectedMask = SynapticTagEncoder.encode("java", "concurrency");
        assertThat(state.mask()).isEqualTo(expectedMask);
    }

    @Test
    void deactivate_resetsState() {
        var state = new HyperfocusState();
        state.activateFromTags("topic");
        assertThat(state.isActive()).isTrue();

        state.deactivate();
        assertThat(state.isActive()).isFalse();
        assertThat(state.mask()).isZero();
    }

    @Test
    void extend_addsToTtl() {
        var state = new HyperfocusState(1000L); // 1 second TTL
        state.activate(0xFFL, 1000L);
        long before = state.remainingMs();

        state.extend(5000L); // extend by 5 seconds
        long after = state.remainingMs();
        assertThat(after).isGreaterThan(before);
    }

    @Test
    void extend_defaultTtl() {
        var state = new HyperfocusState(5000L);
        state.activate(0xFFL, 1000L);
        state.extend(); // extends by defaultTtlMs (5000L)
        assertThat(state.remainingMs()).isGreaterThan(4000L);
    }

    @Test
    void extend_noOpWhenInactive() {
        var state = new HyperfocusState();
        state.extend(5000L); // should be safe, no-op
        assertThat(state.isActive()).isFalse();
    }

    @Test
    void customTtl_used() {
        var state = new HyperfocusState(60_000L); // 1 minute
        assertThat(state.defaultTtlMs()).isEqualTo(60_000L);
    }

    @Test
    void expiration_returnsZeroMaskAfterTtl() throws InterruptedException {
        var state = new HyperfocusState(50L); // 50ms TTL
        state.activate(0xFFL, 50L);
        assertThat(state.isActive()).isTrue();

        Thread.sleep(100); // wait for expiration
        assertThat(state.isActive()).isFalse();
        assertThat(state.mask()).isZero();
    }
}
