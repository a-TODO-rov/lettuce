# G2 - Connection Interfaces Implementation Plan

**Status:** Critical Blocker - Must be completed first
**Dependencies:** None
**Blocks:** G3, G4, G5, G6, G7, G8

---

## Problem Statement

The connection interfaces expose `reactive()` methods that return Reactor-based command types. When the JVM loads these interfaces, it must resolve the Reactor types in the method signatures, causing `NoClassDefFoundError` if Reactor is not on the classpath.

This is the **fundamental blocker** that prevents Reactor from being optional.

---

## Solution: Dual Interface Approach

Split each connection interface into two:
- **Base interface** - Contains `sync()` and `async()` methods only, NO Reactor imports
- **Reactive interface** - Extends base, adds `reactive()` method, HAS Reactor imports

This approach:
- Breaks the class loading chain at the interface level
- Provides a clean, typed API for both reactive and non-reactive users
- Sets up cleanly for a future `lettuce-reactive` module separation

---

## Interface Hierarchy

### Current Structure (problematic)
```
StatefulConnection<K,V>                    (base - no reactive)
       ↑
StatefulRedisConnection<K,V>              (has reactive() - THE PROBLEM)
       ↑
StatefulRedisConnectionImpl<K,V>          (implementation)
```

### New Structure (dual interface)
```
StatefulConnection<K,V>                    (base - no reactive)
       ↑
StatefulRedisConnection<K,V>              (sync + async only, NO reactive)
       ↑
ReactiveStatefulRedisConnection<K,V>      (NEW - adds reactive())
       ↑
StatefulRedisConnectionImpl<K,V>          (implements ReactiveStatefulRedisConnection)
```

---

## Affected Files

### Base Interfaces (5 files) - Modify to remove `reactive()`

| Interface | File |
|-----------|------|
| `StatefulRedisConnection` | `src/main/java/io/lettuce/core/api/StatefulRedisConnection.java` |
| `StatefulRedisClusterConnection` | `src/main/java/io/lettuce/core/cluster/api/StatefulRedisClusterConnection.java` |
| `StatefulRedisPubSubConnection` | `src/main/java/io/lettuce/core/pubsub/StatefulRedisPubSubConnection.java` |
| `StatefulRedisSentinelConnection` | `src/main/java/io/lettuce/core/sentinel/api/StatefulRedisSentinelConnection.java` |
| `StatefulRedisClusterPubSubConnection` | `src/main/java/io/lettuce/core/cluster/pubsub/StatefulRedisClusterPubSubConnection.java` |

### Reactive Interfaces (5 NEW files) - Create in reactive packages

| New Interface | Location |
|---------------|----------|
| `ReactiveStatefulRedisConnection` | `src/main/java/io/lettuce/core/api/reactive/` |
| `ReactiveStatefulRedisClusterConnection` | `src/main/java/io/lettuce/core/cluster/api/reactive/` |
| `ReactiveStatefulRedisPubSubConnection` | `src/main/java/io/lettuce/core/pubsub/api/reactive/` |
| `ReactiveStatefulRedisSentinelConnection` | `src/main/java/io/lettuce/core/sentinel/api/reactive/` |
| `ReactiveStatefulRedisClusterPubSubConnection` | `src/main/java/io/lettuce/core/cluster/pubsub/api/reactive/` |

### Implementations (5 files) - Change `implements` declaration

| Implementation | File |
|----------------|------|
| `StatefulRedisConnectionImpl` | `src/main/java/io/lettuce/core/StatefulRedisConnectionImpl.java` |
| `StatefulRedisClusterConnectionImpl` | `src/main/java/io/lettuce/core/cluster/StatefulRedisClusterConnectionImpl.java` |
| `StatefulRedisPubSubConnectionImpl` | `src/main/java/io/lettuce/core/pubsub/StatefulRedisPubSubConnectionImpl.java` |
| `StatefulRedisSentinelConnectionImpl` | `src/main/java/io/lettuce/core/sentinel/StatefulRedisSentinelConnectionImpl.java` |
| `StatefulRedisClusterPubSubConnectionImpl` | `src/main/java/io/lettuce/core/cluster/StatefulRedisClusterPubSubConnectionImpl.java` |

### Client Classes (2 files) - Add `connectReactive()` methods (optional)

| Client | File |
|--------|------|
| `RedisClient` | `src/main/java/io/lettuce/core/RedisClient.java` |
| `RedisClusterClient` | `src/main/java/io/lettuce/core/cluster/RedisClusterClient.java` |

---

## Implementation Strategy

### Phase 1: Create Reactive Interfaces (Non-Breaking)

Create 5 new reactive interfaces that extend the base interfaces:

```java
// ReactiveStatefulRedisConnection.java (NEW)
package io.lettuce.core.api.reactive;

import io.lettuce.core.api.StatefulRedisConnection;

public interface ReactiveStatefulRedisConnection<K, V>
        extends StatefulRedisConnection<K, V> {

    RedisReactiveCommands<K, V> reactive();
}
```

### Phase 2: Update Implementations (Non-Breaking)

Change implementations to implement the reactive interface (which extends base):

```java
// Before
public class StatefulRedisConnectionImpl<K, V>
        implements StatefulRedisConnection<K, V> { ... }

// After
public class StatefulRedisConnectionImpl<K, V>
        implements ReactiveStatefulRedisConnection<K, V> { ... }
```

