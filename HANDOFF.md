# Lettuce — Reactor Optional Dependency: Handoff Document

## Branch & Repo
- **Branch:** `reactor-optional-minimal`
- **Repo:** `git@github.com:redis/lettuce.git`
- **Base:** Built on top of `reactor-optional` branch
- **Build:** `mvn compile -DskipTests -Dformatter.skip=true -Dimpsort.skip=true` — **passes clean**
- **Install:** `mvn install -Dmaven.test.skip=true -Dformatter.skip=true -Dimpsort.skip=true` — **passes**
- **Redis port for testing:** `16379`

## Objective
Make Project Reactor (`reactor-core`) an **optional** runtime dependency in Lettuce. Users who only use sync/async APIs should not need Reactor on the classpath. Public reactive interfaces (`Mono`/`Flux` return types) remain unchanged.

## What Was Done

### Command Stripping (PoC scope reduction)
Commands were stripped down to **SET, GET, MGET** across all API surfaces (sync, async, reactive, coroutines, cluster, node-selection). This reduces noise for testing different Reactor-bypass approaches. Full infrastructure (Cluster, Sentinel, Master/Replica, Pub/Sub) is preserved.

### Internal Reactor Removal — COMPLETE
All internal infrastructure code has been decoupled from Reactor:

| Phase | Area | Files Changed | Pattern |
|-------|------|--------------|---------|
| G3 | Client & Connection | `ClientOptions`, `RedisClusterClient`, `AbstractClusterNodeConnectionFactory`, `StatefulRedisClusterConnectionImpl` | `Mono` → `CompletableFuture` |
| G4 | Master-Replica (12 files) | `MasterReplicaConnectionProvider`, `SentinelTopologyProvider`, `StaticMasterReplicaTopologyProvider`, all connectors, `AsyncConnections`, `Connections`, `Requests`, `ResumeAfter`, `ReplicaTopologyProvider`, `MasterReplicaTopologyRefresh` | `Mono`/`Flux`/`Tuple2` → `CompletableFuture`/`Pair` |
| G5 | EventBus | `EventBus` (interface), `DefaultEventBus`, `ReactorEventBus` | Callback-based `subscribe(Consumer<Event>)`, Reactor only via `reactive()` |

### New Utility Classes
- **`io.lettuce.core.internal.Pair<T1, T2>`** — Replaces `reactor.util.function.Tuple2`
- **`io.lettuce.core.resource.ReactorProvider`** — Runtime detection of Reactor availability (like `EpollProvider`)
- **`io.lettuce.core.event.ReactorNotAvailableException`** — Thrown when `eventBus.reactive()` called without Reactor
- **`io.lettuce.core.event.DefaultEventBus`** — Reactor-free EventBus using `ConcurrentHashMap.newKeySet()` listeners

### Key Design Patterns Used
- **`CompletableFuture.thenCompose/thenCombine/handle`** replacing `Mono.flatMap/zip/onErrorResume`
- **`connection.async().role()`** (returns `RedisFuture<List<Object>>`) replacing `connection.reactive().role()` (returns `Flux<Object>`)
- **`connection.async().master()/replicas()`** replacing `connection.reactive().master()/replicas()` in Sentinel topology
- **Manual timeout via `ScheduledExecutorService`** replacing `Mono.timeout()` (Java 8 compatible — no `orTimeout`)
- **`ReactorProvider.isAvailable()`** for conditional Reactor initialization (e.g., `DefaultClientResources.createEventBus()`)

## What Remains (28 files still import Reactor)

All remaining Reactor usage is in **public API** that must stay unchanged:

### Category 1: Public Reactive Command Layer (21 files) — DO NOT CHANGE
- `AbstractRedisReactiveCommands`, `RedisPublisher`, `Operators`
- All `api/reactive/*` interfaces and implementations
- `ReactiveEventBus`, `ReactorEventBus`
- `ReactiveTypeAdapters`, `ReactiveTypes`
- Sentinel/PubSub reactive implementations

### Category 2: Public API with Reactor Types (7 files) — CANNOT CHANGE WITHOUT API BREAK
- **Credentials (3):** `RedisCredentialsProvider` (returns `Mono`/`Flux`), `TokenBasedRedisCredentialsProvider`, `RedisAuthenticationHandler` (consumes `Flux` from provider)
- **Tracing (4):** `Tracing` (static methods use `reactor.util.context.Context`), `TraceContextProvider` (`getTraceContextLater()` returns `Mono`), `BraveTracing`, `MicrometerTracing`

## Test App
Located at `test-app/`. Demonstrates Lettuce working **without Reactor on classpath**:
- `test-app/pom.xml` — excludes `reactor-core` from lettuce dependency
- `test-app/src/main/java/test/Main.java` — uses only sync + async APIs
- Run: `cd test-app && mvn compile exec:java -Dexec.mainClass=test.Main`

## Next Steps / Open Items

### 1. Native Image Build (in progress)
The test-app was about to be configured for GraalVM native image compilation:
- GraalVM 21 and 25 are installed on the dev machine
- `test-app/pom.xml` needs: Java 21 target, `native-maven-plugin`, `mainClass` config
- Will need tracing agent run first to generate `reflect-config.json` for Netty
- The Reactor-free internal path should make native image smaller/simpler

### 2. Test Compilation
`mvn test-compile` fails because Kotlin test files reference stripped commands (`zadd`, `zscan`, `multi`, etc.). Use `-Dmaven.test.skip=true` to skip test compilation entirely.

### 3. Future Reactor Decoupling (if desired)
To remove Reactor from credentials/tracing, the public API would need new non-reactive alternatives:
- `RedisCredentialsProvider`: Add `CompletableFuture<RedisCredentials> resolveCredentialsAsync()` + callback-based `onCredentialsChanged(Consumer)`
- `TraceContextProvider`: Add synchronous-only trace context resolution
- These would be **breaking API changes** (suitable for a major version)

## Documentation
- Confluence: [Lettuce - Reactor Optional Dependency](https://redislabs.atlassian.net/wiki/spaces/CAE/pages/5983043629/Lettuce+-+Reactor+Optional+Dependency)
- Groups G3–G7 are documented there with rationale and file lists

## Build Commands Quick Reference
```bash
# Compile (main sources only)
mvn compile -DskipTests -Dformatter.skip=true -Dimpsort.skip=true

# Install to local repo (skip tests entirely)
mvn install -Dmaven.test.skip=true -Dformatter.skip=true -Dimpsort.skip=true

# Run test-app (needs Redis on port 16379)
cd test-app && mvn compile exec:java -Dexec.mainClass=test.Main

# Check remaining Reactor imports
grep -rn 'import reactor\.' src/main/java/ | cut -d: -f1 | sort -u
```

