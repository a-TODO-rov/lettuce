package io.lettuce.core.event;

import java.io.Closeable;
import java.util.function.Consumer;

/**
 * Interface for an EventBus. Events can be published over the bus that are delivered to the subscribers.
 * <p>
 * This base interface does not depend on Project Reactor, making it safe to use when Reactor is not on the classpath. For
 * reactive streaming support, use {@link #reactive()} to obtain a {@link ReactiveEventBus}.
 *
 * @author Mark Paluch
 * @since 3.4
 * @see ReactiveEventBus
 */
public interface EventBus {

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

    /**
     * Returns the reactive EventBus for {@link reactor.core.publisher.Flux}-based subscriptions.
     * <p>
     * This method requires Project Reactor (reactor-core) to be on the classpath.
     *
     * @return the {@link ReactiveEventBus}
     * @throws ReactorNotAvailableException if Project Reactor is not available on the classpath
     * @since 7.5
     */
    ReactiveEventBus reactive();

}
