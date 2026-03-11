# Reactor Usage Analysis in Lettuce

This document catalogs Reactor (Project Reactor) usage in the Lettuce codebase, organized by logical groups. It serves as a reference inventory for the "Reactor Optional Dependency" initiative.

## Summary

Reactor types are used throughout Lettuce, both for the reactive API feature (legitimate usage) and in internal/public infrastructure (areas that need refactoring to make Reactor optional).

**Primary Reactor imports:**
- `Mono` - single async value (most common)
- `Flux` - stream of values
- `Tuple2/Tuples` - pair values
- `Context` - reactive context propagation
- `Sinks` - programmatic signal emission
- `FluxSink` - Pub/Sub streaming
- Other - Schedulers, Hooks, Exceptions, etc.

**Goal:** Make Reactor optional for users who only need sync/async APIs.

**Related documents:**
- `docs/implementation-plan/EXECUTIVE_SUMMARY.md` - Solution overview
- `docs/implementation-plan/RELEASE_ROADMAP.md` - Release strategy
- `docs/implementation-plan/G1_REACTIVE_API.md` through `G8_*.md` - Implementation details

---

## Reactor Types - Quick Reference

| Type | Values | Completion | Use Case |
|------|--------|------------|----------|
| `CompletableFuture` | 0-1 | Once | One-shot async operations |
| `Mono` | 0-1 | Once | Same as CF, but with reactive operators |
| `Flux` | 0-N | Eventually | Streams, subscriptions, events over time |

**Key insight**: `Flux` cannot be replaced with `CompletableFuture` because it represents multiple values over time.

---

## Group 1: Reactive API (G1)

**Status**: **No changes required** - This IS the reactive API feature

The reactive command interfaces and implementations that return `Mono<T>` and `Flux<T>`. This is the feature itself, not a "leak" to be refactored. The goal is to isolate G1 so it only loads when explicitly used.

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

See `docs/implementation-plan/G1_REACTIVE_API.md` for details.

---

## Group 2: Connection Interfaces (G2)

**Status**: **Critical blocker** - Public API with Reactor types in method signatures

**Problem:** The `reactive()` method on connection interfaces forces Reactor class loading even when not used.

**Solution:** Dual Interface Pattern - split into base interfaces (no Reactor) and reactive interfaces (has Reactor).

### Known Affected Files

| File | Location | Reactor Types | Usage |
|------|----------|---------------|-------|
| `StatefulRedisConnection` | `core/api/` | Mono (via reactive()) | Connection interface |
| `StatefulRedisClusterConnection` | `core/cluster/api/` | Mono (via reactive()) | Cluster connection interface |
| `StatefulRedisPubSubConnection` | `core/pubsub/api/` | Mono (via reactive()) | Pub/Sub connection interface |
| `StatefulRedisSentinelConnection` | `core/sentinel/api/` | Mono (via reactive()) | Sentinel connection interface |
| `StatefulRedisConnectionImpl` | `core/` | Mono | Connection implementation |
| `StatefulRedisClusterConnectionImpl` | `core/cluster/` | Mono | Cluster connection implementation |

See `docs/implementation-plan/G2_CONNECTION_INTERFACES.md` for details.

---

## Group 3: Client Infrastructure (G3)

**Status**: **Internal** - Uses Mono/Tuple2 for async operations

### Known Affected Files

| File | Location | Reactor Types | Usage |
|------|----------|---------------|-------|
| `AbstractRedisClient` | `core/` | Mono | Base client with `Mono<SocketAddress>` in connection methods |
| `RedisClient` | `core/` | Mono | Internal async connection setup |
| `RedisClusterClient` | `core/cluster/` | Mono | Internal async connection setup |
| `ConnectionBuilder` | `core/` | Mono | `Mono<SocketAddress>` socket address supplier |
| `ConnectionWatchdog` | `core/protocol/` | Mono, Tuple2 | Reconnection with `Mono<SocketAddress>` |
| `MaintenanceAwareConnectionWatchdog` | `core/protocol/` | Mono | Extends ConnectionWatchdog |
| `ReconnectionHandler` | `core/protocol/` | Mono, Tuple2, Tuples | Reconnect logic |
| `AbstractClusterNodeConnectionFactory` | `core/cluster/` | Mono | Cluster node connections |
| `RedisURI` | `core/` | Mono | Credential resolution (internal) |
| `ClientOptions` | `core/` | Mono | Socket address supplier |
| `RedisPublisher` | `core/` | CoreSubscriber, Exceptions, Context | Reactive streams bridge |
| `Operators` | `core/` | Exceptions, Hooks, Context, Queues | Reactive utilities |
| `ScanStream` | `core/` | Flux, Mono | Scan iteration helper |
| `ClusterScanSupport` | `core/cluster/` | Mono | Cluster scan helper |

### Notes
- Pattern for replacement: `Mono<SocketAddress>` to `Supplier<CompletionStage<SocketAddress>>`
- `Tuple2` to `Pair<T1, T2>` (new utility class needed)

See `docs/implementation-plan/G3_REDISCLIENT_CLUSTERLIENT.md` for details.

---

## Group 4: Master-Replica Topology (G4)

**Status**: **Internal** - Performance-critical hot path

### Known Affected Files

