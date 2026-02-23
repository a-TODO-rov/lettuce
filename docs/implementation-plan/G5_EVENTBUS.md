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

Additionally, `DefaultEventBus` uses highly optimized Reactor primitives:
- `Sinks.Many.multicast().directBestEffort()` - Lock-free multicast
- Busy-loop `tryEmitNext()` - Optimized for high throughput

**This is a performance-critical component** - events are published on every connection, command, etc.

---

## Breaking Changes (8.0)

### 1. Remove `Flux<Event> get()` from EventBus interface

**Current:**
```java
public interface EventBus {
    Flux<Event> get();
    void publish(Event event);
}
```

**New (8.0):**
```java
public interface EventBus {
    /**
     * Subscribe to events.
     * @return Closeable to unsubscribe
     */
    Closeable subscribe(Consumer<Event> listener);
    
    void publish(Event event);
}
```

### 2. Remove Reactor import from EventBus.java

| File | Line | Import to Remove |
|------|------|------------------|
| `EventBus.java` | 3 | `reactor.core.publisher.Flux` |

---

## Deprecations (7.x - ASAP Release)

### Add `subscribe()` method and deprecate `get()`

```java
public interface EventBus {
    
    /**
     * @deprecated Use {@link #subscribe(Consumer)} instead.
     *             This method will be removed in Lettuce 8.0.
     */
    @Deprecated
    Flux<Event> get();
    
    /**
     * Subscribe to events.
     * @param listener the event listener
     * @return Closeable to unsubscribe
     * @since 7.x
     */
    Closeable subscribe(Consumer<Event> listener);
    
    void publish(Event event);
}
```

---

## Non-Breaking Additions (7.x)

### 1. Add `subscribe(Consumer<Event>)` to EventBus interface

**File:** `src/main/java/io/lettuce/core/event/EventBus.java`

### 2. Create Reactor-free EventBus implementation

**New file:** `src/main/java/io/lettuce/core/event/CallbackEventBus.java`

```java
public class CallbackEventBus implements EventBus {
    
    private final CopyOnWriteArrayList<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();
    private final Executor executor;
    private final EventRecorder recorder = EventRecorder.getInstance();
    
    public CallbackEventBus(Executor executor) {
        this.executor = executor;
    }
    
    @Override
    public Closeable subscribe(Consumer<Event> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
    
    @Override
    public void publish(Event event) {
        recorder.record(event);
        for (Consumer<Event> listener : listeners) {
            executor.execute(() -> {
                try {
                    listener.accept(event);
                } catch (Exception e) {
                    // Log and continue - isolate listener failures
                }
            });
        }
    }
    
    @Override
    @Deprecated
    public Flux<Event> get() {
        throw new UnsupportedOperationException(
            "Flux-based subscription not available. Use subscribe(Consumer) instead. " +
            "Add reactor-core to classpath for Flux support.");
    }
}
```

### 3. Create Reactor-optimized EventBus implementation

**New file:** `src/main/java/io/lettuce/core/event/ReactorEventBus.java`

```java
public class ReactorEventBus implements EventBus {
    
    private final Sinks.Many<Event> bus;
    private final Scheduler scheduler;
    private final EventRecorder recorder = EventRecorder.getInstance();
    
    public ReactorEventBus(Scheduler scheduler) {
        this.bus = Sinks.many().multicast().directBestEffort();
        this.scheduler = scheduler;
    }
    
    @Override
    public Flux<Event> get() {
        return bus.asFlux().onBackpressureDrop().publishOn(scheduler);
    }
    
    @Override
    public Closeable subscribe(Consumer<Event> listener) {
        Disposable disposable = get().subscribe(listener::accept);
        return disposable::dispose;
    }
    
    @Override
    public void publish(Event event) {
        recorder.record(event);
        Sinks.EmitResult emitResult;
        while ((emitResult = bus.tryEmitNext(event)) == Sinks.EmitResult.FAIL_NON_SERIALIZED) {
            // busy-loop
        }
        if (emitResult != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
            emitResult.orThrow();
        }
    }
}
```

### 4. Create SPI loader for EventBus

**New file:** `src/main/java/io/lettuce/core/event/EventBusFactory.java`

```java
public final class EventBusFactory {
    
    private static final boolean REACTOR_PRESENT = isReactorPresent();
    
    public static EventBus create(Executor executor, Object schedulerOrNull) {
        if (REACTOR_PRESENT && schedulerOrNull != null) {
            return new ReactorEventBus((Scheduler) schedulerOrNull);
        }
        return new CallbackEventBus(executor);
    }
    
    private static boolean isReactorPresent() {
        try {
            Class.forName("reactor.core.publisher.Sinks");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
```

---

## Reactive Adapter (in Reactive Island)

**New file:** `src/main/java/io/lettuce/core/api/reactive/EventBusReactiveAdapter.java`

