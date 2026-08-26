/*
 * Copyright (c) 2026-Present, Redis Ltd. All rights reserved.
 * SPDX-License-Identifier: MIT
 */
package io.lettuce.core.dynamic;

import io.lettuce.core.internal.ReactorIncompatible;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;

import io.lettuce.core.AbstractRedisReactiveCommands;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.reactive.RedisReactiveCommands;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.reactive.RedisAdvancedClusterReactiveCommands;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.dynamic.output.CommandOutputFactoryResolver;
import io.lettuce.core.internal.LettuceAssert;
import io.lettuce.core.support.ConnectionWrapping;

/**
 * Builds the reactive dynamic-command {@link ExecutableCommandLookupStrategy}. This is Reactor-only and is removed from the
 * reactor-free distribution; {@link RedisCommandFactory} reaches it reflectively so that class carries no reactive symbol.
 */
@ReactorIncompatible
class ReactiveCommandLookupFactory {

    @SuppressWarnings({ "rawtypes", "unchecked" })
    static ExecutableCommandLookupStrategy create(List<RedisCodec<?, ?>> redisCodecs,
            CommandOutputFactoryResolver outputFactoryResolver, CommandMethodVerifier verifier,
            StatefulConnection<?, ?> connection) {

        AbstractRedisReactiveCommands reactive = getReactiveCommands(connection);
        LettuceAssert.isTrue(reactive != null, "Reactive commands is null");
        return new ReactiveExecutableCommandLookupStrategy(redisCodecs, outputFactoryResolver, verifier, reactive);
    }

    private static AbstractRedisReactiveCommands getReactiveCommands(StatefulConnection<?, ?> connection) {

        Object reactive = null;

        if (connection instanceof StatefulRedisConnection) {
            reactive = ((StatefulRedisConnection) connection).commands(RedisReactiveCommands.factory());
        }

        if (connection instanceof StatefulRedisClusterConnection) {
            reactive = ((StatefulRedisClusterConnection) connection).commands(RedisAdvancedClusterReactiveCommands.factory());
        }

        if (reactive != null && Proxy.isProxyClass(reactive.getClass())) {
            InvocationHandler invocationHandler = Proxy.getInvocationHandler(reactive);
            reactive = ConnectionWrapping.unwrap(invocationHandler);
        }

        return (AbstractRedisReactiveCommands) reactive;
    }

}
