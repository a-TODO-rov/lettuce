package io.lettuce.core.api;

import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.push.PushListener;
import io.lettuce.core.api.reactive.RedisReactiveCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.protocol.ConnectionWatchdog;

/**
 * A thread-safe connection to a Redis server. Multiple threads may share one {@link StatefulRedisConnection}.
 * <p>
 * A {@link ConnectionWatchdog} monitors each connection and reconnects automatically until {@link #close} is called. All
 * pending commands will be (re)sent after successful reconnection.
 * <p>
 * This interface provides access to synchronous ({@link #sync()}), asynchronous ({@link #async()}), and reactive
 * ({@link #reactive()}) command APIs.
 * <p>
 * The reactive API requires Project Reactor on the classpath. Calling {@link #reactive()} without Reactor will throw an
 * {@link IllegalStateException}. Users who only need synchronous or asynchronous APIs can use Lettuce without Reactor.
 *
 * @param <K> Key type.
 * @param <V> Value type.
 * @author Mark Paluch
 * @since 4.0
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
     * <p>
     * This method requires Project Reactor (reactor-core) to be on the classpath.
     *
     * @return the reactive API for the underlying connection.
     * @throws IllegalStateException if Project Reactor is not available on the classpath.
     */
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
