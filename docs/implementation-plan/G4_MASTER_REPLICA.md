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

**⚠️ HOT PATH:** `MasterReplicaConnectionProvider.getConnectionAsync()` is called on **every read operation** when using `ReadFrom` settings. Requires benchmarking.

---

## Affected Files (12 files)

### Connection Provider (HOT PATH)

| File | Reactor Types | Notes |
|------|---------------|-------|
| `src/main/java/io/lettuce/core/masterreplica/MasterReplicaConnectionProvider.java` | `Flux`, `Mono` | **Performance critical** |

### Topology Providers

| File | Reactor Types |
|------|---------------|
| `src/main/java/io/lettuce/core/masterreplica/MasterReplicaTopologyRefresh.java` | `Mono` |
| `src/main/java/io/lettuce/core/masterreplica/StaticMasterReplicaTopologyProvider.java` | `Flux`, `Mono` |
| `src/main/java/io/lettuce/core/masterreplica/SentinelTopologyProvider.java` | `Mono`, `Tuple2` |
| `src/main/java/io/lettuce/core/masterreplica/ReplicaTopologyProvider.java` | `Mono` |

### Connectors

| File | Reactor Types |
|------|---------------|
| `src/main/java/io/lettuce/core/masterreplica/AutodiscoveryConnector.java` | `Mono`, `Tuple2`, `Tuples` |
| `src/main/java/io/lettuce/core/masterreplica/SentinelConnector.java` | `Mono` |
| `src/main/java/io/lettuce/core/masterreplica/StaticMasterReplicaConnector.java` | `Mono` |

### Utilities

| File | Reactor Types |
|------|---------------|
| `src/main/java/io/lettuce/core/masterreplica/ResumeAfter.java` | `Mono` |
| `src/main/java/io/lettuce/core/masterreplica/AsyncConnections.java` | `Mono`, `Tuples` |
| `src/main/java/io/lettuce/core/masterreplica/Connections.java` | `Mono`, `Tuple2` |
| `src/main/java/io/lettuce/core/masterreplica/Requests.java` | `Tuple2`, `Tuples` |

---

## Implementation Strategy

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
- All Reactor usage is internal implementation ✅
- No public API changes ✅

### Breaking
- **None** ✅

---

## Hotspots & Considerations

### 1. Performance-Critical Connection Selection

`MasterReplicaConnectionProvider.getConnectionAsync()` is called on every read with `ReadFrom`.

**Action:**
1. Implement CF-based version
2. **Benchmark before/after**
3. If regression >10%: Consider dual implementation (CF default, Reactor when present)

### 2. `Flux.concatWith()` Semantics

Current implementation tries connections sequentially. CF replacement must preserve this lazy sequential behavior - don't fetch all connections upfront.

### 3. Reuse `Pair` from G3

G3 creates `Pair.java` - reuse it here for all `Tuple2` replacements.

---

## Task Summary

**Prerequisite:** G3-1 (`Pair.java` creation)

### Phase 1: Utilities

| Task | Description |
|------|-------------|
| G4-1 | Refactor `ResumeAfter` - Mono to CF |
| G4-2 | Refactor `AsyncConnections` - Mono to CF |
| G4-3 | Refactor `Connections` - Tuple2 to Pair |
| G4-4 | Refactor `Requests` - Tuple2/Tuples to Pair |

### Phase 2: Topology Providers

| Task | Description |
|------|-------------|
| G4-5 | Refactor `MasterReplicaTopologyRefresh` |
| G4-6 | Refactor `StaticMasterReplicaTopologyProvider` |
| G4-7 | Refactor `SentinelTopologyProvider` |
| G4-8 | Refactor `ReplicaTopologyProvider` |

### Phase 3: Connectors

| Task | Description |
|------|-------------|
| G4-9 | Refactor `StaticMasterReplicaConnector` |
| G4-10 | Refactor `SentinelConnector` |
| G4-11 | Refactor `AutodiscoveryConnector` |

### Phase 4: Hot Path (Careful!)

| Task | Description |
|------|-------------|
| G4-12 | **Refactor `MasterReplicaConnectionProvider`** |
| G4-13 | **Create performance benchmark** |

### Phase 5: Cleanup

| Task | Description |
|------|-------------|
| G4-14 | Remove Reactor imports from all 12 files |
| G4-15 | Verify all integration tests pass |

---

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Performance regression in connection selection | Medium | High | Benchmark, consider dual impl |
| Behavioral differences in sequential fallback | Low | Medium | Integration tests |

---

## Decision Point: Dual Implementation

If benchmark shows >10% regression:

**Option A:** Accept if absolute numbers acceptable

**Option B:** Dual implementation via SPI
- `DefaultMasterReplicaConnectionProvider` - CF-based (no Reactor)
- `ReactorMasterReplicaConnectionProvider` - Flux-based (when Reactor present)

**Recommendation:** Start with CF, benchmark, then decide.

