# Testing Strategy for Reactor Optional Dependency

**Status:** Critical - Required for all groups
**Dependencies:** All implementation groups (G2-G8)

---

## Problem Statement

The Reactor optional dependency refactoring requires verifying **two distinct scenarios**:

1. **Without Reactor on classpath** - No `NoClassDefFoundError`, sync/async APIs work
2. **With Reactor on classpath** - Reactive APIs work correctly, no regressions

Currently, all tests run with Reactor on the classpath. There is no existing pattern for running tests without an optional dependency.

---

## Current Test Infrastructure

| Component | Details |
|-----------|---------|
| **Unit Tests** | `*UnitTests`, `*Tests` (excludes `*IntegrationTests`) - surefire plugin |
| **Integration Tests** | `*IntegrationTests`, `*Test` - failsafe plugin, require Redis |
| **Test Tags** | `UNIT_TEST`, `INTEGRATION_TEST`, `SCENARIO_TEST`, `ENTRA_ID`, `API_GENERATOR` |
| **Test Extension** | `LettuceExtension` - JUnit 5 extension for resource injection |
| **Reactive Testing** | `reactor-test` with `StepVerifier` for reactive verification |
| **CI Matrix** | Runs against multiple Redis versions |

---

## Potentially Affected Test Areas

### EventBus Tests
- Tests using `eventBus.get()` returning `Flux`
- Tests using `StepVerifier` for event verification

### Credentials Tests
- Tests using `resolveCredentials()` returning `Mono`
- Tests for streaming credentials with `Flux`
- Test helpers using `Sinks`

### Tests NOT Requiring Changes
- Connection tests (interfaces change, behavior unchanged)
- Internal utility tests (`Pair.java` is internal)
- Master-Replica tests (internal `Mono` to CF change)
- Reactive command tests (testing reactive feature - should use Reactor)

---

## Testing Approach

### 7.x (Deprecation Period)

#### Keep Existing Tests Running

- No immediate test changes required
- Deprecated methods (`EventBus.get()`, etc.) still work
- `reactor-test` stays in test scope
- All `StepVerifier`-based tests continue to pass

#### Add Parallel Tests for New APIs

For each breaking change (G5, G6), add **new tests** for callback/CompletionStage APIs alongside existing tests:

```java
// EXISTING (keep during 7.x, mark deprecated)
@Test
@Deprecated
void publishToSubscriberFlux() {
    StepVerifier.create(sut.get())
        .then(() -> sut.publish(event))
        .expectNext(event)
        .thenCancel()
        .verify();
}

// NEW (add for 7.x)
@Test
void publishToSubscriberCallback() {
    BlockingQueue<Event> received = new ArrayBlockingQueue<>(1);
    try (Closeable sub = sut.subscribe(received::add)) {
        sut.publish(event);
        assertThat(received.poll(1, TimeUnit.SECONDS)).isEqualTo(event);
    }
}
```

#### Create No-Reactor Maven Profile

Add a new Maven profile to run tests **without Reactor on classpath**:

```xml
<profile>
    <id>no-reactor</id>
    <build>
        <plugins>
            <plugin>
                <artifactId>maven-failsafe-plugin</artifactId>
                <configuration>
                    <classpathDependencyExcludes>
                        <classpathDependencyExclude>io.projectreactor:reactor-core</classpathDependencyExclude>
                        <classpathDependencyExclude>io.projectreactor:reactor-test</classpathDependencyExclude>
                    </classpathDependencyExcludes>
                    <includes>
                        <include>**/NoReactor*IntegrationTests.java</include>
                    </includes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</profile>
```

#### Create No-Reactor Integration Tests

Create dedicated tests that verify Lettuce works without Reactor:

```java
@Tag(INTEGRATION_TEST)
class NoReactorSyncAsyncIntegrationTests {

    @Test
    void shouldConnectWithSyncApi() {
        RedisClient client = RedisClient.create("redis://localhost:6479");
        StatefulRedisConnection<String, String> conn = client.connect();

        // Use ONLY sync API - no reactive()
        assertThat(conn.sync().ping()).isEqualTo("PONG");
        conn.close();
        client.shutdown();
    }

    @Test
    void shouldConnectWithAsyncApi() throws Exception {
        RedisClient client = RedisClient.create("redis://localhost:6479");
        StatefulRedisConnection<String, String> conn = client.connect();

        // Use async API
        String result = conn.async().ping().get(1, TimeUnit.SECONDS);
        assertThat(result).isEqualTo("PONG");
        conn.close();
        client.shutdown();
    }

    @Test
    void shouldSubscribeToEventsWithCallback() throws Exception {
        RedisClient client = RedisClient.create("redis://localhost:6479");
        BlockingQueue<Event> events = new ArrayBlockingQueue<>(10);

        try (Closeable sub = client.getResources().eventBus().subscribe(events::add)) {
            StatefulRedisConnection<String, String> conn = client.connect();
            assertThat(events.poll(5, TimeUnit.SECONDS)).isNotNull();
            conn.close();
        }
        client.shutdown();
    }
}
```

