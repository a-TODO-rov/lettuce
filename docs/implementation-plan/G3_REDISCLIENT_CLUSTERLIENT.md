# G3 - Client & Connection Infrastructure Implementation Plan

**Status:** Required - Internal refactoring
**Dependencies:** G2 (Connection Interfaces)
**Blocks:** G4 (Master-Replica)

---

## Problem Statement

`RedisClient`, `RedisClusterClient`, and the underlying connection infrastructure use Reactor's `Mono` for:
- Connection orchestration and retry logic
- Socket address resolution (async address suppliers)
- Reconnection handling
- Resource management

**Key insight:** Unlike G2, these are **internal implementation details** - no public API changes required. All changes are non-breaking.

---

## Affected Files (10 files + 2 new)

### Client Classes

| File | Reactor Types | Purpose |
|------|---------------|---------|
| `src/main/java/io/lettuce/core/RedisClient.java` | `Mono` | Sentinel connection loops, master lookup |
| `src/main/java/io/lettuce/core/cluster/RedisClusterClient.java` | `Mono` | Cluster connection retry |
| `src/main/java/io/lettuce/core/AbstractRedisClient.java` | `Mono` | `Mono<SocketAddress>` in connection builder |

### Connection Infrastructure

| File | Reactor Types | Purpose |
|------|---------------|---------|
| `src/main/java/io/lettuce/core/ConnectionBuilder.java` | `Mono` | Socket address supplier |
| `src/main/java/io/lettuce/core/protocol/ConnectionWatchdog.java` | `Mono`, `Tuple2` | Reconnection logic |
| `src/main/java/io/lettuce/core/protocol/MaintenanceAwareConnectionWatchdog.java` | `Mono` | Extends ConnectionWatchdog |
| `src/main/java/io/lettuce/core/protocol/ReconnectionHandler.java` | `Mono`, `Tuple2`, `Tuples` | Reconnect with timeout |
| `src/main/java/io/lettuce/core/cluster/AbstractClusterNodeConnectionFactory.java` | `Mono` | Cluster node address resolution |

### Additional Files (from analysis)

| File | Reactor Types | Purpose |
|------|---------------|---------|
| `src/main/java/io/lettuce/core/RedisURI.java` | `Mono` | Credential resolution |
| `src/main/java/io/lettuce/core/ClientOptions.java` | `Mono` | Socket address supplier |

### New Utility Classes

| File | Purpose |
|------|---------|
| `src/main/java/io/lettuce/core/internal/Pair.java` | Replace Reactor's `Tuple2` |
| `src/main/java/io/lettuce/core/internal/Futures.java` | Async utility methods |

---

## Implementation Strategy

### Core Pattern: `Mono<SocketAddress>` → `Supplier<CompletionStage<SocketAddress>>`

The connection infrastructure uses `Mono<SocketAddress>` for **lazy async address resolution**. The `Supplier` wrapper preserves this laziness:

```java
// Current (Reactor)
Mono<SocketAddress> socketAddressSupplier;

// Replacement (CompletableFuture)
Supplier<CompletionStage<SocketAddress>> socketAddressSupplier;
```

### Key Reactor Pattern Replacements

| Reactor Pattern | CompletableFuture Equivalent |
|-----------------|------------------------------|
| `Mono.fromCompletionStage()` | Direct use of `CompletionStage` |
| `mono.onErrorResume(t -> ...)` | `cf.exceptionallyCompose(t -> ...)` |
| `Mono.defer(() -> ...)` | `Supplier<CompletionStage<T>>` |
| `mono.map(fn)` | `cf.thenApply(fn)` |
| `mono.flatMap(fn)` | `cf.thenCompose(fn)` |
| `Mono.usingWhen(...)` | `cf.whenComplete((r, ex) -> cleanup())` |
| `Tuple2<A, B>` | `Pair<A, B>` (new utility) |

---

## Breaking vs Non-Breaking Changes

### Non-Breaking (All changes)
- All Reactor usage is internal implementation ✅
- No public API changes ✅
- Clients continue to use `connect()`, `connectAsync()`, etc. ✅

### Breaking
- **None** ✅

---

## Hotspots & Considerations

### 1. `lookupRedis()` Uses Reactive API

**Problem:** `RedisClient.lookupRedis()` uses `c.reactive().getMasterAddrByName()` which returns `Mono`.

**Solution:** Change to use async API:
```java
// Before
c.reactive().getMasterAddrByName(sentinelMasterId).map(...)

// After
c.async().getMasterAddrByName(sentinelMasterId).thenApply(...)
```

### 2. Retry Loop Semantics

Current Reactor retry loops:
```java
Mono<Connection> connectionMono = Mono.defer(() -> connect(...));
for (int i = 1; i < getConnectionAttempts(); i++) {
    connectionMono = connectionMono.onErrorResume(t -> connect(...));
}
```

CF replacement must preserve lazy sequential behavior - use utility method:
```java
Futures.retryAsync(() -> connect(...), getConnectionAttempts(), logger::warn);
```

### 3. No Blocking on Event Loop

All replacements must remain non-blocking. Avoid `join()` and `get()` inside async chains.

### 4. Exception Wrapping

Reactor's `onErrorMap()` wraps exceptions. Ensure CF replacement preserves exception types.

---

## Task Summary

### Phase 1: Utility Classes (Prerequisites)

| Task | Description |
|------|-------------|
| G3-1 | Create `Pair<T1, T2>` utility class (Tuple2 replacement) |
| G3-2 | Create `Futures` utility class with `retryAsync()` and `firstSuccessful()` |

### Phase 2: Connection Infrastructure

| Task | Description |
|------|-------------|
| G3-3 | Refactor `ConnectionBuilder` - change socket address supplier type |
| G3-4 | Refactor `ReconnectionHandler` - replace Mono/Tuple2 with CF/Pair |
| G3-5 | Refactor `ConnectionWatchdog` - replace Mono/Tuple2 |
| G3-6 | Refactor `MaintenanceAwareConnectionWatchdog` |
| G3-7 | Refactor `AbstractClusterNodeConnectionFactory` |

### Phase 3: Client Classes

| Task | Description |
|------|-------------|
| G3-8 | Refactor `AbstractRedisClient` - update socket address parameters |
| G3-9 | Refactor `RedisClient` - replace Mono chains with CF |
| G3-10 | Change `lookupRedis()` to use `async()` instead of `reactive()` |
| G3-11 | Refactor `RedisClusterClient` - replace Mono retry loops |

### Phase 4: Additional Files & Cleanup

| Task | Description |
|------|-------------|
| G3-12 | Refactor `RedisURI` and `ClientOptions` |
| G3-13 | Remove Reactor imports from all refactored files |
| G3-14 | Verify all integration tests pass |

---

## Dependency Order

```
Phase 1: G3-1, G3-2 (parallel)
    ↓
Phase 2: G3-3 first, then G3-4 to G3-7 (parallel)
    ↓
Phase 3: G3-8 to G3-11 (RedisClient and RedisClusterClient can be parallel)
    ↓
Phase 4: G3-12 to G3-14
```

