# Reactor Usage Analysis in Lettuce

This document catalogs all Reactor (Project Reactor) usage in the Lettuce codebase, organized by logical groups.

## Summary

- **Total files using Reactor**: 72
- **Files outside reactive API**: 48 (these need refactoring)
- **Primary imports**:
  - `Mono`: 55+ files (single async value)
  - `Flux`: 37+ files (stream of values)
  - `Tuple2/Tuples`: 10 files (pair values)
  - `Context`: 3 files (reactive context propagation)
  - `Sinks`: 2 files (programmatic signal emission)
  - `FluxSink`: 2 files (Pub/Sub streaming)
  - Other: 5 files (Schedulers, Hooks, Exceptions, etc.)

---

## Reactor Types - Quick Reference

| Type | Values | Completion | Use Case |
|------|--------|------------|----------|
| `CompletableFuture` | 0-1 | Once | One-shot async operations |
| `Mono` | 0-1 | Once | Same as CF, but with reactive operators |
| `Flux` | 0-N | Eventually | Streams, subscriptions, events over time |

**Key insight**: `Flux` cannot be replaced with `CompletableFuture` because it represents multiple values over time.

---

## Group 1: Reactive API (Core Feature)

**Status**: ❌ **Cannot remove** - This IS the reactive API feature

### Interfaces (Public API)

| File | Location | Reactor Types |
|------|----------|---------------|
| `BaseRedisReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RedisReactiveCommands` | `core/api/reactive/` | Mono |
| `RedisHashReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RedisStringReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RedisSetReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RedisSortedSetReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RedisListReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RedisKeyReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RedisStreamReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RedisGeoReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RedisServerReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RedisAclReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RedisScriptingReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RedisFunctionReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RedisJsonReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RedisVectorSetReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RediSearchReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RedisHLLReactiveCommands` | `core/api/reactive/` | Mono |
| `RedisTransactionalReactiveCommands` | `core/api/reactive/` | Mono |
| `RedisPubSubReactiveCommands` | `core/pubsub/api/reactive/` | Flux, FluxSink, Mono |
| `RedisSentinelReactiveCommands` | `core/sentinel/api/reactive/` | Flux, Mono |
| `RedisClusterReactiveCommands` | `core/cluster/api/reactive/` | Flux, Mono |
| `RedisAdvancedClusterReactiveCommands` | `core/cluster/api/reactive/` | Flux, Mono |
| `ReactiveExecutions` | `core/cluster/api/reactive/` | Flux |

### Implementations

| File | Location | Reactor Types |
|------|----------|---------------|
| `AbstractRedisReactiveCommands` | `core/` | Flux, Mono |
| `RedisReactiveCommandsImpl` | `core/` | Mono |
| `RedisPubSubReactiveCommandsImpl` | `core/pubsub/` | Flux, FluxSink, Mono |
| `RedisSentinelReactiveCommandsImpl` | `core/sentinel/` | Flux, Mono |
| `RedisAdvancedClusterReactiveCommandsImpl` | `core/cluster/` | Flux, Mono |
| `RedisClusterPubSubReactiveCommandsImpl` | `core/cluster/` | Flux |
| `ReactiveExecutionsImpl` | `core/cluster/` | Flux, Mono |

---

## Group 2: Client & Connection Infrastructure

**Status**: 🟡 **Internal** - Could potentially use CompletableFuture

| File | Location | Reactor Types | Usage |
|------|----------|---------------|-------|
| `AbstractRedisClient` | `core/` | Mono | Base client with `Mono<SocketAddress>` in connection methods |
| `RedisClient` | `core/` | Mono | Internal async connection setup |
| `RedisClusterClient` | `core/cluster/` | Mono | Internal async connection setup |
| `ConnectionBuilder` | `core/` | Mono | `Mono<SocketAddress>` socket address supplier |
| `ConnectionWatchdog` | `core/protocol/` | Mono, Tuple2 | Reconnection with `Mono<SocketAddress>` |
| `MaintenanceAwareConnectionWatchdog` | `core/protocol/` | Mono | Extends ConnectionWatchdog with `Mono<SocketAddress>` |
| `ReconnectionHandler` | `core/protocol/` | Mono, Tuple2, Tuples | Reconnect logic with `Mono<SocketAddress>` |
| `AbstractClusterNodeConnectionFactory` | `core/cluster/` | Mono | `Mono<SocketAddress>` for cluster node connections |
| `RedisURI` | `core/` | Mono | Credential resolution (internal) |
| `ClientOptions` | `core/` | Mono | Socket address supplier |
| `StatefulRedisConnectionImpl` | `core/` | Mono | Close handling |
| `StatefulRedisClusterConnectionImpl` | `core/cluster/` | Mono | Close handling |
| `RedisPublisher` | `core/` | CoreSubscriber, Exceptions, Context | Reactive streams bridge |
| `Operators` | `core/` | Exceptions, Hooks, Context, Queues | Reactive utilities |
| `ScanStream` | `core/` | Flux, Mono | Scan iteration helper |
| `ClusterScanSupport` | `core/cluster/` | Mono | Cluster scan helper |

### Notes
- The `protocol/` package files (`ConnectionWatchdog`, `ReconnectionHandler`, etc.) use `Mono<SocketAddress>` for async address resolution
- `Tuple2` is used to pair socket addresses with connection metadata
- Pattern for replacement: `Mono<SocketAddress>` → `Supplier<CompletionStage<SocketAddress>>`
- `Tuple2` → `Pair<T1, T2>` (new utility class needed)

---

## Group 3: Master-Replica Topology

**Status**: 🟡 **Internal** - Could potentially use CompletableFuture

