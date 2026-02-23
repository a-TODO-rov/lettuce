# Lettuce Reactor Optional - Alignment Document

## Purpose
This document captures the aligned findings and decisions from the Lettuce Reactor Optional refactoring analysis. Use this as the source of truth for continued planning.

---

## 1. Clarified Requirements

| Requirement | Decision |
|-------------|----------|
| **Breaking Changes** | Acceptable in major version (8.0) |
| **Performance Regression** | **Zero tolerance** - users who need maximum performance can add Reactor dependency to get optimized implementations |
| **Timeline** | To be decided - phased (7.x→8.0) or single major release (8.0) |
| **Artifact Strategy** | Both solutions must be proposed with pros/cons assessment |

---

## 2. Critical Architectural Concern: The Import Problem (G2)

### The Problem

The `StatefulRedisConnection` interface (and similar connection interfaces) has Reactor types in its **method signatures**:

```java
// StatefulRedisConnection.java
import io.lettuce.core.api.reactive.RedisReactiveCommands;  // Line 5

public interface StatefulRedisConnection<K, V> extends StatefulConnection<K, V> {
    RedisReactiveCommands<K, V> reactive();  // Line 47
}
```

**Why this breaks without Reactor:**
1. JVM loads `StatefulRedisConnection.class`
2. JVM must resolve `RedisReactiveCommands` in the method signature
3. `RedisReactiveCommands` imports `Flux`, `Mono` from Reactor
4. **`NoClassDefFoundError`** if Reactor is not on classpath

### Why JsonParser Pattern Doesn't Directly Apply

| JsonParser (Jackson optional) | StatefulRedisConnection (Reactor) |
|------------------------------|-----------------------------------|
| Optional types are **inside** implementation | Optional types are **in interface signature** |
| Interface has no Jackson imports | Interface imports `RedisReactiveCommands` |
| JVM never loads Jackson unless impl is used | JVM loads Reactor when interface is loaded |

### Solution: Remove Reactor Types from Core Interface Signatures

**Phase 1 (7.x - Non-breaking):**
```java
public interface StatefulRedisConnection<K, V> {
    // NEW: Extension-based access (no Reactor in signature)
    <T> T getExtension(Class<T> type);

    // DEPRECATED: Will be removed in 8.0
    @Deprecated
    RedisReactiveCommands<K, V> reactive();
}
```

**Phase 2 (8.0 - Breaking):**
```java
public interface StatefulRedisConnection<K, V> {
    // Reactor types completely removed from interface
    <T> T getExtension(Class<T> type);

    // reactive() method REMOVED
}

// Usage:
RedisReactiveCommands<K,V> reactive = connection.getExtension(RedisReactiveCommands.class);
```

### Affected Interfaces

All connection interfaces that expose `reactive()`:
- `StatefulRedisConnection`
- `StatefulRedisClusterConnection`
- `StatefulRedisPubSubConnection`
- `StatefulRedisSentinelConnection`
- `StatefulRedisClusterPubSubConnection`

---

## 3. Performance Risk Analysis (Code-Based Findings)

We investigated the actual Reactor usage in the codebase. Here are the findings:

### Components Analyzed

| Component | File | Reactor Usage | Hot Path? | Dual Impl Needed? |
|-----------|------|---------------|-----------|-------------------|
| **EventBus** | `DefaultEventBus.java` | `Sinks.Many.multicast().directBestEffort()` with busy-loop `tryEmitNext` | YES (every event) | **YES** |
| **MasterReplica** | `MasterReplicaConnectionProvider.java` | `Flux.concatWith(Mono.fromFuture())` for connection selection | YES (every read) | **MAYBE** (benchmark needed) |
| **RedisClient Sentinel** | `RedisClient.java:755` | `Mono.usingWhen()` for resource management | NO (connection time) | NO |
| **RedisClient Retry** | `RedisClient.java:539` | `Mono.onErrorResume()` for fallback | NO (error path) | NO |
| **Credentials** | `RedisCredentialsProvider.java` | `Flux<RedisCredentials>` streaming | NO (auth/refresh) | NO |
| **Tracing** | `Tracing.java` | `Mono<TraceContext>` | NO (per-command) | NO |
| **ClientResources** | `DefaultClientResources.java:225` | `Schedulers.fromExecutorService()` | NO (initialization) | NO |

### Key Finding: EventBus Requires Dual Implementation

The `DefaultEventBus` uses `Sinks.Many.multicast().directBestEffort()` which is highly optimized for:
- Lock-free concurrent publishing
- Efficient multicast to multiple subscribers  
- Backpressure handling with busy-loop retry

