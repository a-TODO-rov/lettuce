# G1 - Reactive API (Core Feature)

**Status:** No changes required
**Dependencies:** None
**Blocks:** None

---

## Overview

G1 represents the **Reactive API itself** - the command interfaces and implementations that return `Mono<T>` and `Flux<T>`. This is the feature that legitimately uses Project Reactor, not a "leak" to be refactored.

The Reactive API is the reason Lettuce has a Reactor dependency in the first place. Users who want reactive programming capabilities will continue to use this API with Reactor on their classpath.

---

## Why G1 Is Not Refactored

| Reason | Explanation |
|--------|-------------|
| **It IS the feature** | The reactive API is designed to return Reactor types |
| **No replacement needed** | `Mono` and `Flux` are the correct types for reactive programming |
| **User expectation** | Reactive users expect and want Reactor types |
| **Isolation is the goal** | We isolate G1, not eliminate it |

The goal of this initiative is to make Reactor optional for users who **don't** need the reactive API - not to remove the reactive API itself.

---

## Scope

### Interfaces (Public API)

| File | Location | Reactor Types |
|------|----------|---------------|
| `BaseRedisReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RedisReactiveCommands` | `core/api/reactive/` | Mono |
| `RedisHashReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RedisStringReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RedisSetReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RedisSortedSetReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RedisListReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RedisKeyReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RedisStreamReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RedisGeoReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RedisServerReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RedisAclReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RedisScriptingReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RedisFunctionReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RedisJsonReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RedisVectorSetReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RediSearchReactiveCommands` | `core/api/reactive/` | Flux, Mono |
| `RedisHLLReactiveCommands` | `core/api/reactive/` | Mono |
| `RedisTransactionalReactiveCommands` | `core/api/reactive/` | Mono |
| `RedisPubSubReactiveCommands` | `core/pubsub/api/reactive/` | Flux, FluxSink, Mono |
| `RedisSentinelReactiveCommands` | `core/sentinel/api/reactive/` | Flux, Mono |
| `RedisClusterReactiveCommands` | `core/cluster/api/reactive/` | Flux, Mono |
| `RedisAdvancedClusterReactiveCommands` | `core/cluster/api/reactive/` | Flux, Mono |
| `ReactiveExecutions` | `core/cluster/api/reactive/` | Flux |

### Implementations

| File | Location | Reactor Types |
|------|----------|---------------|
| `AbstractRedisReactiveCommands` | `core/` | Flux, Mono |
| `RedisReactiveCommandsImpl` | `core/` | Mono |
| `RedisPubSubReactiveCommandsImpl` | `core/pubsub/` | Flux, FluxSink, Mono |
| `RedisSentinelReactiveCommandsImpl` | `core/sentinel/` | Flux, Mono |
| `RedisAdvancedClusterReactiveCommandsImpl` | `core/cluster/` | Flux, Mono |
| `RedisClusterPubSubReactiveCommandsImpl` | `core/cluster/` | Flux |
| `ReactiveExecutionsImpl` | `core/cluster/` | Flux, Mono |

---

## Reactor Types Used

| Type | Usage |
|------|-------|
| `Mono<T>` | Single-value async operations (GET, SET, etc.) |
| `Flux<T>` | Multi-value operations (SCAN, KEYS, HGETALL, etc.) |
| `FluxSink` | Pub/Sub message streaming |

---

## Relationship to Other Groups

G1 is **isolated** by the other groups:

| Group | How it isolates G1 |
|-------|-------------------|
| G2 | Moves `reactive()` to separate interface, so base interfaces don't reference G1 |
| G3 | Removes Reactor from client infrastructure, so G1 is only loaded when needed |
| G5 | Provides non-Reactor EventBus, so G1 isn't loaded for event handling |
| G6 | Provides non-Reactor credentials, so G1 isn't loaded for auth |
| G7 | Provides non-Reactor tracing, so G1 isn't loaded for tracing |
| G8 | Guards dynamic type loading, so G1 isn't loaded by reflection |

After refactoring, G1 code is only loaded when:
1. User explicitly calls `connectReactive()` to get a reactive connection
2. User calls `reactive()` on a reactive connection interface
3. User explicitly uses reactive command types

---

## User Impact

| User Type | Impact |
|-----------|--------|
| Reactive users | No change - continue using `Mono`/`Flux` APIs |
| Sync/Async users | No change - G1 is simply not loaded |

---

## Verification

After all groups (G2-G8) are complete:
- G1 code should only load when reactive features are explicitly used
- Users without Reactor on classpath should never encounter G1 classes
- Users with Reactor should see no behavioral changes

---

## References

- `docs/reactor-usage-analysis.md` - Full Reactor usage inventory
- `docs/implementation-plan/G2_CONNECTION_INTERFACES.md` - How G1 is isolated at interface level

