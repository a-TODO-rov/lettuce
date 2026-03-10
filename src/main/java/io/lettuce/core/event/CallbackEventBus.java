package io.lettuce.core.event;

import java.io.Closeable;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import io.lettuce.core.event.jfr.EventRecorder;

/**
 * Non-reactive EventBus implementation using callbacks. Thread-safe, lock-free multicast to subscribers.
 * <p>
 * This implementation does not depend on Reactor, making it safe to use in environments where Reactor is not available. Events
 * are dispatched asynchronously using the provided {@link Executor}.
 *
 * @author Mark Paluch
 * @since 7.5
 */
public class CallbackEventBus implements EventBus {

    private final Set<Consumer<Event>> listeners = ConcurrentHashMap.newKeySet();

    private final Executor executor;

    private final EventRecorder recorder = EventRecorder.getInstance();

    public CallbackEventBus(Executor executor) {
        this.executor = executor;
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
            executor.execute(() -> {
                try {
                    listener.accept(event);
                } catch (Exception e) {
                    // Log but don't propagate - isolate listener failures
                }
            });
        }
    }

}
