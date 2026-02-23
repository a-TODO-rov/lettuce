# Lettuce -- Reactor Optional Refactoring Plan (Exhaustive Technical Version)

Version Context: Lettuce 7.4.x Constraint: Single artifact
(`lettuce-core`), `reactor-core` optional dependency Architectural Rule:
Reactor usage must exist ONLY inside the Reactive API island.

------------------------------------------------------------------------

# 1. Problem Statement

Lettuce currently uses Reactor across multiple architectural layers: -
Reactive command API (expected) - Client connect/retry orchestration -
Master-replica topology management - EventBus implementation -
Credentials streaming - Tracing context integration - ClientResources
scheduler

Because core public types reference Reactor types (Mono, Flux, Context),
`reactor-core` becomes effectively mandatory at runtime.

Goal: Make Reactor optional without: - Introducing a new artifact -
Breaking sync/async functionality - Degrading performance in critical
paths - Violating Netty non-blocking constraints

------------------------------------------------------------------------

# 2. Architectural Boundary Definition

## Reactor-Allowed Zone (Reactive Island)

Allowed to import reactor.\*:

-   io.lettuce.core.api.reactive.\*
-   Reactive command implementations
-   RedisPublisher
-   Operators
-   ScanStream
-   ReactiveTypeAdapters
-   Reactor adapters for EventBus, Credentials, Tracing

These must NOT be referenced by non-reactive core code paths.

## Reactor-Free Core Zone

Must not import reactor.\*:

-   io.lettuce.core.api.sync.\*
-   io.lettuce.core.api.async.\*
-   io.lettuce.core.RedisClient
-   io.lettuce.core.cluster.RedisClusterClient
-   io.lettuce.core.masterreplica.\*
-   io.lettuce.core.event.\*
-   io.lettuce.core.tracing.\*
-   io.lettuce.core.resource.\*
-   Stateful connection interfaces and implementations

------------------------------------------------------------------------

# 3. Group-by-Group Deep Technical Plan

------------------------------------------------------------------------

## G1 -- Reactive API (Unchanged Semantics)

Scope: - api/reactive/\* - AbstractRedisReactiveCommands -
RedisReactiveCommandsImpl - RedisPublisher - Operators - ScanStream -
ReactiveTypeAdapters

Rules: - No references to these types from core public interfaces. -
Must be instantiated lazily.

Implementation Controls: - Add architecture test ensuring no core
package imports reactor.\*. - Add classloader smoke test to confirm
reactive classes are not loaded during core-only usage.

------------------------------------------------------------------------

## G2 -- Connection Entry Points (Critical Refactor)

Problem: Core interfaces expose reactive() returning reactive types.

### Phase 1 (Minor Release)

Add extension hook to base connection type:

    <T> T getExtension(Class<T> type);

Implementation: - Maintain internal extension registry. - If type ==
RedisReactiveCommands.class AND Reactor present: return reactive
instance. - Else return null.

Deprecate reactive() on connection interfaces.

Deprecated reactive() implementation: - Check Reactor presence via
Class.forName. - If absent → throw IllegalStateException with clear
message. - If present → delegate to getExtension().

### Phase 2 (Major Release)

Remove reactive() from core interfaces.

------------------------------------------------------------------------

## G3 -- RedisClient / RedisClusterClient

Replace Mono-based orchestration.

### Replace Patterns

Mono.flatMap → thenCompose Mono.onErrorResume → handle + conditional
compose Mono.zip → CompletableFuture.allOf Mono.timeout → scheduled race
future RetryWhen → custom retry utility

### Utilities To Introduce

Internal helper class Futures:

-   static `<T>`{=html} CompletionStage`<T>`{=html} withTimeout(...)
-   static `<T>`{=html} CompletionStage`<T>`{=html} retryAsync(...)
-   static `<T>`{=html} CompletionStage`<T>`{=html} compose(...)

Ensure: - No blocking calls. - No joining inside event-loop. - Preserve
exception wrapping semantics.

------------------------------------------------------------------------

## G4 -- Master-Replica

Replace Reactor orchestration with explicit async state machine.

Approach:

