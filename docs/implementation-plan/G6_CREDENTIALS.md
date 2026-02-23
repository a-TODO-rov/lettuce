# G6 - Credentials & Authentication Implementation Plan

**Status:** Required - Public interface with streaming credentials
**Dependencies:** G2 (Connection Interfaces)
**Blocks:** None

---

## Problem Statement

The `RedisCredentialsProvider` interface has Reactor types in its **public API**:

```java
public interface RedisCredentialsProvider {
    Mono<RedisCredentials> resolveCredentials();  // Single credential fetch
    Flux<RedisCredentials> credentials();          // Streaming credentials (for rotation)
}
```

**Key challenge:** `credentials()` returns `Flux` for streaming credential updates over time (e.g., token rotation). This cannot be replaced with `CompletableFuture`.

---

## Potentially Affected Areas

### Core Interface
- `RedisCredentialsProvider` - Public interface with `Flux`, `Mono` return types

### Implementations
- `StaticCredentialsProvider` - Static credentials (no rotation)
- `TokenBasedRedisCredentialsProvider` - Token-based auth with streaming

### Consumers
- `RedisAuthenticationHandler` - Subscribes to credential stream

---

## Implementation Approach

### Replace Single-Value Methods

`resolveCredentials()` returns `Mono<RedisCredentials>` - replace with `CompletionStage<RedisCredentials>`:

```java
// Current
Mono<RedisCredentials> resolveCredentials();

// New
CompletionStage<RedisCredentials> resolveCredentialsAsync();
```

### Handle Streaming Credentials

`credentials()` returns `Flux<RedisCredentials>` for streaming updates. Options:

**Option A: Callback pattern**
```java
Closeable subscribeToCredentials(Consumer<RedisCredentials> listener);
```

**Option B: Keep Flux but in extended interface (like G2)**
```java
// Base interface (no Reactor)
public interface RedisCredentialsProvider {
    CompletionStage<RedisCredentials> resolveCredentialsAsync();
    Closeable subscribeToCredentials(Consumer<RedisCredentials> listener);
}

// Extended interface (has Reactor)
public interface ReactiveRedisCredentialsProvider extends RedisCredentialsProvider {
    Mono<RedisCredentials> resolveCredentials();  // @Deprecated in 7.x
    Flux<RedisCredentials> credentials();         // @Deprecated in 7.x
}
```

**Recommendation:** Option B - Dual interface approach (consistent with G2)

---

## Breaking vs Non-Breaking Changes

### Non-Breaking (7.x)
- Add `resolveCredentialsAsync()` to interface
- Add `subscribeToCredentials(Consumer)` to interface
- Deprecate `resolveCredentials()` and `credentials()`

### Breaking (8.0)
- Remove `resolveCredentials()` method
- Remove `credentials()` method
- Remove Reactor imports

---

## Hotspots & Considerations

### 1. Token Rotation (TokenBasedRedisCredentialsProvider)

Uses `Flux` to stream new tokens as they become available. Must preserve this capability with callback pattern.

### 2. RedisAuthenticationHandler

Subscribes to credential stream using `Flux.subscribe()`. Needs migration to callback pattern.

### 3. Backward Compatibility for Existing Providers

Users who have implemented `RedisCredentialsProvider` need migration path:
- 7.x: Both methods available, new ones preferred
- 8.0: Only new methods remain

---

## Scope Summary

### 7.x (Deprecation)

| Scope | Description |
|-------|-------------|
| Interface update | Add async methods, deprecate Reactor methods |
| Implementations | Update all credential providers |
| Consumers | Update authentication handler to use callbacks |

### 8.0 (Breaking)

| Scope | Description |
|-------|-------------|
| Remove deprecated | Remove Reactor-based methods |
| Remove imports | Remove `Flux`/`Mono` imports |

---

## Migration Guide

**Before (7.x - deprecated):**
```java
provider.resolveCredentials()
    .subscribe(creds -> authenticate(creds));

provider.credentials()
    .subscribe(creds -> reauthenticate(creds));
```

**After (7.x+ - recommended):**
```java
provider.resolveCredentialsAsync()
    .thenAccept(creds -> authenticate(creds));

Closeable subscription = provider.subscribeToCredentials(creds -> reauthenticate(creds));
// Later: subscription.close();
```

