# Migration Plan: Making Reactor Optional in Lettuce

## Executive Summary

This document outlines the migration plan to make Project Reactor an **optional dependency** in Lettuce. 
The goal is to allow users who only need the synchronous or async (`CompletableFuture`) APIs to use Lettuce 
without requiring Reactor on the classpath.

## Current State

Reactor has "leaked" into core Lettuce functionality:

| Area | Reactor Usage | Impact |
|------|--------------|--------|
| **EventBus** | `Flux<Event> get()` | All users who subscribe to events |
| **RedisCredentialsProvider** | `Flux<RedisCredentials> credentials()` | Users with streaming credentials |
| **TraceContextProvider** | `Mono<TraceContext> getTraceContextLater()` | Custom tracing implementations |
| **Tracing** | Static methods using `reactor.util.context.Context` | Custom tracing |
| **Client connections** | Internal `Mono` for retry/chaining | Internal only |
| **Master-Replica** | Internal `Mono` for topology | Internal only |
| **ScanStream** | Public utility returning `Flux` | Reactive scan users |

## Migration Phases

### Phase 1: Non-Breaking Additions (Minor Version)

Add alternative APIs that don't require Reactor, deprecate Reactor-based methods.

#### 1.1 EventBus

**Current:**
```java
public interface EventBus {
    Flux<Event> get();
    void publish(Event event);
}
```

**Target:**
```java
public interface EventBus {
    // New - no Reactor required
    Closeable subscribe(Consumer<Event> listener);
    void publish(Event event);
    
    // Deprecated - requires Reactor
    @Deprecated
    Flux<Event> get();
}
```

#### 1.2 RedisCredentialsProvider

**Current:**
```java
public interface RedisCredentialsProvider {
    CompletionStage<RedisCredentials> resolveCredentials();
    default boolean supportsStreaming() { return false; }
    default Flux<RedisCredentials> credentials() { throw new UnsupportedOperationException(); }
}
```

**Target:**
```java
public interface RedisCredentialsProvider {
    CompletionStage<RedisCredentials> resolveCredentials();
    default boolean supportsStreaming() { return false; }
    
    // New - callback-based streaming, no Reactor required
    default Closeable subscribe(Consumer<RedisCredentials> listener) {
        throw new UnsupportedOperationException("Streaming not supported");
    }
    
    // Deprecated - requires Reactor
    @Deprecated
    default Flux<RedisCredentials> credentials() { ... }
}
```

#### 1.3 TraceContextProvider

**Current:**
```java
public interface TraceContextProvider {
    TraceContext getTraceContext();
    default Mono<TraceContext> getTraceContextLater() { ... }
}
```

**Target:**
```java
public interface TraceContextProvider {
    TraceContext getTraceContext();
    
    // New - CompletionStage based
    default CompletionStage<TraceContext> getTraceContextAsync() {
        return CompletableFuture.completedFuture(getTraceContext());
    }
    
    // Deprecated - requires Reactor
    @Deprecated
    default Mono<TraceContext> getTraceContextLater() { ... }
}
```

#### 1.4 Tracing Static Methods

**Current:**
```java
public interface Tracing {
    static Mono<TraceContextProvider> getContext() { ... }
    static Function<Context, Context> clearContext() { ... }
    static Context withTraceContextProvider(TraceContextProvider supplier) { ... }
}
```

**Target:**
Move reactive context methods to a separate class:
```java
// In core - no Reactor
public interface Tracing {
    TracerProvider getTracerProvider();
    TraceContextProvider initialTraceContextProvider();
    boolean isEnabled();
    // ... other non-Reactor methods
}

// New class for reactive context (only loaded if Reactor present)
public class ReactiveTracingSupport {
    public static Mono<TraceContextProvider> getContext() { ... }
    public static Function<Context, Context> clearContext() { ... }
    public static Context withTraceContextProvider(TraceContextProvider supplier) { ... }
}
```

---

### Phase 2: Internal Refactoring (Minor Version)

Replace internal Reactor usage with `CompletableFuture`. No public API changes.

#### 2.1 Client Connection Setup

**Files:**
- `RedisClient.java`
- `RedisClusterClient.java`