-   refreshTopology(): CompletionStage`<Topology>`{=html}
-   schedulePeriodicRefresh(): ScheduledExecutorService or Netty
    scheduler
-   Replace Flux pipelines with explicit sequential or parallel CF
    chains.

Concurrency Controls: - AtomicReference for topology - Synchronization
only where necessary - Preserve ordering of updates

------------------------------------------------------------------------

## G5 -- EventBus

### New Core API

    Closeable subscribe(Consumer<Event> listener);
    void publish(Event event);

### Delivery Strategy

Hybrid:

if on Netty event-loop thread: offload to single-thread dispatcher
executor else: deliver inline

### Dispatcher Design

-   Single-thread executor
-   Bounded queue (configurable)
-   Drop policy: DROP_LATEST or DROP_OLDEST
-   Exception isolation per listener

### Reactive Adapter

Located in reactive island:

    Flux<Event> toFlux(EventBus bus)

Uses Flux.create with cancellation hook.

------------------------------------------------------------------------

## G6 -- Credentials Provider

### New Core API

    CompletionStage<RedisCredentials> resolveCredentials();
    Closeable subscribe(Consumer<RedisCredentials> listener);

### Auth Handler Changes

-   Use resolveCredentials() for initial authentication.
-   Subscribe only if streaming supported.

Reactive adapter in reactive island: Flux`<RedisCredentials>`{=html}
adapt(provider)

------------------------------------------------------------------------

## G7 -- Tracing

### Core API

    TraceContext getTraceContext();
    CompletionStage<TraceContext> getTraceContextAsync();

Remove Mono-based method.

Move Reactor Context helpers into:

    ReactiveTracingSupport

This class lives in reactive island.

------------------------------------------------------------------------

## G8 -- ClientResources

Remove: - Reactor Scheduler usage

Replace with: - ScheduledExecutorService - Netty event loop scheduling

Ensure lifecycle management: - Proper shutdown - Daemon threads - No
thread leaks

------------------------------------------------------------------------

# 4. SPI / Defensive Loader

Implement DefensiveProviderLoader:

-   Read META-INF/services entries.
-   Attempt Class.forName(provider, false, cl).
-   Catch LinkageError, ClassNotFoundException.
-   Only register providers that successfully link.

Used for: - Reactive extension providers - Optional optimized
implementations

------------------------------------------------------------------------

# 5. Performance Risk Matrix

Safe To Replace: - Connect/retry orchestration - Master-replica
orchestration - Close lifecycle - Credentials - Tracing -
ClientResources scheduler

Potentially Sensitive: - EventBus fan-out under high load - Pub/Sub
bridge (if present outside reactive island)

If fallback implementation shows \>10% regression: - Introduce dual
implementation (reactor-optimized provider) - Load only when Reactor
present

------------------------------------------------------------------------

# 6. CI & Testing Strategy

## CI Matrix

1.  Core-only build
    -   Exclude reactor-core from test runtime.
    -   Run sync/async integration tests.
    -   Run classloader smoke test.
2.  Reactive build
    -   Include reactor-core.
    -   Run full reactive test suite.

## Classloader Smoke Test

-   Create URLClassLoader without reactor-core.
-   Load key core types.
-   Instantiate RedisClient.
-   Perform async command.

Ensure no linkage errors.

------------------------------------------------------------------------

# 7. Deprecation & Rollout Plan

Minor Release: - Add new reactorless APIs. - Deprecate Reactor-based
core methods. - Document migration.

Major Release: - Remove deprecated methods. - Enforce reactorless core
surface.

------------------------------------------------------------------------

# 8. Definition of Done

✔ Core compiles and runs without Reactor. ✔ No public core types
reference reactor.\*. ✔ Reactive API works when Reactor present. ✔
Friendly failure when reactive requested without Reactor. ✔ Performance
regressions \< agreed thresholds. ✔ CI matrix green.

------------------------------------------------------------------------

# 9. Long-Term Architectural Principle

Reactor becomes an integration feature, not a structural dependency of
the driver.

Core remains: - Java 8 compatible - Netty-safe - Deterministic -
Optional-reactive capable
