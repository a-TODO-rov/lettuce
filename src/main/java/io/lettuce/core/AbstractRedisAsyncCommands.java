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
import io.lettuce.core.api.async.*;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.internal.LettuceAssert;
import io.lettuce.core.json.JsonParser;
import io.lettuce.core.output.CommandOutput;
import io.lettuce.core.output.KeyValueStreamingChannel;
import io.lettuce.core.protocol.AsyncCommand;
import io.lettuce.core.protocol.Command;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandType;
import io.lettuce.core.protocol.ProtocolKeyword;
import io.lettuce.core.protocol.RedisCommand;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static io.lettuce.core.ClientOptions.DEFAULT_JSON_PARSER;
import static io.lettuce.core.protocol.CommandType.EXEC;

/**
 * An asynchronous and thread-safe API for a Redis connection.
 *
 * @param <K> Key type.
 * @param <V> Value type.
 * @author Will Glozer
 * @author Mark Paluch
 * @author Tugdual Grall
 * @author dengliming
 * @author Andrey Shlykov
 * @author Ali Takavci
 * @author SeugnSu Kim
 */
@SuppressWarnings("unchecked")
public abstract class AbstractRedisAsyncCommands<K, V>
        implements RedisStringAsyncCommands<K, V>, BaseRedisAsyncCommands<K, V>, RedisClusterAsyncCommands<K, V> {

    private final StatefulConnection<K, V> connection;

    private final RedisCommandBuilder<K, V> commandBuilder;

    private final Supplier<JsonParser> parser;

    /**
     * Initialize a new instance.
     *
     * @param connection the connection to operate on
     * @param codec the codec for command encoding
     * @param parser the implementation of the {@link JsonParser} to use
     */
    public AbstractRedisAsyncCommands(StatefulConnection<K, V> connection, RedisCodec<K, V> codec,
            Supplier<JsonParser> parser) {
        this.parser = parser;
        this.connection = connection;
        this.commandBuilder = new RedisCommandBuilder<>(codec);
    }

    /**
     * Initialize a new instance.
     *
     * @param connection the connection to operate on
     * @param codec the codec for command encoding
     */
    public AbstractRedisAsyncCommands(StatefulConnection<K, V> connection, RedisCodec<K, V> codec) {
        this(connection, codec, DEFAULT_JSON_PARSER);
    }

    @Override
    public RedisFuture<String> asking() {
        return dispatch(commandBuilder.asking());
    }

    @Override
    public RedisFuture<String> auth(CharSequence password) {

        LettuceAssert.notNull(password, "Password must not be null");
        return dispatch(commandBuilder.auth(password));
    }

    public RedisFuture<String> auth(char[] password) {

        LettuceAssert.notNull(password, "Password must not be null");
        return dispatch(commandBuilder.auth(password));
    }

    @Override
    public RedisFuture<String> auth(String username, CharSequence password) {
        LettuceAssert.notNull(username, "Username must not be null");
        LettuceAssert.notNull(password, "Password must not be null");
        return dispatch(commandBuilder.auth(username, password));
    }

    public RedisFuture<String> auth(String username, char[] password) {
        LettuceAssert.notNull(username, "Username must not be null");
        LettuceAssert.notNull(password, "Password must not be null");
        return dispatch(commandBuilder.auth(username, password));
    }

    @Override
    public RedisFuture<String> clusterAddSlots(int... slots) {
        return dispatch(commandBuilder.clusterAddslots(slots));
    }

    @Override
    public RedisFuture<String> clusterAddSlotsRange(Range<Integer>... ranges) {
        return dispatch(commandBuilder.clusterAddSlotsRange(ranges));
    }

    @Override
    public RedisFuture<String> clusterBumpepoch() {
        return dispatch(commandBuilder.clusterBumpepoch());
    }

    @Override
    public RedisFuture<Long> clusterCountFailureReports(String nodeId) {
        return dispatch(commandBuilder.clusterCountFailureReports(nodeId));
    }

    @Override
    public RedisFuture<Long> clusterCountKeysInSlot(int slot) {
        return dispatch(commandBuilder.clusterCountKeysInSlot(slot));
    }

    @Override
    public RedisFuture<String> clusterDelSlots(int... slots) {
        return dispatch(commandBuilder.clusterDelslots(slots));
    }

    @Override
    public RedisFuture<String> clusterDelSlotsRange(Range<Integer>... ranges) {
        return dispatch(commandBuilder.clusterDelSlotsRange(ranges));
    }

    @Override
    public RedisFuture<String> clusterFailover(boolean force) {
        return dispatch(commandBuilder.clusterFailover(force));
    }

    @Override
    public RedisFuture<String> clusterFailover(boolean force, boolean takeOver) {
        return dispatch(commandBuilder.clusterFailover(force, takeOver));
    }

    @Override
    public RedisFuture<String> clusterFlushslots() {
        return dispatch(commandBuilder.clusterFlushslots());
    }

    @Override
    public RedisFuture<String> clusterForget(String nodeId) {
        return dispatch(commandBuilder.clusterForget(nodeId));
    }

    @Override
    public RedisFuture<List<K>> clusterGetKeysInSlot(int slot, int count) {
        return dispatch(commandBuilder.clusterGetKeysInSlot(slot, count));
    }

    @Override
    public RedisFuture<String> clusterInfo() {
        return dispatch(commandBuilder.clusterInfo());
    }

    @Override
    public RedisFuture<Long> clusterKeyslot(K key) {
        return dispatch(commandBuilder.clusterKeyslot(key));
    }

    @Override
    public RedisFuture<String> clusterMeet(String ip, int port) {
        return dispatch(commandBuilder.clusterMeet(ip, port));
    }

    @Override
    public RedisFuture<String> clusterMyId() {
        return dispatch(commandBuilder.clusterMyId());
    }

    @Override
    public RedisFuture<String> clusterMyShardId() {
        return dispatch(commandBuilder.clusterMyShardId());
    }

    @Override
    public RedisFuture<String> clusterNodes() {
        return dispatch(commandBuilder.clusterNodes());
    }

    @Override
    public RedisFuture<String> clusterReplicate(String nodeId) {
        return dispatch(commandBuilder.clusterReplicate(nodeId));
    }

    @Override
    public RedisFuture<List<String>> clusterReplicas(String nodeId) {
        return dispatch(commandBuilder.clusterReplicas(nodeId));
    }

    @Override
    public RedisFuture<String> clusterReset(boolean hard) {
        return dispatch(commandBuilder.clusterReset(hard));
    }

    @Override
    public RedisFuture<String> clusterSaveconfig() {
        return dispatch(commandBuilder.clusterSaveconfig());
    }

    @Override
    public RedisFuture<String> clusterSetConfigEpoch(long configEpoch) {
        return dispatch(commandBuilder.clusterSetConfigEpoch(configEpoch));
    }

    @Override
    public RedisFuture<String> clusterSetSlotImporting(int slot, String nodeId) {
        return dispatch(commandBuilder.clusterSetSlotImporting(slot, nodeId));
    }

    @Override
    public RedisFuture<String> clusterSetSlotMigrating(int slot, String nodeId) {
        return dispatch(commandBuilder.clusterSetSlotMigrating(slot, nodeId));
    }

    @Override
    public RedisFuture<String> clusterSetSlotNode(int slot, String nodeId) {
        return dispatch(commandBuilder.clusterSetSlotNode(slot, nodeId));
    }

    @Override
    public RedisFuture<String> clusterSetSlotStable(int slot) {
        return dispatch(commandBuilder.clusterSetSlotStable(slot));
    }

    @Override
    public RedisFuture<List<Object>> clusterShards() {
        return dispatch(commandBuilder.clusterShards());
    }

    @Override
    public RedisFuture<List<String>> clusterSlaves(String nodeId) {
        return dispatch(commandBuilder.clusterSlaves(nodeId));
    }

    @Override
    public RedisFuture<List<Object>> clusterSlots() {
        return dispatch(commandBuilder.clusterSlots());
    }

    public RedisFuture<String> discard() {
        return dispatch(commandBuilder.discard());
    }

    @Override
    public <T> RedisFuture<T> dispatch(ProtocolKeyword type, CommandOutput<K, V, T> output) {

        LettuceAssert.notNull(type, "Command type must not be null");
        LettuceAssert.notNull(output, "CommandOutput type must not be null");

        return dispatch(new AsyncCommand<>(new Command<>(type, output)));
    }

    @Override
    public <T> RedisFuture<T> dispatch(ProtocolKeyword type, CommandOutput<K, V, T> output, CommandArgs<K, V> args) {

        LettuceAssert.notNull(type, "Command type must not be null");
        LettuceAssert.notNull(output, "CommandOutput type must not be null");
        LettuceAssert.notNull(args, "CommandArgs type must not be null");

        return dispatch(new AsyncCommand<>(new Command<>(type, output, args)));
    }

    protected <T> RedisFuture<T> dispatch(CommandType type, CommandOutput<K, V, T> output) {
        return dispatch(type, output, null);
    }

    protected <T> RedisFuture<T> dispatch(CommandType type, CommandOutput<K, V, T> output, CommandArgs<K, V> args) {
        return dispatch(new AsyncCommand<>(new Command<>(type, output, args)));
    }

    public <T> AsyncCommand<K, V, T> dispatch(RedisCommand<K, V, T> cmd) {
        AsyncCommand<K, V, T> asyncCommand = new AsyncCommand<>(cmd);
        RedisCommand<K, V, T> dispatched = connection.dispatch(asyncCommand);
        if (dispatched instanceof AsyncCommand) {
            return (AsyncCommand<K, V, T>) dispatched;
        }
        return asyncCommand;
    }

    @Override
    public RedisFuture<V> echo(V msg) {
        return dispatch(commandBuilder.echo(msg));
    }

    public RedisFuture<TransactionResult> exec() {
        return dispatch(EXEC, null);
    }

    @Override
    public RedisFuture<V> get(K key) {
        return dispatch(commandBuilder.get(key));
    }

    public StatefulConnection<K, V> getConnection() {
        return connection;
    }

    @Override
    public RedisFuture<List<KeyValue<K, V>>> mget(K... keys) {
        return dispatch(commandBuilder.mgetKeyValue(keys));
    }

    public RedisFuture<List<KeyValue<K, V>>> mget(Iterable<K> keys) {
        return dispatch(commandBuilder.mgetKeyValue(keys));
    }

    @Override
    public RedisFuture<Long> mget(KeyValueStreamingChannel<K, V> channel, K... keys) {
        return dispatch(commandBuilder.mget(channel, keys));
    }

    public RedisFuture<Long> mget(KeyValueStreamingChannel<K, V> channel, Iterable<K> keys) {
        return dispatch(commandBuilder.mget(channel, keys));
    }

    public RedisFuture<String> multi() {
        return dispatch(commandBuilder.multi());
    }

    @Override
    public RedisFuture<String> ping() {
        return dispatch(commandBuilder.ping());
    }

    @Override
    public RedisFuture<Long> publish(K channel, V message) {
        return dispatch(commandBuilder.publish(channel, message));
    }

    @Override
    public RedisFuture<Long> spublish(K shardChannel, V message) {
        return dispatch(commandBuilder.spublish(shardChannel, message));
    }

    @Override
    public RedisFuture<List<K>> pubsubChannels() {
        return dispatch(commandBuilder.pubsubChannels());
    }

    @Override
    public RedisFuture<List<K>> pubsubChannels(K channel) {
        return dispatch(commandBuilder.pubsubChannels(channel));
    }

    @Override
    public RedisFuture<Long> pubsubNumpat() {
        return dispatch(commandBuilder.pubsubNumpat());
    }

    @Override
    public RedisFuture<Map<K, Long>> pubsubNumsub(K... channels) {
        return dispatch(commandBuilder.pubsubNumsub(channels));
    }

    @Override
    public RedisFuture<List<K>> pubsubShardChannels() {
        return dispatch(commandBuilder.pubsubShardChannels());
    }

    @Override
    public RedisFuture<List<K>> pubsubShardChannels(K pattern) {
        return dispatch(commandBuilder.pubsubShardChannels(pattern));
    }

    @Override
    public RedisFuture<Map<K, Long>> pubsubShardNumsub(K... shardChannels) {
        return dispatch(commandBuilder.pubsubShardNumsub(shardChannels));
    }

    @Override
    public RedisFuture<String> quit() {
        return dispatch(commandBuilder.quit());
    }

    @Override
    public RedisFuture<String> readOnly() {
        return dispatch(commandBuilder.readOnly());
    }

    @Override
    public RedisFuture<String> readWrite() {
        return dispatch(commandBuilder.readWrite());
    }

    @Override
    public RedisFuture<List<Object>> role() {
        return dispatch(commandBuilder.role());
    }

    public RedisFuture<String> select(int db) {
        return dispatch(commandBuilder.select(db));
    }

    @Override
    public RedisFuture<String> set(K key, V value) {
        return dispatch(commandBuilder.set(key, value));
    }

    @Override
    public RedisFuture<String> set(K key, V value, SetArgs setArgs) {
        return dispatch(commandBuilder.set(key, value, setArgs));
    }

    public RedisFuture<V> setGet(K key, V value) {
        return dispatch(commandBuilder.setGet(key, value));
    }

    public RedisFuture<V> setGet(K key, V value, SetArgs setArgs) {
        return dispatch(commandBuilder.setGet(key, value, setArgs));
    }

    @Override
    public void setTimeout(Duration timeout) {
        connection.setTimeout(timeout);
    }

    public RedisFuture<String> swapdb(int db1, int db2) {
        return dispatch(commandBuilder.swapdb(db1, db2));
    }

    public RedisFuture<String> unwatch() {
        return dispatch(commandBuilder.unwatch());
    }

    @Override
    public RedisFuture<Long> waitForReplication(int replicas, long timeout) {
        return dispatch(commandBuilder.wait(replicas, timeout));
    }

    public RedisFuture<String> watch(K... keys) {
        return dispatch(commandBuilder.watch(keys));
    }

    @Override
    public RedisFuture<List<Map<String, Object>>> clusterLinks() {
        return dispatch(commandBuilder.clusterLinks());
    }

    @Override
    public JsonParser getJsonParser() {
        return this.parser.get();
    }

}