**Key insight**: Since `ReactiveStatefulRedisConnection` extends `StatefulRedisConnection`, the implementation still IS-A `StatefulRedisConnection`. Existing code continues to work.

### Phase 3: Add connectReactive() Methods (Non-Breaking, Optional)

```java
// In RedisClient.java
public ReactiveStatefulRedisConnection<String, String> connectReactive() {
    return (ReactiveStatefulRedisConnection<String, String>) connect();
}

public <K, V> ReactiveStatefulRedisConnection<K, V> connectReactive(RedisCodec<K, V> codec) {
    return (ReactiveStatefulRedisConnection<K, V>) connect(codec);
}
```

### Phase 4: Remove `reactive()` from Base Interfaces (BREAKING - 8.0)

Move `reactive()` method declaration from base interface to reactive interface.

---

## Breaking vs Non-Breaking Changes

### Non-Breaking (7.x)
- Creating new reactive interfaces ✅
- Changing implementations to implement reactive interface ✅
- Adding `connectReactive()` methods ✅
- Deprecating `reactive()` in base interfaces ✅

### Breaking (8.0)
- Removing `reactive()` method from base interfaces ❌
- Removing Reactor imports from base interfaces ❌

**Migration path**: Users calling `connection.reactive()` must either:
1. Cast to `ReactiveStatefulRedisConnection` first
2. Use `client.connectReactive()` to get a typed reactive connection

---

## User Experience

### Non-reactive users (NO changes needed)
```java
// Works exactly as before - Reactor never loaded
StatefulRedisConnection<String, String> conn = client.connect();
RedisCommands<String, String> sync = conn.sync();
RedisAsyncCommands<String, String> async = conn.async();
```

### Reactive users - Option A: Cast
```java
// Cast to reactive interface
ReactiveStatefulRedisConnection<String, String> conn =
    (ReactiveStatefulRedisConnection<String, String>) client.connect();
RedisReactiveCommands<String, String> reactive = conn.reactive();
```

### Reactive users - Option B: Typed connect (recommended)
```java
// Use new typed method
ReactiveStatefulRedisConnection<String, String> conn = client.connectReactive();
RedisReactiveCommands<String, String> reactive = conn.reactive();
```

---

## Hotspots & Considerations

### 1. `RedisChannelHandler` Type Parameter
The `RedisChannelHandler` base class uses `StatefulConnection` as a type bound. Verify that reactive interfaces work correctly through the inheritance chain.

### 2. Factory Methods
`newStatefulRedisConnection()` in `RedisClient` and `RedisClusterClient` return `StatefulRedisConnectionImpl`. These should continue to work since the impl now implements the reactive interface.

### 3. Connection Pools
`GenericObjectPool<StatefulRedisConnection<K,V>>` and similar pooling constructs will work with base interface. Users needing reactive must cast pooled connections.

### 4. Master-Replica & Sentinel
`MasterReplica` and Sentinel connections follow the same pattern. Ensure consistency across all connection types.

### 5. Test Files
Many tests use `connection.reactive()` directly. These will need updates in 8.0 to use the reactive interface type.

---

## Future Direction: Separate Module

This design enables a clean future module split:

```
Phase 1 (Now/7.x):
┌─────────────────────────────────────────┐
│  lettuce-core                           │
│  ├── StatefulRedisConnection (base)     │  ← No Reactor imports
│  ├── ReactiveStatefulRedisConnection    │  ← Has Reactor imports (optional dep)
│  └── All implementations                │
└─────────────────────────────────────────┘

Phase 2 (Future/8.x+):
┌───────────────────────┐     ┌─────────────────────────────┐
│  lettuce-core         │ ←── │  lettuce-reactive           │
│  (no Reactor)         │     │  (has Reactor)              │
│  Base interfaces      │     │  Reactive interfaces        │
│  Base impls           │     │  Reactive impls             │
└───────────────────────┘     └─────────────────────────────┘
```

The interfaces stay the same - files just move between modules.

---

## Task Summary

### 7.x Tasks (Non-Breaking)

| Task | Description |
|------|-------------|
| G2-1 | Create 5 new reactive interfaces in reactive packages |
| G2-2 | Update 5 implementation classes to implement reactive interfaces |
| G2-3 | Deprecate `reactive()` in base interfaces with migration message |
| G2-4 | Add `connectReactive()` methods to `RedisClient` and `RedisClusterClient` |
| G2-5 | Update documentation and migration guide |

### 8.0 Tasks (Breaking)

| Task | Description |
|------|-------------|
| G2-6 | Remove `reactive()` method from 5 base interfaces |
| G2-7 | Remove Reactor imports from 5 base interfaces |
| G2-8 | Update all tests to use reactive interface types |
| G2-9 | Add classloader isolation test to verify Reactor not loaded |

---

## Migration Guide

### 7.x (Deprecation Period)
```java
// Old way (deprecated, still works)
StatefulRedisConnection<String, String> conn = client.connect();
conn.reactive().get("key").subscribe();  // Deprecation warning

// New way (recommended)
ReactiveStatefulRedisConnection<String, String> conn = client.connectReactive();
conn.reactive().get("key").subscribe();  // Clean
```

### 8.0 (Breaking)
```java
// This will NOT compile - reactive() removed from base interface
StatefulRedisConnection<String, String> conn = client.connect();
conn.reactive();  // Compile error!

// Must use reactive interface
ReactiveStatefulRedisConnection<String, String> conn = client.connectReactive();
conn.reactive().get("key").subscribe();  // Works
```

