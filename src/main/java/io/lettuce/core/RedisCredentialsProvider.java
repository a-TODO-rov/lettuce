package io.lettuce.core;

import java.io.Closeable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.lettuce.core.internal.LettuceAssert;

/**
 * Interface for loading {@link RedisCredentials} that are used for authentication. A commonly-used implementation is
 * {@link StaticCredentialsProvider} for a fixed set of credentials.
 * <p>
 * Credentials are requested by the driver after connecting to the server. Therefore, credential retrieval is subject to
 * complete within the configured connection creation timeout to avoid connection failures.
 * <p>
 * Since 7.0 this interface is Reactor-free. Implementations that want to expose credentials through Project Reactor types
 * ({@code Mono}/{@code Flux}) should implement {@link ReactiveRedisCredentialsProvider} instead, which extends this interface.
 *
 * @author Mark Paluch
 * @since 6.2
 */
@FunctionalInterface
public interface RedisCredentialsProvider {

    /**
     * Returns {@link RedisCredentials} that can be used to authorize a Redis connection. Each implementation of
     * {@code RedisCredentialsProvider} can choose its own strategy for loading credentials. For example, an implementation
     * might load credentials from an existing key management system, or load new credentials when credentials are rotated. If
     * an error occurs during the loading of credentials or credentials could not be found, a runtime exception will be raised.
     *
     * @return a {@link CompletionStage} emitting {@link RedisCredentials} that can be used to authorize a Redis connection.
     * @since 7.0
     */
    CompletionStage<RedisCredentials> resolveCredentialsAsync();

    /**
     * Creates a new {@link RedisCredentialsProvider} from a given {@link Supplier}.
     *
     * @param supplier must not be {@code null}.
     * @return the {@link RedisCredentials} using credentials from {@link Supplier}.
     */
    static RedisCredentialsProvider from(Supplier<RedisCredentials> supplier) {

        LettuceAssert.notNull(supplier, "Supplier must not be null");

        return () -> CompletableFuture.completedFuture(supplier.get());
    }

    /**
     * Some implementations of the {@link RedisCredentialsProvider} may support streaming new credentials, based on some event
     * that originates outside the driver. In this case they should indicate that so the {@link RedisAuthenticationHandler} is
     * able to process these new credentials.
     *
     * @return whether the {@link RedisCredentialsProvider} supports streaming credentials.
     */
    default boolean supportsStreaming() {
        return false;
    }

    /**
     * Subscribes to a stream of {@link RedisCredentials} updates. For implementations that support streaming credentials (as
     * indicated by {@link #supportsStreaming()} returning {@code true}), the provided {@code listener} is invoked whenever new
     * credentials become available, typically based on external events like token renewal or rotation.
     * <p>
     * For implementations that do not support streaming credentials (where {@link #supportsStreaming()} returns {@code false}),
     * this method throws an {@link UnsupportedOperationException} by default.
     *
     * @param listener the consumer that accepts newly emitted credentials. Must not be {@code null}.
     * @return a {@link Closeable} that can be used to cancel the subscription.
     * @throws UnsupportedOperationException if the provider does not support streaming credentials.
     * @since 7.0
     */
    default Closeable subscribeToCredentials(Consumer<RedisCredentials> listener) {
        throw new UnsupportedOperationException("Streaming credentials are not supported by this provider.");
    }

    /**
     * Extension to {@link RedisCredentialsProvider} that resolves credentials immediately without the need to defer the
     * credential resolution.
     */
    @FunctionalInterface
    interface ImmediateRedisCredentialsProvider extends RedisCredentialsProvider {

        @Override
        default CompletionStage<RedisCredentials> resolveCredentialsAsync() {
            return CompletableFuture.completedFuture(resolveCredentialsNow());
        }

        /**
         * Returns {@link RedisCredentials} that can be used to authorize a Redis connection. Each implementation of
         * {@code RedisCredentialsProvider} can choose its own strategy for loading credentials. For example, an implementation
         * might load credentials from an existing key management system, or load new credentials when credentials are rotated.
         * If an error occurs during the loading of credentials or credentials could not be found, a runtime exception will be
         * raised.
         *
         * @return the resolved {@link RedisCredentials} that can be used to authorize a Redis connection.
         */
        RedisCredentials resolveCredentialsNow();

    }

}
