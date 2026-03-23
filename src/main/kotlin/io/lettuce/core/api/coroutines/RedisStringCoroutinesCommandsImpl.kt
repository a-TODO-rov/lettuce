/*
 * Copyright 2020-Present, Redis Ltd. and Contributors
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

package io.lettuce.core.api.coroutines

import io.lettuce.core.*
import io.lettuce.core.api.reactive.RedisStringReactiveCommands
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull


/**
 * Coroutine executed commands (based on reactive commands) for Strings.
 *
 * @param <K> Key type.
 * @param <V> Value type.
 * @author Mikhael Sokolov
 * @author Mark Paluch
 * @since 6.0
 */
@ExperimentalLettuceCoroutinesApi
internal class RedisStringCoroutinesCommandsImpl<K : Any, V : Any>(internal val ops: RedisStringReactiveCommands<K, V>) : RedisStringCoroutinesCommands<K, V> {

    override suspend fun get(key: K): V? = ops.get(key).awaitFirstOrNull()

    override fun mget(vararg keys: K): Flow<KeyValue<K, V>> = ops.mget(*keys).asFlow()

    override suspend fun set(key: K, value: V): String? = ops.set(key, value).awaitFirstOrNull()

    override suspend fun set(key: K, value: V, setArgs: SetArgs): String? = ops.set(key, value, setArgs).awaitFirstOrNull()

}

