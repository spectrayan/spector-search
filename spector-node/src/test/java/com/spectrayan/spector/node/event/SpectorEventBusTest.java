/*
 * Copyright 2026 Spectrayan
 */
package com.spectrayan.spector.node.event;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SpectorEventBus} — subscribe, publish, cancel, typed filtering.
 */
@DisplayName("SpectorEventBus")
class SpectorEventBusTest {

    private SpectorEventBus bus;

    @BeforeEach
    void setUp() {
        bus = new SpectorEventBus();
    }

    // ══════════════════════════════════════════════════════════════
    // Basic publish/subscribe
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("subscriber receives published event")
    void subscriberReceivesEvent() {
        var received = new AtomicReference<SpectorEvent>();
        bus.subscribe(received::set);

        var event = new SpectorSearchCompletedEvent("node-1", Instant.now(), 5, 12L, "HYBRID");
        bus.publish(event);

        assertThat(received.get()).isEqualTo(event);
    }

    @Test
    @DisplayName("multiple subscribers all receive event")
    void multipleSubscribers() {
        var count = new AtomicInteger(0);
        bus.subscribe(_ -> count.incrementAndGet());
        bus.subscribe(_ -> count.incrementAndGet());
        bus.subscribe(_ -> count.incrementAndGet());

        bus.publish(new SpectorNodeStartedEvent("node-1", Instant.now(), 7070, "STANDALONE"));

        assertThat(count.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("subscriberCount tracks active subscriptions")
    void subscriberCount() {
        assertThat(bus.subscriberCount()).isZero();
        var sub = bus.subscribe(_ -> {});
        assertThat(bus.subscriberCount()).isEqualTo(1);
        sub.cancel();
        assertThat(bus.subscriberCount()).isZero();
    }

    // ══════════════════════════════════════════════════════════════
    // Cancel
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("cancel subscription")
    class CancelTests {

        @Test
        @DisplayName("cancelled subscriber does not receive subsequent events")
        void cancelledSubscriberSkipped() {
            var count = new AtomicInteger(0);
            var sub = bus.subscribe(_ -> count.incrementAndGet());

            bus.publish(new SpectorNodeStartedEvent("n", Instant.now(), 7070, "STANDALONE"));
            assertThat(count.get()).isEqualTo(1);

            sub.cancel();
            bus.publish(new SpectorNodeStartedEvent("n", Instant.now(), 7070, "STANDALONE"));
            assertThat(count.get()).isEqualTo(1); // no increment after cancel
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Typed subscription
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("typed subscription")
    class TypedTests {

        @Test
        @DisplayName("typed subscriber only receives matching events")
        void typedFilter() {
            var received = new AtomicReference<SpectorSearchCompletedEvent>();
            bus.subscribe(SpectorSearchCompletedEvent.class, received::set);

            // Publish non-matching event
            bus.publish(new SpectorNodeStartedEvent("n", Instant.now(), 7070, "STANDALONE"));
            assertThat(received.get()).isNull();

            // Publish matching event
            var event = new SpectorSearchCompletedEvent("n", Instant.now(), 10, 5L, "KEYWORD");
            bus.publish(event);
            assertThat(received.get()).isEqualTo(event);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Error handling
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("error handling")
    class ErrorHandling {

        @Test
        @DisplayName("failing subscriber does not block other subscribers")
        void failingSubscriberIsolated() {
            var received = new AtomicInteger(0);

            bus.subscribe(_ -> { throw new RuntimeException("boom"); });
            bus.subscribe(_ -> received.incrementAndGet());

            bus.publish(new SpectorNodeStartedEvent("n", Instant.now(), 7070, "STANDALONE"));

            assertThat(received.get()).isEqualTo(1); // second subscriber still fires
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Async mode
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("default mode is sync")
    void defaultIsSync() {
        assertThat(bus.isAsyncMode()).isFalse();
    }
}
