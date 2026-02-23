# G3 - RedisClient / RedisClusterClient / Connection Infrastructure Implementation Plan

**Status:** Required - Replace Mono-based orchestration and address resolution
**Dependencies:** G2 (Connection Interfaces)
**Blocks:** G4 (Master-Replica)

---

## Problem Statement

`RedisClient`, `RedisClusterClient`, and the underlying connection infrastructure use Reactor's `Mono` for:
- Connection orchestration and retry logic
- **Socket address resolution** (async address suppliers)
- Reconnection handling (`ConnectionWatchdog`, `ReconnectionHandler`)
- Resource management

These are **internal implementation details** that can be replaced with `CompletableFuture` chains without breaking the public API.

**Key insight:** Unlike G2, these changes are **internal refactoring** - no public API changes required.

---

## Reactor Usage Analysis

### RedisClient.java

| Location | Lines | Pattern | Purpose |
|----------|-------|---------|---------|
| Sentinel connection loop | 526-548 | `Mono.fromCompletionStage()` + `onErrorResume()` | Try each sentinel, fallback on error |
| Sentinel master lookup | 751-786 | `Mono.usingWhen()` | Resource management (connect, use, close) |
| Sentinel address resolution | 703-705 | `switchIfEmpty()` + `toFuture()` | Error if no address found |

### RedisClusterClient.java

| Location | Lines | Pattern | Purpose |
|----------|-------|---------|---------|
| Cluster connection retry | 707-718 | `Mono.defer()` + `onErrorResume()` loop | Retry connection N times |
| PubSub connection retry | 826-837 | Same pattern | Retry connection N times |
| Connect helper methods | 759-775 | `Mono.fromCompletionStage()` + `doOnError()` | Wrap CF with logging |

### Connection Infrastructure (PREVIOUSLY MISSING)

| File | Location | Reactor Types | Usage |
|------|----------|---------------|-------|
| `AbstractRedisClient.java` | `core/` | `Mono` | `Mono<SocketAddress>` in `connectionBuilder()` methods |
| `ConnectionBuilder.java` | `core/` | `Mono` | `Mono<SocketAddress> socketAddress()` - address supplier |
| `ConnectionWatchdog.java` | `core/protocol/` | `Mono`, `Tuple2` | `Mono<SocketAddress>` for reconnection, `Tuple2` for address+context |
| `MaintenanceAwareConnectionWatchdog.java` | `core/protocol/` | `Mono` | Extends ConnectionWatchdog, wraps `Mono<SocketAddress>` |
| `ReconnectionHandler.java` | `core/protocol/` | `Mono`, `Tuple2`, `Tuples` | Reconnect logic with `Mono<SocketAddress>`, timeout handling |
| `AbstractClusterNodeConnectionFactory.java` | `core/cluster/` | `Mono` | `Mono<SocketAddress>` for cluster node address resolution |

---

## Breaking Changes (8.0)

**None** - All Reactor usage is internal implementation detail.

---

## Deprecations (7.x)

**None** - No public API uses Reactor types in these classes.

---

## Non-Breaking Changes (7.x or 8.0)

### Internal Refactoring Required

All changes are internal and can be done in either 7.x or 8.0.

---

## Pattern Replacements

### Pattern 1: `Mono.fromCompletionStage()` + `onErrorResume()` chain

**Current (Reactor):**
```java
Mono<Connection> connectionLoop = null;
for (RedisURI uri : sentinels) {
    Mono<Connection> connectionMono = Mono
        .fromCompletionStage(() -> doConnectAsync(uri))
        .onErrorMap(e -> new RedisConnectionException("Cannot connect to " + uri, e));
    
    if (connectionLoop == null) {
        connectionLoop = connectionMono;
    } else {
        connectionLoop = connectionLoop.onErrorResume(t -> connectionMono);
    }
}
return connectionLoop.toFuture();
```

**Replacement (CompletableFuture):**
```java
CompletableFuture<Connection> connectionLoop = null;
for (RedisURI uri : sentinels) {
    if (connectionLoop == null) {
        connectionLoop = doConnectAsync(uri).toCompletableFuture()
            .exceptionally(e -> { throw wrapException(uri, e); });
    } else {
        final CompletableFuture<Connection> previous = connectionLoop;
        connectionLoop = previous.exceptionallyCompose(t -> 
            doConnectAsync(uri).toCompletableFuture()
                .exceptionally(e -> { throw wrapException(uri, e); })
        );
    }
}
return connectionLoop;
```

