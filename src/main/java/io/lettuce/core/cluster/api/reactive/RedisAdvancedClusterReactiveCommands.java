/*
 * Copyright 2011-Present, Redis Ltd. and Contributors
 * All rights reserved.
 *
 * Licensed under the MIT License.
 *
 * This file contains contributions from third-party contributors
 * licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.lettuce.core.cluster.api.reactive;

import java.util.Map;

import io.lettuce.core.MSetExArgs;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import io.lettuce.core.KeyValue;
import io.lettuce.core.api.reactive.RedisStringReactiveCommands;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;

/**
 * Advanced reactive and thread-safe Redis Cluster API.
 *
 * @author Mark Paluch
 * @author Jon Chambers
 * @since 5.0
 */
public interface RedisAdvancedClusterReactiveCommands<K, V> extends RedisClusterReactiveCommands<K, V> {

    /**
     * Retrieve a connection to the specified cluster node using the nodeId. Host and port are looked up in the node list. In
     * contrast to the {@link RedisAdvancedClusterReactiveCommands}, node-connections do not route commands to other cluster
     * nodes
     *
     * @param nodeId the node Id
     * @return a connection to the requested cluster node
     */
    RedisClusterReactiveCommands<K, V> getConnection(String nodeId);

    /**
     * Retrieve a connection to the specified cluster node using host and port. In contrast to the
     * {@link RedisAdvancedClusterReactiveCommands}, node-connections do not route commands to other cluster nodes. Host and
     * port connections are verified by default for cluster membership, see
     * {@link ClusterClientOptions#isValidateClusterNodeMembership()}.
     *
     * @param host the host
     * @param port the port
     * @return a connection to the requested cluster node
     */
    RedisClusterReactiveCommands<K, V> getConnection(String host, int port);

    /**
     * @return the underlying connection.
     * @since 6.2, will be removed with Lettuce 7 to avoid exposing the underlying connection.
     */
    @Deprecated
    StatefulRedisClusterConnection<K, V> getStatefulConnection();

    /**
     * Get the values of all the given keys with pipelining. Cross-slot keys will result in multiple calls to the particular
     * cluster nodes.
     *
     * @param keys the key
     * @return V array-reply list of values at the specified keys.
     * @see RedisStringReactiveCommands#mget(Object[])
     */
    Flux<KeyValue<K, V>> mget(K... keys);

}
