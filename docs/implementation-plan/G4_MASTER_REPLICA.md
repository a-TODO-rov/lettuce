# G4 - Master-Replica Implementation Plan

**Status:** Required - Internal refactoring
**Dependencies:** G2 (Connection Interfaces), G3 (Client Infrastructure for `Pair.java`)
**Blocks:** None

---

## Problem Statement

The `masterreplica` package uses Reactor's `Mono` and `Flux` for:
- Topology discovery and refresh
- Connection selection
- Resource cleanup orchestration

**Key insight:** All Reactor usage is internal - no public API changes required. All changes are non-breaking.

**HOT PATH:** `MasterReplicaConnectionProvider.getConnectionAsync()` is called on **every read operation** when using `ReadFrom` settings. Requires benchmarking.

---

## Potentially Affected Areas

### Connection Provider (HOT PATH)
- `MasterReplicaConnectionProvider` - Uses `Flux`, `Mono` - **Performance critical**

### Topology Providers
- `MasterReplicaTopologyRefresh`
- `StaticMasterReplicaTopologyProvider` - Uses `Flux`, `Mono`
- `SentinelTopologyProvider` - Uses `Mono`, `Tuple2`
- `ReplicaTopologyProvider`

### Connectors
- `AutodiscoveryConnector` - Uses `Mono`, `Tuple2`, `Tuples`
- `SentinelConnector`
- `StaticMasterReplicaConnector`

### Utilities
- `ResumeAfter`
- `AsyncConnections` - Uses `Mono`, `Tuples`
- `Connections` - Uses `Mono`, `Tuple2`
- `Requests` - Uses `Tuple2`, `Tuples`

---

## Implementation Approach

### Replace `Tuple2` with `Pair`

Use `Pair<A, B>` utility class created in G3.

### Replace Mono Patterns

Same patterns as G3 - use `CompletableFuture` chains with `thenCompose()`, `exceptionallyCompose()`, etc.

### Hot Path: Connection Selection

Current Flux-based sequential connection selection:
```java
Flux<StatefulRedisConnection<K, V>> connections = Flux.empty();
for (RedisNodeDescription node : selection) {
    connections = connections.concatWith(Mono.fromFuture(getConnection(node)));
}
```

CF replacement must preserve lazy sequential behavior for order-sensitive reads.

---

## Breaking vs Non-Breaking Changes

### Non-Breaking (All changes)
- All Reactor usage is internal implementation
- No public API changes

### Breaking
- **None**

---

## Hotspots & Considerations

### 1. Performance-Critical Connection Selection

`MasterReplicaConnectionProvider.getConnectionAsync()` is called on every read with `ReadFrom`.

**Action:**
1. Implement CF-based version
2. **Benchmark before/after**
3. If regression is significant: Consider dual implementation (CF default, Reactor when present)

### 2. `Flux.concatWith()` Semantics

Current implementation tries connections sequentially. CF replacement must preserve this lazy sequential behavior - don't fetch all connections upfront.

### 3. Reuse `Pair` from G3

G3 creates `Pair.java` - reuse it here for all `Tuple2` replacements.

---

## Scope Summary

| Scope | Description |
|-------|-------------|
| Utilities | Refactor `ResumeAfter`, `AsyncConnections`, `Connections`, `Requests` |
| Topology providers | Refactor all topology discovery components |
| Connectors | Refactor all connector implementations |
| Hot path | Refactor `MasterReplicaConnectionProvider` with benchmarking |
| Cleanup | Remove Reactor imports, verify tests |

---

## Risk Considerations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Performance regression in connection selection | High | Benchmark, consider dual impl |
| Behavioral differences in sequential fallback | Medium | Integration tests |

---

## Decision Point: Dual Implementation

If benchmark shows significant regression:

**Option A:** Accept if absolute numbers acceptable

**Option B:** Dual implementation via SPI
- `DefaultMasterReplicaConnectionProvider` - CF-based (no Reactor)
- `ReactorMasterReplicaConnectionProvider` - Flux-based (when Reactor present)

**Recommendation:** Start with CF, benchmark, then decide.

