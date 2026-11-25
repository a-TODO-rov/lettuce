package io.lettuce.core.event;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;

import io.lettuce.core.internal.LettuceAssert;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 * Publishes events to an {@link EventBus} so that every subscriber receives them in the order their places were reserved,
 * whatever order the events are submitted in.
 * <p>
 * {@link #reserve() Reserve} a {@link Ticket} where the order is decided, for example while holding the lock that protects a
 * state change, and {@link Ticket#submit(Event) submit} the event later, after releasing the lock:
 *
 * <pre class="code">
 * EventSequencer.Ticket ticket;
 * synchronized (lock) {
 *     state = newState;
 *     ticket = sequencer.reserve();
 * }
 * try (EventSequencer.Ticket t = ticket) {
 *     t.submit(new StateChangedEvent(newState));
 * }
 * </pre>
 *
 * Use {@link #submit(Event)} to reserve and submit in one step.
 * <p>
 * Submitting never waits. When the submitted ticket's place is next, the submitting thread releases its event to
 * {@link EventBus#publish(Event)}, followed by every held-back event right after it. Otherwise the event is held back until the
 * places before it are submitted or cancelled; the thread that fills the gap releases it. The order covers the events of one
 * sequencer only. Events of other sequencers, and events published directly with {@link EventBus#publish(Event)}, are not
 * ordered against them.
 * <p>
 * Every reserved ticket must be submitted or cancelled. Until then, later tickets of the same sequencer are held back.
 * {@link Ticket#close() Closing} a ticket cancels it unless it was submitted, so try-with-resources never leaves a gap. A
 * sequencer created with an expiry also expires a ticket that is neither submitted nor cancelled in time; submitting it
 * afterwards drops the event. Held-back events are limited: once the limit is reached, further events submitted out of turn are
 * rejected and logged, except the event that all others wait for.
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
     * Placeholder for a place that was cancelled, expired or rejected instead of filled with an event.
     */
    private static final Event EMPTY_PLACE = new Event() {
    };

    private final EventBus eventBus;

    private final Timer timer;

    private final Duration expireAfter;

    private final int maxHeldBack;

    private final AtomicLong nextPlace = new AtomicLong();

    /**
     * Submitted places that are not released yet, by place.
     */
    private final ConcurrentHashMap<Long, Event> submitted = new ConcurrentHashMap<>();

    private final AtomicInteger heldBack = new AtomicInteger();

    /**
     * Number of submissions not yet handled by a release pass. The submission that moves it from {@code 0} releases.
     */
    private final AtomicInteger releasing = new AtomicInteger();

    /**
     * The place to release next. Written only by the releasing thread; read by others to never reject the event that all
     * held-back events wait for.
     */
    private volatile long nextToRelease;

    /**
     * Creates a new {@link EventSequencer} that publishes to {@code eventBus}. Tickets must be submitted or cancelled; a lost
     * ticket holds back later tickets of this sequencer.
     *
     * @param eventBus the event bus to publish to, must not be {@code null}.
     */
    public EventSequencer(EventBus eventBus) {
        this(eventBus, null, null, DEFAULT_MAX_HELD_BACK);
    }

    /**
     * Creates a new {@link EventSequencer} that publishes to {@code eventBus} and expires a ticket that is neither submitted
     * nor cancelled within {@code expireAfter}.
     *
     * @param eventBus the event bus to publish to, must not be {@code null}.
     * @param timer the timer that expires tickets, for example {@code ClientResources.timer()}, must not be {@code null}.
     * @param expireAfter how long a ticket may stay reserved before it expires, must be greater than zero.
     */
    public EventSequencer(EventBus eventBus, Timer timer, Duration expireAfter) {
        this(eventBus, timer, expireAfter, DEFAULT_MAX_HELD_BACK);
        LettuceAssert.notNull(timer, "Timer must not be null");
        LettuceAssert.notNull(expireAfter, "Expiry must not be null");
        LettuceAssert.isTrue(!expireAfter.isNegative() && !expireAfter.isZero(), "Expiry must be greater than zero");
    }

    EventSequencer(EventBus eventBus, Timer timer, Duration expireAfter, int maxHeldBack) {
        LettuceAssert.notNull(eventBus, "EventBus must not be null");
        LettuceAssert.isTrue(maxHeldBack > 0, "maxHeldBack must be greater than 0");
        this.eventBus = eventBus;
        this.timer = timer;
        this.expireAfter = expireAfter;
        this.maxHeldBack = maxHeldBack;
    }

    /**
     * Reserves the next place in this sequencer's order. The returned {@link Ticket} must be submitted or cancelled.
     *
     * @return a new {@link Ticket}.
     */
    public Ticket reserve() {

        Ticket ticket = new Ticket(this, nextPlace.getAndIncrement());

        if (timer != null) {
            ticket.expiryTask = timer.newTimeout(t -> ticket.expire(), expireAfter.toNanos(), TimeUnit.NANOSECONDS);
        }
        return ticket;
    }

    /**
     * Submits {@code event} in order with this sequencer's other events. Same as reserving a ticket and submitting it right
     * away.
     *
     * @param event the event to submit, must not be {@code null}.
     */
    public void submit(Event event) {

        LettuceAssert.notNull(event, "Event must not be null");

        try (Ticket ticket = reserve()) {
            ticket.submit(event);
        }
    }

    /**
     * Fills {@code place} with {@code event} (or {@link #EMPTY_PLACE}) and releases what is ready, unless another thread is
     * releasing already.
     */
    private void fill(long place, Event event) {

        if (event != EMPTY_PLACE && heldBack.incrementAndGet() > maxHeldBack && place != nextToRelease) {
            // over the limit, and not the event the others wait for: reject it, but keep its place so nothing waits for it
            heldBack.decrementAndGet();
            logger.warn("Rejecting event {}: {} events are already held back by an earlier ticket", event.getClass().getName(),
                    maxHeldBack);
            event = EMPTY_PLACE;
        }

        submitted.put(place, event);

        if (releasing.getAndIncrement() != 0) {
            // another thread is releasing and will release this place once its turn comes
            return;
        }

        int missed = 1;
        for (;;) {

            Event next;
            while ((next = submitted.remove(nextToRelease)) != null) {

                nextToRelease++;

                if (next == EMPTY_PLACE) {
                    continue;
                }

                heldBack.decrementAndGet();
                try {
                    eventBus.publish(next);
                } catch (Throwable t) {
                    logger.warn("Event sequencer failed to publish {}", next.getClass().getName(), t);
                }
            }

            missed = releasing.addAndGet(-missed);
            if (missed == 0) {
                return;
            }
        }
    }

    /**
     * A reserved place in an {@link EventSequencer}'s order. {@link #submit(Event) Submit} an event for it exactly once, or
     * {@link #cancel() cancel} it.
     *
     * @since 8.0
     */
    public static final class Ticket implements AutoCloseable {

        private static final int STATE_RESERVED = 0;

        private static final int STATE_SUBMITTED = 1;

        private static final int STATE_CANCELLED = 2;

        private static final int STATE_EXPIRED = 3;

        private static final AtomicIntegerFieldUpdater<Ticket> STATE = AtomicIntegerFieldUpdater.newUpdater(Ticket.class,
                "state");

        private final EventSequencer sequencer;

        private final long place;

        // Updated with STATE
        @SuppressWarnings("unused")
        private volatile int state = STATE_RESERVED;

        private volatile Timeout expiryTask;

        private Ticket(EventSequencer sequencer, long place) {
            this.sequencer = sequencer;
            this.place = place;
        }

        /**
         * Submits {@code event} for this ticket's place. If the place is next, the event is published right away; otherwise it
         * is held back until the places before it are submitted or cancelled. If this ticket has expired, the event is dropped.
         *
         * @param event the event to submit, must not be {@code null}.
         * @throws IllegalStateException if this ticket was already submitted or cancelled.
         */
        public void submit(Event event) {

            LettuceAssert.notNull(event, "Event must not be null");

            if (STATE.compareAndSet(this, STATE_RESERVED, STATE_SUBMITTED)) {
                cancelExpiry();
                sequencer.fill(place, event);
                return;
            }

            if (STATE.get(this) == STATE_EXPIRED) {
                logger.warn("Dropping event {}: its ticket expired after {}", event.getClass().getName(),
                        sequencer.expireAfter);
                return;
            }

            throw new IllegalStateException("Ticket already submitted or cancelled");
        }

        /**
         * Gives up this ticket's place, so that later tickets are no longer held back. Does nothing if the ticket was already
         * submitted, cancelled or expired.
         */
        public void cancel() {
            if (STATE.compareAndSet(this, STATE_RESERVED, STATE_CANCELLED)) {
                cancelExpiry();
                sequencer.fill(place, EMPTY_PLACE);
            }
        }

        /**
         * Cancels this ticket unless it was submitted, so that try-with-resources never leaves a gap in the order.
         */
        @Override
        public void close() {
            cancel();
        }

        private void expire() {
            if (STATE.compareAndSet(this, STATE_RESERVED, STATE_EXPIRED)) {
                logger.warn("Expiring event ticket that was neither submitted nor cancelled within {}", sequencer.expireAfter);
                sequencer.fill(place, EMPTY_PLACE);
            }
        }

        private void cancelExpiry() {
            Timeout timeout = expiryTask;
            if (timeout != null) {
                timeout.cancel();
            }
        }

    }

}