| File | Location | Reactor Types |
|------|----------|---------------|
| `MasterReplicaConnectionProvider` | `core/masterreplica/` | Flux, Mono |
| `MasterReplicaTopologyRefresh` | `core/masterreplica/` | Mono |
| `StaticMasterReplicaConnector` | `core/masterreplica/` | Mono |
| `StaticMasterReplicaTopologyProvider` | `core/masterreplica/` | Flux, Mono |
| `AutodiscoveryConnector` | `core/masterreplica/` | Mono, Tuple2, Tuples |
| `SentinelConnector` | `core/masterreplica/` | Mono |
| `SentinelTopologyProvider` | `core/masterreplica/` | Mono, Tuple2 |
| `ReplicaTopologyProvider` | `core/masterreplica/` | Mono |
| `Connections` | `core/masterreplica/` | Mono, Tuple2 |
| `AsyncConnections` | `core/masterreplica/` | Mono, Tuples |
| `Requests` | `core/masterreplica/` | Tuple2, Tuples |
| `ResumeAfter` | `core/masterreplica/` | Mono |

---

## Group 4: Credentials & Authentication

**Status**: 🟡 **Needs refactoring** - Uses both `Mono` and `Flux`

| File | Location | Reactor Types | Usage |
|------|----------|---------------|-------|
| `RedisCredentialsProvider` | `core/` | Flux, Mono | `resolveCredentials()` uses Mono, `credentials()` returns Flux |
| `StaticCredentialsProvider` | `core/` | Mono | Implements `RedisCredentialsProvider` with `Mono.just()` |
| `RedisAuthenticationHandler` | `core/` | Disposable, Flux | Subscribes to credential stream |
| `TokenBasedRedisCredentialsProvider` | `authx/` | Flux, Mono, Sinks | Token-based auth with streaming |

### Notes
- `resolveCredentials()` returns `Mono<RedisCredentials>` - can be changed to `CompletionStage<RedisCredentials>`
- `credentials()` returns `Flux<RedisCredentials>` - streams multiple credential updates over time (cannot use CompletableFuture)
- For streaming credentials, need alternative like `subscribe(Consumer<RedisCredentials>)` pattern

---

## Group 5: Tracing

**Status**: 🟡 **Public interface** - Uses Mono for context retrieval

| File | Location | Reactor Types | Usage |
|------|----------|---------------|-------|
| `Tracing` | `core/tracing/` | Mono, Context | Trace context interface |
| `TraceContextProvider` | `core/tracing/` | Mono | Get trace context |
| `BraveTracing` | `core/tracing/` | Mono | Brave implementation |
| `MicrometerTracing` | `core/tracing/` | Mono | Micrometer implementation |

---

## Group 6: Event Bus

**Status**: 🟡 **Public interface** - Uses Flux for event streaming

| File | Location | Reactor Types | Usage |
|------|----------|---------------|-------|
| `EventBus` | `core/event/` | Flux | `get()` returns event stream |
| `DefaultEventBus` | `core/event/` | Flux, Sinks, Scheduler | Event emission |

### Notes
- `EventBus.get()` returns `Flux<Event>` - this is a streaming use case
- Events are emitted over time, so Flux is semantically correct

---

## Group 7: Dynamic Commands / Type Adapters

**Status**: 🟡 **Supports reactive API**

| File | Location | Reactor Types | Usage |
|------|----------|---------------|-------|
| `ReactiveTypes` | `core/dynamic/` | Flux, Mono | Type detection utilities |
| `ReactiveTypeAdapters` | `core/dynamic/` | Flux, Mono | Convert between reactive types |

---

## Group 8: Resources

**Status**: 🟡 **Internal** - Scheduler integration

| File | Location | Reactor Types | Usage |
|------|----------|---------------|-------|
| `DefaultClientResources` | `core/resource/` | Schedulers | Provides Reactor scheduler |

---

## Refactoring Considerations

### Can be refactored to CompletableFuture
- Single-value async operations (currently using `Mono`)
- Internal connection setup
- Topology discovery (single result)
- Tracing context retrieval

### Cannot be refactored to CompletableFuture
- Reactive command API (Flux/Mono are the feature)
- Streaming credentials (`credentials()`)
- Event bus (`Flux<Event>`)
- Pub/Sub message streams

### Trade-offs
- Lettuce already depends on Reactor for its reactive API
- Removing Reactor from internal code provides limited benefit
- Public API changes require careful consideration of backward compatibility

---

## Replacement Patterns

| Reactor Type | Replacement | Notes |
|--------------|-------------|-------|
| `Mono<T>` | `CompletionStage<T>` / `CompletableFuture<T>` | Single async value |
| `Mono<SocketAddress>` | `Supplier<CompletionStage<SocketAddress>>` | Lazy evaluation needed |
| `Tuple2<T1, T2>` | `Pair<T1, T2>` | New utility class |
| `Tuples.of(a, b)` | `Pair.of(a, b)` | Factory method |
| `Flux<T>` | `subscribe(Consumer<T>)` pattern | For streaming (cannot use CF) |
| `Sinks.Many<T>` | Callback-based emission | Internal implementation |
| `Disposable` | `Closeable` / `AutoCloseable` | Subscription management |

---

## File Count by Group

| Group | Files | Priority |
|-------|-------|----------|
| G1: Reactive API | 24+ | ❌ Cannot change (IS the feature) |
| G2: Client & Connection | 16 | High - core infrastructure |
| G3: Master-Replica | 12 | Medium |
| G4: Credentials | 4 | High - public interface |
| G5: Tracing | 4 | Medium - public interface |
| G6: Event Bus | 2 | Medium - public interface |
| G7: Dynamic Commands | 2 | Low - supports reactive API |
| G8: Resources | 1 | Low |

