# G4 - Master-Replica Implementation Plan

**Status:** Required - Replace Flux/Mono pipelines with CF chains  
**Dependencies:** G2 (Connection Interfaces), G3 (RedisClient/ClusterClient)  
**Blocks:** None

---

## Problem Statement

The `masterreplica` package uses Reactor's `Mono` and `Flux` extensively for:
- Topology discovery and refresh
- Connection selection (potentially hot path)
- Resource cleanup orchestration

**Key concern:** `MasterReplicaConnectionProvider.getConnectionAsync()` is called on **every read operation** when using ReadFrom settings. This is a potential performance-sensitive path that may require benchmarking.

---

## Reactor Usage Analysis

### Files with Reactor Usage (12 files)

| File | Reactor Types | Purpose |
|------|---------------|---------|
| `MasterReplicaConnectionProvider.java` | `Flux`, `Mono` | Connection selection (HOT PATH) |
| `MasterReplicaTopologyRefresh.java` | `Mono` | Topology discovery |
| `StaticMasterReplicaTopologyProvider.java` | `Flux`, `Mono` | Static topology provider |
| `SentinelTopologyProvider.java` | `Mono`, `Tuple2` | Sentinel topology provider |
| `ReplicaTopologyProvider.java` | `Mono` | Replica topology provider |
| `AutodiscoveryConnector.java` | `Mono`, `Tuple2`, `Tuples` | Initial connection setup |
| `SentinelConnector.java` | `Mono` | Sentinel-based connection |
| `StaticMasterReplicaConnector.java` | `Mono` | Static topology connection |
| `ResumeAfter.java` | `Mono` | Resource cleanup utility |
| `AsyncConnections.java` | `Mono`, `Tuples` | Connection aggregation |
| `Connections.java` | `Mono`, `Tuple2` | Connection holder |
| `Requests.java` | `Tuple2`, `Tuples` | Request holder (no Mono)

---

## Breaking Changes (8.0)

**None** - All Reactor usage is internal implementation detail.

---

## Deprecations (7.x)

**None** - No public API uses Reactor types in this package.

---

## Detailed Analysis by File

### 1. MasterReplicaConnectionProvider.java (HOT PATH - CRITICAL)

**Lines 136-149:** Connection selection using Flux pipeline

```java
// Current (Reactor)
Flux<StatefulRedisConnection<K, V>> connections = Flux.empty();
for (RedisNodeDescription node : selection) {
    connections = connections.concatWith(Mono.fromFuture(getConnection(node)));
}

if (OrderingReadFromAccessor.isOrderSensitive(readFrom) || selection.size() == 1) {
    return connections.filter(StatefulConnection::isOpen).next()
        .switchIfEmpty(connections.next()).toFuture();
}

return connections.filter(StatefulConnection::isOpen).collectList()
    .filter(it -> !it.isEmpty())
    .map(it -> it.get(ThreadLocalRandom.current().nextInt(it.size())))
    .switchIfEmpty(connections.next()).toFuture();
```

**Replacement (CompletableFuture):**
```java
// For order-sensitive: try connections sequentially until one is open
List<CompletableFuture<StatefulRedisConnection<K, V>>> futures = new ArrayList<>();
for (RedisNodeDescription node : selection) {
    futures.add(getConnection(node));
}

if (OrderingReadFromAccessor.isOrderSensitive(readFrom) || selection.size() == 1) {
    return firstOpenConnection(futures);
}

// For random selection: get all, filter open, pick random
return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
    .thenApply(v -> {
        List<StatefulRedisConnection<K, V>> open = futures.stream()
            .map(CompletableFuture::join)  // Safe: allOf completed
            .filter(StatefulConnection::isOpen)
            .collect(Collectors.toList());
        
        if (open.isEmpty()) {
            return futures.get(0).join();  // Fallback to first
        }
        return open.get(ThreadLocalRandom.current().nextInt(open.size()));
    });
```