```java
package io.lettuce.core.api.reactive;

import reactor.core.publisher.Flux;
import io.lettuce.core.event.Event;
import io.lettuce.core.event.EventBus;

/**
 * Adapter to get Flux from callback-based EventBus.
 * Located in reactive island - only loaded when Reactor present.
 */
public final class EventBusReactiveAdapter {

    public static Flux<Event> toFlux(EventBus eventBus) {
        return Flux.create(sink -> {
            Closeable subscription = eventBus.subscribe(sink::next);
            sink.onDispose(() -> {
                try {
                    subscription.close();
                } catch (Exception e) {
                    // ignore
                }
            });
        });
    }
}
```

---

## Files Affected Summary

### Existing Files

| File | Deprecation (7.x) | Breaking (8.0) |
|------|-------------------|----------------|
| `src/main/java/io/lettuce/core/event/EventBus.java` | Add `subscribe()`, deprecate `get()` | Remove `get()` + Flux import |
| `src/main/java/io/lettuce/core/event/DefaultEventBus.java` | Implement `subscribe()` | Rename to `ReactorEventBus` or remove |

### New Files

| File | Purpose |
|------|---------|
| `src/main/java/io/lettuce/core/event/CallbackEventBus.java` | Reactor-free implementation |
| `src/main/java/io/lettuce/core/event/ReactorEventBus.java` | Reactor-optimized implementation |
| `src/main/java/io/lettuce/core/event/EventBusFactory.java` | SPI loader |
| `src/main/java/io/lettuce/core/api/reactive/EventBusReactiveAdapter.java` | Flux adapter |

---

## Performance Considerations

### Current Implementation (Reactor)

- `Sinks.Many.multicast().directBestEffort()` - Lock-free, optimized for concurrent publishers
- Busy-loop `tryEmitNext()` - Handles contention without blocking
- `publishOn(scheduler)` - Offloads to scheduler thread

### Callback Implementation

- `CopyOnWriteArrayList` - Thread-safe but copies on write
- `Executor.execute()` - Task submission overhead
- Per-listener exception isolation

### Benchmark Required

Must benchmark:
1. Event publish throughput (events/second)
2. Latency (time from publish to listener receive)
3. Memory allocation rate

**Acceptance criteria:** Callback implementation must be within 10% of Reactor implementation for typical workloads.

---

## Testing Requirements

### New Tests

1. **Test `subscribe()` receives events**
2. **Test `subscribe()` returns working Closeable**
3. **Test multiple subscribers receive same event**
4. **Test listener exception isolation** - One failing listener doesn't affect others
5. **Test EventBusFactory selects correct implementation**
6. **Benchmark: Reactor vs Callback throughput**

### Existing Tests to Verify

- All event-related integration tests
- Metrics publishing tests
- Connection event tests

---

## Task Breakdown

### 7.x Tasks (Deprecations - ASAP)

| Task ID | Description | Files |
|---------|-------------|-------|
| G5-1 | Add `subscribe(Consumer<Event>)` to `EventBus` interface | 1 |
| G5-2 | Add `@Deprecated` to `get()` in `EventBus` interface | 1 |
| G5-3 | Implement `subscribe()` in `DefaultEventBus` | 1 |
| G5-4 | Create `CallbackEventBus` implementation | 1 (new) |
| G5-5 | Create `EventBusFactory` with Reactor detection | 1 (new) |
| G5-6 | Create `EventBusReactiveAdapter` in reactive island | 1 (new) |
| G5-7 | Update `ClientResources` to use `EventBusFactory` | 1 |
| G5-8 | Add unit tests for new implementations | 1 |
| G5-9 | Create benchmark comparing implementations | 1 |

### 8.0 Tasks (Breaking Changes)

| Task ID | Description | Files |
|---------|-------------|-------|
| G5-10 | Remove `get()` method from `EventBus` interface | 1 |
| G5-11 | Remove Flux import from `EventBus.java` | 1 |
| G5-12 | Rename `DefaultEventBus` to `ReactorEventBus` | 1 |
| G5-13 | Update all code using `eventBus.get()` to use `subscribe()` | Many |
| G5-14 | Update all tests using `eventBus.get()` | Many |

---

## Parallelization Notes

- G5-1, G5-2, G5-3 (interface changes) should be done together
- G5-4, G5-5, G5-6 (new implementations) can be done in parallel
- G5-7 depends on G5-5
- G5-8, G5-9 (tests/benchmarks) can be done after implementations

**Parallel work possible:** One developer on interface + DefaultEventBus, one on new implementations

---

## Migration Guide for Users

### Before (current API):
```java
EventBus eventBus = clientResources.eventBus();
eventBus.get().subscribe(event -> System.out.println(event));
```

### After (7.x - recommended):
```java
EventBus eventBus = clientResources.eventBus();
Closeable subscription = eventBus.subscribe(event -> System.out.println(event));
// Later: subscription.close();
```

### For Flux users (7.x+):
```java
// If you need Flux, use the adapter
Flux<Event> events = EventBusReactiveAdapter.toFlux(eventBus);
events.subscribe(event -> System.out.println(event));
```
```

