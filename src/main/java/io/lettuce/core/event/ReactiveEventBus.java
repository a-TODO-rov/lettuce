package io.lettuce.core.event;

import reactor.core.publisher.Flux;

import java.io.Closeable;
import java.util.function.Consumer;

/**
 * Reactive extension of {@link EventBus} that provides reactive streaming support via Project Reactor.
 * <p>
 * This interface requires Reactor to be on the classpath. If Reactor is not available, use the base {@link EventBus} interface
 * with callback-based subscription via {@link #subscribe(java.util.function.Consumer)}.
 *
 * @author Mark Paluch
 * @since 7.5
 * @see EventBus
 */
public interface ReactiveEventBus extends EventBus {

    /**
     * Subscribe to the event bus and {@link Event}s. The {@link Flux} drops events on backpressure to avoid contention.
     *
     * @return the observable to obtain events.
     */
    Flux<Event> get();

}
