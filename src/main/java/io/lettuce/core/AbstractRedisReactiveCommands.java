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
package io.lettuce.core;

import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.reactive.*;
import io.lettuce.core.cluster.api.reactive.RedisClusterReactiveCommands;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.internal.LettuceAssert;
import io.lettuce.core.json.JsonParser;
import io.lettuce.core.output.CommandOutput;
import io.lettuce.core.output.KeyValueStreamingChannel;
import io.lettuce.core.output.ValueStreamingChannel;
import io.lettuce.core.protocol.Command;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandType;
import io.lettuce.core.protocol.ProtocolKeyword;
import io.lettuce.core.protocol.RedisCommand;
import io.lettuce.core.protocol.TracedCommand;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.tracing.TraceContext;
import io.lettuce.core.tracing.TraceContextProvider;
import io.lettuce.core.tracing.Tracing;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.ImmediateEventExecutor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static io.lettuce.core.ClientOptions.DEFAULT_JSON_PARSER;
import static io.lettuce.core.protocol.CommandType.EXEC;

/**
 * A reactive and thread-safe API for a Redis connection.
 *
 * @param <K> Key type.
 * @param <V> Value type.
 * @author Mark Paluch
 * @author Nikolai Perevozchikov
 * @author Tugdual Grall
 * @author dengliming
 * @author Andrey Shlykov
 * @author Ali Takavci
 * @author Tihomir Mateev
 * @author SeugnSu Kim
 * @since 4.0
 */
