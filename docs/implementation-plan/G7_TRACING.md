# G7 - Tracing Implementation Plan

**Status:** Required - Public interface uses Mono  
**Dependencies:** G2 (Connection Interfaces)  
**Blocks:** None

---

## Problem Statement

The `Tracing` and `TraceContextProvider` interfaces use Reactor types:

```java
public interface TraceContextProvider {
    Mono<TraceContext> getTraceContextLater();
}

public interface Tracing {
    TraceContextProvider getTraceContextProvider();
    // ...
}
```

Additionally, the implementations (`BraveTracing`, `MicrometerTracing`) use `Mono` internally.

---

## Affected Files (4 files)

| File | Reactor Types | Notes |
|------|---------------|-------|
| `src/main/java/io/lettuce/core/tracing/Tracing.java` | `Mono`, `Context` | **Public interface** |
| `src/main/java/io/lettuce/core/tracing/TraceContextProvider.java` | `Mono` | **Public interface** |
| `src/main/java/io/lettuce/core/tracing/BraveTracing.java` | `Mono` | Brave implementation |
| `src/main/java/io/lettuce/core/tracing/MicrometerTracing.java` | `Mono` | Micrometer implementation |

---

## Implementation Strategy

### Replace Mono with CompletionStage

`Mono<TraceContext>` represents a single async value - replace with `CompletionStage<TraceContext>`:

```java
// Current
public interface TraceContextProvider {
    Mono<TraceContext> getTraceContextLater();
}

// New (7.x)
public interface TraceContextProvider {
    @Deprecated
    Mono<TraceContext> getTraceContextLater();
    
    CompletionStage<TraceContext> getTraceContextAsync();  // New
}

// New (8.0)
public interface TraceContextProvider {
    CompletionStage<TraceContext> getTraceContextAsync();
}
```

### Handle Reactor Context

`Tracing.java` uses `reactor.util.context.Context` for propagating trace context. This is specifically for reactive pipelines and needs special handling:

**Options:**
1. Keep Context support only for reactive users (via extended interface)
2. Replace with a generic Map-based context

**Recommendation:** Option 1 - Dual interface pattern for Reactor Context support.

---

## Breaking vs Non-Breaking Changes

### Non-Breaking (7.x)
- Add `getTraceContextAsync()` to `TraceContextProvider` ✅
- Deprecate `getTraceContextLater()` ✅
- Update implementations to support both ✅

### Breaking (8.0)
- Remove `getTraceContextLater()` method ⚠️
- Remove Reactor imports from base interfaces ⚠️

---

## Hotspots & Considerations

### 1. Reactor Context Usage

`Tracing.java` may use `reactor.util.context.Context` for context propagation in reactive pipelines. This is valid only for reactive users.

**Solution:** Keep Context support in reactive island or extended interface.

### 2. Third-Party Tracing Libraries

Users may have custom `Tracing` implementations. Deprecation period gives migration time.

### 3. Internal Callers

Find all internal code that calls `getTraceContextLater()` and update to `getTraceContextAsync()`.

---

## Task Summary

### 7.x Tasks (Deprecation)

| Task | Description |
|------|-------------|
| G7-1 | Add `getTraceContextAsync()` to `TraceContextProvider` |
| G7-2 | Deprecate `getTraceContextLater()` |
| G7-3 | Update `BraveTracing` to implement new method |
| G7-4 | Update `MicrometerTracing` to implement new method |
| G7-5 | Update internal callers |

### 8.0 Tasks (Breaking)

| Task | Description |
|------|-------------|
| G7-6 | Remove deprecated methods |
| G7-7 | Remove Reactor imports |

---

## Migration Guide

**Before (7.x - deprecated):**
```java
traceContextProvider.getTraceContextLater()
    .subscribe(ctx -> useContext(ctx));
```

**After (7.x+ - recommended):**
```java
traceContextProvider.getTraceContextAsync()
    .thenAccept(ctx -> useContext(ctx));
```

