package com.spectrayan.spector.node.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe publish/subscribe event bus for Spector node events.
 *
 * <p>Implements the Observer pattern. Any component can publish events,
 * and any number of subscribers can listen. Subscribers receive events
 * synchronously on the publisher's thread — keep handlers fast.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   SpectorEventBus eventBus = new SpectorEventBus();
 *
 *   // Subscribe
 *   SpectorEventBus.Subscription sub = eventBus.subscribe(event -> {
 *       if (event instanceof SpectorSearchCompletedEvent e) {
 *           log.info("Search completed: {} results", e.resultCount());
 *       }
 *   });
 *
 *   // Publish
 *   eventBus.publish(new SpectorSearchCompletedEvent("node-1", Instant.now(), 5, 12L, "HYBRID"));
 *
 *   // Unsubscribe
 *   sub.cancel();
 * }</pre>
 *
 * <h3>Thread Safety</h3>
 * <p>Uses {@link CopyOnWriteArrayList} for lock-free reads during event
 * dispatch. Suitable for high-throughput event publishing with infrequent
 * subscribe/unsubscribe operations.</p>
 */
public class SpectorEventBus {

    private static final Logger log = LoggerFactory.getLogger(SpectorEventBus.class);

    private final List<Consumer<SpectorEvent>> subscribers = new CopyOnWriteArrayList<>();

    /**
     * Publishes an event to all subscribers.
     *
     * <p>Exceptions thrown by individual subscribers are caught and logged
     * to prevent one failing subscriber from blocking others.</p>
     *
     * @param event the event to publish
     */
    public void publish(SpectorEvent event) {
        for (Consumer<SpectorEvent> subscriber : subscribers) {
            try {
                subscriber.accept(event);
            } catch (Exception e) {
                log.warn("Event subscriber threw exception for {}: {}",
                        event.eventType(), e.getMessage(), e);
            }
        }
    }

    /**
     * Subscribes to all events.
     *
     * @param subscriber the event handler
     * @return a subscription handle that can be cancelled
     */
    public Subscription subscribe(Consumer<SpectorEvent> subscriber) {
        subscribers.add(subscriber);
        log.debug("Event subscriber added (total: {})", subscribers.size());
        return () -> {
            subscribers.remove(subscriber);
            log.debug("Event subscriber removed (total: {})", subscribers.size());
        };
    }

    /**
     * Subscribes to events of a specific type only.
     *
     * @param eventType  the event class to filter for
     * @param subscriber the typed event handler
     * @param <T>        the event type
     * @return a subscription handle that can be cancelled
     */
    @SuppressWarnings("unchecked")
    public <T extends SpectorEvent> Subscription subscribe(Class<T> eventType, Consumer<T> subscriber) {
        Consumer<SpectorEvent> wrapped = event -> {
            if (eventType.isInstance(event)) {
                subscriber.accept((T) event);
            }
        };
        subscribers.add(wrapped);
        return () -> subscribers.remove(wrapped);
    }

    /** Returns the current subscriber count (for monitoring). */
    public int subscriberCount() {
        return subscribers.size();
    }

    /**
     * Handle for cancelling an event subscription.
     */
    @FunctionalInterface
    public interface Subscription {
        /** Cancels the subscription — the subscriber will no longer receive events. */
        void cancel();
    }
}