**Change:** Replace `Mono` retry chains with `CompletableFuture` retry loops.

**Before:**
```java
Mono<Connection> connectionMono = Mono.defer(() -> connect(...));
for (int i = 1; i < attempts; i++) {
    connectionMono = connectionMono.onErrorResume(t -> connect(...));
}
return connectionMono.toFuture();
```

**After:**
```java
private CompletableFuture<Connection> connectWithRetry(int remainingAttempts) {
    return connectAttempt().exceptionallyCompose(t -> {
        if (remainingAttempts > 0) {
            return connectWithRetry(remainingAttempts - 1);
        }
        return CompletableFuture.failedFuture(t);
    });
}
```

#### 2.2 Master-Replica Topology

**Files:**
- `MasterReplica.java`
- `MasterReplicaConnectionProvider.java`
- `ReplicaTopologyProvider.java`
- `SentinelTopologyProvider.java`
- `StaticMasterReplicaTopologyProvider.java`
- `MasterReplicaTopologyRefresh.java`

**Change:** Replace `Mono.flatMap()` chains with `CompletableFuture.thenCompose()`.

#### 2.3 Connection Close Handling

**Files:**
- `StatefulRedisConnectionImpl.java`
- `StatefulRedisClusterConnectionImpl.java`
- `StatefulRedisPubSubConnectionImpl.java`

**Change:** Replace `Mono` close handling with `CompletableFuture`.

#### 2.4 DefaultEventBus Implementation

**Change:** Replace `Sinks.Many<Event>` with `CopyOnWriteArrayList<Consumer<Event>>`.

#### 2.5 DefaultClientResources

**Change:** Replace `Schedulers.boundedElastic()` with standard `ExecutorService`.

---

### Phase 3: Module Restructuring (Major Version)

Restructure Maven modules to separate reactive functionality.

#### 3.1 New Module Structure

```
lettuce/
├── lettuce-core/                    # Core module - NO Reactor dependency
│   ├── Sync API
│   ├── Async API (CompletableFuture)
│   ├── EventBus (callback-based)
│   ├── Credentials (callback-based)
│   └── Tracing (sync)
│
├── lettuce-reactive/                # Reactive module - REQUIRES Reactor
│   ├── Reactive API (Mono/Flux)
│   ├── ScanStream
│   ├── ReactiveTracingSupport
│   └── Flux-based EventBus.get()
│
└── lettuce-all/                     # Convenience module - includes both
```

#### 3.2 Dependency Changes

**lettuce-core pom.xml:**
```xml
<!-- Reactor is OPTIONAL - only needed at compile time for reactive module -->
<dependency>
    <groupId>io.projectreactor</groupId>
    <artifactId>reactor-core</artifactId>
    <optional>true</optional>
</dependency>
```

**lettuce-reactive pom.xml:**
```xml
<dependency>
    <groupId>io.lettuce</groupId>
    <artifactId>lettuce-core</artifactId>
</dependency>
<dependency>
    <groupId>io.projectreactor</groupId>
    <artifactId>reactor-core</artifactId>
    <!-- Required, not optional -->
</dependency>
```

#### 3.3 Move Classes to Reactive Module

| Class | From | To |
|-------|------|-----|
| `ScanStream` | `core` | `reactive` |
| `*ReactiveCommands*` interfaces | `core/api/reactive` | `reactive` |
| `*ReactiveCommandsImpl` | `core` | `reactive` |
| `RedisPublisher` | `core` | `reactive` |
| `Operators` | `core` | `reactive` |
| `ReactiveTypes` | `core/dynamic` | `reactive` |
| `ReactiveTypeAdapters` | `core/dynamic` | `reactive` |
| `ReactiveTracingSupport` | `core/tracing` | `reactive` |

---

### Phase 4: Breaking Changes (Major Version)

Remove deprecated Reactor-based methods from core interfaces.

#### 4.1 EventBus

```java
public interface EventBus {
    Closeable subscribe(Consumer<Event> listener);
    void publish(Event event);
    // REMOVED: Flux<Event> get()
}
```

#### 4.2 RedisCredentialsProvider

