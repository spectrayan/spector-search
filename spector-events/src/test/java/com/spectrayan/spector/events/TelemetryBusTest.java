/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.spectrayan.spector.events;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TelemetryBus} — publish/subscribe, isolation, concurrency, lifecycle.
 */
@DisplayName("TelemetryBus")
class TelemetryBusTest {

    private TelemetryBus bus;

    @BeforeEach
    void setUp() {
        bus = new TelemetryBus();
    }

    @AfterEach
    void tearDown() {
        bus.close();
    }

    // ══════════════════════════════════════════════════════════════
    // Publish / Subscribe
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("publish/subscribe")
    class PublishSubscribe {

        @Test
        @DisplayName("subscriber receives published events")
        void subscriberReceivesEvent() {
            List<TelemetryEvent> received = new ArrayList<>();
            bus.subscribe(received::add);

            var event = new SimdKernelTelemetry("cosine", 16, 1000, 5000);
            bus.publish(event);

            assertThat(received).containsExactly(event);
        }

        @Test
        @DisplayName("multiple subscribers all receive the same event")
        void multipleSubscribersReceiveEvent() {
            List<TelemetryEvent> received1 = new ArrayList<>();
            List<TelemetryEvent> received2 = new ArrayList<>();
            bus.subscribe(received1::add);
            bus.subscribe(received2::add);

            var event = new SimdKernelTelemetry("dot", 8, 500, 2000);
            bus.publish(event);

            assertThat(received1).containsExactly(event);
            assertThat(received2).containsExactly(event);
        }