### Pattern 2: `Mono.defer()` + `onErrorResume()` retry loop

**Current (Reactor):**
```java
Mono<Connection> connectionMono = Mono.defer(() -> connect(...));
for (int i = 1; i < getConnectionAttempts(); i++) {
    connectionMono = connectionMono.onErrorResume(t -> connect(...));
}
return connectionMono.doOnNext(c -> c.register(...)).toFuture();
```

**Replacement (CompletableFuture):**
```java
CompletableFuture<Connection> connectionFuture = connect(...).toCompletableFuture();
for (int i = 1; i < getConnectionAttempts(); i++) {
    connectionFuture = connectionFuture.exceptionallyCompose(t -> {
        logger.warn(t.getMessage());
        return connect(...).toCompletableFuture();
    });
}
return connectionFuture.thenApply(c -> {
    c.register(...);
    return c;
});
```

### Pattern 3: `Mono.usingWhen()` for resource management

**Current (Reactor):**
```java
return Mono.usingWhen(
    Mono.fromCompletionStage(() -> connectSentinelAsync(...)),  // acquire
    connection -> connection.reactive().getMasterAddrByName(masterId)  // use
        .map(this::resolveAddress)
        .timeout(timeout),
    connection -> Mono.fromCompletionStage(connection::closeAsync),  // release
    (connection, ex) -> Mono.fromCompletionStage(connection::closeAsync),  // error release
    connection -> Mono.fromCompletionStage(connection::closeAsync)   // cancel release
);
```

**Replacement (CompletableFuture):**
```java
return connectSentinelAsync(...).thenCompose(connection -> {
    return connection.async().getMasterAddrByName(masterId)
        .thenApply(this::resolveAddress)
        .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
        .whenComplete((result, ex) -> connection.closeAsync());
});
```

**Note:** The `lookupRedis()` method uses `c.reactive().getMasterAddrByName()` which returns a `Mono`. This needs to be changed to `c.async().getMasterAddrByName()` which returns `RedisFuture`.

---

## Utility Class: Futures

Create internal utility class for common async patterns:

**File:** `src/main/java/io/lettuce/core/internal/Futures.java`

```java
public final class Futures {
    
    /**
     * Retry an async operation up to maxAttempts times.
     */
    public static <T> CompletableFuture<T> retryAsync(
            Supplier<CompletionStage<T>> operation,
            int maxAttempts,
            Consumer<Throwable> onError) {
        
        CompletableFuture<T> result = operation.get().toCompletableFuture();
        for (int i = 1; i < maxAttempts; i++) {
            result = result.exceptionallyCompose(t -> {
                if (onError != null) onError.accept(t);
                return operation.get().toCompletableFuture();
            });
        }
        return result;
    }
    
    /**
     * Try multiple operations in sequence, using next on failure.
     */
    public static <T> CompletableFuture<T> firstSuccessful(
            List<Supplier<CompletionStage<T>>> operations,
            Function<Throwable, Throwable> errorMapper) {
        // Implementation...
    }
}
```

---

## Infrastructure Pattern: Mono<SocketAddress> Replacement

The connection infrastructure uses `Mono<SocketAddress>` for lazy async address resolution. This must be replaced with:

**Pattern:** `Mono<SocketAddress>` → `Supplier<CompletionStage<SocketAddress>>`

### Why Supplier?

The `Mono` is used for **lazy evaluation** - the address is resolved each time a reconnection happens. A `Supplier` preserves this laziness:

```java
// Current (Reactor)
Mono<SocketAddress> socketAddressSupplier;

// Replacement
Supplier<CompletionStage<SocketAddress>> socketAddressSupplier;
```

### ConnectionBuilder Changes

**File:** `src/main/java/io/lettuce/core/ConnectionBuilder.java`

