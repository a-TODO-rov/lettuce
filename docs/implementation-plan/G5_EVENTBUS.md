# G5 - EventBus Implementation Plan

**Status:** Implemented
**Dependencies:** G2 (Connection Interfaces)
**Blocks:** G8 (ClientResources)

---

## Problem Statement

The `EventBus` interface had Reactor types in its **public API signature**:

```java
public interface EventBus {
    Flux<Event> get();  // <-- Reactor type in public interface
    void publish(Event event);
}
```

---

## Solution: Composition Pattern

Instead of SPI factory selection, we use **composition**:

1. `DefaultEventBus` - Reactor-free, callback-based implementation
2. `ReactorEventBus` - Reactor-based implementation (uses `Sinks`, `Flux`)
3. `DefaultEventBus` optionally **wraps** `ReactorEventBus` when Reactor is available

This leverages the same JVM classloading insight from G2: assigning `null` to a field doesn't trigger class loading of the field's type.

---

## Implementation

### EventBus Interface (Updated)

```java
public interface EventBus {
    // Reactor-free callback subscription
    Closeable subscribe(Consumer<Event> listener);

    void publish(Event event);

    // Access to reactive API - throws if Reactor not available
    ReactiveEventBus reactive();
}
```

### ReactiveEventBus Interface (New)

```java
public interface ReactiveEventBus {
    Flux<Event> get();
    void publish(Event event);
}
```

### DefaultEventBus (Reactor-Free)

```java
public class DefaultEventBus implements EventBus {
    private final Set<Consumer<Event>> listeners = ConcurrentHashMap.newKeySet();
    private final ReactorEventBus reactorEventBus;  // Can be null

    // Reactor-free constructor
    public DefaultEventBus() {
        this.reactorEventBus = null;  // NO class loading triggered!
    }

    // With Reactor support
    public DefaultEventBus(ReactorEventBus reactorEventBus) {
        this.reactorEventBus = reactorEventBus;
    }

    @Override
    public Closeable subscribe(Consumer<Event> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    @Override
    public void publish(Event event) {
        // Dispatch to callback listeners
        for (Consumer<Event> listener : listeners) {
            listener.accept(event);
        }
        // Also publish to reactive bus if available
        if (reactorEventBus != null) {
            reactorEventBus.publish(event);
        }
    }

    @Override
    public ReactiveEventBus reactive() {
        if (reactorEventBus == null) {
            throw new ReactorNotAvailableException(
                "Reactive EventBus not available. Add reactor-core to your classpath.");
        }
        return reactorEventBus;
    }
}
```

### Why This Works (JVM Classloading)

```java
private final ReactorEventBus reactorEventBus;

public DefaultEventBus() {
    this.reactorEventBus = null;  // Just assigns null - NO class loading!
}
```

| Operation | Triggers ReactorEventBus Loading? |
|-----------|----------------------------------|
| `this.reactorEventBus = null` | **NO** |
| `this.reactorEventBus = new ReactorEventBus()` | **YES** |
| `if (reactorEventBus != null)` | **NO** - null check doesn't load type |
| `reactorEventBus.publish(event)` | **YES** - but only reached if non-null |

---

## ClientResources Integration

`DefaultClientResources` creates the appropriate EventBus:

```java
// In DefaultClientResources.Builder
public EventBus createEventBus() {
    if (ReactorProvider.isAvailable()) {
        return new DefaultEventBus(new ReactorEventBus(...));
    } else {
        return new DefaultEventBus();  // Reactor-free
    }
}
```

---

## Affected Files

### Modified
- `EventBus` interface - Added `subscribe()` and `reactive()` methods
- `DefaultClientResources` - Conditional EventBus creation

### Created
- `ReactiveEventBus` - Interface for reactive operations
- `ReactorEventBus` - Reactor-based implementation
- `ReactorNotAvailableException` - Clear error for missing Reactor

### Renamed
- ~~`DefaultEventBus`~~ kept as-is, but now wraps optional `ReactorEventBus`

---

## User Experience

### Callback-based (works without Reactor)
```java
Closeable subscription = eventBus.subscribe(event -> {
    System.out.println("Event: " + event);
});
// Later: subscription.close();
```

### Reactive (requires Reactor)
```java
eventBus.reactive().get()
    .filter(event -> event instanceof ConnectionEvent)
    .subscribe(event -> handle(event));
```

### Without Reactor - clear error
```java
eventBus.reactive();  // Throws ReactorNotAvailableException
// "Reactive EventBus not available. Add reactor-core to your classpath."
```

---

## Summary

| Approach | Complexity | Why Chosen |
|----------|------------|------------|
| ~~SPI Factory~~ | Higher | Requires service loader, more classes |
| **Composition** | Lower | Simple null check, leverages JVM behavior |

The composition pattern is simpler and leverages the same JVM classloading insight used in G2: `field = null` doesn't trigger class loading.
