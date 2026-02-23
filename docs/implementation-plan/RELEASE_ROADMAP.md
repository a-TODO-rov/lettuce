# Release Roadmap: Reactor Optional Dependency

**Purpose:** Organize implementation by release to ensure deprecations ship ASAP, giving users maximum migration time.

---

## Overview

The refactoring is organized into **three releases**:

| Release | Type | Goal |
|---------|------|------|
| **7.x-ASAP** | Deprecation | Ship new APIs + deprecate old ones |
| **7.x-Later** | Internal | Internal refactoring, performance |
| **8.0** | Breaking | Remove deprecated APIs, Reactor truly optional |

---

## Release 1: 7.x-ASAP (Deprecation Release)

**Goal:** Give users the new APIs so they can start migrating immediately.

**Principle:** Ship the minimum required for deprecation. Internal refactoring can wait.

### G2 - Connection Interfaces

**Scope:** Create extended reactive interfaces so `reactive()` can be deprecated on base interfaces.

**Approach:**
- Create interfaces like `ReactiveStatefulRedisConnection` that extend base interfaces and add `reactive()` method
- Update implementations to implement the reactive interface (which IS-A base interface)
- Deprecate `reactive()` on base interfaces with migration message
- Consider adding `connectReactive()` methods to clients

**Potentially affected areas:**
- `StatefulRedisConnection` and related base interfaces
- Connection implementation classes
- `RedisClient`, `RedisClusterClient`

---

### G5 - EventBus

**Scope:** Add callback-based subscription method, deprecate Flux-based method.

**Approach:**
- Add `Closeable subscribe(Consumer<Event>)` to `EventBus`
- Deprecate `Flux<Event> get()`
- Implementation can delegate to existing Flux internally for now

**Potentially affected areas:**
- `EventBus.java`
- `DefaultEventBus.java`

---

### G6 - Credentials

**Scope:** Add CompletionStage/callback methods, deprecate Mono/Flux methods.

**Approach:**
- Add `CompletionStage<RedisCredentials> resolveCredentialsAsync()`
- Add `Closeable subscribeToCredentials(Consumer<RedisCredentials>)` for streaming
- Deprecate `resolveCredentials()` and `credentials()`
- Implementations can delegate to existing Mono/Flux internally for now

**Potentially affected areas:**
- `RedisCredentialsProvider.java`
- `StaticCredentialsProvider.java`
- `TokenBasedRedisCredentialsProvider.java`

---

### G7 - Tracing

**Scope:** Add CompletionStage method, deprecate Mono method.

**Approach:**
- Add `CompletionStage<TraceContext> getTraceContextAsync()`
- Deprecate `Mono<TraceContext> getTraceContextLater()`
- Implementations can delegate to existing Mono internally for now

**Potentially affected areas:**
- `TraceContextProvider.java`
- `BraveTracing.java`
- `MicrometerTracing.java`

---

### Summary: 7.x-ASAP

| Group | New APIs | Deprecations |
|-------|----------|--------------|
| G2 | Reactive interfaces, `connectReactive()` | `reactive()` on base |
| G5 | `subscribe()` | `get()` |
| G6 | `resolveCredentialsAsync()`, `subscribeToCredentials()` | `resolveCredentials()`, `credentials()` |
| G7 | `getTraceContextAsync()` | `getTraceContextLater()` |

---

## Release 2: 7.x-Later (Internal Refactoring)

**Goal:** Refactor internals to not depend on Reactor. No user-facing API changes.

### G3 - Client Infrastructure

**Scope:** Replace internal Reactor usage with CompletableFuture.

**Approach:**
- Create `Pair.java` utility to replace `Tuple2` usage
- Refactor internal Mono chains to CompletableFuture chains

**Potentially affected areas:**
- `RedisClient.java`
- `RedisClusterClient.java`

---

### G4 - Master-Replica (HOT PATH)

**Scope:** Replace Flux-based connection selection with CF-based approach.

**Approach:**
- Refactor utilities that use Flux/Mono
- Refactor topology providers
- Refactor connection selection (this is the hot path - benchmark before/after)