```java
// Current
private Mono<SocketAddress> socketAddressSupplier;

public Mono<SocketAddress> socketAddress() {
    return socketAddressSupplier;
}

public void socketAddressSupplier(Mono<SocketAddress> socketAddressSupplier) {
    this.socketAddressSupplier = socketAddressSupplier;
}

// New
private Supplier<CompletionStage<SocketAddress>> socketAddressSupplier;

public Supplier<CompletionStage<SocketAddress>> socketAddress() {
    return socketAddressSupplier;
}

public void socketAddressSupplier(Supplier<CompletionStage<SocketAddress>> socketAddressSupplier) {
    this.socketAddressSupplier = socketAddressSupplier;
}
```

### ReconnectionHandler Changes

**File:** `src/main/java/io/lettuce/core/protocol/ReconnectionHandler.java`

```java
// Current
private final Mono<SocketAddress> socketAddressSupplier;

public ReconnectionHandler(..., Mono<SocketAddress> socketAddressSupplier, ...) {
    this.socketAddressSupplier = socketAddressSupplier;
}

// Uses Mono operators: .timeout(), .map(), .zipWith()
Tuple2<SocketAddress, Bootstrap> tuple = socketAddressSupplier
    .timeout(timeout)
    .zipWith(Mono.just(bootstrap))
    .toFuture().get();

// New
private final Supplier<CompletionStage<SocketAddress>> socketAddressSupplier;

public ReconnectionHandler(..., Supplier<CompletionStage<SocketAddress>> socketAddressSupplier, ...) {
    this.socketAddressSupplier = socketAddressSupplier;
}

// Uses CompletableFuture operators
Pair<SocketAddress, Bootstrap> pair = socketAddressSupplier.get()
    .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
    .thenApply(addr -> Pair.of(addr, bootstrap))
    .toCompletableFuture().get();
```

### ConnectionWatchdog Changes

**File:** `src/main/java/io/lettuce/core/protocol/ConnectionWatchdog.java`

Similar pattern - replace `Mono<SocketAddress>` parameter with `Supplier<CompletionStage<SocketAddress>>`.

### Tuple2 → Pair Replacement

**Reactor's `Tuple2`** is used to pair socket address with other data. Replace with:

**File:** `src/main/java/io/lettuce/core/internal/Pair.java` (NEW)

```java
public final class Pair<T1, T2> {
    private final T1 first;
    private final T2 second;

    public static <T1, T2> Pair<T1, T2> of(T1 first, T2 second) {
        return new Pair<>(first, second);
    }

    public T1 getFirst() { return first; }
    public T2 getSecond() { return second; }
}
```

---

## Files Affected Summary

| File | Change Type | Description |
|------|-------------|-------------|
| `src/main/java/io/lettuce/core/RedisClient.java` | Internal refactor | Replace Mono patterns with CF |
| `src/main/java/io/lettuce/core/cluster/RedisClusterClient.java` | Internal refactor | Replace Mono patterns with CF |
| `src/main/java/io/lettuce/core/AbstractRedisClient.java` | Internal refactor | Change `Mono<SocketAddress>` to `Supplier<CompletionStage<SocketAddress>>` |
| `src/main/java/io/lettuce/core/ConnectionBuilder.java` | Internal refactor | Change socket address supplier type |
| `src/main/java/io/lettuce/core/protocol/ConnectionWatchdog.java` | Internal refactor | Replace `Mono<SocketAddress>`, `Tuple2` |
| `src/main/java/io/lettuce/core/protocol/MaintenanceAwareConnectionWatchdog.java` | Internal refactor | Replace `Mono<SocketAddress>` wrapping |
| `src/main/java/io/lettuce/core/protocol/ReconnectionHandler.java` | Internal refactor | Replace `Mono<SocketAddress>`, `Tuple2`, `Tuples` |
| `src/main/java/io/lettuce/core/cluster/AbstractClusterNodeConnectionFactory.java` | Internal refactor | Replace `Mono<SocketAddress>` |
| `src/main/java/io/lettuce/core/internal/Futures.java` | **New file** | Async utility methods |
| `src/main/java/io/lettuce/core/internal/Pair.java` | **New file** | Tuple2 replacement |

---

## Special Considerations

### 1. `lookupRedis()` uses `reactive()` API

**Problem:** Line 759 uses `c.reactive().getMasterAddrByName(sentinelMasterId)` which:
1. Returns a `Mono<SocketAddress>`
2. Uses the reactive API that we're trying to make optional

