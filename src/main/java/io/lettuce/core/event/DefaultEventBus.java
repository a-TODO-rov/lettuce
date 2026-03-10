package io.lettuce.core.event;

import java.io.Closeable;
import java.util.function.Consumer;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import io.lettuce.core.event.jfr.EventRecorder;

/**
 * Default implementation for an {@link EventBus} with reactive streaming support. Events are published using a
 * {@link Scheduler} and events are recorded through {@link EventRecorder#record(Event) EventRecorder}.
 * <p>
 * This implementation requires Reactor to be on the classpath.
 *
 * @author Mark Paluch
 * @since 3.4
 */
public class DefaultEventBus implements ReactiveEventBus {

    private final Sinks.Many<Event> bus;

    private final Scheduler scheduler;

    private final EventRecorder recorder = EventRecorder.getInstance();

    public DefaultEventBus(Scheduler scheduler) {
        this.bus = Sinks.many().multicast().directBestEffort();
        this.scheduler = scheduler;
    }

    @Override
    public Flux<Event> get() {
        return bus.asFlux().onBackpressureDrop().publishOn(scheduler);
    }

    @Override
    public Closeable subscribe(Consumer<Event> listener) {
        Disposable disposable = get().subscribe(listener::accept);
        return disposable::dispose;
    }

    @Override
    public void publish(Event event) {

        recorder.record(event);

        Sinks.EmitResult emitResult;

        while ((emitResult = bus.tryEmitNext(event)) == Sinks.EmitResult.FAIL_NON_SERIALIZED) {
            // busy-loop
        }

        if (emitResult != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
            emitResult.orThrow();
        }
    }

}
