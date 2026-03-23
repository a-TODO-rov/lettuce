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
package io.lettuce.core.cluster.api.async;

import java.util.List;
import java.util.function.Predicate;

import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.async.RedisStringAsyncCommands;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.api.NodeSelectionSupport;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;

/**
 * Advanced asynchronous and thread-safe Redis Cluster API.
 *
 * @author Mark Paluch
 * @author Jon Chambers
 * @since 4.0
 */
public interface RedisAdvancedClusterAsyncCommands<K, V> extends RedisClusterAsyncCommands<K, V> {

    /**
     * Retrieve a connection to the specified cluster node using the nodeId. Host and port are looked up in the node list.
     *
     * In contrast to the {@link RedisAdvancedClusterAsyncCommands}, node-connections do not route commands to other cluster
     * nodes
     *
     * @param nodeId the node Id
     * @return a connection to the requested cluster node
     */
    RedisClusterAsyncCommands<K, V> getConnection(String nodeId);

    /**
     * Retrieve a connection to the specified cluster node using host and port. In contrast to the
     * {@link RedisAdvancedClusterAsyncCommands}, node-connections do not route commands to other cluster nodes. Host and port
     * connections are verified by default for cluster membership, see
     * {@link ClusterClientOptions#isValidateClusterNodeMembership()}.
     *
     * @param host the host
     * @param port the port
     * @return a connection to the requested cluster node
     */
    RedisClusterAsyncCommands<K, V> getConnection(String host, int port);

    /**
     * @return the underlying connection.
     * @since 6.2, will be removed with Lettuce 7 to avoid exposing the underlying connection.
     */
    @Deprecated
    StatefulRedisClusterConnection<K, V> getStatefulConnection();

    /**
     * Select all upstream nodes.
     *
     * @return API with asynchronous executed commands on a selection of upstream cluster nodes.
     * @deprecated since 6.0 in favor of {@link #upstream()}.
     */
    default AsyncNodeSelection<K, V> masters() {
        return nodes(redisClusterNode -> redisClusterNode.is(RedisClusterNode.NodeFlag.UPSTREAM));
    }

    /**
     * Select all upstream nodes.
     *
     * @return API with asynchronous executed commands on a selection of upstream cluster nodes.
     * @since 6.0
     */
    default AsyncNodeSelection<K, V> upstream() {
        return nodes(redisClusterNode -> redisClusterNode.is(RedisClusterNode.NodeFlag.UPSTREAM));
    }

    /**
     * Select all replicas.
     *
     * @return API with asynchronous executed commands on a selection of replica cluster nodes.
     * @deprecated since 5.2, use {@link #replicas()}
     */
    @Deprecated
    default AsyncNodeSelection<K, V> slaves() {
        return readonly(redisClusterNode -> redisClusterNode.is(RedisClusterNode.NodeFlag.SLAVE));
    }

    /**
     * Select all replicas.
     *
     * @param predicate Predicate to filter nodes
     * @return API with asynchronous executed commands on a selection of replica cluster nodes.
     * @deprecated use {@link #replicas(Predicate)}
     */
    @Deprecated
    default AsyncNodeSelection<K, V> slaves(Predicate<RedisClusterNode> predicate) {
        return readonly(
                redisClusterNode -> predicate.test(redisClusterNode) && redisClusterNode.is(RedisClusterNode.NodeFlag.REPLICA));
    }

    /**
     * Select all replicas.
     *
     * @return API with asynchronous executed commands on a selection of replica cluster nodes.
     * @since 5.2
     */
    default AsyncNodeSelection<K, V> replicas() {
        return readonly(redisClusterNode -> redisClusterNode.is(RedisClusterNode.NodeFlag.REPLICA));
    }

    /**
     * Select all replicas.
     *
     * @param predicate Predicate to filter nodes
     * @return API with asynchronous executed commands on a selection of replica cluster nodes.
     * @since 5.2
     */
    default AsyncNodeSelection<K, V> replicas(Predicate<RedisClusterNode> predicate) {
        return readonly(
                redisClusterNode -> predicate.test(redisClusterNode) && redisClusterNode.is(RedisClusterNode.NodeFlag.REPLICA));
    }

    /**
     * Select all known cluster nodes.
     *
     * @return API with asynchronous executed commands on a selection of all cluster nodes.
     */
    default AsyncNodeSelection<K, V> all() {
        return nodes(redisClusterNode -> true);
    }

    /**
     * Select replica nodes by a predicate and keeps a static selection. Replica connections operate in {@literal READONLY}
     * mode. The set of nodes within the {@link NodeSelectionSupport} does not change when the cluster view changes.
     *
     * @param predicate Predicate to filter nodes
     * @return API with asynchronous executed commands on a selection of cluster nodes matching {@code predicate}
     */
    AsyncNodeSelection<K, V> readonly(Predicate<RedisClusterNode> predicate);

    /**
     * Select nodes by a predicate and keeps a static selection. The set of nodes within the {@link NodeSelectionSupport} does
     * not change when the cluster view changes.
     *
     * @param predicate Predicate to filter nodes
     * @return API with asynchronous executed commands on a selection of cluster nodes matching {@code predicate}
     */
    AsyncNodeSelection<K, V> nodes(Predicate<RedisClusterNode> predicate);

    /**
     * Select nodes by a predicate
     *
     * @param predicate Predicate to filter nodes
     * @param dynamic Defines, whether the set of nodes within the {@link NodeSelectionSupport} can change when the cluster view
     *        changes.
     * @return API with asynchronous executed commands on a selection of cluster nodes matching {@code predicate}
     */
    AsyncNodeSelection<K, V> nodes(Predicate<RedisClusterNode> predicate, boolean dynamic);

    /**
     * Get the values of all the given keys with pipelining. Cross-slot keys will result in multiple calls to the particular
     * cluster nodes.
     *
     * @param keys the key
     * @return List&lt;V&gt; array-reply list of values at the specified keys.
     * @see RedisStringAsyncCommands#mget(Object[])
     */
    RedisFuture<List<KeyValue<K, V>>> mget(K... keys);

}