A `CopyOnWriteArrayList<Consumer<Event>>` + `Executor` alternative may have measurable overhead under high event load.

---

## 4. Solution Proposals

### Solution A: Single Artifact with Dual Implementations via SPI

**Approach:**
- Keep single `lettuce-core` artifact
- Reactor remains `<optional>true</optional>` in pom.xml
- Performance-sensitive components (EventBus): dual implementation via ServiceLoader
- Non-performance-sensitive components: replace Reactor with CompletableFuture

**Pattern (like existing JsonParser):**
```java
// Interface without Reactor types
public interface EventBus {
    Closeable subscribe(Consumer<Event> listener);
    void publish(Event event);
}

// Default implementation (CF-based, no Reactor)
public class DefaultEventBus implements EventBus { ... }

// Reactor-optimized implementation (loaded via SPI when Reactor present)
public class ReactorEventBus implements EventBus { ... }

// META-INF/services/io.lettuce.core.event.EventBus
// Contains: io.lettuce.core.event.ReactorEventBus
```

| Pros | Cons |
|------|------|
| No breaking change to artifact structure | More complex internal architecture |
| Users don't need to change dependencies | Testing complexity (both paths) |
| Automatic optimization when Reactor present | Risk of subtle behavioral differences |
| Single artifact to maintain | Larger JAR size |
| Familiar pattern (JsonParser exists) | |

---

### Solution B: Multi-Module Split

**Approach:**
- `lettuce-core`: Core functionality, no Reactor dependency
- `lettuce-reactive`: Reactive API + optimized implementations

**User dependency:**
```xml
<!-- Sync/async only (no Reactor) -->
<dependency>
    <groupId>io.lettuce</groupId>
    <artifactId>lettuce-core</artifactId>
</dependency>

<!-- Reactive API + optimized performance -->
<dependency>
    <groupId>io.lettuce</groupId>
    <artifactId>lettuce-reactive</artifactId>
</dependency>
```

| Pros | Cons |
|------|------|
| Clean separation of concerns | Breaking change for existing users |
| Smaller JAR for non-reactive users | Two artifacts to maintain/release |
| No dual implementation complexity | Potential version mismatches |
| Clear dependency story | Users must explicitly opt-in |

---

## 5. Timeline Options

### Option 1: Phased Approach (Recommended)

**Phase 1 (7.x - Non-breaking):** ~2-3 months
- Add new callback-based APIs alongside existing Reactor APIs
- Deprecate Reactor-based methods in core interfaces
- Internal refactoring of RedisClient, MasterReplica orchestration

**Phase 2 (8.0 - Breaking):** ~1-2 months after Phase 1
- Remove deprecated methods
- Remove Reactor imports from core interfaces
- Finalize dual implementations

**Total: 4-6 months**

### Option 2: Single Major Release (8.0)

- All changes in one release
- No deprecation period
- **Total: 3-4 months**

---

## 6. Functional Groups Summary

| Group | Description | Approach |
|-------|-------------|----------|
| **G1** | Reactive API (`api/reactive/*`) | Unchanged - IS the feature |
| **G2** | Connection Entry Points (`reactive()` method) | **CRITICAL** - Remove Reactor from signatures, use `getExtension(Class<T>)` |
| **G3** | RedisClient/RedisClusterClient | Replace Mono orchestration with CF |
| **G4** | Master-Replica | Replace Flux pipelines with CF chains |
| **G5** | EventBus | **Dual implementation** (SPI) |
| **G6** | Credentials Provider | Add `resolveCredentials()` + `subscribe()` |
| **G7** | Tracing | Move Reactor Context to ReactiveTracingSupport |
| **G8** | ClientResources | Replace Reactor Scheduler with Executor |

---

## 7. Open Decisions

Before creating the detailed implementation plan, decisions needed:

1. **Which solution?** A (Single Artifact) or B (Multi-Module)?
2. **Which timeline?** Phased (7.x→8.0) or Single (8.0)?
3. **Benchmark scope?** Include MasterReplicaConnectionProvider benchmarks?

---

## 8. Definition of Done

- [ ] Core compiles and runs without Reactor on classpath
- [ ] No public core types reference `reactor.*`
- [ ] Reactive API works when Reactor present
- [ ] Friendly error when reactive requested without Reactor
- [ ] Zero performance regression when Reactor present (dual impl)
- [ ] CI matrix: core-only + reactive builds both green
- [ ] Classloader smoke test passes

