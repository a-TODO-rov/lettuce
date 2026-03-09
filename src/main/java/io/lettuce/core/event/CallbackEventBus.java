package io.lettuce.core.event;

import java.io.Closeable;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import io.lettuce.core.event.jfr.EventRecorder;
import reactor.core.publisher.Flux;

/**
 * Non-reactive EventBus implementation using callbacks. Thread-safe, lock-free multicast to subscribers.
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
    public Flux<Event> get() {
        throw new UnsupportedOperationException("Reactive event streaming requires Reactor. Use subscribe(Consumer) instead.");
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
