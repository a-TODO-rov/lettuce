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

import static io.lettuce.core.cluster.ClusterScanSupport.*;
import static io.lettuce.core.cluster.models.partitions.RedisClusterNode.NodeFlag.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;


import io.lettuce.core.search.HybridReply;
import io.lettuce.core.search.arguments.hybrid.HybridArgs;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import io.lettuce.core.protocol.CommandType;
import io.lettuce.core.protocol.ProtocolKeyword;
import io.lettuce.core.protocol.Command;

import io.lettuce.core.protocol.RedisCommand;

import io.lettuce.core.json.JsonParser;
import org.reactivestreams.Publisher;

import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;

import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.reactive.RedisAdvancedClusterReactiveCommands;
import io.lettuce.core.cluster.api.reactive.RedisClusterReactiveCommands;
import io.lettuce.core.cluster.models.partitions.Partitions;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.internal.LettuceLists;
import io.lettuce.core.output.KeyStreamingChannel;
import io.lettuce.core.output.KeyValueStreamingChannel;
import io.lettuce.core.protocol.ConnectionIntent;
import reactor.core.publisher.Flux;
import io.lettuce.core.search.AggregationReply;
import io.lettuce.core.search.AggregationReply.Cursor;

import io.lettuce.core.search.SearchReply;
import io.lettuce.core.search.SpellCheckResult;
import io.lettuce.core.search.arguments.AggregateArgs;
import io.lettuce.core.search.arguments.SearchArgs;
import io.lettuce.core.search.arguments.ExplainArgs;
import io.lettuce.core.search.arguments.CreateArgs;
import io.lettuce.core.search.arguments.FieldArgs;

import io.lettuce.core.search.arguments.SpellCheckArgs;
import io.lettuce.core.search.arguments.SynUpdateArgs;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * An advanced reactive and thread-safe API to a Redis Cluster connection.
 *
 * @param <K> Key type.
 * @param <V> Value type.
 * @author Mark Paluch
 * @author Jon Chambers
 * @since 4.0
 */
