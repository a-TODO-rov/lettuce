# G2 - Connection Interfaces Implementation Plan

**Status:** Critical Blocker - Must be completed first  
**Dependencies:** None  
**Blocks:** G3, G4, G5, G6, G7, G8

---

## Problem Statement

The connection interfaces expose `reactive()` methods that return Reactor-based command types. When the JVM loads these interfaces, it must resolve the Reactor types in the method signatures, causing `NoClassDefFoundError` if Reactor is not on the classpath.

This is the **fundamental blocker** that prevents Reactor from being optional.

---

## Breaking Changes (8.0)

### Interfaces - Remove `reactive()` method

| Interface | File | Line | Change |
|-----------|------|------|--------|
| `StatefulRedisConnection` | `src/main/java/io/lettuce/core/api/StatefulRedisConnection.java` | 47 | Remove `RedisReactiveCommands<K, V> reactive()` |
| `StatefulRedisClusterConnection` | `src/main/java/io/lettuce/core/cluster/api/StatefulRedisClusterConnection.java` | 68 | Remove `RedisAdvancedClusterReactiveCommands<K, V> reactive()` |
| `StatefulRedisPubSubConnection` | `src/main/java/io/lettuce/core/pubsub/StatefulRedisPubSubConnection.java` | 44 | Remove `RedisPubSubReactiveCommands<K, V> reactive()` |
| `StatefulRedisSentinelConnection` | `src/main/java/io/lettuce/core/sentinel/api/StatefulRedisSentinelConnection.java` | 41 | Remove `RedisSentinelReactiveCommands<K, V> reactive()` |
| `StatefulRedisClusterPubSubConnection` | `src/main/java/io/lettuce/core/cluster/pubsub/StatefulRedisClusterPubSubConnection.java` | 80 | Remove `RedisClusterPubSubReactiveCommands<K, V> reactive()` |

### Interfaces - Remove Reactor imports

| Interface | File | Line | Import to Remove |
|-----------|------|------|------------------|
| `StatefulRedisConnection` | `src/main/java/io/lettuce/core/api/StatefulRedisConnection.java` | 5 | `io.lettuce.core.api.reactive.RedisReactiveCommands` |
| `StatefulRedisClusterConnection` | `src/main/java/io/lettuce/core/cluster/api/StatefulRedisClusterConnection.java` | 32 | `io.lettuce.core.cluster.api.reactive.RedisAdvancedClusterReactiveCommands` |
| `StatefulRedisPubSubConnection` | `src/main/java/io/lettuce/core/pubsub/StatefulRedisPubSubConnection.java` | 5 | `io.lettuce.core.pubsub.api.reactive.RedisPubSubReactiveCommands` |
| `StatefulRedisSentinelConnection` | `src/main/java/io/lettuce/core/sentinel/api/StatefulRedisSentinelConnection.java` | 6 | `io.lettuce.core.sentinel.api.reactive.RedisSentinelReactiveCommands` |
| `StatefulRedisClusterPubSubConnection` | `src/main/java/io/lettuce/core/cluster/pubsub/StatefulRedisClusterPubSubConnection.java` | 12 | `io.lettuce.core.cluster.pubsub.api.reactive.RedisClusterPubSubReactiveCommands` |

---

## Deprecations (7.x - ASAP Release)

### Add `@Deprecated` to `reactive()` methods

All 5 interfaces need deprecation annotations with clear migration message:

```java
/**
 * @deprecated Use {@code getExtension(RedisReactiveCommands.class)} instead.
 *             This method will be removed in Lettuce 8.0.
 *             Reactor dependency will become optional in 8.0.
 */
@Deprecated
RedisReactiveCommands<K, V> reactive();
```

### Files to modify:

1. `src/main/java/io/lettuce/core/api/StatefulRedisConnection.java` - Line 47
2. `src/main/java/io/lettuce/core/cluster/api/StatefulRedisClusterConnection.java` - Line 68
3. `src/main/java/io/lettuce/core/pubsub/StatefulRedisPubSubConnection.java` - Line 44
4. `src/main/java/io/lettuce/core/sentinel/api/StatefulRedisSentinelConnection.java` - Line 41
5. `src/main/java/io/lettuce/core/cluster/pubsub/StatefulRedisClusterPubSubConnection.java` - Line 80

---

## Non-Breaking Additions (7.x)

### 1. Add `getExtension()` to `StatefulConnection` interface

**File:** `src/main/java/io/lettuce/core/api/StatefulConnection.java`

