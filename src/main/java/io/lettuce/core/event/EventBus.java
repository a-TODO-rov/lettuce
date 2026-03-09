package io.lettuce.core.event;

import java.io.Closeable;
import java.util.function.Consumer;

import reactor.core.publisher.Flux;

/**
 * Interface for an EventBus. Events can be published over the bus that are delivered to the subscribers.
 *
 * @author Mark Paluch
 * @since 3.4
 */
public interface EventBus {

    /**
     * Subscribe to the event bus and {@link Event}s. The {@link Flux} drops events on backpressure to avoid contention.
     *
     * @return the observable to obtain events.
     */
    Flux<Event> get();

    /**
     * Subscribe to events using a callback. Returns a {@link Closeable} to unsubscribe.
     *
     * @param listener the event consumer
     * @return {@link Closeable} to cancel the subscription
     * @since 7.5
     */
    Closeable subscribe(Consumer<Event> listener);

    /**
     * Publish a {@link Event} to the bus.
     *
     * @param event the event to publish
     */
    void publish(Event event);

}
