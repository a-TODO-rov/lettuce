package io.lettuce.core.event;

import static io.lettuce.TestTags.UNIT_TEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.lettuce.core.Subscription;
import io.netty.util.HashedWheelTimer;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;

/**
 * Unit tests for {@link EventSequencer}.
 */
@Tag(UNIT_TEST)
class EventSequencerUnitTests {

    private final RecordingEventBus bus = new RecordingEventBus();

    private final EventExecutorGroup group = new DefaultEventExecutorGroup(2);

    @AfterEach
    void tearDown() {
        group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
    }

    @Test
    void releasesInTicketOrder() {

        EventSequencer sut = new EventSequencer(bus);
        EventSequencer.Ticket t0 = sut.reserve();
        EventSequencer.Ticket t1 = sut.reserve();
        EventSequencer.Ticket t2 = sut.reserve();

        t2.submit(new IndexedEvent(2));
        t0.submit(new IndexedEvent(0));
        t1.submit(new IndexedEvent(1));

        assertThat(bus.indexes()).containsExactly(0, 1, 2);
    }

    @Test
    void laterTicketWaitsForEarlierOne() {

        EventSequencer sut = new EventSequencer(bus);
        EventSequencer.Ticket t0 = sut.reserve();
        EventSequencer.Ticket t1 = sut.reserve();

        t1.submit(new IndexedEvent(1));
        assertThat(bus.indexes()).isEmpty();

        t0.submit(new IndexedEvent(0));
        assertThat(bus.indexes()).containsExactly(0, 1);
    }

    @Test
    void allSubscribersSeeTicketOrder() throws Exception {

        DefaultEventBus eventBus = new DefaultEventBus(group);
        List<Integer> first = new CopyOnWriteArrayList<>();
        List<Integer> second = new CopyOnWriteArrayList<>();
        CountDownLatch received = new CountDownLatch(6);
        eventBus.subscribe(IndexedEvent.class, e -> {
            first.add(e.index);
            received.countDown();
        });
        eventBus.subscribe(IndexedEvent.class, e -> {
            second.add(e.index);
            received.countDown();
        });

        EventSequencer sut = new EventSequencer(eventBus);
        EventSequencer.Ticket t0 = sut.reserve();
        EventSequencer.Ticket t1 = sut.reserve();
        EventSequencer.Ticket t2 = sut.reserve();

        Thread late = new Thread(() -> t0.submit(new IndexedEvent(0)));
        t2.submit(new IndexedEvent(2));
        t1.submit(new IndexedEvent(1));
        late.start();
        late.join(TimeUnit.SECONDS.toMillis(1));

        assertThat(received.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(first).containsExactly(0, 1, 2);
        assertThat(second).containsExactly(0, 1, 2);
    }

    @Test
    void cancelledTicketLeavesNoGap() {

        EventSequencer sut = new EventSequencer(bus);
        EventSequencer.Ticket t0 = sut.reserve();
        EventSequencer.Ticket t1 = sut.reserve();

        t1.submit(new IndexedEvent(1));
        t0.cancel();

        assertThat(bus.indexes()).containsExactly(1);
    }

    @Test
    void closeAfterSubmitDoesNothing() {

        EventSequencer sut = new EventSequencer(bus);

        try (EventSequencer.Ticket ticket = sut.reserve()) {
            ticket.submit(new IndexedEvent(0));
        }

        assertThat(bus.indexes()).containsExactly(0);
    }

    @Test
    void ticketCannotBeSubmittedTwice() {

        EventSequencer sut = new EventSequencer(bus);
        EventSequencer.Ticket ticket = sut.reserve();
        ticket.submit(new IndexedEvent(0));

        assertThatThrownBy(() -> ticket.submit(new IndexedEvent(1))).isInstanceOf(IllegalStateException.class);
        assertThat(bus.indexes()).containsExactly(0);
    }

    @Test
    void submitReservesAndSubmitsInOneStep() {

        EventSequencer sut = new EventSequencer(bus);
        EventSequencer.Ticket earlier = sut.reserve();

        sut.submit(new IndexedEvent(1));
        assertThat(bus.indexes()).isEmpty();

        earlier.submit(new IndexedEvent(0));
        assertThat(bus.indexes()).containsExactly(0, 1);
    }

    @Test
    void expiredTicketLeavesNoGap() throws Exception {

        HashedWheelTimer timer = new HashedWheelTimer(10, TimeUnit.MILLISECONDS);
        try {
            EventSequencer sut = new EventSequencer(bus, timer, Duration.ofMillis(50));
            EventSequencer.Ticket lost = sut.reserve();
            sut.submit(new IndexedEvent(1));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (bus.indexes().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(bus.indexes()).containsExactly(1);

            lost.submit(new IndexedEvent(0)); // after expiry: dropped, not an error
            assertThat(bus.indexes()).containsExactly(1);
        } finally {
            timer.stop();
        }
    }

    @Test
    void heldBackEventsAreLimited() {

        EventSequencer sut = new EventSequencer(bus, null, null, 2);
        EventSequencer.Ticket t0 = sut.reserve();
        EventSequencer.Ticket t1 = sut.reserve();
        EventSequencer.Ticket t2 = sut.reserve();
        EventSequencer.Ticket t3 = sut.reserve();

        t1.submit(new IndexedEvent(1));
        t2.submit(new IndexedEvent(2));
        t3.submit(new IndexedEvent(3)); // over the limit: rejected
        t0.submit(new IndexedEvent(0)); // the event all others wait for: never rejected

        assertThat(bus.indexes()).containsExactly(0, 1, 2);
    }

    @Test
    void failingPublishDoesNotStopLaterEvents() {

        EventBus failing = new RecordingEventBus() {

            @Override
            public void publish(Event event) {
                if (((IndexedEvent) event).index == 0) {
                    throw new IllegalStateException("boom");
                }
                super.publish(event);
            }

        };
        RecordingEventBus recorded = (RecordingEventBus) failing;
        EventSequencer sut = new EventSequencer(failing);

        sut.submit(new IndexedEvent(0));
        sut.submit(new IndexedEvent(1));

        assertThat(recorded.indexes()).containsExactly(1);
    }

    @Test
    void closeCancelsUnsubmittedTicket() {

        EventSequencer sut = new EventSequencer(bus);
        EventSequencer.Ticket t1;

        try (EventSequencer.Ticket t0 = sut.reserve()) {
            t1 = sut.reserve();
            // t0 is never submitted: closing it must not leave a gap
        }
        t1.submit(new IndexedEvent(1));

        assertThat(bus.indexes()).containsExactly(1);
    }

    @Test
    void cancelAfterSubmitDoesNothing() {

        EventSequencer sut = new EventSequencer(bus);
        EventSequencer.Ticket ticket = sut.reserve();
        ticket.submit(new IndexedEvent(0));
        ticket.cancel();

        assertThat(bus.indexes()).containsExactly(0);
    }

    static class RecordingEventBus implements EventBus {

        private final List<Event> published = new CopyOnWriteArrayList<>();

        @Override
        public void publish(Event event) {
            published.add(event);
        }

        @Override
        public Subscription subscribe(Consumer<Event> listener) {
            return () -> {
            };
        }

        List<Integer> indexes() {
            List<Integer> indexes = new CopyOnWriteArrayList<>();
            for (Event event : published) {
                indexes.add(((IndexedEvent) event).index);
            }
            return indexes;
        }

    }

    static class IndexedEvent implements Event {

        final int index;

        IndexedEvent(int index) {
            this.index = index;
        }

    }

}