**Solution:** Change to use async API:
```java
// Before
c.reactive().getMasterAddrByName(sentinelMasterId).map(...)

// After
c.async().getMasterAddrByName(sentinelMasterId).thenApply(...)
```

### 2. Exception wrapping semantics

Reactor's `onErrorMap()` wraps exceptions. Ensure CF replacement preserves this:
```java
.exceptionally(e -> {
    throw new RedisConnectionException("Cannot connect to " + uri,
        e instanceof CompletionException ? e.getCause() : e);
})
```

### 3. No blocking on event loop

All replacements must remain non-blocking. Never use:
- `CompletableFuture.join()`
- `CompletableFuture.get()`
- Any blocking call inside the async chain

---

## Testing Requirements

### Existing Tests to Verify

- All Sentinel connection tests
- All cluster connection tests
- Retry behavior tests
- Timeout tests

### New Tests

1. **Test retry logic with CF implementation** - Verify same retry semantics
2. **Test exception wrapping** - Verify exception types are preserved
3. **Test resource cleanup** - Verify connections are closed on error

---

## Task Breakdown

### 7.x or 8.0 Tasks (Internal Refactoring - No API Changes)

#### Phase 1: Utility Classes (Prerequisites)

| Task ID | Description | Files |
|---------|-------------|-------|
| G3-1 | Create `Pair<T1, T2>` utility class (Tuple2 replacement) | 1 (new) |
| G3-2 | Create `Futures` utility class with `retryAsync()` and `firstSuccessful()` | 1 (new) |

#### Phase 2: Connection Infrastructure (Foundation)

| Task ID | Description | Files |
|---------|-------------|-------|
| G3-3 | Refactor `ConnectionBuilder` - change `Mono<SocketAddress>` to `Supplier<CompletionStage<SocketAddress>>` | 1 |
| G3-4 | Refactor `ReconnectionHandler` - replace Mono/Tuple2 with CF/Pair | 1 |
| G3-5 | Refactor `ConnectionWatchdog` - replace Mono/Tuple2 with CF/Pair | 1 |
| G3-6 | Refactor `MaintenanceAwareConnectionWatchdog` - update Mono wrapping | 1 |
| G3-7 | Refactor `AbstractClusterNodeConnectionFactory` - replace Mono<SocketAddress> | 1 |

#### Phase 3: Client Classes (Depends on Phase 2)

| Task ID | Description | Files |
|---------|-------------|-------|
| G3-8 | Refactor `AbstractRedisClient.connectionBuilder()` - update Mono<SocketAddress> parameters | 1 |
| G3-9 | Refactor `RedisClient.connectSentinelAsync()` - replace Mono chain with CF | 1 |
| G3-10 | Refactor `RedisClient.lookupRedis()` - replace `Mono.usingWhen()` with CF | 1 |
| G3-11 | Change `lookupRedis()` to use `async()` instead of `reactive()` | 1 |
| G3-12 | Refactor `RedisClusterClient.connectClusterAsync()` - replace Mono retry loop | 1 |
| G3-13 | Refactor `RedisClusterClient.connectClusterPubSubAsync()` - replace Mono retry loop | 1 |

#### Phase 4: Cleanup

| Task ID | Description | Files |
|---------|-------------|-------|
| G3-14 | Remove Reactor imports from all refactored files | 10 |
| G3-15 | Add/update unit tests for reconnection and retry logic | - |
| G3-16 | Verify all integration tests pass | - |

---

## Parallelization Notes

**Phase 1:** G3-1, G3-2 can be done independently and in parallel

**Phase 2:** After Phase 1:
- G3-3 (ConnectionBuilder) must be done first - other files depend on it
- G3-4, G3-5, G3-6, G3-7 can be done in parallel after G3-3

**Phase 3:** After Phase 2:
- G3-8 depends on G3-3
- G3-9, G3-10, G3-11 (RedisClient) can be done together
- G3-12, G3-13 (RedisClusterClient) can be done together
- RedisClient and RedisClusterClient work can be parallelized

**Phase 4:** After all Mono usage is removed

**Parallel work possible:**
- One developer on infrastructure (Phase 2)
- One developer on RedisClient (Phase 3)
- One developer on RedisClusterClient (Phase 3)

