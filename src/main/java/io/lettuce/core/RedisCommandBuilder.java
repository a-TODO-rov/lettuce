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

import io.lettuce.core.Range.Boundary;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.internal.LettuceAssert;
import io.lettuce.core.output.*;
import io.lettuce.core.protocol.BaseRedisCommandBuilder;
import io.lettuce.core.protocol.Command;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandKeyword;
import io.lettuce.core.protocol.CommandType;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static io.lettuce.core.protocol.CommandKeyword.*;
import static io.lettuce.core.protocol.CommandType.*;
import static io.lettuce.core.protocol.CommandType.DEL;
import static io.lettuce.core.protocol.CommandType.SAVE;

/**
 * @param <K>
 * @param <V>
 * @author Mark Paluch
 * @author Zhang Jessey
 * @author Tugdual Grall
 * @author dengliming
 * @author Mikhael Sokolov
 * @author Tihomir Mateev
 * @author Ali Takavci
 * @author Seonghwan Lee
 */
@SuppressWarnings({ "unchecked", "varargs" })
class RedisCommandBuilder<K, V> extends BaseRedisCommandBuilder<K, V> {

    RedisCommandBuilder(RedisCodec<K, V> codec) {
        super(codec);
    }

    Command<K, V, String> asking() {

        CommandArgs<K, V> args = new CommandArgs<>(codec);
        return createCommand(ASKING, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> auth(CharSequence password) {
        LettuceAssert.notNull(password, "Password " + MUST_NOT_BE_NULL);

        char[] chars = new char[password.length()];
        for (int i = 0; i < password.length(); i++) {
            chars[i] = password.charAt(i);
        }
        return auth(chars);
    }

    Command<K, V, String> auth(char[] password) {
        LettuceAssert.notNull(password, "Password " + MUST_NOT_BE_NULL);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(password);
        return createCommand(AUTH, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> auth(String username, CharSequence password) {
        LettuceAssert.notNull(username, "Username " + MUST_NOT_BE_NULL);
        LettuceAssert.isTrue(!username.isEmpty(), "Username " + MUST_NOT_BE_EMPTY);
        LettuceAssert.notNull(password, "Password " + MUST_NOT_BE_NULL);

        char[] chars = new char[password.length()];
        for (int i = 0; i < password.length(); i++) {
            chars[i] = password.charAt(i);
        }
        return auth(username, chars);
    }

    Command<K, V, String> auth(String username, char[] password) {
        LettuceAssert.notNull(username, "Username " + MUST_NOT_BE_NULL);
        LettuceAssert.isTrue(!username.isEmpty(), "Username " + MUST_NOT_BE_EMPTY);
        LettuceAssert.notNull(password, "Password " + MUST_NOT_BE_NULL);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(username).add(password);
        return createCommand(AUTH, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> clientCaching(boolean enabled) {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(CACHING).add(enabled ? YES : NO);
        return createCommand(CLIENT, new StatusOutput<>(codec), args);
    }

    Command<K, V, K> clientGetname() {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(GETNAME);
        return createCommand(CLIENT, new KeyOutput<>(codec), args);
    }

    Command<K, V, Long> clientGetredir() {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(GETREDIR);
        return createCommand(CLIENT, new IntegerOutput<>(codec), args);
    }

    Command<K, V, String> clientKill(String addr) {
        LettuceAssert.notNull(addr, "Addr " + MUST_NOT_BE_NULL);
        LettuceAssert.notEmpty(addr, "Addr " + MUST_NOT_BE_EMPTY);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(KILL).add(addr);
        return createCommand(CLIENT, new StatusOutput<>(codec), args);
    }

    Command<K, V, Long> clientKill(KillArgs killArgs) {
        LettuceAssert.notNull(killArgs, "KillArgs " + MUST_NOT_BE_NULL);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(KILL);
        killArgs.build(args);
        return createCommand(CLIENT, new IntegerOutput<>(codec), args);
    }

    Command<K, V, String> clientList() {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(LIST);
        return createCommand(CLIENT, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> clientList(ClientListArgs clientListArgs) {
        LettuceAssert.notNull(clientListArgs, "ClientListArgs " + MUST_NOT_BE_NULL);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(LIST);
        clientListArgs.build(args);
        return createCommand(CLIENT, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> clientInfo() {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(CommandKeyword.INFO);
        return createCommand(CLIENT, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> clientNoEvict(boolean on) {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add("NO-EVICT").add(on ? ON : OFF);
        return createCommand(CLIENT, new StatusOutput<>(codec), args);
    }

    Command<K, V, Long> clientId() {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(ID);
        return createCommand(CLIENT, new IntegerOutput<>(codec), args);
    }

    Command<K, V, String> clientPause(long timeout) {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(PAUSE).add(timeout);
        return createCommand(CLIENT, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> clientSetname(K name) {
        LettuceAssert.notNull(name, "Name " + MUST_NOT_BE_NULL);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(SETNAME).addKey(name);
        return createCommand(CLIENT, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> clientSetinfo(String key, String value) {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(SETINFO).add(key).add(value);
        return createCommand(CLIENT, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> clientTracking(TrackingArgs trackingArgs) {
        LettuceAssert.notNull(trackingArgs, "TrackingArgs " + MUST_NOT_BE_NULL);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(TRACKING);
        trackingArgs.build(args);
        return createCommand(CLIENT, new StatusOutput<>(codec), args);
    }

    Command<K, V, TrackingInfo> clientTrackinginfo() {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(TRACKINGINFO);

        return new Command<>(CLIENT, new ComplexOutput<>(codec, TrackingInfoParser.INSTANCE), args);
    }

    Command<K, V, Long> clientUnblock(long id, UnblockType type) {
        LettuceAssert.notNull(type, "UnblockType " + MUST_NOT_BE_NULL);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(UNBLOCK).add(id).add(type);
        return createCommand(CLIENT, new IntegerOutput<>(codec), args);
    }

    Command<K, V, String> clusterAddslots(int[] slots) {
        notEmptySlots(slots);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(ADDSLOTS);

        for (int slot : slots) {
            args.add(slot);
        }
        return createCommand(CLUSTER, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> clusterAddSlotsRange(Range<Integer>... ranges) {
        notEmptyRanges(ranges);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(ADDSLOTSRANGE);

        for (Range<Integer> range : ranges) {
            args.add(range.getLower().getValue());
            args.add(range.getUpper().getValue());
        }
        return createCommand(CLUSTER, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> clusterBumpepoch() {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(BUMPEPOCH);
        return createCommand(CLUSTER, new StatusOutput<>(codec), args);
    }

    Command<K, V, Long> clusterCountFailureReports(String nodeId) {
        assertNodeId(nodeId);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add("COUNT-FAILURE-REPORTS").add(nodeId);
        return createCommand(CLUSTER, new IntegerOutput<>(codec), args);
    }

    Command<K, V, Long> clusterCountKeysInSlot(int slot) {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(COUNTKEYSINSLOT).add(slot);
        return createCommand(CLUSTER, new IntegerOutput<>(codec), args);
    }

    Command<K, V, String> clusterDelslots(int[] slots) {
        notEmptySlots(slots);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(DELSLOTS);

        for (int slot : slots) {
            args.add(slot);
        }
        return createCommand(CLUSTER, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> clusterDelSlotsRange(Range<Integer>... ranges) {
        notEmptyRanges(ranges);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(DELSLOTSRANGE);

        for (Range<Integer> range : ranges) {
            args.add(range.getLower().getValue());
            args.add(range.getUpper().getValue());
        }
        return createCommand(CLUSTER, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> clusterFailover(boolean force) {
        return clusterFailover(force, false);
    }

    Command<K, V, String> clusterFailover(boolean force, boolean takeOver) {

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(FAILOVER);
        if (force) {
            args.add(FORCE);
        } else if (takeOver) {
            args.add(TAKEOVER);
        }
        return createCommand(CLUSTER, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> clusterFlushslots() {

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(FLUSHSLOTS);
        return createCommand(CLUSTER, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> clusterForget(String nodeId) {
        assertNodeId(nodeId);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(FORGET).add(nodeId);
        return createCommand(CLUSTER, new StatusOutput<>(codec), args);
    }

    Command<K, V, List<K>> clusterGetKeysInSlot(int slot, int count) {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(GETKEYSINSLOT).add(slot).add(count);
        return createCommand(CLUSTER, new KeyListOutput<>(codec), args);
    }

    Command<K, V, String> clusterInfo() {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(CommandType.INFO);

        return createCommand(CLUSTER, new StatusOutput<>(codec), args);
    }

    Command<K, V, Long> clusterKeyslot(K key) {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(KEYSLOT).addKey(key);
        return createCommand(CLUSTER, new IntegerOutput<>(codec), args);
    }

    Command<K, V, String> clusterMeet(String ip, int port) {
        LettuceAssert.notNull(ip, "IP " + MUST_NOT_BE_NULL);
        LettuceAssert.notEmpty(ip, "IP " + MUST_NOT_BE_EMPTY);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(MEET).add(ip).add(port);
        return createCommand(CLUSTER, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> clusterMyId() {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(MYID);

        return createCommand(CLUSTER, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> clusterMyShardId() {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(MYSHARDID);

        return createCommand(CLUSTER, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> clusterNodes() {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(NODES);

        return createCommand(CLUSTER, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> clusterReplicate(String nodeId) {
        assertNodeId(nodeId);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(REPLICATE).add(nodeId);
        return createCommand(CLUSTER, new StatusOutput<>(codec), args);
    }

    Command<K, V, List<String>> clusterReplicas(String nodeId) {
        assertNodeId(nodeId);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(REPLICAS).add(nodeId);
        return createCommand(CLUSTER, new StringListOutput<>(codec), args);
    }

    Command<K, V, String> clusterReset(boolean hard) {

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(RESET);
        if (hard) {
            args.add(HARD);
        } else {
            args.add(SOFT);
        }
        return createCommand(CLUSTER, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> clusterSaveconfig() {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(SAVECONFIG);
        return createCommand(CLUSTER, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> clusterSetConfigEpoch(long configEpoch) {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add("SET-CONFIG-EPOCH").add(configEpoch);
        return createCommand(CLUSTER, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> clusterSetSlotImporting(int slot, String nodeId) {
        assertNodeId(nodeId);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(SETSLOT).add(slot).add(IMPORTING).add(nodeId);
        return createCommand(CLUSTER, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> clusterSetSlotMigrating(int slot, String nodeId) {
        assertNodeId(nodeId);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(SETSLOT).add(slot).add(MIGRATING).add(nodeId);
        return createCommand(CLUSTER, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> clusterSetSlotNode(int slot, String nodeId) {
        assertNodeId(nodeId);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(SETSLOT).add(slot).add(NODE).add(nodeId);
        return createCommand(CLUSTER, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> clusterSetSlotStable(int slot) {

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(SETSLOT).add(slot).add(STABLE);
        return createCommand(CLUSTER, new StatusOutput<>(codec), args);
    }

    Command<K, V, List<Object>> clusterShards() {

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(SHARDS);
        return createCommand(CLUSTER, new ArrayOutput<>(codec), args);
    }

    Command<K, V, List<String>> clusterSlaves(String nodeId) {
        assertNodeId(nodeId);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(SLAVES).add(nodeId);
        return createCommand(CLUSTER, new StringListOutput<>(codec), args);
    }

    Command<K, V, List<Object>> clusterSlots() {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(SLOTS);
        return createCommand(CLUSTER, new ArrayOutput<>(codec), args);
    }

    Command<K, V, List<Object>> command() {
        CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8);
        return Command.class.cast(new Command(COMMAND, new ArrayOutput<>(StringCodec.UTF8), args));
    }

    Command<K, V, Long> commandCount() {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(COUNT);
        return createCommand(COMMAND, new IntegerOutput<>(codec), args);
    }

    Command<K, V, List<Object>> commandInfo(String... commands) {
        LettuceAssert.notNull(commands, "Commands " + MUST_NOT_BE_NULL);
        LettuceAssert.notEmpty(commands, "Commands " + MUST_NOT_BE_EMPTY);
        LettuceAssert.noNullElements(commands, "Commands " + MUST_NOT_CONTAIN_NULL_ELEMENTS);

        CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8);
        args.add(CommandKeyword.INFO);

        for (String command : commands) {
            args.add(command);
        }

        return Command.class.cast(new Command<>(COMMAND, new ArrayOutput<>(StringCodec.UTF8), args));
    }

    Command<K, V, Map<String, String>> configGet(String parameter) {
        LettuceAssert.notNull(parameter, "Parameter " + MUST_NOT_BE_NULL);
        LettuceAssert.notEmpty(parameter, "Parameter " + MUST_NOT_BE_EMPTY);

        CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8).add(GET).add(parameter);
        return Command.class.cast(new Command<>(CONFIG, new MapOutput<>(StringCodec.UTF8), args));
    }

    Command<K, V, Map<String, String>> configGet(String... parameters) {
        LettuceAssert.notNull(parameters, "Parameters " + MUST_NOT_BE_NULL);
        LettuceAssert.notEmpty(parameters, "Parameters " + MUST_NOT_BE_EMPTY);

        CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8).add(GET);
        for (String parameter : parameters) {
            args.add(parameter);
        }
        return Command.class.cast(new Command<>(CONFIG, new MapOutput<>(StringCodec.UTF8), args));
    }

    Command<K, V, String> configResetstat() {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(RESETSTAT);
        return createCommand(CONFIG, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> configRewrite() {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(REWRITE);
        return createCommand(CONFIG, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> configSet(String parameter, String value) {
        LettuceAssert.notNull(parameter, "Parameter " + MUST_NOT_BE_NULL);
        LettuceAssert.notEmpty(parameter, "Parameter " + MUST_NOT_BE_EMPTY);
        LettuceAssert.notNull(value, "Value " + MUST_NOT_BE_NULL);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(SET).add(parameter).add(value);
        return createCommand(CONFIG, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> configSet(Map<String, String> configValues) {
        LettuceAssert.notNull(configValues, "ConfigValues " + MUST_NOT_BE_NULL);
        LettuceAssert.isTrue(!configValues.isEmpty(), "ConfigValues " + MUST_NOT_BE_EMPTY);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(SET);
        configValues.forEach((parameter, value) -> {
            args.add(parameter);
            args.add(value);
        });
        return createCommand(CONFIG, new StatusOutput<>(codec), args);
    }

    Command<K, V, Long> dbsize() {
        return createCommand(DBSIZE, new IntegerOutput<>(codec));
    }

    Command<K, V, Long> del(K... keys) {
        notEmpty(keys);

        CommandArgs<K, V> args = new CommandArgs<>(codec).addKeys(keys);
        return createCommand(DEL, new IntegerOutput<>(codec), args);
    }

    Command<K, V, Long> del(Iterable<K> keys) {
        LettuceAssert.notNull(keys, "Keys " + MUST_NOT_BE_NULL);

        CommandArgs<K, V> args = new CommandArgs<>(codec).addKeys(keys);
        return createCommand(DEL, new IntegerOutput<>(codec), args);
    }

    Command<K, V, String> discard() {
        return createCommand(DISCARD, new StatusOutput<>(codec));
    }

    Command<K, V, V> echo(V msg) {
        LettuceAssert.notNull(msg, "message " + MUST_NOT_BE_NULL);

        CommandArgs<K, V> args = new CommandArgs<>(codec).addValue(msg);
        return createCommand(ECHO, new ValueOutput<>(codec), args);
    }

    <T> Command<K, V, T> eval(byte[] script, ScriptOutputType type, K[] keys, V... values) {
        return eval(script, type, false, keys, values);
    }

    <T> Command<K, V, T> eval(byte[] script, ScriptOutputType type, boolean readonly, K[] keys, V... values) {
        LettuceAssert.notNull(script, "Script " + MUST_NOT_BE_NULL);
        LettuceAssert.notNull(type, "ScriptOutputType " + MUST_NOT_BE_NULL);
        LettuceAssert.notNull(keys, "Keys " + MUST_NOT_BE_NULL);
        LettuceAssert.notNull(values, "Values " + MUST_NOT_BE_NULL);

        CommandArgs<K, V> args = new CommandArgs<>(codec);
        args.add(script).add(keys.length).addKeys(keys).addValues(values);
        CommandOutput<K, V, T> output = newScriptOutput(codec, type);

        return createCommand(readonly ? EVAL_RO : EVAL, output, args);
    }

    <T> Command<K, V, T> evalsha(String digest, ScriptOutputType type, K[] keys, V... values) {
        return evalsha(digest, type, false, keys, values);
    }

    <T> Command<K, V, T> evalsha(String digest, ScriptOutputType type, boolean readonly, K[] keys, V... values) {
        LettuceAssert.notNull(digest, "Digest " + MUST_NOT_BE_NULL);
        LettuceAssert.notEmpty(digest, "Digest " + MUST_NOT_BE_EMPTY);
        LettuceAssert.notNull(type, "ScriptOutputType " + MUST_NOT_BE_NULL);
        LettuceAssert.notNull(keys, "Keys " + MUST_NOT_BE_NULL);
        LettuceAssert.notNull(values, "Values " + MUST_NOT_BE_NULL);

        CommandArgs<K, V> args = new CommandArgs<>(codec);
        args.add(digest).add(keys.length).addKeys(keys).addValues(values);
        CommandOutput<K, V, T> output = newScriptOutput(codec, type);

        return createCommand(readonly ? EVALSHA_RO : EVALSHA, output, args);
    }

    Command<K, V, Boolean> exists(K key) {
        notNullKey(key);

        return createCommand(EXISTS, new BooleanOutput<>(codec), key);
    }

    Command<K, V, Long> exists(K... keys) {
        notEmpty(keys);

        return createCommand(EXISTS, new IntegerOutput<>(codec), new CommandArgs<>(codec).addKeys(keys));
    }

    Command<K, V, Long> exists(Iterable<K> keys) {
        LettuceAssert.notNull(keys, "Keys " + MUST_NOT_BE_NULL);

        return createCommand(EXISTS, new IntegerOutput<>(codec), new CommandArgs<>(codec).addKeys(keys));
    }

    Command<K, V, String> flushall() {
        return createCommand(FLUSHALL, new StatusOutput<>(codec));
    }

    Command<K, V, String> flushall(FlushMode flushMode) {
        LettuceAssert.notNull(flushMode, "FlushMode " + MUST_NOT_BE_NULL);

        return createCommand(FLUSHALL, new StatusOutput<>(codec), new CommandArgs<>(codec).add(flushMode));
    }

    Command<K, V, String> flushdb() {
        return createCommand(FLUSHDB, new StatusOutput<>(codec));
    }

    Command<K, V, String> flushdb(FlushMode flushMode) {
        LettuceAssert.notNull(flushMode, "FlushMode " + MUST_NOT_BE_NULL);

        return createCommand(FLUSHDB, new StatusOutput<>(codec), new CommandArgs<>(codec).add(flushMode));
    }

    <T> Command<K, V, T> fcall(String function, ScriptOutputType type, boolean readonly, K[] keys, V... values) {
        LettuceAssert.notEmpty(function, "Function " + MUST_NOT_BE_EMPTY);
        LettuceAssert.notNull(type, "ScriptOutputType " + MUST_NOT_BE_NULL);
        LettuceAssert.notNull(keys, "Keys " + MUST_NOT_BE_NULL);
        LettuceAssert.notNull(values, "Values " + MUST_NOT_BE_NULL);

        CommandArgs<K, V> args = new CommandArgs<>(codec);
        args.add(function).add(keys.length).addKeys(keys).addValues(values);
        CommandOutput<K, V, T> output = newScriptOutput(codec, type);

        return createCommand(readonly ? FCALL_RO : FCALL, output, args);
    }

    Command<K, V, String> functionLoad(byte[] functionCode, boolean replace) {
        LettuceAssert.notNull(functionCode, "Function code " + MUST_NOT_BE_NULL);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(LOAD);
        if (replace) {
            args.add(REPLACE);
        }
        args.add(functionCode);

        return createCommand(FUNCTION, new StatusOutput<>(codec), args);
    }

    Command<K, V, byte[]> functionDump() {
        return createCommand(FUNCTION, new ByteArrayOutput<>(codec), new CommandArgs<>(codec).add(DUMP));
    }

    Command<K, V, String> functionRestore(byte dump[], FunctionRestoreMode mode) {

        LettuceAssert.notNull(dump, "Function dump " + MUST_NOT_BE_NULL);
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(RESTORE).add(dump);

        if (mode != null) {
            args.add(mode);
        }

        return createCommand(FUNCTION, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> functionFlush(FlushMode mode) {

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(FLUSH);

        if (mode != null) {
            args.add(mode);
        }

        return createCommand(FUNCTION, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> functionKill() {
        return createCommand(FUNCTION, new StatusOutput<>(codec), new CommandArgs<>(codec).add(KILL));
    }

    Command<K, V, List<Map<String, Object>>> functionList(String pattern) {

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(LIST);

        if (pattern != null) {
            args.add("LIBRARYNAME").add(pattern);
        }

        return createCommand(FUNCTION, (CommandOutput) new ObjectOutput<>(StringCodec.UTF8), args);
    }

    Command<K, V, V> get(K key) {
        notNullKey(key);

        return createCommand(GET, new ValueOutput<>(codec), key);
    }

    Command<String, String, Map<String, Object>> hello(int protocolVersion, String user, char[] password, String name) {

        CommandArgs<String, String> args = new CommandArgs<>(StringCodec.ASCII).add(protocolVersion);

        if (user != null && password != null) {
            args.add(AUTH).add(user).add(password);
        }

        if (name != null) {
            args.add(SETNAME).add(name);
        }

        return new Command<>(HELLO, new GenericMapOutput<>(StringCodec.ASCII), args);
    }

    Command<K, V, String> info() {
        return createCommand(CommandType.INFO, new StatusOutput<>(codec));
    }

    Command<K, V, String> info(String section) {
        LettuceAssert.notNull(section, "Section " + MUST_NOT_BE_NULL);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(section);
        return createCommand(CommandType.INFO, new StatusOutput<>(codec), args);
    }

    Command<K, V, List<V>> mget(K... keys) {
        notEmpty(keys);

        CommandArgs<K, V> args = new CommandArgs<>(codec).addKeys(keys);
        return createCommand(MGET, new ValueListOutput<>(codec), args);
    }

    Command<K, V, List<V>> mget(Iterable<K> keys) {
        LettuceAssert.notNull(keys, "Keys " + MUST_NOT_BE_NULL);

        CommandArgs<K, V> args = new CommandArgs<>(codec).addKeys(keys);
        return createCommand(MGET, new ValueListOutput<>(codec), args);
    }

    Command<K, V, Long> mget(ValueStreamingChannel<V> channel, K... keys) {
        notEmpty(keys);
        notNull(channel);

        CommandArgs<K, V> args = new CommandArgs<>(codec).addKeys(keys);
        return createCommand(MGET, new ValueStreamingOutput<>(codec, channel), args);
    }

    Command<K, V, Long> mget(KeyValueStreamingChannel<K, V> channel, K... keys) {
        notEmpty(keys);
        notNull(channel);

        CommandArgs<K, V> args = new CommandArgs<>(codec).addKeys(keys);
        return createCommand(MGET, new KeyValueStreamingOutput<>(codec, channel, Arrays.asList(keys)), args);
    }

    Command<K, V, Long> mget(ValueStreamingChannel<V> channel, Iterable<K> keys) {
        LettuceAssert.notNull(keys, "Keys " + MUST_NOT_BE_NULL);
        notNull(channel);

        CommandArgs<K, V> args = new CommandArgs<>(codec).addKeys(keys);
        return createCommand(MGET, new ValueStreamingOutput<>(codec, channel), args);
    }

    Command<K, V, Long> mget(KeyValueStreamingChannel<K, V> channel, Iterable<K> keys) {
        LettuceAssert.notNull(keys, "Keys " + MUST_NOT_BE_NULL);
        notNull(channel);

        CommandArgs<K, V> args = new CommandArgs<>(codec).addKeys(keys);
        return createCommand(MGET, new KeyValueStreamingOutput<>(codec, channel, keys), args);
    }

    Command<K, V, List<KeyValue<K, V>>> mgetKeyValue(K... keys) {
        notEmpty(keys);

        CommandArgs<K, V> args = new CommandArgs<>(codec).addKeys(keys);
        return createCommand(MGET, new KeyValueListOutput<>(codec, Arrays.asList(keys)), args);
    }

    Command<K, V, List<KeyValue<K, V>>> mgetKeyValue(Iterable<K> keys) {
        LettuceAssert.notNull(keys, "Keys " + MUST_NOT_BE_NULL);

        CommandArgs<K, V> args = new CommandArgs<>(codec).addKeys(keys);
        return createCommand(MGET, new KeyValueListOutput<>(codec, keys), args);
    }

    Command<K, V, String> multi() {
        return createCommand(MULTI, new StatusOutput<>(codec));
    }

    Command<K, V, String> ping() {
        return createCommand(PING, new StatusOutput<>(codec));
    }

    Command<K, V, Long> publish(K channel, V message) {
        LettuceAssert.notNull(channel, "Channel " + MUST_NOT_BE_NULL);

        CommandArgs<K, V> args = new CommandArgs<>(codec).addKey(channel).addValue(message);
        return createCommand(PUBLISH, new IntegerOutput<>(codec), args);
    }

    Command<K, V, List<K>> pubsubChannels() {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(CHANNELS);
        return createCommand(PUBSUB, new KeyListOutput<>(codec), args);
    }

    Command<K, V, List<K>> pubsubChannels(K pattern) {
        LettuceAssert.notNull(pattern, "Pattern " + MUST_NOT_BE_NULL);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(CHANNELS).addKey(pattern);
        return createCommand(PUBSUB, new KeyListOutput<>(codec), args);
    }

    Command<K, V, Long> pubsubNumpat() {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(NUMPAT);
        return createCommand(PUBSUB, new IntegerOutput<>(codec), args);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })

    Command<K, V, Map<K, Long>> pubsubNumsub(K... channels) {
        LettuceAssert.notNull(channels, "Channels " + MUST_NOT_BE_NULL);
        LettuceAssert.notEmpty(channels, "Channels " + MUST_NOT_BE_EMPTY);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(NUMSUB).addKeys(channels);
        return createCommand(PUBSUB, (MapOutput) new MapOutput<K, Long>((RedisCodec) codec), args);
    }

    Command<K, V, List<K>> pubsubShardChannels() {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(SHARDCHANNELS);
        return createCommand(PUBSUB, new KeyListOutput<>(codec), args);
    }

    Command<K, V, List<K>> pubsubShardChannels(K pattern) {
        LettuceAssert.notNull(pattern, "Pattern " + MUST_NOT_BE_NULL);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(SHARDCHANNELS).addKey(pattern);
        return createCommand(PUBSUB, new KeyListOutput<>(codec), args);
    }

    Command<K, V, Map<K, Long>> pubsubShardNumsub(K... shardChannels) {
        LettuceAssert.notNull(shardChannels, "ShardChannels " + MUST_NOT_BE_NULL);
        LettuceAssert.notEmpty(shardChannels, "ShardChannels " + MUST_NOT_BE_EMPTY);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(SHARDNUMSUB).addKeys(shardChannels);
        return createCommand(PUBSUB, (MapOutput) new MapOutput<K, Long>((RedisCodec) codec), args);
    }

    Command<K, V, String> quit() {
        return createCommand(QUIT, new StatusOutput<>(codec));
    }

    Command<K, V, K> randomkey() {
        return createCommand(RANDOMKEY, new KeyOutput<>(codec));
    }

    Command<K, V, String> readOnly() {
        return createCommand(READONLY, new StatusOutput<>(codec));
    }

    Command<K, V, String> readWrite() {
        return createCommand(READWRITE, new StatusOutput<>(codec));
    }

    Command<K, V, String> replicaof(String host, int port) {
        LettuceAssert.notNull(host, "Host " + MUST_NOT_BE_NULL);
        LettuceAssert.notEmpty(host, "Host " + MUST_NOT_BE_EMPTY);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(host).add(port);
        return createCommand(REPLICAOF, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> replicaofNoOne() {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(NO).add(ONE);
        return createCommand(REPLICAOF, new StatusOutput<>(codec), args);
    }

    Command<K, V, List<Object>> role() {
        return createCommand(ROLE, new ArrayOutput<>(codec));
    }

    protected void scanArgs(ScanCursor scanCursor, ScanArgs scanArgs, CommandArgs<K, V> args) {
        LettuceAssert.notNull(scanCursor, "ScanCursor " + MUST_NOT_BE_NULL);
        LettuceAssert.isTrue(!scanCursor.isFinished(), "ScanCursor must not be finished");
        args.add(scanCursor.getCursor());
        if (scanArgs != null) {
            scanArgs.build(args);
        }
    }

    Command<K, V, List<Boolean>> scriptExists(String... digests) {
        LettuceAssert.notNull(digests, "Digests " + MUST_NOT_BE_NULL);
        LettuceAssert.notEmpty(digests, "Digests " + MUST_NOT_BE_EMPTY);
        LettuceAssert.noNullElements(digests, "Digests " + MUST_NOT_CONTAIN_NULL_ELEMENTS);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(EXISTS);
        for (String sha : digests) {
            args.add(sha);
        }
        return createCommand(SCRIPT, new BooleanListOutput<>(codec), args);
    }

    Command<K, V, String> scriptFlush() {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(FLUSH);
        return createCommand(SCRIPT, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> scriptFlush(FlushMode flushMode) {
        LettuceAssert.notNull(flushMode, "FlushMode " + MUST_NOT_BE_NULL);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(FLUSH).add(flushMode.name());
        return createCommand(SCRIPT, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> scriptKill() {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(KILL);
        return createCommand(SCRIPT, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> scriptLoad(byte[] script) {
        LettuceAssert.notNull(script, "Script " + MUST_NOT_BE_NULL);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(LOAD).add(script);
        return createCommand(SCRIPT, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> select(int db) {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(db);
        return createCommand(SELECT, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> set(K key, V value) {
        notNullKey(key);

        return createCommand(SET, new StatusOutput<>(codec), key, value);
    }

    Command<K, V, String> set(K key, V value, SetArgs setArgs) {
        notNullKey(key);

        CommandArgs<K, V> args = new CommandArgs<>(codec).addKey(key).addValue(value);
        setArgs.build(args);
        return createCommand(SET, new StatusOutput<>(codec), args);
    }

    Command<K, V, V> setGet(K key, V value) {
        return setGet(key, value, new SetArgs());
    }

    Command<K, V, V> setGet(K key, V value, SetArgs setArgs) {
        notNullKey(key);
        LettuceAssert.notNull(setArgs, "SetArgs " + MUST_NOT_BE_NULL);

        CommandArgs<K, V> args = new CommandArgs<>(codec).addKey(key).addValue(value);
        setArgs.build(args);
        args.add(GET);

        return createCommand(SET, new ValueOutput<>(codec), args);
    }

    Command<K, V, String> shutdown(boolean save) {
        CommandArgs<K, V> args = new CommandArgs<>(codec);
        return createCommand(SHUTDOWN, new StatusOutput<>(codec), save ? args.add(SAVE) : args.add(NOSAVE));
    }

    Command<K, V, String> shutdown(ShutdownArgs shutdownArgs) {
        LettuceAssert.notNull(shutdownArgs, "shutdownArgs " + MUST_NOT_BE_NULL);

        CommandArgs<K, V> args = new CommandArgs<>(codec);
        shutdownArgs.build(args);
        return createCommand(SHUTDOWN, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> slaveof(String host, int port) {
        LettuceAssert.notNull(host, "Host " + MUST_NOT_BE_NULL);
        LettuceAssert.notEmpty(host, "Host " + MUST_NOT_BE_EMPTY);

        CommandArgs<K, V> args = new CommandArgs<>(codec).add(host).add(port);
        return createCommand(SLAVEOF, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> slaveofNoOne() {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(NO).add(ONE);
        return createCommand(SLAVEOF, new StatusOutput<>(codec), args);
    }

    Command<K, V, Long> spublish(K shardChannel, V message) {
        LettuceAssert.notNull(shardChannel, "ShardChannel " + MUST_NOT_BE_NULL);

        CommandArgs<K, V> args = new CommandArgs<>(codec).addKey(shardChannel).addValue(message);
        return createCommand(SPUBLISH, new IntegerOutput<>(codec), args);
    }

    Command<K, V, String> swapdb(int db1, int db2) {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(db1).add(db2);
        return createCommand(SWAPDB, new StatusOutput<>(codec), args);
    }

    Command<K, V, String> sync() {
        return createCommand(SYNC, new StatusOutput<>(codec));
    }

    Command<K, V, List<V>> time() {
        CommandArgs<K, V> args = new CommandArgs<>(codec);
        return createCommand(TIME, new ValueListOutput<>(codec), args);
    }

    Command<K, V, String> type(K key) {
        notNullKey(key);

        return createCommand(CommandType.TYPE, new StatusOutput<>(codec), key);
    }

    Command<K, V, String> unwatch() {
        return createCommand(UNWATCH, new StatusOutput<>(codec));
    }

    Command<K, V, Long> wait(int replicas, long timeout) {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(replicas).add(timeout);

        return createCommand(WAIT, new IntegerOutput<>(codec), args);
    }

    Command<K, V, String> watch(K... keys) {
        notEmpty(keys);

        CommandArgs<K, V> args = new CommandArgs<>(codec).addKeys(keys);
        return createCommand(WATCH, new StatusOutput<>(codec), args);
    }

    private static String getLowerValue(Range<String> range) {

        Boundary<String> boundary = range.getLower();

        return boundary.equals(Boundary.unbounded()) ? "-" : getRange(boundary);
    }

    private static String getUpperValue(Range<String> range) {

        Boundary<String> boundary = range.getUpper();

        return boundary.equals(Boundary.unbounded()) ? "+" : getRange(boundary);
    }

    private static String getRange(Boundary<String> boundary) {
        return !boundary.isIncluding() ? "(" + boundary.getValue() : boundary.getValue();
    }

    private byte[] encode(K k) {

        ByteBuffer byteBuffer = codec.encodeKey(k);

        byte[] result = new byte[byteBuffer.remaining()];
        byteBuffer.get(result);

        return result;
    }

    @SuppressWarnings("unchecked")

    Command<K, V, List<Map<String, Object>>> clusterLinks() {
        CommandArgs<K, V> args = new CommandArgs<>(codec).add(LINKS);
        return createCommand(CLUSTER, (CommandOutput) new ObjectOutput<>(StringCodec.UTF8), args);
    }

    enum LongCodec implements RedisCodec<Long, Long> {

        INSTANCE;

        @Override
        public Long decodeKey(ByteBuffer bytes) {
            String s = StringCodec.ASCII.decodeKey(bytes);
            return s == null ? null : Long.valueOf(s);
        }

        @Override
        public Long decodeValue(ByteBuffer bytes) {
            return decodeKey(bytes);
        }

        @Override
        public ByteBuffer encodeKey(Long key) {
            return StringCodec.ASCII.encodeKey(key == null ? null : key.toString());
        }

        @Override
        public ByteBuffer encodeValue(Long value) {
            return encodeKey(value);
        }

    }

}
