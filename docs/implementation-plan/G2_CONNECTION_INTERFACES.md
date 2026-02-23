# G2 - Connection Interfaces Implementation Plan

**Status:** Critical Blocker
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

## Potentially Affected Areas

### Base Interfaces
Interfaces that currently have `reactive()` method - will need deprecation then removal:
- `StatefulRedisConnection`
- `StatefulRedisClusterConnection`
- `StatefulRedisPubSubConnection`
- `StatefulRedisSentinelConnection`
- `StatefulRedisClusterPubSubConnection`

### New Reactive Interfaces
Create extended interfaces in reactive packages:
- `ReactiveStatefulRedisConnection`
- `ReactiveStatefulRedisClusterConnection`
- `ReactiveStatefulRedisPubSubConnection`
- `ReactiveStatefulRedisSentinelConnection`
- `ReactiveStatefulRedisClusterPubSubConnection`

### Implementations
Connection implementation classes - change `implements` to use reactive interface:
- `StatefulRedisConnectionImpl`
- `StatefulRedisClusterConnectionImpl`
- `StatefulRedisPubSubConnectionImpl`
- `StatefulRedisSentinelConnectionImpl`
- `StatefulRedisClusterPubSubConnectionImpl`

### Clients
Consider adding `connectReactive()` methods:
- `RedisClient`
- `RedisClusterClient`

---

## Implementation Approach

### Create Reactive Interfaces (Non-Breaking)

Create new reactive interfaces that extend the base interfaces:

```java
// ReactiveStatefulRedisConnection.java (NEW)
package io.lettuce.core.api.reactive;

import io.lettuce.core.api.StatefulRedisConnection;

public interface ReactiveStatefulRedisConnection<K, V>
        extends StatefulRedisConnection<K, V> {

    RedisReactiveCommands<K, V> reactive();
}
```

### Update Implementations (Non-Breaking)

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

### Add connectReactive() Methods (Non-Breaking, Optional)

```java
// In RedisClient.java
public ReactiveStatefulRedisConnection<String, String> connectReactive() {
    return (ReactiveStatefulRedisConnection<String, String>) connect();
}

public <K, V> ReactiveStatefulRedisConnection<K, V> connectReactive(RedisCodec<K, V> codec) {
    return (ReactiveStatefulRedisConnection<K, V>) connect(codec);
}
```

### Remove `reactive()` from Base Interfaces (BREAKING - 8.0)

Move `reactive()` method declaration from base interface to reactive interface.

---

## Breaking vs Non-Breaking Changes

### Non-Breaking (7.x)
- Creating new reactive interfaces
- Changing implementations to implement reactive interface
- Adding `connectReactive()` methods
- Deprecating `reactive()` in base interfaces

### Breaking (8.0)
- Removing `reactive()` method from base interfaces
- Removing Reactor imports from base interfaces

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
`newStatefulRedisConnection()` in `RedisClient` and `RedisClusterClient` return implementation classes. These should continue to work since the impl now implements the reactive interface.

### 3. Connection Pools
`GenericObjectPool<StatefulRedisConnection<K,V>>` and similar pooling constructs will work with base interface. Users needing reactive must cast pooled connections.

### 4. Master-Replica & Sentinel
`MasterReplica` and Sentinel connections follow the same pattern. Ensure consistency across all connection types.

### 5. Test Files
Tests using `connection.reactive()` directly will need updates in 8.0 to use the reactive interface type.

---

## Future Direction: Separate Module

This design enables a clean future module split:

```
Now/7.x:
┌─────────────────────────────────────────┐
│  lettuce-core                           │
│  ├── StatefulRedisConnection (base)     │  <- No Reactor imports
│  ├── ReactiveStatefulRedisConnection    │  <- Has Reactor imports (optional dep)
│  └── All implementations                │
└─────────────────────────────────────────┘

Future/8.x+:
┌───────────────────────┐     ┌─────────────────────────────┐
│  lettuce-core         │ <-- │  lettuce-reactive           │
│  (no Reactor)         │     │  (has Reactor)              │
│  Base interfaces      │     │  Reactive interfaces        │
│  Base impls           │     │  Reactive impls             │
└───────────────────────┘     └─────────────────────────────┘
```

The interfaces stay the same - files just move between modules.

---

## Scope Summary

### 7.x (Non-Breaking)

| Scope | Description |
|-------|-------------|
| Create reactive interfaces | Extended interfaces with `reactive()` method |
| Update implementations | Change `implements` to use reactive interface |
| Deprecate base `reactive()` | Add `@Deprecated` with migration message |
| Add `connectReactive()` | Typed connection methods in clients |
| Documentation | Migration guide for reactive users |

### 8.0 (Breaking)

| Scope | Description |
|-------|-------------|
| Remove `reactive()` | Remove from base interfaces |
| Remove Reactor imports | Clean base interfaces of Reactor types |
| Update tests | Use reactive interface types |
| Verification | Classloader isolation test |

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

