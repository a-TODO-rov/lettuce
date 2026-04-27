# Lettuce — Reactor Optional Dependency: Handoff Document

## Branch & Repo
- **Active branch:** `reactor-optional-reactive-facade` (has uncommitted facade changes on top of `reactor-optional-minimal`)
- **Base branch:** `reactor-optional-minimal` (internal Reactor removal, no facade)
- **Prototype branch:** `reactive-facade-prototype` (facade applied to `main`, for reference)
- **Repo:** `git@github.com:redis/lettuce.git`
- **Build:** `mvn compile -DskipTests -Dmaven.test.skip=true -Dformatter.skip=true -Dimpsort.skip=true` — **passes clean** (use JDK 21, Kotlin compiler has issues with JDK 25)
- **Install:** `mvn install -Dmaven.test.skip=true -Dformatter.skip=true -Dimpsort.skip=true` — **passes**
- **Redis port for testing:** `16379`
- **Lettuce version on this branch:** `reactive-facade` (custom version in pom.xml)

## Objective
Make Project Reactor (`reactor-core`) an **optional** runtime dependency in Lettuce. Users who only use sync/async APIs should not need Reactor on the classpath. Targeting Lettuce **8.0** for the full change (breaking API changes acceptable).

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

## GraalVM Native Image — Findings

### JVM vs GraalVM: The Core Difference

| | JVM | GraalVM Native Image |
|---|---|---|
| **Class loading** | Lazy — classes loaded only when first accessed at runtime | Closed-world — all reachable types resolved at build time |
| **Method parsing** | Method bodies parsed only when the method is **called** | Method bodies parsed when the method is **reachable in the call graph** |
| **Implication** | Reactor types never loaded if sync-only code path is used | Reactor types must be resolvable if any reachable method references them |

### Test Results

| Environment | JVM | GraalVM Native |
|---|---|---|
| **Standalone (no framework)** without reactor-core | ✅ Works | ✅ Works (with our fixes) |
| **Quarkus** (custom BOM, no reactor-core) | ✅ Works | ✅ Works (0.036s startup) |
| **Spring Boot 4 + SDR** without reactor-core | ✅ Works | ❌ `NoClassDefFoundError: Mono` |
| **Spring Boot 4 + SDR** with reactor-core | ✅ Works | ✅ Works (0.062s startup) |

### Three Classes Fixed for GraalVM (commit 341e9af, PoC-grade)

**Fix 1: `RedisAuthenticationHandler`** — Field type `AtomicReference<Disposable>` resolved by GraalVM even though generics are erased on JVM. Fixed by changing to `AtomicReference<Object>` and using reflection for `Flux.subscribe()` calls.

**Fix 2: `ClusterScanSupport`** — `static final` fields with lambdas referencing `Mono` execute during `<clinit>` at GraalVM build time. Fixed with lazy initialization (volatile + null check).

**Fix 3: `DefaultClientResources` + `ReactorEventBus`** — `Schedulers.fromExecutorService()` called inside `if (ReactorProvider.isAvailable())` — but GraalVM can't evaluate runtime guards, parses both branches. Fixed by moving the `Schedulers` reference into `ReactorEventBus.create()` factory method.

### Key GraalVM Lessons

1. **Field types matter.** Even generic parameters like `AtomicReference<Disposable>` are resolved at class load time on GraalVM. On JVM, generics are erased.
2. **`static final` initializers execute at build time.** Any expression referencing an optional type will fail.
3. **Runtime guards (`if` checks) don't help.** GraalVM's static analysis cannot evaluate `if (isAvailable())` — it parses both branches.
4. **Method reachability is the boundary.** If a method is never reachable in the call graph, GraalVM won't parse it (e.g., `getTraceContextLater()` returning `Mono` didn't fail — only called from reactive path).
5. **These fixes are PoC-grade.** Production would need a clean SPI/factory boundary: no Reactor types in field declarations or static initializers of classes on the sync path.

