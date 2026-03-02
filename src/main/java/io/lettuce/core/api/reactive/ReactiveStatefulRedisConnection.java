package io.lettuce.core.api.reactive;

import io.lettuce.core.api.StatefulRedisConnection;

/**
 * A reactive extension of {@link StatefulRedisConnection} that provides access to the reactive command API.
 * <p>
 * This interface is part of the dual interface pattern that makes Project Reactor an optional dependency. Users who only need
 * synchronous or asynchronous APIs can use {@link StatefulRedisConnection} without requiring Reactor on the classpath. Users
 * who need reactive APIs should use this interface.
 * <p>
 * To obtain a {@link ReactiveStatefulRedisConnection}:
 * <ul>
 * <li>Use {@code RedisClient.connectReactive()} to get a typed reactive connection directly</li>
 * <li>Cast from {@link StatefulRedisConnection} when you know the implementation supports reactive</li>
 * </ul>
 *
 * <h2>Example Usage</h2>
 * 
 * <pre>
 * 
 * {
 *     &#64;code
 *     // Option 1: Use connectReactive() for typed access
 *     ReactiveStatefulRedisConnection<String, String> conn = client.connectReactive();
 *     RedisReactiveCommands<String, String> reactive = conn.reactive();
 *
 *     // Option 2: Cast from connect() when reactive is needed
 *     ReactiveStatefulRedisConnection<String, String> conn = (ReactiveStatefulRedisConnection<String, String>) client
 *             .connect();
 *     RedisReactiveCommands<String, String> reactive = conn.reactive();
 * }
 * </pre>
 *
 * @param <K> Key type.
 * @param <V> Value type.
 * @author Aleksandar Todorov
 * @since 7.0
 * @see StatefulRedisConnection
 * @see RedisReactiveCommands
 */
public interface ReactiveStatefulRedisConnection<K, V> extends StatefulRedisConnection<K, V> {

    /**
     * Returns the {@link RedisReactiveCommands} API for the current connection. Does not create a new connection.
     * <p>
     * This method requires Project Reactor (reactor-core) to be on the classpath.
     *
     * @return the reactive API for the underlying connection.
     */
    @Override
    RedisReactiveCommands<K, V> reactive();

}
