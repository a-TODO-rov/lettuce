# Lettuce -- Reactor Optional Plan (Aligned to Repository Reality)

Version Context: Lettuce 7.4.x\
Primary Constraint: Single artifact (`lettuce-core`)\
Dependency Model: `reactor-core` becomes optional\
Non‑Negotiable Rule: Core APIs must be completely reactorless\
Performance Rule: Zero tolerance for measurable regression when Reactor
is present

This document is aligned with repository reality and practical
constraints.

------------------------------------------------------------------------

# 1. Core Architectural Objective

We are NOT trying to remove Reactor.

We are trying to:

1.  Make Reactor optional at runtime.
2.  Ensure core public APIs do not link against reactor.\* types.
3.  Preserve existing reactive API behavior.
4.  Preserve performance when Reactor is present.

That implies:

-   Reactive API stays Reactor-based.
-   Core becomes reactorless.
-   Certain hotspots may use dual implementations (reactor-optimized +
    fallback).
-   Interface signatures are the primary blocker.

------------------------------------------------------------------------

# 2. Hard Blocking Issue -- Connection Interfaces (G2)

## Problem

Core connection interfaces expose:

    reactive()

This returns reactive types, forcing Reactor linkage at class loading.

## Required Plan

### 7.x (Minor Release)

1.  Introduce extension hook on base connection:

        <T> T getExtension(Class<T> type);

2.  Deprecate all reactive() methods on connection interfaces.

3.  Implement deprecated methods to:

    -   Check Reactor presence
    -   Throw friendly IllegalStateException if absent
    -   Delegate to extension mechanism if present

### 8.0 (Major Release)

Remove reactive() from all core interfaces:

-   StatefulRedisConnection
-   StatefulRedisClusterConnection
-   PubSub variants
-   Sentinel variants
-   Any other interface exposing reactive()

Reactive access becomes:

    connection.getExtension(RedisReactiveCommands.class)

This step is mandatory before any other optionality can succeed.

------------------------------------------------------------------------

# 3. Group-by-Group Implementation Strategy

------------------------------------------------------------------------

## G1 -- Reactive API (No Change)

Scope:

-   api/reactive/\*
-   Reactive command implementations
-   RedisPublisher
-   Operators
-   ScanStream
-   ReactiveTypeAdapters

Rules:

-   Remain Reactor-based.
-   Must not be referenced by reactorless core code.

------------------------------------------------------------------------

## G3 -- RedisClient / RedisClusterClient

Current: Mono-based connect and retry chains.

Target: CompletableFuture / CompletionStage orchestration.

Implementation:

Replace:

-   Mono.flatMap → thenCompose
-   Mono.zip → CompletableFuture.allOf
-   Mono.timeout → scheduled future race
-   RetryWhen → custom retry utility

These are control-plane operations and not performance critical.

------------------------------------------------------------------------

## G4 -- Master-Replica Topology

Current: Mono/Flux orchestration.

Target: Explicit async state machine using CompletionStage.

Important:

MasterReplicaConnectionProvider may be performance-sensitive.

Action:

1.  Implement CompletableFuture-based version.
2.  Benchmark selection path.
3.  If regression \> acceptable threshold:
    -   Introduce optional Reactor-optimized provider via SPI.

------------------------------------------------------------------------

## G5 -- EventBus (Critical Hotspot)

EventBus currently uses:

-   Sinks.Many.multicast().directBestEffort()
-   Busy-loop tryEmitNext()

This is optimized for throughput.

We cannot regress performance.

### Required Design

Dual implementation:

1.  Core reactorless EventBus interface: Closeable
    subscribe(Consumer`<Event>`{=html} listener); void publish(Event
    event);

2.  Default reactorless implementation (callback-based).

3.  Reactor-optimized implementation:

    -   Uses Sinks.Many
    -   Loaded via defensive SPI
    -   Activated only if Reactor present

Performance rule:

When Reactor present → optimized path must be used.

------------------------------------------------------------------------

## G6 -- Credentials Provider

Replace:

    Mono resolveCredentials()
    Flux credentials()

With:

    CompletionStage resolveCredentials()
    Closeable subscribe(Consumer<RedisCredentials>)

Reactive adapter provided in reactive island.

Low throughput area --- safe replacement.

------------------------------------------------------------------------

## G7 -- Tracing

Replace:

    Mono<TraceContext>

With:

    CompletionStage<TraceContext>

Move Reactor Context helpers into:

    ReactiveTracingSupport

Reactor Context integration becomes optional feature.

------------------------------------------------------------------------

## G8 -- ClientResources

Remove Reactor Scheduler usage.

Replace with:

-   ScheduledExecutorService
-   Netty event-loop scheduling

Ensure proper shutdown and lifecycle.

------------------------------------------------------------------------

# 4. Dual Implementation Pattern (Where Required)

For performance-sensitive components (e.g., EventBus):

1.  Define reactorless public interface.

2.  Provide default implementation in core.

3.  Provide Reactor-optimized implementation in same jar.

4.  Load via DefensiveProviderLoader:

    -   Read META-INF/services
    -   Class.forName(provider, false, cl)
    -   Catch LinkageError / ClassNotFoundException
    -   Register only if successful

This preserves optional dependency while retaining performance.

------------------------------------------------------------------------

# 5. CI Strategy

Two mandatory CI modes:

## Core-only Mode

-   Exclude reactor-core
-   Run sync + async tests
-   Run classloader smoke test
-   Ensure no linkage errors

## Reactor Mode

-   Include reactor-core
-   Run reactive test suite
-   Benchmark EventBus path
-   Verify optimized provider selected

------------------------------------------------------------------------

# 6. Performance Requirements

Zero tolerance regression in:

-   EventBus throughput
-   MasterReplica connection selection (if benchmark proves sensitive)
-   Reactive streaming

Acceptable minimal regression in:

-   Connect/retry orchestration
-   Credentials
-   Tracing
-   ClientResources scheduling

------------------------------------------------------------------------

# 7. Alternative Strategy (Documented but Not Primary)

Alternative: Split into separate artifacts:

-   lettuce-core
-   lettuce-reactive

Pros: - Clean separation - Simpler enforcement

Cons: - Build complexity - Dependency management change - Larger
ecosystem impact

Primary plan remains single artifact with dual implementations.

------------------------------------------------------------------------

# 8. Execution Order

1.  Fix connection interface leakage (G2).
2.  Implement EventBus dual implementation.
3.  Replace client connect/retry Mono chains.
4.  Replace master-replica pipelines.
5.  Replace credentials and tracing.
6.  Remove Reactor scheduler from ClientResources.
7.  Add CI matrix and benchmarks.
8.  Deprecate in 7.x, remove in 8.0.

------------------------------------------------------------------------

# 9. Definition of Done

✔ Core loads and runs without Reactor\
✔ No public core types reference reactor.\*\
✔ Reactive API works unchanged\
✔ Optimized paths used when Reactor present\
✔ No measurable performance regression\
✔ Friendly error when reactive requested without Reactor

------------------------------------------------------------------------

# 10. Guiding Principle

Reactor becomes:

An optional integration layer.

Core becomes:

Reactor-independent, Netty-safe, Java 8 based, and performance-stable.
