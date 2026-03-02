package io.lettuce.core.masterreplica;

import io.lettuce.core.api.reactive.ReactiveStatefulRedisConnection;
import io.lettuce.core.api.reactive.RedisReactiveCommands;

/**
 * A reactive extension of {@link StatefulRedisMasterReplicaConnection} that provides access to the reactive command API.
 * <p>
 * This interface is part of the dual interface pattern that makes Project Reactor an optional dependency. Users who only need
 * synchronous or asynchronous APIs can use {@link StatefulRedisMasterReplicaConnection} without requiring Reactor on the
 * classpath. Users who need reactive APIs should use this interface.
 *
 * @param <K> Key type.
 * @param <V> Value type.
 * @author Aleksandar Todorov
 * @since 7.0
 * @see StatefulRedisMasterReplicaConnection
 * @see ReactiveStatefulRedisConnection
 */
public interface ReactiveStatefulRedisMasterReplicaConnection<K, V>
        extends StatefulRedisMasterReplicaConnection<K, V>, ReactiveStatefulRedisConnection<K, V> {

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

