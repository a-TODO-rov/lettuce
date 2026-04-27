package io.lettuce.core.event;

import reactor.core.publisher.Flux;

/**
 * Reactive EventBus interface providing reactive streaming support via Project Reactor.
 * <p>
 * This interface requires Reactor to be on the classpath. It provides a {@link Flux}-based subscription model for event
 * streaming.
 * <p>
 * Obtain a {@link ReactiveEventBus} via {@link EventBus#reactive()}. If Reactor is not available, that method will throw
 * {@link ReactorNotAvailableException}.
 *
 * @author Mark Paluch
 * @since 3.4
 * @see EventBus
 */
public interface ReactiveEventBus {

    /**
     * Subscribe to the event bus and {@link Event}s. The {@link Flux} drops events on backpressure to avoid contention.
     *
     * @return the observable to obtain events.
     */
    Flux<Event> get();

    /**
     * Publish a {@link Event} to the bus.
     *
     * @param event the event to publish
     */
    void publish(Event event);

}
