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
package io.lettuce.core.cluster;

import static io.lettuce.core.cluster.NodeSelectionInvocationHandler.ExecutionModel.*;
import static io.lettuce.core.cluster.models.partitions.RedisClusterNode.NodeFlag.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.cluster.api.NodeSelectionSupport;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.async.AsyncNodeSelection;
import io.lettuce.core.cluster.api.async.NodeSelectionAsyncCommands;
import io.lettuce.core.cluster.api.async.RedisAdvancedClusterAsyncCommands;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.json.JsonParser;
import io.lettuce.core.output.KeyValueStreamingChannel;
import io.lettuce.core.protocol.Command;
import io.lettuce.core.protocol.ConnectionIntent;
import io.lettuce.core.protocol.ProtocolKeyword;
import io.lettuce.core.protocol.RedisCommand;

/**
 * An advanced asynchronous and thread-safe API for a Redis Cluster connection.
 *
 * @param <K> Key type.
 * @param <V> Value type.
 * @author Mark Paluch
 * @author Jon Chambers
 * @author Tihomir Mateev
 * @since 3.3
 */
@SuppressWarnings("unchecked")
public class RedisAdvancedClusterAsyncCommandsImpl<K, V> extends AbstractRedisAsyncCommands<K, V>
        implements RedisAdvancedClusterAsyncCommands<K, V> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(RedisAdvancedClusterAsyncCommandsImpl.class);

    private final RedisCodec<K, V> codec;

    /**
     * Initialize a new connection.
     *
     * @param connection the stateful connection
     * @param codec Codec used to encode/decode keys and values.
     * @param parser the implementation of the {@link JsonParser} to use
     * @deprecated since 5.1, use
     *             {@link #RedisAdvancedClusterAsyncCommandsImpl(StatefulRedisClusterConnection, RedisCodec, Supplier)}.
     */
    @Deprecated
    public RedisAdvancedClusterAsyncCommandsImpl(StatefulRedisClusterConnectionImpl<K, V> connection, RedisCodec<K, V> codec,
            Supplier<JsonParser> parser) {
        super(connection, codec, parser);
        this.codec = codec;
    }

    /**
     * Initialize a new connection.
     *
     * @param connection the stateful connection
     * @param codec Codec used to encode/decode keys and values.
     * @deprecated since 5.1, use
     *             {@link #RedisAdvancedClusterAsyncCommandsImpl(StatefulRedisClusterConnection, RedisCodec, Supplier)}.
     */
    @Deprecated
    public RedisAdvancedClusterAsyncCommandsImpl(StatefulRedisClusterConnectionImpl<K, V> connection, RedisCodec<K, V> codec) {
        super(connection, codec);
        this.codec = codec;
    }

    /**
     * Initialize a new connection.
     *
     * @param connection the stateful connection
     * @param codec Codec used to encode/decode keys and values.
     * @param parser the implementation of the {@link JsonParser} to use
     */
    public RedisAdvancedClusterAsyncCommandsImpl(StatefulRedisClusterConnection<K, V> connection, RedisCodec<K, V> codec,
            Supplier<JsonParser> parser) {
        super(connection, codec, parser);
        this.codec = codec;
    }

    /**
     * Initialize a new connection.
     *
     * @param connection the stateful connection
     * @param codec Codec used to encode/decode keys and values.
     */
    public RedisAdvancedClusterAsyncCommandsImpl(StatefulRedisClusterConnection<K, V> connection, RedisCodec<K, V> codec) {
        super(connection, codec);
        this.codec = codec;
    }

    @Override
    public RedisFuture<Long> clusterCountKeysInSlot(int slot) {

        RedisClusterAsyncCommands<K, V> connectionBySlot = findConnectionBySlot(slot);

        if (connectionBySlot != null) {
            return connectionBySlot.clusterCountKeysInSlot(slot);
        }

        return super.clusterCountKeysInSlot(slot);
    }

    @Override
    public RedisFuture<List<K>> clusterGetKeysInSlot(int slot, int count) {

        RedisClusterAsyncCommands<K, V> connectionBySlot = findConnectionBySlot(slot);

        if (connectionBySlot != null) {
            return connectionBySlot.clusterGetKeysInSlot(slot, count);
        }

        return super.clusterGetKeysInSlot(slot, count);
    }

    @Override
    public RedisFuture<List<KeyValue<K, V>>> mget(K... keys) {
        return mget(Arrays.asList(keys));
    }

    @Override
    public RedisFuture<List<KeyValue<K, V>>> mget(Iterable<K> keys) {
        Map<Integer, List<K>> partitioned = SlotHash.partition(codec, keys);

        if (partitioned.size() < 2) {
            return super.mget(keys);
        }

        // For a given partition, maps the key to its index within the List<K> in partitioned for faster lookups below
        Map<Integer, Map<K, Integer>> partitionedKeysToIndexes = mapKeyToIndex(partitioned);
        Map<K, Integer> slots = SlotHash.getSlots(partitioned);
        Map<Integer, RedisFuture<List<KeyValue<K, V>>>> executions = new HashMap<>(partitioned.size());

        for (Map.Entry<Integer, List<K>> entry : partitioned.entrySet()) {
            RedisFuture<List<KeyValue<K, V>>> mget = super.mget(entry.getValue());
            executions.put(entry.getKey(), mget);
        }

        // restore order of key
        return new PipelinedRedisFuture<>(executions, objectPipelinedRedisFuture -> {
            List<KeyValue<K, V>> result = new ArrayList<>(slots.size());
            for (K opKey : keys) {
                int slot = slots.get(opKey);

                int position = partitionedKeysToIndexes.get(slot).get(opKey);
                RedisFuture<List<KeyValue<K, V>>> listRedisFuture = executions.get(slot);
                result.add(MultiNodeExecution.execute(() -> listRedisFuture.get().get(position)));
            }

            return result;
        });
    }

    private Map<Integer, Map<K, Integer>> mapKeyToIndex(Map<Integer, List<K>> partitioned) {
        Map<Integer, Map<K, Integer>> result = new HashMap<>(partitioned.size());
        for (Integer partition : partitioned.keySet()) {
            List<K> keysForPartition = partitioned.get(partition);
            Map<K, Integer> keysToIndexes = new HashMap<>(keysForPartition.size());
            for (int i = 0; i < keysForPartition.size(); i++) {
                keysToIndexes.put(keysForPartition.get(i), i);
            }
            result.put(partition, keysToIndexes);
        }

        return result;
    }

    @Override
    public RedisFuture<Long> mget(KeyValueStreamingChannel<K, V> channel, K... keys) {
        return mget(channel, Arrays.asList(keys));
    }

    @Override
    public RedisFuture<Long> mget(KeyValueStreamingChannel<K, V> channel, Iterable<K> keys) {
        Map<Integer, List<K>> partitioned = SlotHash.partition(codec, keys);

        if (partitioned.size() < 2) {
            return super.mget(channel, keys);
        }

        Map<Integer, RedisFuture<Long>> executions = new HashMap<>();

        for (Map.Entry<Integer, List<K>> entry : partitioned.entrySet()) {
            RedisFuture<Long> del = super.mget(channel, entry.getValue());
            executions.put(entry.getKey(), del);
        }

        return MultiNodeExecution.aggregateAsync(executions);
    }

    @Override
    public RedisClusterAsyncCommands<K, V> getConnection(String nodeId) {
        return getStatefulConnection().getConnection(nodeId).async();
    }

    @Override
    public RedisClusterAsyncCommands<K, V> getConnection(String host, int port) {
        return getStatefulConnection().getConnection(host, port).async();
    }

    private CompletableFuture<StatefulRedisConnection<K, V>> getStatefulConnection(String nodeId) {
        return getConnectionProvider().getConnectionAsync(ConnectionIntent.WRITE, nodeId);
    }

    private CompletableFuture<StatefulRedisConnection<K, V>> getStatefulConnection(String host, int port) {
        return getConnectionProvider().getConnectionAsync(ConnectionIntent.WRITE, host, port);
    }

    private CompletableFuture<RedisClusterAsyncCommands<K, V>> getConnectionAsync(String host, int port) {
        return getConnectionProvider().<K, V> getConnectionAsync(ConnectionIntent.WRITE, host, port)
                .thenApply(StatefulRedisConnection::async);
    }

    @Override
    public StatefulRedisClusterConnection<K, V> getStatefulConnection() {
        return (StatefulRedisClusterConnection<K, V>) super.getConnection();
    }

    /**
     * Obtain a random node-scoped connection for the given intent (READ/WRITE). Selection honors the current ReadFrom policy
     * via the cluster connection provider.
     */
    private CompletableFuture<StatefulRedisConnection<K, V>> getRandomStatefulConnection(ConnectionIntent intent) {
        return getConnectionProvider().getRandomConnectionAsync(intent);
    }

    @Override
    public AsyncNodeSelection<K, V> nodes(Predicate<RedisClusterNode> predicate) {
        return nodes(predicate, false);
    }

    @Override
    public AsyncNodeSelection<K, V> readonly(Predicate<RedisClusterNode> predicate) {
        return nodes(predicate, ConnectionIntent.READ, false);
    }

    @Override
    public AsyncNodeSelection<K, V> nodes(Predicate<RedisClusterNode> predicate, boolean dynamic) {
        return nodes(predicate, ConnectionIntent.WRITE, dynamic);
    }

    @SuppressWarnings("unchecked")
    protected AsyncNodeSelection<K, V> nodes(Predicate<RedisClusterNode> predicate, ConnectionIntent connectionIntent,
            boolean dynamic) {

        NodeSelectionSupport<RedisAsyncCommands<K, V>, ?> selection;

        StatefulRedisClusterConnectionImpl<K, V> impl = (StatefulRedisClusterConnectionImpl<K, V>) getConnection();
        if (dynamic) {
            selection = new DynamicNodeSelection<RedisAsyncCommands<K, V>, Object, K, V>(
                    impl.getClusterDistributionChannelWriter(), predicate, connectionIntent, StatefulRedisConnection::async);
        } else {
            selection = new StaticNodeSelection<RedisAsyncCommands<K, V>, Object, K, V>(
                    impl.getClusterDistributionChannelWriter(), predicate, connectionIntent, StatefulRedisConnection::async);
        }

        NodeSelectionInvocationHandler h = new NodeSelectionInvocationHandler((AbstractNodeSelection<?, ?, ?, ?>) selection,
                RedisClusterAsyncCommands.class, ASYNC);
        return (AsyncNodeSelection<K, V>) Proxy.newProxyInstance(NodeSelectionSupport.class.getClassLoader(),
                new Class<?>[] { NodeSelectionAsyncCommands.class, AsyncNodeSelection.class }, h);
    }

    /**
     * Route a keyless RediSearch command using cluster-aware connection selection.
     * <p>
     * Honors the current ReadFrom policy and the READ/WRITE intent derived from {@code commandType}. If routing fails, falls
     * back to {@code superCall} to preserve existing behavior.
     *
     * @param superCall supplier of the superclass implementation used as a fallback
     * @param routedCall function invoked with a node-scoped async connection to execute the command
     * @param commandType protocol command used to classify READ vs WRITE intent
     * @param <R> result type
     * @return RedisFuture wrapping the routed execution
     */
    <R> RedisFuture<R> routeKeyless(Supplier<RedisFuture<R>> superCall,
            Function<RedisAsyncCommands<K, V>, CompletionStage<R>> routedCall, ProtocolKeyword commandType) {

        ConnectionIntent intent = getConnectionIntent(commandType);

        CompletableFuture<R> future = getRandomStatefulConnection(intent).thenApply(StatefulRedisConnection::async)
                .thenCompose(routedCall).handle((res, err) -> {
                    if (err != null) {
                        logger.error("Cluster routing failed for {} - falling back to superCall", commandType, err);
                        return superCall.get().toCompletableFuture();
                    }
                    return CompletableFuture.completedFuture(res);
                }).thenCompose(Function.identity());

        return new PipelinedRedisFuture<>(future);
    }

    /**
     * Route a keyless RediSearch command with node context.
     * <p>
     * Obtains the executing node id via CLUSTER MYID on the selected node and passes it to {@code routedCall}, allowing reply
     * stamping (e.g., cursor.nodeId). Honors ReadFrom and READ/WRITE intent. If routing fails, falls back to {@code superCall}
     * to preserve existing behavior.
     *
     * @param superCall supplier of the superclass implementation used as a fallback
     * @param routedCall bi-function receiving {@code nodeId} and a node-scoped cluster async connection
     * @param commandType protocol command used to classify READ vs WRITE intent
     * @param <R> result type
     * @return RedisFuture wrapping the routed execution
     */
    <R> RedisFuture<R> routeKeyless(Supplier<RedisFuture<R>> superCall,
            BiFunction<String, RedisClusterAsyncCommands<K, V>, CompletionStage<R>> routedCall, ProtocolKeyword commandType) {

        ConnectionIntent intent = getConnectionIntent(commandType);

        CompletableFuture<R> future = getRandomStatefulConnection(intent).thenCompose(conn -> {
            RedisClusterAsyncCommands<K, V> async = conn.async();
            return async.clusterMyId().toCompletableFuture().thenCompose(nodeId -> routedCall.apply(nodeId, async));
        }).handle((res, err) -> {
            if (err != null) {
                logger.error("Cluster routing failed for {} - falling back to superCall", commandType, err);
                return superCall.get().toCompletableFuture();
            }
            return CompletableFuture.completedFuture(res);
        }).thenCompose(Function.identity());

        return new PipelinedRedisFuture<>(future);
    }

    private ConnectionIntent getConnectionIntent(ProtocolKeyword commandType) {
        try {
            RedisCommand probe = new Command(commandType, null);
            boolean isReadOnly = getStatefulConnection().getOptions().getReadOnlyCommands().isReadOnly(probe);
            return isReadOnly ? ConnectionIntent.READ : ConnectionIntent.WRITE;
        } catch (Exception e) {
            logger.error("Error while determining connection intent for " + commandType, e);
            return ConnectionIntent.WRITE;
        }
    }

    /**
     * Run a command on all available masters,
     *
     * @param function function producing the command
     * @param <T> result type
     * @return map of a key (counter) and commands.
     */
    protected <T> Map<String, CompletableFuture<T>> executeOnUpstream(
            Function<RedisClusterAsyncCommands<K, V>, RedisFuture<T>> function) {
        return executeOnNodes(function, redisClusterNode -> redisClusterNode.is(UPSTREAM));
    }

    /**
     * Run a command on all available nodes that match {@code filter}.
     *
     * @param function function producing the command
     * @param filter filter function for the node selection
     * @param <T> result type
     * @return map of a key (counter) and commands.
     */
    protected <T> Map<String, CompletableFuture<T>> executeOnNodes(
            Function<RedisClusterAsyncCommands<K, V>, RedisFuture<T>> function, Function<RedisClusterNode, Boolean> filter) {
        Map<String, CompletableFuture<T>> executions = new HashMap<>();

        for (RedisClusterNode redisClusterNode : getStatefulConnection().getPartitions()) {

            if (!filter.apply(redisClusterNode)) {
                continue;
            }

            RedisURI uri = redisClusterNode.getUri();
            CompletableFuture<RedisClusterAsyncCommands<K, V>> connection = getConnectionAsync(uri.getHost(), uri.getPort());

            executions.put(redisClusterNode.getNodeId(), connection.thenCompose(function::apply));

        }
        return executions;
    }

    private RedisClusterAsyncCommands<K, V> findConnectionBySlot(int slot) {
        RedisClusterNode node = getStatefulConnection().getPartitions().getPartitionBySlot(slot);
        if (node != null) {
            return getConnection(node.getUri().getHost(), node.getUri().getPort());
        }

        return null;
    }

    private AsyncClusterConnectionProvider getConnectionProvider() {

        ClusterDistributionChannelWriter writer = (ClusterDistributionChannelWriter) getStatefulConnection().getChannelWriter();
        return (AsyncClusterConnectionProvider) writer.getClusterConnectionProvider();
    }

}