---

### 8.0 (Breaking Release)

#### Remove Deprecated API Tests

- Delete tests using `EventBus.get()` (returns Flux)
- Delete tests using `resolveCredentials()` (returns Mono)
- Keep `StepVerifier` tests **only** for reactive command APIs

#### Update CI Matrix

Expand CI to run both with and without Reactor:

```yaml
# .github/workflows/integration.yml
strategy:
  matrix:
    redis_version: ["8.6", "8.4", "8.2", "8.0", "7.4", "7.2"]
    reactor_mode: ["with-reactor", "without-reactor"]

steps:
  - name: Run tests
    run: |
      if [ "${{ matrix.reactor_mode }}" == "without-reactor" ]; then
        make test PROFILE=no-reactor
      else
        make test
      fi
```

---

## Test Execution Summary

### Profiles

| Profile | Reactor Present | Tests Run |
|---------|-----------------|-----------|
| `default` | Yes | All tests |
| `no-reactor` | No | Only `NoReactor*` tests |
| `doctests` | Yes | Example tests only |

### Commands

```bash
# Run all tests (default - with Reactor)
make test

# Run no-reactor tests (verify optional dependency works)
mvn -Pno-reactor -DskipITs=false verify

# Run coverage
make test-coverage
```

---

## New Test Areas

| Area | Purpose |
|------|---------|
| No-Reactor sync/async tests | Verify basic sync/async work without Reactor |
| No-Reactor EventBus tests | Verify callback EventBus without Reactor |
| No-Reactor credentials tests | Verify CF credentials without Reactor |
| No-Reactor cluster tests | Verify cluster works without Reactor |
| No-Reactor master-replica tests | Verify master-replica without Reactor |

---

## Hotspots & Considerations

### 1. StepVerifier Usage

`StepVerifier` is from `reactor-test` - it cannot be used in no-reactor tests. Use blocking queues and `CountDownLatch` instead.

### 2. Test Helper Classes

Test helpers using `Sinks.Many` need parallel callback-based helpers.

### 3. Integration Test Resource Injection

`LettuceExtension` provides connection injection. Verify it works when reactive interfaces are not loaded.

### 4. Example Files

Example files in `src/test/java/io/redis/examples/reactive/` should continue to use Reactor - they demonstrate the reactive API.

---

## Verification Criteria

### Before 7.x Release

- All existing tests pass (with Reactor on classpath)
- New callback/CF tests added and passing
- `mvn -Pno-reactor verify` runs without `NoClassDefFoundError`
- No-reactor tests verify sync/async APIs work
- No-reactor tests verify EventBus callback works
- No-reactor tests verify credentials CF works

### Before 8.0 Release

- Deprecated method tests removed
- All `StepVerifier` usage is ONLY for reactive API commands
- CI runs both `with-reactor` and `without-reactor` profiles
- Performance benchmarks for hot paths (G4 connection selection)
- Migration guide updated with test examples

---

## Scope Summary

### 7.x Scope

| Scope | Description |
|-------|-------------|
| Maven profile | Create `no-reactor` profile |
| No-reactor tests | Integration tests verifying core functionality without Reactor |
| Callback tests | Tests for new callback/CF APIs |
| Test helpers | Callback-based test helpers for streaming scenarios |
| Infrastructure | Verify `LettuceExtension` works without reactive interfaces |

### 8.0 Scope

| Scope | Description |
|-------|-------------|
| Cleanup | Remove deprecated API tests |
| CI | Update workflow for reactor matrix |
| Helpers | Clean up test helpers using Reactor for non-reactive features |

---

## Migration Examples

### EventBus Test Migration

**Before (using Flux):**
```java
@Test
void publishToSubscriber() {
    EventBus sut = new DefaultEventBus(Schedulers.immediate());
    StepVerifier.create(sut.get())
        .then(() -> sut.publish(event))
        .expectNext(event)
        .thenCancel()
        .verify();
}
```

**After (using callback):**
```java
@Test
void publishToSubscriber() throws Exception {
    EventBus sut = new CallbackEventBus();
    BlockingQueue<Event> received = new ArrayBlockingQueue<>(1);

    try (Closeable sub = sut.subscribe(received::add)) {
        sut.publish(event);
        assertThat(received.poll(1, TimeUnit.SECONDS)).isEqualTo(event);
    }
}
```

### Credentials Test Migration

**Before (using Mono):**
```java
@Test
void shouldResolveCredentials() {
    StepVerifier.create(provider.resolveCredentials())
        .expectNextMatches(creds -> "user".equals(creds.getUsername()))
        .verifyComplete();
}
```

**After (using CompletionStage):**
```java
@Test
void shouldResolveCredentials() throws Exception {
    CompletionStage<RedisCredentials> stage = provider.resolveCredentialsAsync();
    RedisCredentials creds = stage.toCompletableFuture().get(1, TimeUnit.SECONDS);
    assertThat(creds.getUsername()).isEqualTo("user");
}
```