**⚠️ BENCHMARK REQUIRED:** This path is executed on every read. Must verify no performance regression.

### 2. MasterReplicaTopologyRefresh.java

**Lines 56-74:** `getNodes()` returns `Mono<List<RedisNodeDescription>>`

```java
// Current signature (internal)
public Mono<List<RedisNodeDescription>> getNodes(RedisURI seed)

// New signature
public CompletableFuture<List<RedisNodeDescription>> getNodes(RedisURI seed)
```

### 3. AutodiscoveryConnector.java

**Lines 69-91:** Complex Mono chain for connection setup

Pattern: `Mono.fromCompletionStage().flatMap().flatMap().onErrorResume()`

**Replacement:** Chain of `thenCompose()` with error handling via `exceptionallyCompose()`

### 4. SentinelConnector.java

**Lines 72-108:** Sentinel topology lookup and connection initialization

Similar pattern to AutodiscoveryConnector.

### 5. StaticMasterReplicaConnector.java

**Lines 72-96:** Static topology connection

Simpler pattern - straightforward replacement.

### 6. ResumeAfter.java

**Lines 35-68:** Utility for cleanup after operation

```java
// Current
public <T> Mono<T> thenEmit(T value)
public <T> Mono<T> thenError(Throwable t)

// New
public <T> CompletableFuture<T> thenEmit(T value)
public <T> CompletableFuture<T> thenError(Throwable t)
```

### 7. AsyncConnections.java

**Lines 40-59:** `asMono()` method

```java
// Current
public Mono<Connections> asMono(Duration timeout, ScheduledExecutorService executor)

// New  
public CompletableFuture<Connections> asCompletableFuture(Duration timeout, ScheduledExecutorService executor)
```

---

## Special Considerations

### 1. Reactor Tuple2 Usage

`AutodiscoveryConnector.java`, `SentinelTopologyProvider.java`, `Connections.java`, and `Requests.java` use `reactor.util.function.Tuple2` and `Tuples.of()`.

**Solution:** Use `Pair<A, B>` utility class (created in G3 - see `src/main/java/io/lettuce/core/internal/Pair.java`).

### 2. Performance Benchmark Required

`MasterReplicaConnectionProvider.getConnectionAsync()` is called on every read when using `ReadFrom` settings.

**Action:**
1. Implement CF-based version
2. Create benchmark comparing Flux vs CF implementation
3. If regression > 10%: Consider dual implementation via SPI (like EventBus)

### 3. Flux.concatWith() Semantics

The current implementation uses `Flux.concatWith()` which:
- Tries connections sequentially
- Moves to next only on error or completion

CF replacement must preserve this lazy sequential behavior.

---

## Files Affected Summary

| File | Change Type | Hot Path? |
|------|-------------|-----------|
| `MasterReplicaConnectionProvider.java` | Internal refactor | **YES** |
| `MasterReplicaTopologyRefresh.java` | Internal refactor | No |
| `AutodiscoveryConnector.java` | Internal refactor (uses Tuple2) | No |
| `SentinelConnector.java` | Internal refactor | No |
| `SentinelTopologyProvider.java` | Internal refactor (uses Tuple2) | No |
| `StaticMasterReplicaConnector.java` | Internal refactor | No |
| `StaticMasterReplicaTopologyProvider.java` | Internal refactor | No |
| `ReplicaTopologyProvider.java` | Internal refactor | No |
| `ResumeAfter.java` | Internal refactor | No |
| `AsyncConnections.java` | Internal refactor (uses Tuples) | No |
| `Connections.java` | Internal refactor (uses Tuple2) | No |
| `Requests.java` | Internal refactor (uses Tuple2, Tuples) | No |

**Note:** `Pair.java` is created in G3 and will be reused here.

---

## Testing Requirements

### Existing Tests to Verify

- All master-replica integration tests
- ReadFrom behavior tests
- Topology refresh tests
- Sentinel failover tests

### New Tests