| File | Location | Reactor Types | Usage |
|------|----------|---------------|-------|
| `MasterReplicaConnectionProvider` | `core/masterreplica/` | Flux, Mono | Hot path for `ReadFrom` |
| `MasterReplicaTopologyRefresh` | `core/masterreplica/` | Mono | Topology refresh |
| `StaticMasterReplicaConnector` | `core/masterreplica/` | Mono | Static connector |
| `StaticMasterReplicaTopologyProvider` | `core/masterreplica/` | Flux, Mono | Static topology |
| `AutodiscoveryConnector` | `core/masterreplica/` | Mono, Tuple2, Tuples | Auto-discovery |
| `SentinelConnector` | `core/masterreplica/` | Mono | Sentinel connector |
| `SentinelTopologyProvider` | `core/masterreplica/` | Mono, Tuple2 | Sentinel topology |
| `ReplicaTopologyProvider` | `core/masterreplica/` | Mono | Replica topology |
| `Connections` | `core/masterreplica/` | Mono, Tuple2 | Connection helpers |
| `AsyncConnections` | `core/masterreplica/` | Mono, Tuples | Async connection helpers |
| `Requests` | `core/masterreplica/` | Tuple2, Tuples | Request helpers |
| `ResumeAfter` | `core/masterreplica/` | Mono | Resume helper |

### Notes
- `MasterReplicaConnectionProvider.getConnectionAsync()` is a hot path called on every read operation with `ReadFrom`
- Performance benchmarking required before and after refactoring

See `docs/implementation-plan/G4_MASTER_REPLICA.md` for details.

---

## Group 5: Event Bus (G5)

**Status**: **Public interface** - Uses Flux for event streaming

**Problem:** `EventBus.get()` returns `Flux<Event>`, forcing Reactor dependency.

**Solution:** SPI Factory Pattern - add `subscribe(Consumer<Event>)` method, use factory for runtime implementation selection.

### Known Affected Files

| File | Location | Reactor Types | Usage |
|------|----------|---------------|-------|
| `EventBus` | `core/event/` | Flux | `get()` returns event stream |
| `DefaultEventBus` | `core/event/` | Flux, Sinks, Scheduler | Event emission |

See `docs/implementation-plan/G5_EVENTBUS.md` for details.

---

## Group 6: Credentials (G6)

**Status**: **Public interface** - Uses both Mono and Flux

**Problem:** `resolveCredentials()` returns Mono, `credentials()` returns Flux.

**Solution:** Add `resolveCredentialsAsync()` returning `CompletionStage`, add `subscribeToCredentials(Consumer)` for streaming.

### Known Affected Files

| File | Location | Reactor Types | Usage |
|------|----------|---------------|-------|
| `RedisCredentialsProvider` | `core/` | Flux, Mono | `resolveCredentials()` Mono, `credentials()` Flux |
| `StaticCredentialsProvider` | `core/` | Mono | Implements with `Mono.just()` |
| `RedisAuthenticationHandler` | `core/` | Disposable, Flux | Subscribes to credential stream |
| `TokenBasedRedisCredentialsProvider` | `authx/` | Flux, Mono, Sinks | Token-based auth with streaming |

See `docs/implementation-plan/G6_CREDENTIALS.md` for details.

---

## Group 7: Tracing (G7)

**Status**: **Public interface** - Uses Mono for context retrieval

**Problem:** `getTraceContextLater()` returns Mono.

**Solution:** Add `getTraceContextAsync()` returning `CompletionStage`.

### Known Affected Files

| File | Location | Reactor Types | Usage |
|------|----------|---------------|-------|
| `Tracing` | `core/tracing/` | Mono, Context | Trace context interface |
| `TraceContextProvider` | `core/tracing/` | Mono | Get trace context |
| `BraveTracing` | `core/tracing/` | Mono | Brave implementation |
| `MicrometerTracing` | `core/tracing/` | Mono | Micrometer implementation |

See `docs/implementation-plan/G7_TRACING.md` for details.

---

## Group 8: Dynamic Resources (G8)

**Status**: **Internal** - Supports reactive API, scheduler integration

**Problem:** Classes reference Reactor types without guards, causing class loading failures.

**Solution:** Guard with `Class.forName()` checks before touching Reactor types.

### Known Affected Files

| File | Location | Reactor Types | Usage |
|------|----------|---------------|-------|
| `ReactiveTypes` | `core/dynamic/` | Flux, Mono | Type detection utilities |
| `ReactiveTypeAdapters` | `core/dynamic/` | Flux, Mono | Convert between reactive types |
| `DefaultClientResources` | `core/resource/` | Schedulers | Provides Reactor scheduler |

See `docs/implementation-plan/G8_DYNAMIC_RESOURCES.md` for details.

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

## Group Summary

| Group | Type | Status | Release |
|-------|------|--------|---------|
| G1: Reactive API | Feature | Keep as-is | N/A |
| G2: Connection Interfaces | Public API | Critical blocker | 7.x ASAP |
| G3: Client Infrastructure | Internal | Refactor | 7.x Later |
| G4: Master-Replica | Internal | Refactor + benchmark | 7.x Later |
| G5: Event Bus | Public API | Add callback API | 7.x ASAP |
| G6: Credentials | Public API | Add async API | 7.x ASAP |
| G7: Tracing | Public API | Add async API | 7.x ASAP |
| G8: Dynamic Resources | Internal | Guard loading | 7.x Later |
