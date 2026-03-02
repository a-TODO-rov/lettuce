package io.lettuce.core.event;

import io.lettuce.core.event.jfr.EventRecorder;

/**
 * Simple {@link EventBus} implementation that only records events without reactive streaming. This is used when Reactor is not
 * on the classpath. Subscribing to events is not supported without Reactor.
 *
 * @since 7.5
 */
public class SimpleEventBus implements EventBus {

    private final EventRecorder recorder = EventRecorder.getInstance();

    @Override
    public reactor.core.publisher.Flux<Event> get() {
        throw new UnsupportedOperationException("Reactive event streaming requires Reactor on the classpath. "
                + "Add io.projectreactor:reactor-core dependency.");
    }

    @Override
    public void publish(Event event) {
        recorder.record(event);
    }

}