1. **Benchmark: Connection selection performance** - Compare Flux vs CF implementation
2. **Test sequential connection fallback** - Verify lazy evaluation preserved
3. **Test random selection** - Verify distribution is correct
4. **Test cleanup on error** - Verify ResumeAfter semantics preserved

---

## Task Breakdown

### 7.x or 8.0 Tasks (Internal Refactoring - No API Changes)

**Prerequisite:** G3 must complete `Pair.java` creation (G3-1)

#### Phase 1: Utilities

| Task ID | Description | Files | Hot Path |
|---------|-------------|-------|----------|
| G4-1 | Refactor `ResumeAfter` to use CF instead of Mono | 1 | No |
| G4-2 | Refactor `AsyncConnections.asMono()` to `asCompletableFuture()` | 1 | No |
| G4-3 | Refactor `Connections` to use `Pair` instead of `Tuple2` | 1 | No |
| G4-4 | Refactor `Requests` to use `Pair` instead of `Tuple2`/`Tuples` | 1 | No |

#### Phase 2: Topology Providers

| Task ID | Description | Files | Hot Path |
|---------|-------------|-------|----------|
| G4-5 | Refactor `MasterReplicaTopologyRefresh.getNodes()` to return CF | 1 | No |
| G4-6 | Refactor `StaticMasterReplicaTopologyProvider` | 1 | No |
| G4-7 | Refactor `SentinelTopologyProvider` to use `Pair` instead of `Tuple2` | 1 | No |
| G4-8 | Refactor `ReplicaTopologyProvider` | 1 | No |

#### Phase 3: Connectors

| Task ID | Description | Files | Hot Path |
|---------|-------------|-------|----------|
| G4-9 | Refactor `StaticMasterReplicaConnector.connectAsync()` | 1 | No |
| G4-10 | Refactor `SentinelConnector.connectAsync()` | 1 | No |
| G4-11 | Refactor `AutodiscoveryConnector.connectAsync()` - replace `Tuple2` | 1 | No |

#### Phase 4: Connection Provider (HOT PATH)

| Task ID | Description | Files | Hot Path |
|---------|-------------|-------|----------|
| G4-12 | **Refactor `MasterReplicaConnectionProvider.getConnectionAsync()`** | 1 | **YES** |
| G4-13 | **Create benchmark for connection selection** | 1 | - |

#### Phase 5: Cleanup

| Task ID | Description | Files | Hot Path |
|---------|-------------|-------|----------|
| G4-14 | Remove Reactor imports from all 12 files | 12 | - |
| G4-15 | Verify all integration tests pass | - | - |

---

## Parallelization Notes

**Phase 1:** G4-1 through G4-4 can be done in parallel

**Phase 2:** G4-5 through G4-8 can be done in parallel after Phase 1

**Phase 3:** G4-9 through G4-11 can be done in parallel after Phase 2

**Phase 4:** G4-12 should be done carefully with benchmark (G4-13)

**Phase 5:** After all Mono/Flux/Tuple2 usage is removed

**Parallel work possible:**
- One developer on utilities + topology providers (Phase 1 + 2)
- One developer on connectors (Phase 3)
- Hot path work (Phase 4) requires focused attention

---

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Performance regression in connection selection | Medium | High | Benchmark before/after, consider dual impl |
| Subtle behavioral differences in sequential fallback | Low | Medium | Comprehensive integration tests |
| Thread safety issues in CF chains | Low | High | Code review, concurrent tests |

---

## Decision Point: Dual Implementation

If benchmark shows >10% regression in `MasterReplicaConnectionProvider.getConnectionAsync()`:

**Option A:** Accept regression (if absolute numbers are still acceptable)

**Option B:** Dual implementation via SPI (like EventBus)
- `DefaultMasterReplicaConnectionProvider` - CF-based
- `ReactorMasterReplicaConnectionProvider` - Flux-based, loaded when Reactor present

Recommend: Start with CF implementation, benchmark, then decide.