Full details in `test-app-spring/FINDINGS.md` on branch `reactor-optional-minimal-findings`.

## Spring Data Redis (SDR) — Findings

### Slack Discussion with Mark Paluch (SDR Maintainer)

We discussed the reactor-optional effort with Mark. Key takeaways:

1. **SDR + Lettuce always requires Reactor.** Mark: *"When using Jedis, Reactor is not needed. When using Lettuce, Reactor is required."* SDR's optional reactor-core in its pom is for the Jedis path only.

2. **JVM mode works fine.** Our PoC (SDR → Lettuce without reactor-core) works for sync/async in JVM mode. SDR doesn't need code changes.

3. **Native image fails.** GraalVM traces `LettuceConnectionFactory.getReactiveConnection()` → `LettuceReactiveRedisConnection$AsyncConnect.<clinit>` → `Mono` → `NoClassDefFoundError`. This is SDR's own code path, not Lettuce's.

4. **The fix for SDR is one line.** `spring-boot-data-redis/pom.xml` needs to add `reactor-core` as an explicit compile dependency (version managed by Spring BOM). No code changes needed.

5. **Mark's API design concern.** He feels keeping `reactive()` on the interface while throwing at runtime if Reactor is absent is not transparent to users. He proposed a **facade/factory pattern** instead. See next section.

### SDR Dependency Chain
```
spring-boot-starter-data-redis
  → spring-boot-data-redis
      → lettuce-core (compile)            ← today brings reactor-core transitively
      → spring-data-redis (compile)       ← reactor-core is <optional>
```
After our change, `spring-boot-data-redis` must add reactor-core explicitly. This is a Spring Boot concern, not ours.

### Management Summary
Mark provided constructive feedback on how the reactive API should be exposed when Reactor becomes optional. His concern is that keeping `reactive()` on the public interface while throwing at runtime if Reactor is absent isn't transparent enough for users. He proposed alternative approaches that would give users compile-time clarity instead.

We are evaluating his suggestions. They would introduce additional breaking changes, which aligns with our plan to target 8.0 for this effort. Our current work — removing Reactor from Lettuce's internals — is unaffected and can continue as planned.

## Reactive Facade Prototype — IMPLEMENTED (uncommitted)

### The Problem with `connection.reactive()`
Keeping `reactive()` on `StatefulRedisConnection` while reactor-core is optional means:
- `connection.reactive()` **compiles** without reactor-core (javac resolves the type name lazily)
- It only fails at **runtime** with `NoClassDefFoundError` or our custom exception
- Users get no compile-time feedback that they need reactor-core

### The Facade Solution
Remove `reactive()` from the interface. Add a static factory `RedisReactiveCommands.from(connection)`:

```java
// Before (compiles without reactor-core — runtime surprise)
RedisReactiveCommands<String, String> reactive = connection.reactive();

// After (fails to compile without reactor-core — compile-time safety)
RedisReactiveCommands<String, String> reactive = RedisReactiveCommands.from(connection);
```

**Why it works:** `RedisReactiveCommands.from()` forces javac to fully resolve the `RedisReactiveCommands` class, including all its method signatures (`Mono<String> auth(...)`, `Flux<K> keys(...)`, etc.). Without reactor-core, javac fails at the **member enter / type resolution** step: `error: cannot access Mono — class file for reactor.core.publisher.Mono not found`.

### Files Changed (9 files, uncommitted on `reactor-optional-reactive-facade`)
| File | Change |
|------|--------|
| `StatefulRedisConnection` | Removed `reactive()` method and `RedisReactiveCommands` import |
| `RedisReactiveCommands` | Added `static <K,V> from(StatefulRedisConnection<K,V>)` factory |
| `StatefulRedisConnectionImpl` | Removed `@Override`, kept `reactive()` as public method |
| `StatefulRedisMultiDbConnectionImpl` | Removed `@Override` from `reactive()` |
| `RedisAdvancedClusterReactiveCommandsImpl` | `.reactive()` → `RedisReactiveCommands.from()` |
| `NodeSelectionInvocationHandler` | `.reactive()` → `RedisReactiveCommands.from()` |
| `RedisCommandFactory` | `.reactive()` → `RedisReactiveCommands.from()` |
| `MasterSlaveConnectionWrapper` | `.reactive()` → `RedisReactiveCommands.from()` |
| `StatefulRedisConnectionExtensions.kt` | `reactive()` → `RedisReactiveCommands.from(this)` |

