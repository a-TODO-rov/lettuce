package io.lettuce.core.event;

import org.reactivestreams.Subscription;

import io.lettuce.core.slimstreams.SimpleSubscriber.NextHandler;

/**
 * Interface for an EventBus. Events can be published over the bus that are delivered to the subscribers.
 * <p>
 * This implementation is Reactor-free and uses SlimStreams (custom Reactive Streams implementation).
 *
 * @author Mark Paluch
 * @author Aleksandar Todorov
 * @since 3.4
 */
public interface EventBus {

    /**
     * Subscribe to the event bus for {@link Event}s of a specific type.
     *
     * @param eventType the event type to subscribe to
     * @param subscriber the event handler
     * @param <T> the event type
     * @return the subscription to cancel the subscription
     * @since 7.5
     */
    <T extends Event> Subscription subscribe(Class<T> eventType, NextHandler<T> subscriber);

    /**
     * Publish a {@link Event} to the bus.
     *
     * @param event the event to publish
     */
    void publish(Event event);

}
