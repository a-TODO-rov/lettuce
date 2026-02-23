package io.lettuce.core.event;

import java.io.Closeable;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import io.lettuce.core.event.jfr.EventRecorder;

/**
 * Default implementation of {@link EventBus} that provides callback-based event subscription.
 * <p>
 * This implementation does not depend on Project Reactor, making it safe to use in environments where Reactor is not available.
 * When Reactor is available, this class wraps a {@link ReactorEventBus} to provide reactive streaming via {@link #reactive()}.
 * <p>
 * Events are recorded through {@link EventRecorder#record(Event) EventRecorder} and dispatched to all callback listeners
 * synchronously. If a {@link ReactorEventBus} is configured, events are also published to it for Flux subscribers.
 *
 * @author Mark Paluch
 * @author Aleksandar Todorov
 * @since 3.4
 */
public class DefaultEventBus implements EventBus {

    private final Set<Consumer<Event>> listeners = ConcurrentHashMap.newKeySet();

    private final EventRecorder recorder = EventRecorder.getInstance();

    private final ReactorEventBus reactorEventBus;

    /**
     * Creates a new {@link DefaultEventBus} without reactive support.
     */
    public DefaultEventBus() {
        this.reactorEventBus = null;
    }

    /**
     * Creates a new {@link DefaultEventBus} with reactive support.
     *
     * @param reactorEventBus the reactive event bus to delegate reactive operations to
     */
    public DefaultEventBus(ReactorEventBus reactorEventBus) {
        this.reactorEventBus = reactorEventBus;
    }

    @Override
    public Closeable subscribe(Consumer<Event> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    @Override
    public void publish(Event event) {
        recorder.record(event);

        // Dispatch to callback listeners
        for (Consumer<Event> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                // Log but don't propagate - isolate listener failures
            }
        }

        // Also publish to reactive bus if available
        if (reactorEventBus != null) {
            reactorEventBus.publish(event);
        }
    }

    @Override
    public ReactiveEventBus reactive() {
        if (reactorEventBus == null) {
            throw new ReactorNotAvailableException(
                    "Reactive EventBus not available. Add reactor-core to your classpath to use the reactive API.");
        }
        return reactorEventBus;
    }

}