```java
/**
 * Returns an extension of the specified type if available.
 * <p>
 * This method provides access to optional API extensions such as reactive commands.
 * Extensions are loaded lazily and may require additional dependencies on the classpath.
 * </p>
 * <p>
 * Example usage:
 * <pre>{@code
 * RedisReactiveCommands<K, V> reactive = connection.getExtension(RedisReactiveCommands.class);
 * if (reactive != null) {
 *     reactive.get("key").subscribe(System.out::println);
 * }
 * }</pre>
 * </p>
 *
 * @param extensionType the extension type class
 * @param <T> the extension type
 * @return the extension instance, or {@code null} if not available
 * @since 7.x
 */
<T> T getExtension(Class<T> extensionType);
```

### 2. Implement extension registry in implementation classes

**Files to modify:**

| Implementation | File |
|----------------|------|
| `StatefulRedisConnectionImpl` | `src/main/java/io/lettuce/core/StatefulRedisConnectionImpl.java` |
| `StatefulRedisClusterConnectionImpl` | `src/main/java/io/lettuce/core/cluster/StatefulRedisClusterConnectionImpl.java` |
| `StatefulRedisPubSubConnectionImpl` | `src/main/java/io/lettuce/core/pubsub/StatefulRedisPubSubConnectionImpl.java` |
| `StatefulRedisSentinelConnectionImpl` | `src/main/java/io/lettuce/core/sentinel/StatefulRedisSentinelConnectionImpl.java` |
| `StatefulRedisClusterPubSubConnectionImpl` | `src/main/java/io/lettuce/core/cluster/StatefulRedisClusterPubSubConnectionImpl.java` |

**Implementation pattern:**

```java
@Override
@SuppressWarnings("unchecked")
public <T> T getExtension(Class<T> extensionType) {
    if (extensionType == RedisReactiveCommands.class) {
        return (T) getOrCreateReactiveCommands();
    }
    return null;
}

private RedisReactiveCommands<K, V> getOrCreateReactiveCommands() {
    // Lazy initialization with Reactor presence check
    if (reactive == null) {
        synchronized (this) {
            if (reactive == null) {
                if (!isReactorPresent()) {
                    return null; // or throw IllegalStateException
                }
                reactive = newRedisReactiveCommandsImpl();
            }
        }
    }
    return reactive;
}

private static boolean isReactorPresent() {
    try {
        Class.forName("reactor.core.publisher.Flux");
        return true;
    } catch (ClassNotFoundException e) {
        return false;
    }
}
```

### 3. Modify deprecated `reactive()` to check Reactor presence

**Implementation pattern for deprecated method:**

```java
@Override
@Deprecated
public RedisReactiveCommands<K, V> reactive() {
    RedisReactiveCommands<K, V> commands = getExtension(RedisReactiveCommands.class);
    if (commands == null) {
        throw new IllegalStateException(
            "Reactive commands are not available. " +
            "Please add reactor-core to your classpath to use reactive API. " +
            "This method is deprecated and will be removed in Lettuce 8.0. " +
            "Use getExtension(RedisReactiveCommands.class) instead."
        );
    }
    return commands;
}
```

---

## Internal Refactoring

### Change reactive field initialization from eager to lazy

**Current (eager):**
```java
// In constructor
this.reactive = newRedisReactiveCommandsImpl();
```

**New (lazy):**
```java
// Field becomes volatile for thread-safety
protected volatile RedisReactiveCommandsImpl<K, V> reactive;

// Initialized only when getExtension() is called
```

### Files affected:

| File | Current Line | Change |
|------|--------------|--------|
| `StatefulRedisConnectionImpl.java` | 65, 109 | Make field volatile, remove constructor init |
| `StatefulRedisClusterConnectionImpl.java` | 87, 126 | Make field volatile, remove constructor init |
| `StatefulRedisSentinelConnectionImpl.java` | 53, 84 | Make field volatile, remove constructor init |

**Note:** PubSub implementations inherit from `StatefulRedisConnectionImpl` and override `newRedisReactiveCommandsImpl()`, so they will automatically benefit from lazy initialization.

---

## Files Affected Summary

### Interfaces (5 files)

