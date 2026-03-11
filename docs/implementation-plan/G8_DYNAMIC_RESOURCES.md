# G8 - Dynamic Commands & Resources Implementation Plan

**Status:** Required - Supports reactive API + Scheduler integration
**Dependencies:** G2 (Connection Interfaces), G5 (EventBus)
**Blocks:** None

---

## Problem Statement

This group covers two areas:

### 1. Dynamic Commands (ReactiveTypes)

Type detection utilities that check if types are reactive (Flux/Mono). Used by dynamic command generation.

### 2. Resources (DefaultClientResources)

`DefaultClientResources` creates and provides a Reactor `Scheduler` for reactive operations.

---

## Potentially Affected Areas

### Dynamic Commands
- `ReactiveTypes` - Type detection for `Flux`, `Mono`
- `ReactiveTypeAdapters` - Type conversion utilities

### Resources
- `DefaultClientResources` - Creates Reactor scheduler

---

## Implementation Approach

### Dynamic Commands

These files support the reactive API feature (G1). They should:
- Remain in the codebase (required for reactive API)
- Be loaded only when Reactor is present
- Guard against class loading when Reactor is absent

**Current implementation (already correct):**

`ReactiveTypes` already uses `LettuceClassUtils.isPresent()` for Reactor detection:

```java
// ReactiveTypes.java (EXISTING - no changes needed)
private static final boolean PROJECT_REACTOR_PRESENT =
    LettuceClassUtils.isPresent("reactor.core.publisher.Mono");
```

This is the shared utility that `ReactorProvider` (G2) should also use, avoiding duplication.

**Relationship to ReactorProvider:**

| Class | Purpose |
|-------|---------|
| `LettuceClassUtils.isPresent()` | General utility for checking class availability |
| `ReactiveTypes.PROJECT_REACTOR_PRESENT` | Internal flag for dynamic command generation |
| `ReactorProvider.isAvailable()` | Public API for reactive island guard (G2) |

`ReactorProvider` adds the `checkForReactorLibrary()` guard method that throws a clear error message - something `ReactiveTypes` doesn't need since it silently degrades.

### Resources

`DefaultClientResources` creates a `Scheduler` for the `EventBus`. After G5 refactoring:
- `EventBusFactory` handles Reactor presence detection
- `DefaultClientResources` should not directly reference `Scheduler` type
- Pass `Executor` to factory, let factory decide implementation

**Current:**
```java
Scheduler scheduler = Schedulers.fromExecutorService(eventExecutorGroup);
EventBus eventBus = new DefaultEventBus(scheduler);
```

**After G5:**
```java
EventBus eventBus = EventBusFactory.create(eventExecutorGroup);
```

---

## Breaking vs Non-Breaking Changes

### Non-Breaking (All changes)
- Guard `ReactiveTypes` with `Class.forName()`
- Guard `ReactiveTypeAdapters` with `Class.forName()`
- Update `DefaultClientResources` to use `EventBusFactory`

### Breaking
- **None**

---

## Hotspots & Considerations

### 1. Class Loading in ReactiveTypes

Current code may directly reference `Flux.class` or `Mono.class`. This causes class loading.

**Solution:** Use string-based class names with `Class.forName()`.

### 2. DefaultClientResources Scheduler

Currently provides `Scheduler computationScheduler()`. After refactoring:
- Keep method for backward compatibility
- Return `null` if Reactor not present
- Or deprecate and provide alternative

### 3. ScanStream

`ScanStream` in G2/G3 uses Flux for scan iteration. This is a reactive API feature and should stay in reactive island.

---

## Scope Summary

| Scope | Description |
|-------|-------------|
| Dynamic commands | Guard `ReactiveTypes` and `ReactiveTypeAdapters` with presence checks |
| Resources | Update `DefaultClientResources` to use `EventBusFactory` |
| Scheduler | Handle `computationScheduler()` for non-Reactor users |

---

## Migration Guide

**No migration required** - all changes are internal and non-breaking.

For users accessing `computationScheduler()` without Reactor:
```java
Scheduler scheduler = clientResources.computationScheduler();
if (scheduler == null) {
    // Reactor not present, use alternative
}
```