### Compile-Time Safety — Verified
Tested with `test-app-facade/` and direct javac invocations:

| Scenario | reactor-core present | Result |
|----------|---------------------|--------|
| `SyncApp` — uses `connection.sync()` only | No | ✅ Compiles |
| `ReactiveApp` — uses `RedisReactiveCommands.from(connection)` | No | ❌ `cannot access Mono` |
| `ReactiveApp` — uses `RedisReactiveCommands.from(connection)` | Yes | ✅ Compiles |
| Old approach — `connection.reactive()` (still on impl) | No | ✅ Compiles (no safety!) |

### What Still Needs Facade Treatment
This prototype only covers `StatefulRedisConnection`. The same pattern should be applied to:
- `StatefulRedisClusterConnection` → `RedisAdvancedClusterReactiveCommands.from()`
- `StatefulRedisPubSubConnection` → `RedisPubSubReactiveCommands.from()`
- `StatefulRedisSentinelConnection` → `RedisSentinelReactiveCommands.from()`

## Test Apps

### `test-app/`
Demonstrates Lettuce working **without Reactor on classpath**:
- `test-app/pom.xml` — depends on `lettuce-core:reactive-facade`, no reactor-core (it's optional)
- `test-app/src/main/java/test/Main.java` — sync + async APIs, plus `RedisReactiveCommands.from()` to prove compile-time failure
- Compile: `cd test-app && mvn compile` (will fail on the reactive line — expected)
- Native image support configured with GraalVM native-maven-plugin

### `test-app-facade/`
Minimal test for facade compile-time safety:
- `SyncApp.java` — sync only, compiles without reactor-core
- `ReactiveApp.java` — uses `RedisReactiveCommands.from()`, fails without reactor-core

### `test-app-spring/`
Spring Boot + SDR test app for native image testing.

## Next Steps / Open Items

### 1. Commit Facade Changes
The facade changes on `reactor-optional-reactive-facade` are uncommitted. Review and commit when ready.

### 2. Extend Facade to Other Connection Types
Apply the same pattern to cluster, pub/sub, and sentinel connections.

### 3. Test Compilation
`mvn test-compile` fails because test files reference `connection.reactive()` and stripped commands. Tests need updating to use `RedisReactiveCommands.from()`.

### 4. Coordinate with Spring Boot
When ready to ship, notify Spring Boot team and provide a PR to `spring-boot-data-redis` adding explicit `reactor-core` dependency.

### 5. Future Reactor Decoupling (if desired)
To remove Reactor from credentials/tracing, the public API would need new non-reactive alternatives:
- `RedisCredentialsProvider`: Add `CompletableFuture<RedisCredentials> resolveCredentialsAsync()`
- `TraceContextProvider`: Add synchronous-only trace context resolution
- These would be **breaking API changes** (suitable for 8.0)

## Working Model: Multi-Branch, Multi-Project Sessions

This effort spans multiple branches and test projects. Each AI agent session typically works on one branch at a time. **Always check which branch you're on before making changes.**

### Branches (reactor-optional effort)

| Branch | Base | Purpose | Status |
|--------|------|---------|--------|
| `reactor-optional-poc` | `main` | Original PoC by kandogu — full reactor removal from infrastructure | Reference only |
| `reactor-optional` | `main` | Cleaned-up version of PoC — internal reactor removal, test app | Reference only |
| `reactor-optional-slimstreams` | `reactor-optional` | Experiment with SlimStreams EventBus alternative | Abandoned |
| `reactor-optional-minimal` | `main` | **Primary work branch** — stripped commands (SET/GET/MGET), internal reactor removed, lazy reactive init, GraalVM fixes | Active |
| `reactor-optional-minimal-test` | `main` | Early test branch — stripped commands, basic reactor removal | Superseded by `reactor-optional-minimal` |
| `reactor-optional-minimal-findings` | `reactor-optional-minimal` | GraalVM native image testing with Spring Boot + Quarkus + standalone | Contains `test-app-spring/` with FINDINGS.md |
| `reactor-optional-reactive-facade` | `reactor-optional-minimal` | **Current active branch** — facade prototype (uncommitted changes) | Active, uncommitted |
| `reactive-facade-prototype` | `main` | Clean facade prototype applied directly to main (for reference) | Reference only |
| `reactor-optional-doc` | — | Task planning and documentation | Reference only |
| `reactor-optional-doc-update` | `reactor-optional-doc` | Updated documentation | Reference only |

### Branch Lineage
```
main
├── reactor-optional-poc (kandogu's original PoC)
├── reactor-optional (cleaned PoC)
│   └── reactor-optional-slimstreams (abandoned)
├── reactor-optional-minimal (primary work branch)
│   ├── reactor-optional-minimal-findings (GraalVM findings + test-app-spring)
│   └── reactor-optional-reactive-facade ← CURRENT (facade changes, uncommitted)
├── reactive-facade-prototype (facade on clean main)
└── reactor-optional-minimal-test (early test, superseded)
```

### Test Projects

| Project | Location | Branch | Purpose |
|---------|----------|--------|---------|
| `test-app/` | In-repo | `reactor-optional-minimal`+ | Standalone app — sync/async with BraveTracing, no reactor-core. Has GraalVM native-maven-plugin config and reflect-config.json. |
| `test-app-spring/` | In-repo | `reactor-optional-minimal-findings` | Spring Boot 4 + SDR app. Proved native image fails without reactor-core (SDR's problem). Contains `FINDINGS.md`. |
| `test-app-facade/` | In-repo | `reactive-facade-prototype` | Minimal compile-time safety test — `SyncApp.java` and `ReactiveApp.java`. |

**Important:** Test projects use **custom lettuce versions** (e.g., `reactive-facade`) that must be installed to local `.m2` first. Check `test-app/pom.xml` for the expected version.

### Session Workflow

1. **Check branch:** `git branch --show-current`
2. **Check uncommitted changes:** `git diff --stat`
3. **Read HANDOFF.md** for context on what's been done
4. **Use JDK 21** for compilation (Kotlin compiler incompatible with JDK 25)
5. **Install lettuce locally** before testing test-apps: `mvn install -Dmaven.test.skip=true ...`
6. **Update HANDOFF.md** at end of session with what was done

## Documentation
- Confluence: [Lettuce - Reactor Optional Dependency](https://redislabs.atlassian.net/wiki/spaces/CAE/pages/5983043629/Lettuce+-+Reactor+Optional+Dependency)
- Groups G3–G7 are documented there with rationale and file lists
- `test-app-spring/FINDINGS.md` on `reactor-optional-minimal-findings` — full GraalVM native image error analysis

## Build Commands Quick Reference
```bash
# Compile (use JDK 21 — Kotlin compiler has issues with JDK 25)
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn compile -DskipTests -Dmaven.test.skip=true -Dformatter.skip=true -Dimpsort.skip=true

# Install to local repo
mvn install -Dmaven.test.skip=true -Dformatter.skip=true -Dimpsort.skip=true

# Compile test-app (expects lettuce version matching pom.xml in local .m2)
cd test-app && mvn compile

# Check remaining Reactor imports
grep -rn 'import reactor\.' src/main/java/ | cut -d: -f1 | sort -u

# Check current branch and uncommitted changes
git branch --show-current && git diff --stat
```

