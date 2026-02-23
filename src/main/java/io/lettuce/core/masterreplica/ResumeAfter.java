package io.lettuce.core.masterreplica;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import io.lettuce.core.api.AsyncCloseable;

/**
 * Utility to close an {@link AsyncCloseable} and then emit a value or error through a {@link CompletableFuture}.
 *
 * @author Mark Paluch
 */
class ResumeAfter {

    private static final AtomicIntegerFieldUpdater<ResumeAfter> UPDATER = AtomicIntegerFieldUpdater
            .newUpdater(ResumeAfter.class, "closed");

    private final AsyncCloseable closeable;

    private static final int ST_OPEN = 0;

    private static final int ST_CLOSED = 1;

    @SuppressWarnings("unused")
    private volatile int closed = ST_OPEN;

    private ResumeAfter(AsyncCloseable closeable) {
        this.closeable = closeable;
    }

    public static ResumeAfter close(AsyncCloseable closeable) {
        return new ResumeAfter(closeable);
    }

    public <T> CompletableFuture<T> thenEmit(T value) {

        CompletableFuture<T> result = new CompletableFuture<>();

        if (firstCloseLatch()) {
            closeable.closeAsync().whenComplete((v, ex) -> result.complete(value));
        } else {
            result.complete(value);
        }

        return result;
    }

    public <T> CompletableFuture<T> thenError(Throwable t) {

        CompletableFuture<T> result = new CompletableFuture<>();

        if (firstCloseLatch()) {
            closeable.closeAsync().whenComplete((v, ex) -> result.completeExceptionally(t));
        } else {
            result.completeExceptionally(t);
        }

        return result;
    }

    private boolean firstCloseLatch() {
        return UPDATER.compareAndSet(ResumeAfter.this, ST_OPEN, ST_CLOSED);
    }

}
