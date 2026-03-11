# G5 - EventBus Implementation Plan

**Status:** Critical - Dual implementation required (SPI)
**Dependencies:** G2 (Connection Interfaces)
**Blocks:** G8 (ClientResources)

---

## Problem Statement

The `EventBus` interface has Reactor types in its **public API signature**:

```java
public interface EventBus {
    Flux<Event> get();  // <-- Reactor type in public interface
    void publish(Event event);
}
```

**This is a performance-critical component** - events are published on every connection/command.

---

## Potentially Affected Areas

### Core EventBus
- `EventBus` interface - Public interface with `Flux` return type
- `DefaultEventBus` - Uses `Flux`, `Sinks`, `Scheduler` (optimized Reactor primitives)

### New Components to Create
- `CallbackEventBus` - Reactor-free implementation using callbacks
- `EventBusFactory` - SPI loader for implementation selection
- `EventBusReactiveAdapter` - Flux adapter in reactive island

### Integration Points
- `DefaultClientResources` - Creates EventBus instance

---

## Implementation Approach

### Dual Implementation via SPI Pattern

1. **Modify `EventBus` interface** - Add `subscribe(Consumer<Event>)`, deprecate `get()`
2. **Create `CallbackEventBus`** - Reactor-free implementation using callbacks
3. **Rename `DefaultEventBus` to `ReactorEventBus`** - Keep for Reactor users
4. **Create `EventBusFactory`** - SPI loader that selects implementation based on Reactor presence
5. **Create `EventBusReactiveAdapter`** - In reactive island, converts callback-based to Flux

### New Interface (7.x deprecation period)

```java
public interface EventBus {
    @Deprecated  // Will be removed in 8.0
    Flux<Event> get();

    Closeable subscribe(Consumer<Event> listener);  // New non-reactive method

    void publish(Event event);
}
```

---

## Breaking vs Non-Breaking Changes

### Non-Breaking (7.x)
- Add `subscribe(Consumer<Event>)` method to `EventBus`
- Add `@Deprecated` to `get()` method
- Create new `CallbackEventBus` implementation
- Create `EventBusFactory` for SPI selection

### Breaking (8.0)
- Remove `get()` method from `EventBus`
- Remove `Flux` import from `EventBus.java`

---

## Hotspots & Considerations

### 1. Performance-Critical Code

`DefaultEventBus` uses highly optimized Reactor primitives:
- `Sinks.Many.multicast().directBestEffort()` - Lock-free multicast
- Busy-loop `tryEmitNext()` - Optimized for contention

**Action:** Benchmark callback implementation vs Reactor implementation.

### 2. Users Consuming Events via Flux

Users currently using `eventBus.get().subscribe(...)` need migration path.

**Solution:** Provide `EventBusReactiveAdapter.toFlux(eventBus)` in reactive island.

### 3. ClientResources Integration

`DefaultClientResources` creates `DefaultEventBus`. Needs update to use `EventBusFactory`.

---

## Scope Summary

### 7.x (Deprecation)

| Scope | Description |
|-------|-------------|
| Interface update | Add `subscribe()`, deprecate `get()` |
| Callback implementation | Create Reactor-free `CallbackEventBus` |
| Factory | Create SPI-based `EventBusFactory` |
| Adapter | Create `EventBusReactiveAdapter` for Flux users |
| Integration | Update `DefaultClientResources` |
| Verification | Benchmark callback vs Reactor performance |

### 8.0 (Breaking)

| Scope | Description |
|-------|-------------|
| Remove deprecated | Remove `get()` from interface |
| Remove imports | Remove `Flux` import from `EventBus.java` |
| Update usages | Update all code using `eventBus.get()` |

---

## Migration Guide

**Before (7.x - deprecated):**
```java
eventBus.get().subscribe(event -> handle(event));
```

**After (7.x+ - recommended):**
```java
Closeable subscription = eventBus.subscribe(event -> handle(event));
// Later: subscription.close();
```

**For Flux users (7.x+):**
```java
Flux<Event> events = EventBusReactiveAdapter.toFlux(eventBus);
```

