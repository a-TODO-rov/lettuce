package io.lettuce.core.event;

import java.io.Closeable;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import io.lettuce.core.event.jfr.EventRecorder;

/**
 * Simple {@link EventBus} implementation that only records events without reactive streaming. This is used when Reactor is not
 * on the classpath. Subscribing to events is not supported without Reactor.
 *
 * @since 7.5
 */
public class SimpleEventBus implements EventBus {

    private final Set<Consumer<Event>> listeners = ConcurrentHashMap.newKeySet();

    private final EventRecorder recorder = EventRecorder.getInstance();

    @Override
    public reactor.core.publisher.Flux<Event> get() {
        throw new UnsupportedOperationException("Reactive event streaming requires Reactor on the classpath. "
                + "Add io.projectreactor:reactor-core dependency.");
    }

    @Override
    public Closeable subscribe(Consumer<Event> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    @Override
    public void publish(Event event) {
        recorder.record(event);
        for (Consumer<Event> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                // Log but don't propagate - isolate listener failures
            }
        }
    }

}