```java
public interface RedisCredentialsProvider {
    CompletionStage<RedisCredentials> resolveCredentials();
    default boolean supportsStreaming() { return false; }
    default Closeable subscribe(Consumer<RedisCredentials> listener) { ... }
    // REMOVED: Flux<RedisCredentials> credentials()
}
```

#### 4.3 TraceContextProvider

```java
public interface TraceContextProvider {
    TraceContext getTraceContext();
    default CompletionStage<TraceContext> getTraceContextAsync() { ... }
    // REMOVED: Mono<TraceContext> getTraceContextLater()
}
```

#### 4.4 Tracing

```java
public interface Tracing {
    TracerProvider getTracerProvider();
    TraceContextProvider initialTraceContextProvider();
    boolean isEnabled();
    boolean includeCommandArgsInSpanTags();
    Endpoint createEndpoint(SocketAddress socketAddress);
    static Tracing disabled() { ... }
    // REMOVED: static Mono<TraceContextProvider> getContext()
    // REMOVED: static Function<Context, Context> clearContext()
    // REMOVED: static Context withTraceContextProvider(...)
}
```

---

## Implementation Order

### Sprint 1: Phase 1 - Non-Breaking Additions
| Task | Priority | Effort | Dependencies |
|------|----------|--------|--------------|
| Add `EventBus.subscribe(Consumer)` | HIGH | Medium | None |
| Add `RedisCredentialsProvider.subscribe(Consumer)` | HIGH | Medium | None |
| Add `TraceContextProvider.getTraceContextAsync()` | LOW | Low | None |
| Create `ReactiveTracingSupport` class | LOW | Low | None |
| Deprecate Reactor-based methods | HIGH | Low | Above tasks |

### Sprint 2: Phase 2 - Internal Refactoring (Part 1)
| Task | Priority | Effort | Dependencies |
|------|----------|--------|--------------|
| Refactor `RedisClient` connection | HIGH | High | Phase 1 |
| Refactor `RedisClusterClient` connection | HIGH | High | Phase 1 |
| Refactor connection close handling | MEDIUM | Medium | None |

### Sprint 3: Phase 2 - Internal Refactoring (Part 2)
| Task | Priority | Effort | Dependencies |
|------|----------|--------|--------------|
| Refactor Master-Replica topology | HIGH | High | Sprint 2 |
| Refactor `DefaultEventBus` | MEDIUM | Medium | Phase 1 |
| Refactor `DefaultClientResources` | LOW | Low | None |

### Sprint 4: Phase 3 - Module Restructuring
| Task | Priority | Effort | Dependencies |
|------|----------|--------|--------------|
| Create `lettuce-reactive` module | HIGH | High | Phase 2 |
| Move reactive classes | HIGH | High | New module |
| Update build configuration | HIGH | Medium | Move classes |
| Update documentation | MEDIUM | Medium | All above |

### Sprint 5: Phase 4 - Breaking Changes (Next Major Version)
| Task | Priority | Effort | Dependencies |
|------|----------|--------|--------------|
| Remove deprecated methods | HIGH | Low | Phase 3 |
| Update migration guide | HIGH | Medium | Removal |
| Release major version | HIGH | Low | All above |

---

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Breaking user code | Medium | High | Deprecation period, clear migration docs |
| Performance regression | Low | Medium | Benchmark before/after |
| Increased complexity | Medium | Medium | Good module boundaries |
| Test coverage gaps | Medium | High | Comprehensive testing |

---

## Success Criteria

1. **Core module compiles without Reactor** on classpath
2. **Sync and Async APIs work** without Reactor dependency
3. **Reactive API works** when Reactor is added
4. **No performance regression** in benchmarks
5. **All existing tests pass** (with appropriate module dependencies)
6. **Clear migration documentation** for users

---

## Timeline Estimate

| Phase | Duration | Version |
|-------|----------|---------|
| Phase 1: Non-Breaking Additions | 2-3 weeks | 6.x minor |
| Phase 2: Internal Refactoring | 4-6 weeks | 6.x minor |
| Phase 3: Module Restructuring | 3-4 weeks | 7.0 |
| Phase 4: Breaking Changes | 1-2 weeks | 7.0 |

**Total: ~10-15 weeks**

