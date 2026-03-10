package io.lettuce.core.event;

import java.io.Closeable;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import io.lettuce.core.event.jfr.EventRecorder;

/**
 * Simple {@link EventBus} implementation that only records events without reactive streaming. This is used when Reactor is not
 * on the classpath.
 * <p>
 * This implementation does not depend on Reactor, making it safe to use in environments where Reactor is not available.
 *
 * @since 7.5
 */
public class SimpleEventBus implements EventBus {

    private final Set<Consumer<Event>> listeners = ConcurrentHashMap.newKeySet();

    private final EventRecorder recorder = EventRecorder.getInstance();

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