public abstract class AbstractRedisReactiveCommands<K, V>
        implements RedisStringReactiveCommands<K, V>, BaseRedisReactiveCommands<K, V>, RedisClusterReactiveCommands<K, V> {

    private final StatefulConnection<K, V> connection;

    private final RedisCommandBuilder<K, V> commandBuilder;

    private final Supplier<JsonParser> parser;

    private final ClientResources clientResources;

    private final boolean tracingEnabled;

    private volatile EventExecutorGroup scheduler;

    /**
     * Initialize a new instance.
     *
     * @param connection the connection to operate on.
     * @param codec the codec for command encoding.
     * @param parser the implementation of the {@link JsonParser} to use
     */
    public AbstractRedisReactiveCommands(StatefulConnection<K, V> connection, RedisCodec<K, V> codec,
            Supplier<JsonParser> parser) {
        this.connection = connection;
        this.parser = parser;
        this.commandBuilder = new RedisCommandBuilder<>(codec);
        this.clientResources = connection.getResources();
        this.tracingEnabled = clientResources.tracing().isEnabled();
    }

    /**
     * Initialize a new instance.
     *
     * @param connection the connection to operate on.
     * @param codec the codec for command encoding.
     */
    public AbstractRedisReactiveCommands(StatefulConnection<K, V> connection, RedisCodec<K, V> codec) {
        this(connection, codec, DEFAULT_JSON_PARSER);
    }

    private EventExecutorGroup getScheduler() {

        EventExecutorGroup scheduler = this.scheduler;
        if (scheduler != null) {
            return scheduler;
        }

        EventExecutorGroup schedulerToUse = ImmediateEventExecutor.INSTANCE;

        if (connection.getOptions().isPublishOnScheduler()) {
            schedulerToUse = connection.getResources().eventExecutorGroup();
        }

        return this.scheduler = schedulerToUse;
    }

    @Override
    public JsonParser getJsonParser() {
        return parser.get();
    }

    @SuppressWarnings("unchecked")
    public <T, R> Flux<R> createDissolvingFlux(Supplier<RedisCommand<K, V, T>> commandSupplier) {
        return (Flux<R>) createFlux(commandSupplier, true);
    }

    public <T> Flux<T> createFlux(Supplier<RedisCommand<K, V, T>> commandSupplier) {
        return createFlux(commandSupplier, false);
    }

    private <T> Flux<T> createFlux(Supplier<RedisCommand<K, V, T>> commandSupplier, boolean dissolve) {

        if (tracingEnabled) {

            return withTraceContext().flatMapMany(it -> Flux
                    .from(new RedisPublisher<>(decorate(commandSupplier, it), connection, dissolve, getScheduler().next())));
        }

        return Flux.from(new RedisPublisher<>(commandSupplier, connection, dissolve, getScheduler().next()));
    }

    private Mono<TraceContext> withTraceContext() {

        return Tracing.getContext()
                .switchIfEmpty(Mono.fromSupplier(() -> clientResources.tracing().initialTraceContextProvider()))
                .flatMap(TraceContextProvider::getTraceContextLater).defaultIfEmpty(TraceContext.EMPTY);
    }

    protected <T> Mono<T> createMono(CommandType type, CommandOutput<K, V, T> output, CommandArgs<K, V> args) {
        return createMono(() -> new Command<>(type, output, args));
    }

    public <T> Mono<T> createMono(Supplier<RedisCommand<K, V, T>> commandSupplier) {

        if (tracingEnabled) {

            return withTraceContext().flatMap(it -> Mono
                    .from(new RedisPublisher<>(decorate(commandSupplier, it), connection, false, getScheduler().next())));
        }

        return Mono.from(new RedisPublisher<>(commandSupplier, connection, false, getScheduler().next()));
    }

    private <T> Supplier<RedisCommand<K, V, T>> decorate(Supplier<RedisCommand<K, V, T>> commandSupplier,
            TraceContext traceContext) {
        return () -> new TracedCommand<>(commandSupplier.get(), traceContext);
    }

    @Override
    public Mono<String> asking() {
        return createMono(commandBuilder::asking);
    }

    @Override
    public Mono<String> auth(CharSequence password) {
        return createMono(() -> commandBuilder.auth(password));
    }

    @Override
    public Mono<String> auth(String username, CharSequence password) {
        return createMono(() -> commandBuilder.auth(username, password));
    }

    @Override
    public Mono<String> clusterAddSlots(int... slots) {
        return createMono(() -> commandBuilder.clusterAddslots(slots));
    }

    @Override
    public Mono<String> clusterAddSlotsRange(Range<Integer>... ranges) {
        return createMono(() -> commandBuilder.clusterAddSlotsRange(ranges));
    }

    @Override
    public Mono<String> clusterBumpepoch() {
        return createMono(() -> commandBuilder.clusterBumpepoch());
    }

    @Override
    public Mono<Long> clusterCountFailureReports(String nodeId) {
        return createMono(() -> commandBuilder.clusterCountFailureReports(nodeId));
    }

    @Override
    public Mono<Long> clusterCountKeysInSlot(int slot) {
        return createMono(() -> commandBuilder.clusterCountKeysInSlot(slot));
    }

    @Override
    public Mono<String> clusterDelSlots(int... slots) {
        return createMono(() -> commandBuilder.clusterDelslots(slots));
    }

    @Override
    public Mono<String> clusterDelSlotsRange(Range<Integer>... ranges) {
        return createMono(() -> commandBuilder.clusterDelSlotsRange(ranges));
    }

    @Override
    public Mono<String> clusterFailover(boolean force) {
        return createMono(() -> commandBuilder.clusterFailover(force));
    }

    @Override
    public Mono<String> clusterFailover(boolean force, boolean takeOver) {
        return createMono(() -> commandBuilder.clusterFailover(force, takeOver));
    }

    @Override
    public Mono<String> clusterFlushslots() {
        return createMono(commandBuilder::clusterFlushslots);
    }

    @Override
    public Mono<String> clusterForget(String nodeId) {
        return createMono(() -> commandBuilder.clusterForget(nodeId));
    }

    @Override
    public Flux<K> clusterGetKeysInSlot(int slot, int count) {
        return createDissolvingFlux(() -> commandBuilder.clusterGetKeysInSlot(slot, count));
    }

    @Override
    public Mono<String> clusterInfo() {
        return createMono(commandBuilder::clusterInfo);
    }

    @Override
    public Mono<Long> clusterKeyslot(K key) {
        return createMono(() -> commandBuilder.clusterKeyslot(key));
    }

    @Override
    public Mono<String> clusterMeet(String ip, int port) {
        return createMono(() -> commandBuilder.clusterMeet(ip, port));
    }

    @Override
    public Mono<String> clusterMyId() {
        return createMono(commandBuilder::clusterMyId);
    }

    @Override
    public Mono<String> clusterMyShardId() {
        return createMono(commandBuilder::clusterMyShardId);
    }

    @Override
    public Mono<String> clusterNodes() {
        return createMono(commandBuilder::clusterNodes);
    }

    @Override
    public Mono<String> clusterReplicate(String nodeId) {
        return createMono(() -> commandBuilder.clusterReplicate(nodeId));
    }

    @Override
    public Flux<String> clusterReplicas(String nodeId) {
        return createDissolvingFlux(() -> commandBuilder.clusterReplicas(nodeId));
    }

    @Override
    public Mono<String> clusterReset(boolean hard) {
        return createMono(() -> commandBuilder.clusterReset(hard));
    }

    @Override
    public Mono<String> clusterSaveconfig() {
        return createMono(() -> commandBuilder.clusterSaveconfig());
    }

    @Override
    public Mono<String> clusterSetConfigEpoch(long configEpoch) {
        return createMono(() -> commandBuilder.clusterSetConfigEpoch(configEpoch));
    }

    @Override
    public Mono<String> clusterSetSlotImporting(int slot, String nodeId) {
        return createMono(() -> commandBuilder.clusterSetSlotImporting(slot, nodeId));
    }

    @Override
    public Mono<String> clusterSetSlotMigrating(int slot, String nodeId) {
        return createMono(() -> commandBuilder.clusterSetSlotMigrating(slot, nodeId));
    }

    @Override
    public Mono<String> clusterSetSlotNode(int slot, String nodeId) {
        return createMono(() -> commandBuilder.clusterSetSlotNode(slot, nodeId));
    }

    @Override
    public Mono<String> clusterSetSlotStable(int slot) {
        return createMono(() -> commandBuilder.clusterSetSlotStable(slot));
    }

    @Override
    public Mono<List<Object>> clusterShards() {
        return createMono(() -> commandBuilder.clusterShards());
    }

    @Override
    public Flux<String> clusterSlaves(String nodeId) {
        return createDissolvingFlux(() -> commandBuilder.clusterSlaves(nodeId));
    }

    @Override
    public Flux<Object> clusterSlots() {
        return createDissolvingFlux(commandBuilder::clusterSlots);
    }

    @SuppressWarnings("unchecked")

    public Mono<String> discard() {
        return createMono(commandBuilder::discard);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public <T> Flux<T> dispatch(ProtocolKeyword type, CommandOutput<K, V, ?> output) {

        LettuceAssert.notNull(type, "Command type must not be null");
        LettuceAssert.notNull(output, "CommandOutput type must not be null");

        return (Flux) createFlux(() -> new Command<>(type, output));
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public <T> Flux<T> dispatch(ProtocolKeyword type, CommandOutput<K, V, ?> output, CommandArgs<K, V> args) {

        LettuceAssert.notNull(type, "Command type must not be null");
        LettuceAssert.notNull(output, "CommandOutput type must not be null");
        LettuceAssert.notNull(args, "CommandArgs type must not be null");

        return (Flux) createFlux(() -> new Command<>(type, output, args));
    }

    @Override
    public Mono<V> echo(V msg) {
        return createMono(() -> commandBuilder.echo(msg));
    }

    public Mono<TransactionResult> exec() {
        return createMono(EXEC, null, null);
    }

    @Override
    public Mono<V> get(K key) {
        return createMono(() -> commandBuilder.get(key));
    }

    public StatefulConnection<K, V> getConnection() {
        return connection;
    }

    @Override
    public Flux<KeyValue<K, V>> mget(K... keys) {
        return createDissolvingFlux(() -> commandBuilder.mgetKeyValue(keys));
    }

    public Flux<KeyValue<K, V>> mget(Iterable<K> keys) {
        return createDissolvingFlux(() -> commandBuilder.mgetKeyValue(keys));
    }

    public Mono<Long> mget(KeyValueStreamingChannel<K, V> channel, K... keys) {
        return createMono(() -> commandBuilder.mget(channel, keys));
    }

    public Mono<Long> mget(ValueStreamingChannel<V> channel, Iterable<K> keys) {
        return createMono(() -> commandBuilder.mget(channel, keys));
    }

    public Mono<Long> mget(KeyValueStreamingChannel<K, V> channel, Iterable<K> keys) {
        return createMono(() -> commandBuilder.mget(channel, keys));
    }

    public Mono<String> multi() {
        return createMono(commandBuilder::multi);
    }

    @Override
    public Mono<String> ping() {
        return createMono(commandBuilder::ping);
    }

    @Override
    public Mono<Long> publish(K channel, V message) {
        return createMono(() -> commandBuilder.publish(channel, message));
    }

    @Override
    public Mono<Long> spublish(K shardChannel, V message) {
        return createMono(() -> commandBuilder.spublish(shardChannel, message));
    }

    @Override
    public Flux<K> pubsubChannels() {
        return createDissolvingFlux(commandBuilder::pubsubChannels);
    }

    @Override
    public Flux<K> pubsubChannels(K channel) {
        return createDissolvingFlux(() -> commandBuilder.pubsubChannels(channel));
    }

    @Override
    public Mono<Long> pubsubNumpat() {
        return createMono(commandBuilder::pubsubNumpat);
    }

    @Override
    public Mono<Map<K, Long>> pubsubNumsub(K... channels) {
        return createMono(() -> commandBuilder.pubsubNumsub(channels));
    }

    @Override
    public Flux<K> pubsubShardChannels() {
        return createDissolvingFlux(commandBuilder::pubsubShardChannels);
    }

    @Override
    public Flux<K> pubsubShardChannels(K pattern) {
        return createDissolvingFlux(() -> commandBuilder.pubsubShardChannels(pattern));
    }

    @Override
    public Mono<Map<K, Long>> pubsubShardNumsub(K... shardChannels) {
        return createMono(() -> commandBuilder.pubsubShardNumsub(shardChannels));
    }

    @Override
    public Mono<String> quit() {
        return createMono(commandBuilder::quit);
    }

    @Override
    public Mono<String> readOnly() {
        return createMono(commandBuilder::readOnly);
    }

    @Override
    public Mono<String> readWrite() {
        return createMono(commandBuilder::readWrite);
    }

    @Override
    public Flux<Object> role() {
        return createDissolvingFlux(commandBuilder::role);
    }

    public Mono<String> select(int db) {
        return createMono(() -> commandBuilder.select(db));
    }

    @Override
    public Mono<String> set(K key, V value) {
        return createMono(() -> commandBuilder.set(key, value));
    }

    @Override
    public Mono<String> set(K key, V value, SetArgs setArgs) {
        return createMono(() -> commandBuilder.set(key, value, setArgs));
    }

    public Mono<V> setGet(K key, V value) {
        return createMono(() -> commandBuilder.setGet(key, value));
    }

    public Mono<V> setGet(K key, V value, SetArgs setArgs) {
        return createMono(() -> commandBuilder.setGet(key, value, setArgs));
    }

    @Override
    public void setTimeout(Duration timeout) {
        connection.setTimeout(timeout);
    }

    public Mono<String> swapdb(int db1, int db2) {
        return createMono(() -> commandBuilder.swapdb(db1, db2));
    }

    public Mono<String> unwatch() {
        return createMono(commandBuilder::unwatch);
    }

    @Override
    public Mono<Long> waitForReplication(int replicas, long timeout) {
        return createMono(() -> commandBuilder.wait(replicas, timeout));
    }

    public Mono<String> watch(K... keys) {
        return createMono(() -> commandBuilder.watch(keys));
    }

    @Override
    public Mono<List<Map<String, Object>>> clusterLinks() {
        return createMono(commandBuilder::clusterLinks);
    }

}
