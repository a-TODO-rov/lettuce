# G2 - Connection Interfaces Implementation Plan

**Status:** Implemented
**Dependencies:** None
**Blocks:** G3, G4, G5, G6, G7, G8

---

## Problem Statement

The connection interfaces expose `reactive()` methods that return Reactor-based command types. The concern was that when the JVM loads these interfaces, it must resolve the Reactor types in the method signatures, causing `NoClassDefFoundError` if Reactor is not on the classpath.

---

## Solution: Lazy Loading Pattern

**Key Discovery:** The JVM does NOT load a method's return type until the method actually returns a value.

Method signatures in interfaces are stored as string constants in the class file. The return type is only resolved and loaded when:
1. The method is invoked AND
2. The method successfully returns a value

This means `reactive()` can remain in the base interface. We use:
1. **Lazy field initialization** - Don't create reactive commands until needed
2. **Guard check** - Throw exception BEFORE returning, preventing type resolution

---

## Implementation

### Interface (Unchanged API)
```java
// StatefulRedisConnection.java - reactive() STAYS in base interface
public interface StatefulRedisConnection<K, V> extends StatefulConnection<K, V> {
    RedisCommands<K, V> sync();
    RedisAsyncCommands<K, V> async();
    RedisReactiveCommands<K, V> reactive();  // Still here!
}
```

### Implementation (Lazy Init + Guard)
```java
// StatefulRedisConnectionImpl.java
public class StatefulRedisConnectionImpl<K, V> implements StatefulRedisConnection<K, V> {

    // NOT initialized in constructor - lazy!
    private volatile RedisReactiveCommandsImpl<K, V> reactive;

    public StatefulRedisConnectionImpl(...) {
        this.async = newRedisAsyncCommandsImpl();
        this.sync = newRedisSyncCommandsImpl();
        // Note: reactive NOT initialized here
    }

    @Override
    public RedisReactiveCommands<K, V> reactive() {
        ReactorProvider.checkForReactorLibrary();  // Guard - throws if no Reactor
        RedisReactiveCommandsImpl<K, V> result = reactive;
        if (result == null) {
            synchronized (this) {
                result = reactive;
                if (result == null) {
                    reactive = result = newRedisReactiveCommandsImpl();
                }
            }
        }
        return result;
    }
}
```

### ReactorProvider Guard
```java
// ReactorProvider.java
public class ReactorProvider {
    private static final boolean REACTOR_AVAILABLE =
        LettuceClassUtils.isPresent("reactor.core.publisher.Mono");

    public static boolean isAvailable() {
        return REACTOR_AVAILABLE;
    }

    public static void checkForReactorLibrary() {
        LettuceAssert.assertState(isAvailable(),
            "Project Reactor (reactor-core) is not available. " +
            "Add reactor-core to your classpath to use the reactive API.");
    }
}
```

---

## Why This Works (JVM Classloading Rules)

| Operation | Triggers Class Loading? |
|-----------|------------------------|
| `private volatile RedisReactiveCommandsImpl reactive;` | **NO** - field declaration is just metadata |
| `this.reactive = null` | **NO** - null doesn't require type resolution |
| `this.reactive = newRedisReactiveCommandsImpl()` | **YES** - method call resolves return type |
| `return reactive` (when method throws before) | **NO** - return type never resolved |

**Critical insight:** If `ReactorProvider.checkForReactorLibrary()` throws an exception, the `return` statement is never reached, so `RedisReactiveCommands` is never resolved by the JVM.

---

## Verification

Test application without Reactor on classpath:
```java
// Works - Reactor never loaded
StatefulRedisConnection<String, String> conn = client.connect();
conn.sync().set("key", "value");
conn.async().get("key").get();

// Throws IllegalStateException with clear message (NOT NoClassDefFoundError)
conn.reactive();  // "Project Reactor (reactor-core) is not available..."
```

---

## Affected Files

### Modified
- `StatefulRedisConnectionImpl` - Lazy init pattern
- `StatefulRedisPubSubConnectionImpl` - Uses `super.reactive()`
- `StatefulRedisClusterConnectionImpl` - Lazy init pattern
- Other connection implementations follow same pattern

### Created
- `ReactorProvider` - Guard class for Reactor availability check

### NOT Needed (Removed from plan)
- ~~`ReactiveStatefulRedisConnection`~~ - No separate interface needed
- ~~`connectReactive()` methods~~ - Not needed, `connect().reactive()` works

---

## Limitations

### RedisClusterClient Limitation

`RedisClusterClient` still uses `Mono<SocketAddress>` internally for socket address resolution, retry logic, and topology refresh. This means:

- **Standalone `RedisClient`**: Works without Reactor
- **`RedisClusterClient`**: Still requires Reactor on classpath

Future work may refactor cluster client internals to use `CompletionStage`.

---

## GraalVM Native Image Considerations

The lazy loading strategy works on **HotSpot JVM** but has different behavior on **GraalVM Native Image**:

### HotSpot JVM (Works)
- Classes loaded lazily at RUNTIME when first referenced
- Our lazy init prevents Reactor loading until `reactive()` is called

### GraalVM Native Image (Requires Additional Configuration)
- Static analysis at BUILD time traces all reachable code paths
- Reactor classes must be available during native-image build
- Even if never executed at runtime, reachable code is analyzed

### Solution for GraalVM
Use conditional reachability metadata in `reachability-metadata.json`:
```json
{
  "reflection": [
    {
      "condition": {
        "typeReached": "reactor.core.publisher.Mono"
      },
      "type": "io.lettuce.core.RedisReactiveCommandsImpl"
    }
  ]
}
```

This tells GraalVM: "Only include reactive classes if Reactor is reached."

---

## User Experience

### All users (NO changes needed)
```java
// Works exactly as before - Reactor never loaded if not called
StatefulRedisConnection<String, String> conn = client.connect();
RedisCommands<String, String> sync = conn.sync();
RedisAsyncCommands<String, String> async = conn.async();
```

### Reactive users (NO changes needed)
```java
// Works exactly as before - just need Reactor on classpath
StatefulRedisConnection<String, String> conn = client.connect();
RedisReactiveCommands<String, String> reactive = conn.reactive();
```

### Without Reactor - clear error message
```java
// Without Reactor on classpath
StatefulRedisConnection<String, String> conn = client.connect();  // Works
conn.reactive();  // Throws IllegalStateException with clear message
// "Project Reactor (reactor-core) is not available. Add reactor-core to your classpath..."
```

---

## Summary

The lazy loading pattern achieves the goal of making Reactor optional without:
- Breaking API compatibility
- Requiring new interfaces
- Requiring new connect methods

| Approach | Complexity | API Changes | Breaking |
|----------|------------|-------------|----------|
| ~~Dual Interface~~ | High | Yes | Eventually |
| **Lazy Loading** | Low | None | No |

The key insight is understanding JVM classloading behavior - method return types are only resolved when the method actually returns a value, not when the interface is loaded.
