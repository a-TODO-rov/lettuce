# Reactor Optional Dependency - Executive Summary

## Problem Statement

Lettuce currently **requires Project Reactor** on the classpath, even for users who only use synchronous or async (CompletableFuture) APIs. Reactor types (`Mono`, `Flux`) appear in public interface method signatures, causing `NoClassDefFoundError` if Reactor is absent.

**Goal:** Make Reactor truly optional - users who don't need reactive APIs shouldn't need Reactor.

---

## Solution Approach

### Dual Interface Pattern

Split connection interfaces into base (no Reactor) and reactive (has Reactor):

| Layer | Contains | Reactor Imports |
|-------|----------|-----------------|
| Base Interface | `sync()`, `async()` | No |
| Reactive Interface | `reactive()` | Yes |
| Implementation | All methods | Yes (isolated) |

The reactive interface **extends** the base, so implementations remain compatible with existing code.

### SPI Factory Pattern

For components like EventBus, use factory-based runtime selection:
- Detect Reactor presence via `Class.forName()`
- Return appropriate implementation (callback-based or Reactor-based)

### Standard Java Replacements

| Reactor Type | Replacement |
|--------------|-------------|
| `Mono<T>` | `CompletionStage<T>` |
| `Flux<T>` (streaming) | `Closeable subscribe(Consumer<T>)` |
| `Tuple2<A,B>` | `Pair<A,B>` (new utility) |

---

## Key Decisions

1. **Dual Interface over single interface with optional method** - Cleaner API, compile-time safety
2. **SPI Factory for EventBus** - Runtime flexibility, optimal implementation per environment
3. **CompletionStage for single values** - Standard Java, no new dependencies
4. **Callback pattern for streams** - Simple, Reactor-free streaming capability
5. **Deprecate-then-remove strategy** - Non-breaking 7.x, breaking 8.0, migration time for users

---

## Release Strategy

| Release | Type | Content |
|---------|------|---------|
| **7.x ASAP** | Non-breaking | Add new APIs, deprecate old Reactor-based methods |
| **7.x Later** | Non-breaking | Internal refactoring (Mono to CF), no user impact |
| **8.0** | Breaking | Remove deprecated APIs, clean Reactor imports |

---

## Scope Overview

| Group | Area | Change Type | User Impact |
|-------|------|-------------|-------------|
| G2 | Connection Interfaces | Public API | Use `connectReactive()` for reactive |
| G3 | Client Infrastructure | Internal | None |
| G4 | Master-Replica | Internal | None (perf benchmarking required) |
| G5 | EventBus | Public API | Use `subscribe(Consumer)` |
| G6 | Credentials | Public API | Use `resolveCredentialsAsync()` |
| G7 | Tracing | Public API | Use `getTraceContextAsync()` |
| G8 | Dynamic Resources | Internal | None |

---

## User Impact Summary

| User Type | Action Required |
|-----------|-----------------|
| Sync/Async only | None in 7.x. In 8.0: can remove Reactor dependency |
| Reactive users | Update to `connectReactive()`, use reactive interface types |
| Custom CredentialsProvider | Implement new async methods alongside existing |
| Custom EventBus consumers | Migrate from `get().subscribe()` to `subscribe(Consumer)` |
| Custom Tracing | Implement new async method |

---

## Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Performance regression in hot paths (G4) | Medium | High | Benchmark before/after, dual impl option |
| User migration friction | Low | Medium | Long deprecation period, clear guides |
| Custom implementation breakage | Low | Medium | Deprecation warnings provide notice |

---

## Testing Strategy

### Core Principle: No-Reactor by Default

**The test suite runs WITHOUT Reactor on the classpath by default.**

Tests that strictly require Reactor are tagged `@Tag("requires-reactor")` and run separately. This ensures the majority of the codebase (~77% of tests) works correctly without Reactor as a dependency.

```
┌────────────────────────────────────────┐     ┌────────────────────────────────────────┐
│ DEFAULT: No-Reactor Profile            │     │ SEPARATE: Reactor-Only Profile         │
├────────────────────────────────────────┤     ├────────────────────────────────────────┤
│ mvn verify -Pno-reactor                │     │ mvn verify -Preactor-only              │
│                                        │     │                                        │
│ - Reactor excluded from classpath      │     │ - Reactor on classpath                 │
│ - Runs ALL tests EXCEPT tagged ones    │     │ - Runs ONLY @Tag("requires-reactor")   │
│ - Verifies isolation works             │     │ - Verifies reactive features work      │
│ - ~77% of test files                   │     │ - ~23% of test files (~95 files)       │
└────────────────────────────────────────┘     └────────────────────────────────────────┘
                    │                                           │
                    └─────────────┬─────────────────────────────┘
                                  ▼
                         BOTH MUST PASS
```

### Why No-Reactor First

Running without Reactor by default catches "leaked" dependencies - if any sync/async test fails because it triggers Reactor class loading, that's a bug to fix in the production code.

### Testing Goals

| Goal | Verification |
|------|--------------|
| **Isolation** | Reactor classes do NOT load when using sync/async APIs only |
| **No regression** | Existing behavior unchanged (with Reactor present) |
| **Equivalence** | New non-Reactor implementations behave identically to Reactor ones |

### Contract Tests (Optional)

For components with dual implementations (Reactor and non-Reactor), contract tests ensure behavioral equivalence. An abstract base class defines expected behavior; both implementations must pass the same tests.

See [TESTING_STRATEGY.md](TESTING_STRATEGY.md) for full details.

---

## References

- `docs/implementation-plan/RELEASE_ROADMAP.md` - Detailed release plan
- `docs/implementation-plan/G2_CONNECTION_INTERFACES.md` - Connection interface details
- `docs/implementation-plan/TESTING_STRATEGY.md` - Testing approach
- `docs/reactor-usage-analysis.md` - Full Reactor usage inventory

