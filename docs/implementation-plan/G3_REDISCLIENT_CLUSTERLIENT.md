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

## Potentially Affected Areas

### Client Classes
- `RedisClient` - Sentinel connection loops, master lookup
- `RedisClusterClient` - Cluster connection retry
- `AbstractRedisClient` - `Mono<SocketAddress>` in connection builder

### Connection Infrastructure
- `ConnectionBuilder` - Socket address supplier
- `ConnectionWatchdog` - Reconnection logic (uses `Mono`, `Tuple2`)
- `MaintenanceAwareConnectionWatchdog` - Extends ConnectionWatchdog
- `ReconnectionHandler` - Reconnect with timeout (uses `Mono`, `Tuple2`, `Tuples`)
- `AbstractClusterNodeConnectionFactory` - Cluster node address resolution

### Additional Areas
- `RedisURI` - Credential resolution
- `ClientOptions` - Socket address supplier

### New Utility Classes
- `Pair<T1, T2>` - Replace Reactor's `Tuple2`
- `Futures` utility class - Async utility methods (retry, composition)

---

## Implementation Approach

### Core Pattern: `Mono<SocketAddress>` to `Supplier<CompletionStage<SocketAddress>>`

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
- All Reactor usage is internal implementation
- No public API changes
- Clients continue to use `connect()`, `connectAsync()`, etc.

### Breaking
- **None**

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

## Scope Summary

| Scope | Description |
|-------|-------------|
| Utility classes | Create `Pair` and `Futures` utilities |
| Connection infrastructure | Refactor connection building, watchdog, reconnection |
| Client classes | Refactor `RedisClient`, `RedisClusterClient`, `AbstractRedisClient` |
| Sentinel integration | Change `lookupRedis()` from reactive to async API |
| Cleanup | Remove Reactor imports, verify tests |

---

## Dependency Order

```
Utilities (Pair, Futures)
    |
    v
Connection Infrastructure (ConnectionBuilder, Watchdog, etc.)
    |
    v
Client Classes (RedisClient, RedisClusterClient)
    |
    v
Cleanup and Verification
```

