# Reactor Optional Dependency - Executive Summary

## Problem Statement

Lettuce currently **requires Project Reactor** on the classpath, even for users who only use synchronous or async (CompletableFuture) APIs. Reactor types (`Mono`, `Flux`) appear in public interface method signatures, causing `NoClassDefFoundError` if Reactor is absent.

**Goal:** Make Reactor truly optional - users who don't need reactive APIs shouldn't need Reactor.

---

## Solution Approach

### Lazy Loading Pattern

Keep `reactive()` in the base interface but use **lazy initialization** combined with **guard checks** to prevent Reactor class loading until actually needed:

| Component | Strategy | Why It Works |
|-----------|----------|--------------|
| Connection Interfaces | `reactive()` in base interface + lazy field init | JVM only loads return type when method actually returns a value |
| EventBus | Composition pattern with null check | Assigning `null` doesn't trigger class loading |
| ReactorProvider | Guard check before reactive code | Throws before Reactor types are resolved |

This approach exploits JVM classloading behavior: method signatures are stored as string constants and only resolved when the method successfully returns.

### JVM Classloading Behavior (Key Insight)

| Operation | Triggers Class Loading? |
|-----------|------------------------|
| `field = null` | **NO** |
| `field = new SomeClass()` | **YES** |
| `field = someMethod()` | **YES** (return type resolved) |
| Method signature in interface | **NO** (just metadata) |
| Method actually returns a value | **YES** (return type verified) |

### Standard Java Replacements

| Reactor Type | Replacement |
|--------------|-------------|
| `Mono<T>` | `CompletionStage<T>` |
| `Flux<T>` (streaming) | `Closeable subscribe(Consumer<T>)` |

---

## Key Decisions

1. **Lazy Loading over Dual Interface** - Simpler API, no interface proliferation, leverages JVM behavior
2. **Composition for EventBus** - `DefaultEventBus` wraps optional `ReactorEventBus`
3. **CompletionStage for single values** - Standard Java, no new dependencies
4. **Callback pattern for streams** - Simple, Reactor-free streaming capability
5. **Non-breaking approach** - Existing code continues to work unchanged

### GraalVM Native Image Caveat

The lazy loading strategy works on **HotSpot JVM** but has limitations on **GraalVM Native Image**:
- Native Image performs static analysis at BUILD time
- All reachable code paths are analyzed, regardless of runtime execution
- Reactor classes must be available at build time if referenced in code
- Solution: Use conditional reachability metadata (`typeReached` conditions)

This is a consideration for frameworks like Quarkus that use GraalVM Native Image.

---

## Release Strategy

| Release | Type | Content |
|---------|------|---------|
| **7.x** | Non-breaking | Lazy loading + guards, backward compatible |
| **Future** | Optional | Consider separate `lettuce-reactive` module |

---

## Scope Overview

| Group | Area | Change Type | User Impact |
|-------|------|-------------|-------------|
| G2 | Connection Interfaces | Internal | None - `reactive()` still available |
| G3 | Client Infrastructure | Internal | None |
| G4 | Master-Replica | Internal | None |
| G5 | EventBus | Public API | Use `subscribe(Consumer)` for Reactor-free |
| G6 | Credentials | Public API | Use `resolveCredentialsAsync()` |
| G7 | Tracing | Public API | Use `getTraceContextAsync()` |
| G8 | Dynamic Resources | Internal | None |

---

## User Impact Summary

| User Type | Action Required |
|-----------|-----------------|
| Sync/Async only (standalone) | None - can now exclude Reactor from classpath |
| Sync/Async only (cluster) | Reactor still required (internal Mono usage) |
| Reactive users | None - API unchanged |
| Custom EventBus consumers | Use `subscribe(Consumer)` or `reactive().get()` |

---

## Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| GraalVM Native Image incompatibility | High | Medium | Document reachability metadata requirements |
| Cluster client still requires Reactor | Known | Low | Document limitation, future work |
| Lazy init thread safety | Low | High | Double-checked locking with volatile |

---

## References

- `docs/implementation-plan/RELEASE_ROADMAP.md` - Detailed release plan
- `docs/implementation-plan/G2_CONNECTION_INTERFACES.md` - Connection interface details
- `docs/implementation-plan/TESTING_STRATEGY.md` - Testing approach
- `docs/reactor-usage-analysis.md` - Full Reactor usage inventory