**Potentially affected areas:**
- `MasterReplicaConnectionProvider.java` (hot path)
- `ResumeAfter.java`, `AsyncConnections.java`, `Connections.java`, `Requests.java`
- `MasterReplicaTopologyRefresh.java` and related
- `AutodiscoveryConnector.java` and related

---

### G5 - EventBus (Full Implementation)

**Scope:** Create Reactor-free EventBus implementation with SPI.

**Approach:**
- Create `CallbackEventBus` (Reactor-free)
- Create `EventBusFactory` for SPI-based selection
- Create adapter for reactive users
- Benchmark performance

**Potentially affected areas:**
- New files to create
- `DefaultClientResources.java`

---

### G6 - Credentials (Full Implementation)

**Scope:** Update internal credential handling to use callbacks.

**Potentially affected areas:**
- `RedisAuthenticationHandler.java`

---

### G8 - Dynamic Resources

**Scope:** Guard Reactor usage with `Class.forName()` checks.

**Potentially affected areas:**
- `DefaultClientResources.java`
- `AddressResolverGroup.java` and related

---

### Testing Infrastructure

**Scope:** Verify library works without Reactor on classpath.

**Approach:**
- Create `no-reactor` Maven profile
- Create integration tests that run without Reactor
- Add tests for new callback/async APIs

---

## Release 3: 8.0 (Breaking Release)

**Goal:** Remove deprecated APIs. Reactor becomes truly optional.

### Scope

- **G2:** Remove `reactive()` from base interfaces, remove `RedisReactiveCommands` imports
- **G5:** Remove `get()` from EventBus, remove Flux import
- **G6:** Remove `resolveCredentials()` and `credentials()`, remove Mono/Flux imports
- **G7:** Remove `getTraceContextLater()`, remove Mono import
- Update all tests and internal callers to use new APIs

### Verification

- Classloader isolation test confirms Reactor not loaded when not used
- CI runs both with-reactor and without-reactor configurations
- Performance benchmarks show no regression

---

## Verification Criteria

### 7.x-ASAP Release

- All new APIs implemented and tested
- All deprecation annotations added with clear migration messages
- Migration guide available
- No breaking changes to existing behavior
- All existing tests pass

### 7.x-Later Release

- Internal refactoring complete
- `no-reactor` Maven profile working
- Integration tests pass without Reactor
- Performance benchmarks show no regression on hot paths

### 8.0 Release

- All deprecated methods removed
- All Reactor imports removed from core interfaces
- CI validates both configurations
- Documentation fully updated

---

## Release Flow

```
7.x-ASAP (Deprecation Release)
|
|   Scope:
|   - G2: Create reactive interfaces, deprecate reactive() on base
|   - G5: Add subscribe(), deprecate get()
|   - G6: Add async/callback methods, deprecate Mono/Flux methods
|   - G7: Add getTraceContextAsync(), deprecate getTraceContextLater()
|
|   --> Users can start migrating
|
v
7.x-Later (Internal Refactoring)
|
|   Scope:
|   - G3: Replace Tuple2 with Pair, internal Mono to CF refactoring
|   - G4: Master-Replica refactoring (hot path - benchmark)
|   - G5: Full EventBus SPI implementation
|   - G6: Internal credential handling refactoring
|   - G8: Class.forName() guards
|   - Testing: no-reactor profile and integration tests
|
|   --> Internal changes only
|
v
8.0 (Breaking Release)
|
|   Scope:
|   - Remove all deprecated methods
|   - Remove Reactor imports from core interfaces
|   - Final verification
|
|   --> Reactor is truly optional
```

---

## Dependencies

**7.x-ASAP:** No blocking dependencies. G2, G5, G6, G7 work streams are independent.

**7.x-Later:**
- G3 (Pair.java) should complete before G4 if Tuple2 replacement is needed there
- G5 full SPI depends on G5 deprecation from 7.x-ASAP
- Testing infrastructure depends on internal refactoring

**8.0:** Depends on all 7.x work and sufficient user migration time.