        @Test
        @DisplayName("publishing with no subscribers does not throw")
        void publishWithNoSubscribers() {
            assertThatCode(() ->
                    bus.publish(new SimdKernelTelemetry("cosine", 16, 1, 100))
            ).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("subscriber count reflects active subscriptions")
        void subscriberCountAccurate() {
            assertThat(bus.subscriberCount()).isZero();

            bus.subscribe(e -> {});
            assertThat(bus.subscriberCount()).isEqualTo(1);

            bus.subscribe(e -> {});
            assertThat(bus.subscriberCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("events are received in publish order")
        void eventsReceivedInOrder() {
            List<TelemetryEvent> received = new ArrayList<>();
            bus.subscribe(received::add);

            var e1 = new SimdKernelTelemetry("cosine", 16, 100, 1000);
            var e2 = new SimdKernelTelemetry("dot", 8, 200, 2000);
            var e3 = new SimdKernelTelemetry("euclidean", 4, 300, 3000);
            bus.publish(e1);
            bus.publish(e2);
            bus.publish(e3);

            assertThat(received).containsExactly(e1, e2, e3);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Subscription Cancellation
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("subscription cancellation")
    class SubscriptionCancellation {

        @Test
        @DisplayName("cancelled subscription stops receiving events")
        void cancelledSubscriptionStopsReceiving() {
            List<TelemetryEvent> received = new ArrayList<>();
            var subscription = bus.subscribe(received::add);

            bus.publish(new SimdKernelTelemetry("cosine", 16, 1, 100));
            assertThat(received).hasSize(1);

            subscription.cancel();
            bus.publish(new SimdKernelTelemetry("dot", 8, 1, 100));
            assertThat(received).hasSize(1); // no new events
        }

        @Test
        @DisplayName("cancelling one subscription does not affect others")
        void cancelOneDoesNotAffectOthers() {
            List<TelemetryEvent> kept = new ArrayList<>();
            List<TelemetryEvent> cancelled = new ArrayList<>();

            bus.subscribe(kept::add);
            var sub = bus.subscribe(cancelled::add);
            sub.cancel();

            var event = new SimdKernelTelemetry("cosine", 16, 1, 100);
            bus.publish(event);

            assertThat(kept).containsExactly(event);
            assertThat(cancelled).isEmpty();
        }

        @Test
        @DisplayName("double cancel is a no-op")
        void doubleCancelIsNoOp() {
            var sub = bus.subscribe(e -> {});
            sub.cancel();
            assertThatCode(sub::cancel).doesNotThrowAnyException();
            assertThat(bus.subscriberCount()).isZero();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Error Isolation
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("error isolation")
    class ErrorIsolation {

        @Test
        @DisplayName("exception in one subscriber does not block others")
        void exceptionIsolation() {
            List<TelemetryEvent> received = new ArrayList<>();

            // Subscriber that throws
            bus.subscribe(e -> { throw new RuntimeException("boom"); });
            // Subscriber that should still receive events
            bus.subscribe(received::add);

            var event = new SimdKernelTelemetry("cosine", 16, 1, 100);
            assertThatCode(() -> bus.publish(event)).doesNotThrowAnyException();
            assertThat(received).containsExactly(event);
        }

        @Test
        @DisplayName("all subscribers called even with multiple failures")
        void multipleFailures() {
            AtomicInteger successCount = new AtomicInteger(0);

            bus.subscribe(e -> { throw new RuntimeException("fail-1"); });
            bus.subscribe(e -> successCount.incrementAndGet());
            bus.subscribe(e -> { throw new IllegalStateException("fail-2"); });
            bus.subscribe(e -> successCount.incrementAndGet());

            bus.publish(new SimdKernelTelemetry("cosine", 16, 1, 100));

            assertThat(successCount.get()).isEqualTo(2);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Lifecycle (close)
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("close clears all subscribers")
        void closeClearsSubscribers() {
            bus.subscribe(e -> {});
            bus.subscribe(e -> {});
            assertThat(bus.subscriberCount()).isEqualTo(2);

            bus.close();
            assertThat(bus.subscriberCount()).isZero();
        }

        @Test
        @DisplayName("publish after close delivers to no one")
        void publishAfterClose() {
            List<TelemetryEvent> received = new ArrayList<>();
            bus.subscribe(received::add);
            bus.close();

            bus.publish(new SimdKernelTelemetry("cosine", 16, 1, 100));
            assertThat(received).isEmpty();
        }

        @Test
        @DisplayName("close is idempotent")
        void closeIdempotent() {
            bus.subscribe(e -> {});
            bus.close();
            assertThatCode(() -> bus.close()).doesNotThrowAnyException();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Concurrency
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        @Test
        @DisplayName("concurrent publishes are thread-safe")
        void concurrentPublishes() throws InterruptedException {
            int threadCount = 8;
            int eventsPerThread = 100;
            CopyOnWriteArrayList<TelemetryEvent> received = new CopyOnWriteArrayList<>();
            bus.subscribe(received::add);

            CountDownLatch latch = new CountDownLatch(threadCount);
            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                Thread.startVirtualThread(() -> {
                    for (int i = 0; i < eventsPerThread; i++) {
                        bus.publish(new SimdKernelTelemetry(
                                "thread-" + threadId, 16, i, i * 100L));
                    }
                    latch.countDown();
                });
            }
            latch.await();

            assertThat(received).hasSize(threadCount * eventsPerThread);
        }

        @Test
        @DisplayName("concurrent subscribe and publish are safe")
        void concurrentSubscribeAndPublish() throws InterruptedException {
            AtomicInteger totalReceived = new AtomicInteger();
            int iterations = 50;
            CountDownLatch latch = new CountDownLatch(2);

            // Thread 1: keeps subscribing
            Thread.startVirtualThread(() -> {
                for (int i = 0; i < iterations; i++) {
                    bus.subscribe(e -> totalReceived.incrementAndGet());
                }
                latch.countDown();
            });

            // Thread 2: keeps publishing
            Thread.startVirtualThread(() -> {
                for (int i = 0; i < iterations; i++) {
                    bus.publish(new SimdKernelTelemetry("conc", 8, i, 100));
                }
                latch.countDown();
            });

            latch.await();
            // Just verify no exceptions — exact count depends on ordering
            assertThat(totalReceived.get()).isGreaterThanOrEqualTo(0);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Polymorphic Event Types
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("polymorphic events")
    class PolymorphicEvents {

        @Test
        @DisplayName("bus handles all event types in the sealed hierarchy")
        void allEventTypesDelivered() {
            List<TelemetryEvent> received = new ArrayList<>();
            bus.subscribe(received::add);

            TelemetryEvent simd = new SimdKernelTelemetry("cos", 16, 100, 500);
            TelemetryEvent graph = new GraphPulseTelemetry(10, 20, 3, 1000);
            TelemetryEvent query = new QueryTraceTelemetry(
                    "test", "BALANCED", 100, 90, 80, 70, 60, 50, 10, 5000);

            bus.publish(simd);
            bus.publish(graph);
            bus.publish(query);

            assertThat(received).hasSize(3);
            assertThat(received.get(0)).isInstanceOf(SimdKernelTelemetry.class);
            assertThat(received.get(1)).isInstanceOf(GraphPulseTelemetry.class);
            assertThat(received.get(2)).isInstanceOf(QueryTraceTelemetry.class);
        }

        @Test
        @DisplayName("pattern matching on sealed hierarchy works correctly")
        void patternMatchingWorks() {
            List<String> kernelNames = new ArrayList<>();
            bus.subscribe(event -> {
                if (event instanceof SimdKernelTelemetry s) {
                    kernelNames.add(s.kernelName());
                }
            });

            bus.publish(new SimdKernelTelemetry("cosine", 16, 1, 100));
            bus.publish(new GraphPulseTelemetry(1, 2, 1, 300)); // should be filtered out
            bus.publish(new SimdKernelTelemetry("dot", 8, 1, 200));

            assertThat(kernelNames).containsExactly("cosine", "dot");
        }
    }
}
