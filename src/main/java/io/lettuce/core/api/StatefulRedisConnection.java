package io.lettuce.core.api;

import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.push.PushListener;
import io.lettuce.core.api.reactive.ReactiveStatefulRedisConnection;
import io.lettuce.core.api.reactive.RedisReactiveCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.protocol.ConnectionWatchdog;

/**
 * A thread-safe connection to a Redis server. Multiple threads may share one {@link StatefulRedisConnection}.
 * <p>
 * A {@link ConnectionWatchdog} monitors each connection and reconnects automatically until {@link #close} is called. All
 * pending commands will be (re)sent after successful reconnection.
 * <p>
 * This interface provides access to synchronous ({@link #sync()}) and asynchronous ({@link #async()}) command APIs. For
 * reactive API access, use {@link ReactiveStatefulRedisConnection} which extends this interface and provides the
 * {@link ReactiveStatefulRedisConnection#reactive()} method.
 * <p>
 * This separation allows Lettuce to work without Project Reactor on the classpath for users who only need synchronous or
 * asynchronous APIs.
 *
 * @param <K> Key type.
 * @param <V> Value type.
 * @author Mark Paluch
 * @since 4.0
 * @see ReactiveStatefulRedisConnection
 */
public interface StatefulRedisConnection<K, V> extends StatefulConnection<K, V> {

    /**
     * @return true, if the connection is within a transaction.
     */
    boolean isMulti();

    /**
     * Returns the {@link RedisCommands} API for the current connection. Does not create a new connection.
     *
     * @return the synchronous API for the underlying connection.
     */
    RedisCommands<K, V> sync();

    /**
     * Returns the {@link RedisAsyncCommands} API for the current connection. Does not create a new connection.
     *
     * @return the asynchronous API for the underlying connection.
     */
    RedisAsyncCommands<K, V> async();

    /**
     * Returns the {@link RedisReactiveCommands} API for the current connection. Does not create a new connection.
     *
     * @return the reactive API for the underlying connection.
     * @deprecated since 7.0, use {@link ReactiveStatefulRedisConnection#reactive()} instead. Obtain a
     *             {@link ReactiveStatefulRedisConnection} via {@code RedisClient.connectReactive()} or by casting this
     *             connection. This method will be removed in Lettuce 8.0 to make Project Reactor an optional dependency.
     */
    @Deprecated
    RedisReactiveCommands<K, V> reactive();

    /**
     * Add a new {@link PushListener listener} to consume push messages.
     *
     * @param listener the listener, must not be {@code null}.
     * @since 6.0
     */
    void addListener(PushListener listener);

    /**
     * Remove an existing {@link PushListener listener}.
     *
     * @param listener the listener, must not be {@code null}.
     * @since 6.0
     */
    void removeListener(PushListener listener);

}
