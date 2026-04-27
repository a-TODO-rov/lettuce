package io.lettuce.core;

import java.io.Closeable;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Extension of {@link RedisCredentialsProvider} that exposes credentials through Project Reactor types ({@link Mono} and
 * {@link Flux}).
 * <p>
 * Implementations of this interface provide credentials reactively. The base {@link RedisCredentialsProvider} API
 * ({@link #resolveCredentialsAsync()} and {@link #subscribeToCredentials(Consumer)}) is bridged to the reactive methods by
 * default implementations, so reactive implementations typically only need to implement {@link #resolveCredentials()} and
 * optionally {@link #credentials()}.
 *
 * @author Mark Paluch
 * @since 7.0
 */
@FunctionalInterface
public interface ReactiveRedisCredentialsProvider extends RedisCredentialsProvider {

    /**
     * Returns {@link RedisCredentials} that can be used to authorize a Redis connection.
     *
     * @return a {@link Mono} emitting {@link RedisCredentials} that can be used to authorize a Redis connection.
     */
    Mono<RedisCredentials> resolveCredentials();

    /**
     * Returns a {@link Flux} emitting {@link RedisCredentials} that can be used to authorize a Redis connection.
     * <p>
     * For implementations that support streaming credentials (as indicated by {@link #supportsStreaming()} returning
     * {@code true}), this method can emit multiple credentials over time, typically based on external events like token renewal
     * or rotation.
     * <p>
     * For implementations that do not support streaming credentials (where {@link #supportsStreaming()} returns {@code false}),
     * this method throws an {@link UnsupportedOperationException} by default.
     *
     * @return a {@link Flux} emitting {@link RedisCredentials}, or throws an exception if streaming is not supported.
     * @throws UnsupportedOperationException if the provider does not support streaming credentials.
     */
    default Flux<RedisCredentials> credentials() {
        throw new UnsupportedOperationException("Streaming credentials are not supported by this provider.");
    }

    @Override
    default CompletionStage<RedisCredentials> resolveCredentialsAsync() {
        return resolveCredentials().toFuture();
    }

    @Override
    default Closeable subscribeToCredentials(Consumer<RedisCredentials> listener) {

        if (!supportsStreaming()) {
            throw new UnsupportedOperationException("Streaming credentials are not supported by this provider.");
        }

        Disposable disposable = credentials().subscribe(listener);
        return disposable::dispose;
    }

}
