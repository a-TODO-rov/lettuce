package io.lettuce.core.api

import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.api.coroutines.RedisCoroutinesCommandsImpl
import io.lettuce.core.api.reactive.ReactiveStatefulRedisConnection

/**
 * Extension for [ReactiveStatefulRedisConnection] to create [RedisCoroutinesCommands].
 *
 * Note: This extension requires Project Reactor on the classpath as it wraps the reactive API.
 *
 * @author Mikhael Sokolov
 * @author Mark Paluch
 * @since 6.0
 */
@ExperimentalLettuceCoroutinesApi
fun <K : Any, V : Any> ReactiveStatefulRedisConnection<K, V>.coroutines(): RedisCoroutinesCommands<K, V> = RedisCoroutinesCommandsImpl(reactive())