public class RedisAdvancedClusterReactiveCommandsImpl<K, V> extends AbstractRedisReactiveCommands<K, V>
        implements RedisAdvancedClusterReactiveCommands<K, V> {

    private static final InternalLogger logger = InternalLoggerFactory
            .getInstance(RedisAdvancedClusterReactiveCommandsImpl.class);

    private static final Predicate<RedisClusterNode> ALL_NODES = node -> true;

    private final RedisCodec<K, V> codec;

    /**
     * Initialize a new connection.
     *
     * @param connection the stateful connection.
     * @param codec Codec used to encode/decode keys and values.
     * @param parser the implementation of the {@link JsonParser} to use
     * @deprecated since 5.2, use
     *             {@link #RedisAdvancedClusterReactiveCommandsImpl(StatefulRedisClusterConnection, RedisCodec, Supplier)}.
     */
    @Deprecated
    public RedisAdvancedClusterReactiveCommandsImpl(StatefulRedisClusterConnectionImpl<K, V> connection, RedisCodec<K, V> codec,
            Supplier<JsonParser> parser) {
        super(connection, codec, parser);
        this.codec = codec;
    }

    /**
     * Initialize a new connection.
     *
     * @param connection the stateful connection.
     * @param codec Codec used to encode/decode keys and values.
     * @deprecated since 5.2, use
     *             {@link #RedisAdvancedClusterReactiveCommandsImpl(StatefulRedisClusterConnection, RedisCodec, Supplier)}.
     */
    @Deprecated
    public RedisAdvancedClusterReactiveCommandsImpl(StatefulRedisClusterConnectionImpl<K, V> connection,
            RedisCodec<K, V> codec) {
        super(connection, codec);
        this.codec = codec;
    }

    /**
     * Initialize a new connection.
     *
     * @param connection the stateful connection.
     * @param codec Codec used to encode/decode keys and values.
     * @param parser the implementation of the {@link JsonParser} to use
     */
    public RedisAdvancedClusterReactiveCommandsImpl(StatefulRedisClusterConnection<K, V> connection, RedisCodec<K, V> codec,
            Supplier<JsonParser> parser) {
        super(connection, codec, parser);
        this.codec = codec;
    }

    /**
     * Initialize a new connection.
     *
     * @param connection the stateful connection.
     * @param codec Codec used to encode/decode keys and values.
     */
    public RedisAdvancedClusterReactiveCommandsImpl(StatefulRedisClusterConnection<K, V> connection, RedisCodec<K, V> codec) {
        super(connection, codec);
        this.codec = codec;
    }


    @Override
    public Mono<Long> clusterCountKeysInSlot(int slot) {

        Mono<RedisClusterReactiveCommands<K, V>> connectionBySlot = findConnectionBySlotReactive(slot);
        return connectionBySlot.flatMap(cmd -> cmd.clusterCountKeysInSlot(slot));
    }

    @Override
    public Flux<K> clusterGetKeysInSlot(int slot, int count) {

        Mono<RedisClusterReactiveCommands<K, V>> connectionBySlot = findConnectionBySlotReactive(slot);
        return connectionBySlot.flatMapMany(conn -> conn.clusterGetKeysInSlot(slot, count));
    }



    @Override
    public Flux<KeyValue<K, V>> mget(K... keys) {
        return mget(Arrays.asList(keys));
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public Flux<KeyValue<K, V>> mget(Iterable<K> keys) {
        List<K> keyList = LettuceLists.newList(keys);
        Map<Integer, List<K>> partitioned = SlotHash.partition(codec, keyList);

        if (partitioned.size() < 2) {
            return super.mget(keyList);
        }

        List<Publisher<KeyValue<K, V>>> publishers = partitioned.values().stream().map(super::mget)
                .collect(Collectors.toList());

        return Flux.mergeSequential(publishers).collectList().map(results -> {
            KeyValue<K, V>[] values = new KeyValue[keyList.size()];
            int offset = 0;

            for (List<K> partitionKeys : partitioned.values()) {
                for (int i = 0; i < keyList.size(); i++) {
                    int index = partitionKeys.indexOf(keyList.get(i));
                    if (index != -1) {
                        values[i] = results.get(offset + index);
                    }
                }
                offset += partitionKeys.size();
            }

            return Arrays.asList(values);
        }).flatMapMany(Flux::fromIterable);
    }

    @Override
    public Mono<Long> mget(KeyValueStreamingChannel<K, V> channel, K... keys) {
        return mget(channel, Arrays.asList(keys));
    }

    @Override
    public Mono<Long> mget(KeyValueStreamingChannel<K, V> channel, Iterable<K> keys) {

        List<K> keyList = LettuceLists.newList(keys);

        Map<Integer, List<K>> partitioned = SlotHash.partition(codec, keyList);

        if (partitioned.size() < 2) {
            return super.mget(channel, keyList);
        }

        List<Publisher<Long>> publishers = new ArrayList<>();

        for (Map.Entry<Integer, List<K>> entry : partitioned.entrySet()) {
            publishers.add(super.mget(channel, entry.getValue()));
        }

        return Flux.merge(publishers).reduce(Long::sum);
    }



    @Override
    public RedisClusterReactiveCommands<K, V> getConnection(String nodeId) {
        return getStatefulConnection().getConnection(nodeId).reactive();
    }

    private Mono<StatefulRedisConnection<K, V>> getStatefulConnection(String nodeId) {
        return getMono(getConnectionProvider().getConnectionAsync(ConnectionIntent.WRITE, nodeId));
    }

    private Mono<RedisClusterReactiveCommands<K, V>> getConnectionReactive(String nodeId) {
        return getMono(getConnectionProvider().<K, V> getConnectionAsync(ConnectionIntent.WRITE, nodeId))
                .map(StatefulRedisConnection::reactive);
    }

    @Override
    public RedisClusterReactiveCommands<K, V> getConnection(String host, int port) {
        return getStatefulConnection().getConnection(host, port).reactive();
    }

    private Mono<RedisClusterReactiveCommands<K, V>> getConnectionReactive(String host, int port) {
        return getMono(getConnectionProvider().<K, V> getConnectionAsync(ConnectionIntent.WRITE, host, port))
                .map(StatefulRedisConnection::reactive);
    }

    private Mono<StatefulRedisConnection<K, V>> getStatefulConnection(String host, int port) {
        return getMono(getConnectionProvider().<K, V> getConnectionAsync(ConnectionIntent.WRITE, host, port));
    }

    @Override
    public StatefulRedisClusterConnection<K, V> getStatefulConnection() {
        return (StatefulRedisClusterConnection<K, V>) super.getConnection();
    }

    /**
     * Obtain a node-scoped connection for the given intent (READ/WRITE). Selection honors the current ReadFrom policy via the
     * cluster connection provider.
     */
    private Mono<StatefulRedisConnection<K, V>> getStatefulConnection(ConnectionIntent intent) {
        return getMono(getConnectionProvider().getRandomConnectionAsync(intent));
    }




    private <T> Flux<T> pipeliningWithMap(Map<K, V> map, Function<Map<K, V>, Flux<T>> function,
            Function<Flux<T>, Flux<T>> resultFunction) {

        Map<Integer, List<K>> partitioned = SlotHash.partition(codec, map.keySet());

        if (partitioned.size() < 2) {
            return function.apply(map);
        }

        List<Flux<T>> publishers = partitioned.values().stream().map(ks -> {
            Map<K, V> op = new HashMap<>();
            ks.forEach(k -> op.put(k, map.get(k)));
            return function.apply(op);
        }).collect(Collectors.toList());

        return resultFunction.apply(Flux.merge(publishers));
    }

    /**
     * Run a command on all available masters,
     *
     * @param function function producing the command
     * @param <T> result type
     * @return map of a key (counter) and commands.
     */
    protected <T> Map<String, Publisher<T>> executeOnUpstream(
            Function<RedisClusterReactiveCommands<K, V>, ? extends Publisher<T>> function) {
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
    protected <T> Map<String, Publisher<T>> executeOnNodes(
            Function<RedisClusterReactiveCommands<K, V>, ? extends Publisher<T>> function, Predicate<RedisClusterNode> filter) {

        Map<String, Publisher<T>> executions = new HashMap<>();

        for (RedisClusterNode redisClusterNode : getStatefulConnection().getPartitions()) {

            if (!filter.test(redisClusterNode)) {
                continue;
            }

            RedisURI uri = redisClusterNode.getUri();
            Mono<RedisClusterReactiveCommands<K, V>> connection = getConnectionReactive(uri.getHost(), uri.getPort());

            executions.put(redisClusterNode.getNodeId(), connection.flatMapMany(function::apply));
        }
        return executions;
    }

    private Mono<RedisClusterReactiveCommands<K, V>> findConnectionBySlotReactive(int slot) {

        RedisClusterNode node = getStatefulConnection().getPartitions().getPartitionBySlot(slot);
        if (node != null) {
            return getConnectionReactive(node.getUri().getHost(), node.getUri().getPort());
        }

        return Mono.error(new RedisException("No partition for slot " + slot));
    }

    private AsyncClusterConnectionProvider getConnectionProvider() {

        ClusterDistributionChannelWriter writer = (ClusterDistributionChannelWriter) getStatefulConnection().getChannelWriter();
        return (AsyncClusterConnectionProvider) writer.getClusterConnectionProvider();
    }

    private static <T> Mono<T> getMono(CompletableFuture<T> future) {
        return Mono.fromCompletionStage(future);
    }

}