| File | Deprecation (7.x) | Breaking (8.0) |
|------|-------------------|----------------|
| `src/main/java/io/lettuce/core/api/StatefulConnection.java` | Add `getExtension()` | - |
| `src/main/java/io/lettuce/core/api/StatefulRedisConnection.java` | Deprecate `reactive()` | Remove `reactive()` + import |
| `src/main/java/io/lettuce/core/cluster/api/StatefulRedisClusterConnection.java` | Deprecate `reactive()` | Remove `reactive()` + import |
| `src/main/java/io/lettuce/core/pubsub/StatefulRedisPubSubConnection.java` | Deprecate `reactive()` | Remove `reactive()` + import |
| `src/main/java/io/lettuce/core/sentinel/api/StatefulRedisSentinelConnection.java` | Deprecate `reactive()` | Remove `reactive()` + import |
| `src/main/java/io/lettuce/core/cluster/pubsub/StatefulRedisClusterPubSubConnection.java` | Deprecate `reactive()` | Remove `reactive()` + import |

### Implementations (5 files)

| File | Changes (7.x) |
|------|---------------|
| `src/main/java/io/lettuce/core/StatefulRedisConnectionImpl.java` | Implement `getExtension()`, lazy init, modify `reactive()` |
| `src/main/java/io/lettuce/core/cluster/StatefulRedisClusterConnectionImpl.java` | Implement `getExtension()`, lazy init, modify `reactive()` |
| `src/main/java/io/lettuce/core/pubsub/StatefulRedisPubSubConnectionImpl.java` | Implement `getExtension()`, modify `reactive()` |
| `src/main/java/io/lettuce/core/sentinel/StatefulRedisSentinelConnectionImpl.java` | Implement `getExtension()`, lazy init, modify `reactive()` |
| `src/main/java/io/lettuce/core/cluster/StatefulRedisClusterPubSubConnectionImpl.java` | Implement `getExtension()`, modify `reactive()` |

---

## Testing Requirements

### New Tests

1. **Test `getExtension()` returns reactive commands when Reactor present**
2. **Test `getExtension()` returns `null` when Reactor absent** (requires separate test module or classloader isolation)
3. **Test deprecated `reactive()` throws `IllegalStateException` when Reactor absent**
4. **Test lazy initialization is thread-safe**

### Existing Tests to Verify

- All existing reactive API tests should continue to pass
- Integration tests using `connection.reactive()` should work with deprecation warnings

---

## Migration Guide for Users

### Before (current API):
```java
StatefulRedisConnection<String, String> connection = client.connect();
RedisReactiveCommands<String, String> reactive = connection.reactive();
reactive.get("key").subscribe(System.out::println);
```

### After (7.x - recommended):
```java
StatefulRedisConnection<String, String> connection = client.connect();
RedisReactiveCommands<String, String> reactive = connection.getExtension(RedisReactiveCommands.class);
if (reactive != null) {
    reactive.get("key").subscribe(System.out::println);
}
```

### After (8.0 - required):
```java
// Same as 7.x recommended approach
// connection.reactive() will no longer exist
```

---

## Task Breakdown

### 7.x Tasks (Deprecations - ASAP)

| Task ID | Description | Files |
|---------|-------------|-------|
| G2-1 | Add `getExtension()` method to `StatefulConnection` interface | 1 |
| G2-2 | Implement `getExtension()` in `StatefulRedisConnectionImpl` with lazy init | 1 |
| G2-3 | Implement `getExtension()` in `StatefulRedisClusterConnectionImpl` with lazy init | 1 |
| G2-4 | Implement `getExtension()` in `StatefulRedisPubSubConnectionImpl` | 1 |
| G2-5 | Implement `getExtension()` in `StatefulRedisSentinelConnectionImpl` with lazy init | 1 |
| G2-6 | Implement `getExtension()` in `StatefulRedisClusterPubSubConnectionImpl` | 1 |
| G2-7 | Add `@Deprecated` to `reactive()` in all 5 interfaces | 5 |
| G2-8 | Modify `reactive()` implementations to check Reactor presence | 5 |
| G2-9 | Add unit tests for `getExtension()` | 1 |
| G2-10 | Add integration tests for lazy initialization | 1 |

### 8.0 Tasks (Breaking Changes)

| Task ID | Description | Files |
|---------|-------------|-------|
| G2-11 | Remove `reactive()` method from all 5 interfaces | 5 |
| G2-12 | Remove Reactor imports from all 5 interfaces | 5 |
| G2-13 | Remove `reactive()` implementations from all 5 impl classes | 5 |
| G2-14 | Update all tests using `reactive()` to use `getExtension()` | Many |
| G2-15 | Add classloader isolation test to verify no Reactor loading | 1 |

