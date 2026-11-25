package io.lettuce.core.event;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.lettuce.core.internal.LettuceAssert;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 * Publishes events to an {@link EventBus} so that every subscriber receives them in the order their tickets were reserved,
 * whatever order the tickets are published in.
 * <p>
 * Reserve a {@link Ticket} where the order is decided, for example while holding the lock that protects a state change, and
 * publish the event later, after releasing the lock:
 *
 * <pre class="code">
 * EventSequencer.Ticket ticket;
 * synchronized (lock) {
 *     state = newState;
 *     ticket = sequencer.reserve();
 * }
 * try (EventSequencer.Ticket t = ticket) {
 *     t.publish(new StateChangedEvent(newState));
 * }
 * </pre>
 *
 * Use {@link #publish(Event)} to reserve and publish in one step. Publishing never waits: a ticket published before an earlier
 * one is held back until the earlier ticket is published or skipped, and the thread that completes the earlier ticket passes
 * both to the {@link EventBus}. The order covers the events of one sequencer only. Events of other sequencers, and events
 * published directly with {@link EventBus#publish(Event)}, are not ordered against them.
 * <p>
 * Every reserved ticket must be published or closed. Until then, later tickets of the same sequencer are held back. Closing a
 * ticket that was not published skips it. A sequencer created with an abandon timeout also skips a ticket that is neither
 * published nor closed within that time. Events held back are limited; once the limit is reached, further events published out
 * of turn are dropped and logged.
 * <p>
 * Events reach subscribers in ticket order as long as {@link EventBus#publish(Event)} hands events to its subscribers in the
 * order it is called, as {@link DefaultEventBus} does.
 *
 * @since 8.0
 */
public final class EventSequencer {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(EventSequencer.class);

    static final int DEFAULT_MAX_HELD_BACK = 1024;

    /**
     * Placeholder for a ticket that was skipped instead of published.
     */
    private static final Event SKIPPED = new Event() {
    };

    private static final int NEW = 0;

    private static final int USED = 1;

    private static final int ABANDONED = 2;

    private final EventBus eventBus;

    private final Timer timer;

    private final Duration abandonTimeout;

    private final int maxHeldBack;

    private final AtomicLong nextTicket = new AtomicLong();

    /**
     * Completed tickets that are not passed to the {@link EventBus} yet, by sequence.
     */
    private final ConcurrentHashMap<Long, Event> completed = new ConcurrentHashMap<>();

    private final AtomicInteger heldBack = new AtomicInteger();

    /**
     * Number of completions not yet handled by a forwarding pass. The completion that moves it from {@code 0} forwards.
     */
    private final AtomicInteger forwarding = new AtomicInteger();

    /**
     * The sequence to pass to the {@link EventBus} next. Written only by the thread that forwards; read by others to never drop
     * the event that all held-back events wait for.
     */
    private volatile long nextToForward;

    /**
     * Creates a new {@link EventSequencer} that publishes to {@code eventBus}. Tickets must be published or closed; a lost
     * ticket holds back later tickets of this sequencer.
     *
     * @param eventBus the event bus to publish to, must not be {@code null}.
     */
    public EventSequencer(EventBus eventBus) {
        this(eventBus, null, null, DEFAULT_MAX_HELD_BACK);
    }

    /**
     * Creates a new {@link EventSequencer} that publishes to {@code eventBus} and skips a ticket that is neither published nor
     * closed within {@code abandonTimeout}.
     *
     * @param eventBus the event bus to publish to, must not be {@code null}.
     * @param timer the timer that skips abandoned tickets, for example {@code ClientResources.timer()}, must not be
     *        {@code null}.
     * @param abandonTimeout how long a ticket may stay reserved before it is skipped, must be greater than zero.
     */
    public EventSequencer(EventBus eventBus, Timer timer, Duration abandonTimeout) {
        this(eventBus, timer, abandonTimeout, DEFAULT_MAX_HELD_BACK);
        LettuceAssert.notNull(timer, "Timer must not be null");
        LettuceAssert.notNull(abandonTimeout, "Abandon timeout must not be null");
        LettuceAssert.isTrue(!abandonTimeout.isNegative() && !abandonTimeout.isZero(),
                "Abandon timeout must be greater than zero");
    }

    EventSequencer(EventBus eventBus, Timer timer, Duration abandonTimeout, int maxHeldBack) {
        LettuceAssert.notNull(eventBus, "EventBus must not be null");
        LettuceAssert.isTrue(maxHeldBack > 0, "maxHeldBack must be greater than 0");
        this.eventBus = eventBus;
        this.timer = timer;
        this.abandonTimeout = abandonTimeout;
        this.maxHeldBack = maxHeldBack;
    }

    /**
     * Reserves the next place in this sequencer's order. The returned {@link Ticket} must be published or closed.
     *
     * @return a new {@link Ticket}.
     */
    public Ticket reserve() {

        Ticket ticket = new Ticket(nextTicket.getAndIncrement());

        if (timer != null) {
            ticket.abandonTask = timer.newTimeout(t -> ticket.abandon(), abandonTimeout.toNanos(), TimeUnit.NANOSECONDS);
        }
        return ticket;
    }

    /**
     * Publishes {@code event} in order with this sequencer's other events. Same as reserving a ticket and publishing it right
     * away.
     *
     * @param event the event to publish, must not be {@code null}.
     */
    public void publish(Event event) {

        LettuceAssert.notNull(event, "Event must not be null");

        try (Ticket ticket = reserve()) {
            ticket.publish(event);
        }
    }

    private void complete(long sequence, Event event) {

        if (event != SKIPPED && heldBack.incrementAndGet() > maxHeldBack && sequence != nextToForward) {
            // over the limit, and not the event the others wait for: drop it, but keep its place so nothing waits for it
            heldBack.decrementAndGet();
            logger.warn("Dropping event {}: {} events are already held back by an unpublished earlier ticket",
                    event.getClass().getName(), maxHeldBack);
            event = SKIPPED;
        }

        completed.put(sequence, event);

        if (forwarding.getAndIncrement() != 0) {
            // another thread is forwarding and will forward this ticket once its turn comes
            return;
        }

        int missed = 1;
        for (;;) {

            Event next;
            while ((next = completed.remove(nextToForward)) != null) {

                nextToForward++;

                if (next == SKIPPED) {
                    continue;
                }

                heldBack.decrementAndGet();
                try {
                    eventBus.publish(next);
                } catch (Throwable t) {
                    logger.warn("Event sequencer failed to publish {}", next.getClass().getName(), t);
                }
            }

            missed = forwarding.addAndGet(-missed);
            if (missed == 0) {
                return;
            }
        }
    }

    /**
     * A reserved place in an {@link EventSequencer}'s order. Publish it exactly once with {@link #publish(Event)}, or close it
     * to skip it.
     *
     * @since 8.0
     */
    public final class Ticket implements AutoCloseable {

        private final long sequence;

        private final AtomicInteger state = new AtomicInteger(NEW);

        private volatile Timeout abandonTask;

        private Ticket(long sequence) {
            this.sequence = sequence;
        }

        /**
         * Publishes {@code event} at this ticket's place in the order.
         *
         * @param event the event to publish, must not be {@code null}.
         * @throws IllegalStateException if this ticket was already published or closed.
         */
        public void publish(Event event) {

            LettuceAssert.notNull(event, "Event must not be null");

            if (state.compareAndSet(NEW, USED)) {
                cancelAbandonTimeout();
                complete(sequence, event);
                return;
            }

            if (state.get() == ABANDONED) {
                logger.warn("Dropping event {}: its ticket was skipped after {}", event.getClass().getName(), abandonTimeout);
                return;
            }

            throw new IllegalStateException("Ticket already published or closed");
        }

        /**
         * Skips this ticket if it was not published, so that later tickets are no longer held back. Does nothing if the ticket
         * was already published or closed.
         */
        @Override
        public void close() {
            if (state.compareAndSet(NEW, USED)) {
                cancelAbandonTimeout();
                complete(sequence, SKIPPED);
            }
        }

        private void abandon() {
            if (state.compareAndSet(NEW, ABANDONED)) {
                logger.warn("Skipping event ticket that was neither published nor closed within {}", abandonTimeout);
                complete(sequence, SKIPPED);
            }
        }

        private void cancelAbandonTimeout() {
            Timeout timeout = abandonTask;
            if (timeout != null) {
                timeout.cancel();
            }
        }

    }

}
