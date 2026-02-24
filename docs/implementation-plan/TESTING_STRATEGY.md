# Testing Strategy for Reactor Optional Dependency

**Status:** Critical - Required for all groups
**Dependencies:** All implementation groups (G2-G8)

---

## Problem Statement

The Reactor optional dependency refactoring requires verifying **three things**:

1. **Isolation** - Reactor classes do NOT load when using sync/async APIs only
2. **No regression** - Existing behavior unchanged (with Reactor present)
3. **Equivalence** - New non-Reactor implementations behave identically to Reactor ones

---

## Core Approach: Reactor-Free by Default

**The test suite should pass WITHOUT Reactor on the classpath by default.**

Tests that strictly require Reactor are tagged `@Tag("requires-reactor")` and excluded from the default run. A separate profile runs only those tagged tests with Reactor present.

| Profile | Classpath | Tests Run | Purpose |
|---------|-----------|-----------|---------|
| **no-reactor** (default) | Reactor excluded | All EXCEPT `@Tag("requires-reactor")` | Verify isolation |
| **reactor-only** | Reactor present | Only `@Tag("requires-reactor")` | Verify reactive features |

This approach catches "leaked" Reactor dependencies - if an existing sync/async test fails because it triggers Reactor class loading, that's a bug to fix.

---

## Research Findings: Tests Requiring Reactor

Analysis of the test suite identified ~95 files (~23% of ~413 test files) that depend on Reactor:

| Category | Files | Reason |
|----------|-------|--------|
| **Reactive command tests** (`/reactive/` folders) | ~35 | Test reactive API |
| **Reactive infrastructure** (`*Reactive*` in name) | ~15 | Test reactive internals |
| **Uses StepVerifier** | ~40 | Requires `reactor-test` |
| **Uses `eventBus.get()`** | ~4 | Returns `Flux<Event>` |
| **Uses `resolveCredentials()`** | ~8 | Returns `Mono<Credentials>` |
| **Uses `.reactive()` incidentally** | ~20 | Calls reactive API in mixed tests |
| **Reactive examples** | ~6 | Documentation examples |

Note: Categories overlap. The ~95 unique files require review and tagging.

---

## Testing Dimensions

| Dimension | What it verifies | How to run |
|-----------|------------------|------------|
| **WITHOUT Reactor** | Sync/async works, no `NoClassDefFoundError` | `mvn verify -Pno-reactor` (default CI) |
| **WITH Reactor** | Reactive features work correctly | `mvn verify -Preactor-only` |
| **Isolation** | Reactor classes only load when explicitly used | Dedicated isolation tests |

---

## Existing Pattern: EpollProvider

Lettuce already handles optional dependencies using `Class.forName()` guards. See `EpollProvider.java`:

```java
static {
    boolean availability;
    try {
        Class.forName("io.netty.channel.epoll.Epoll");
        availability = Epoll.isAvailable();
    } catch (ClassNotFoundException e) {
        availability = false;
    }
    EPOLL_AVAILABLE = availability;
}
```

This pattern should be followed for Reactor isolation in G8 (Dynamic Resources).

---

## Core Testing Patterns

### Pattern 1: Contract Tests (Behavioral Equivalence)

**The key pattern for regression-free refactoring.**

Instead of writing separate tests for Reactor and non-Reactor implementations, write **one contract test** that both implementations must pass:

```java
/**
 * Contract test for EventBus behavior.
 * Both Reactor-based and callback-based implementations must pass.
 */
abstract class EventBusContractTest {

    protected abstract EventBus createEventBus();
    protected abstract void subscribe(EventBus bus, Consumer<Event> handler);
    protected abstract void unsubscribe();

    @Test
    void shouldDeliverEventToSubscriber() throws Exception {
        EventBus bus = createEventBus();
        BlockingQueue<Event> received = new ArrayBlockingQueue<>(1);

        subscribe(bus, received::add);
        bus.publish(new ConnectedEvent("test"));

        Event event = received.poll(1, TimeUnit.SECONDS);
        assertThat(event).isInstanceOf(ConnectedEvent.class);
        unsubscribe();
    }

    @Test
    void shouldDeliverMultipleEvents() throws Exception {
        // ... same contract, both impls must pass
    }
}
```

Then two concrete test classes:

```java
// Runs WITH Reactor - tests the Flux-based API
@Tag("requires-reactor")
class ReactorEventBusContractTest extends EventBusContractTest {
    private Disposable subscription;

    @Override
    protected EventBus createEventBus() {
        return new DefaultEventBus(Schedulers.immediate());
    }

    @Override
    protected void subscribe(EventBus bus, Consumer<Event> handler) {
        subscription = bus.get().subscribe(handler::accept);
    }

    @Override
    protected void unsubscribe() {
        subscription.dispose();
    }
}

// Runs WITHOUT Reactor - tests the new callback API
class CallbackEventBusContractTest extends EventBusContractTest {
    private Closeable subscription;

    @Override
    protected EventBus createEventBus() {
        return new CallbackEventBus();
    }

    @Override
    protected void subscribe(EventBus bus, Consumer<Event> handler) {
        subscription = bus.subscribe(handler);
    }

    @Override
    protected void unsubscribe() throws Exception {
        subscription.close();
    }
}
```

**Why this matters:**
- One source of truth for expected behavior
- If implementations diverge, contract test fails
- Freedom to refactor implementation without changing tests
- Guarantees behavioral equivalence

---

### Pattern 2: Class Loading Isolation Tests

Verify that Reactor classes are NOT loaded when using sync/async APIs only:

```java
// No tag - runs in default no-reactor profile
class ReactorIsolationIntegrationTests {

    @Test
    void shouldNotLoadReactorClasses_whenUsingSyncApi() {
        // Capture loaded classes before
        Set<String> reactorClassesBefore = getLoadedReactorClasses();

        // Use only sync API
        RedisClient client = RedisClient.create("redis://localhost:6379");
        StatefulRedisConnection<String, String> conn = client.connect();
        conn.sync().set("key", "value");
        conn.sync().get("key");
        conn.close();
        client.shutdown();

        // Verify no new Reactor classes loaded
        Set<String> reactorClassesAfter = getLoadedReactorClasses();
        assertThat(reactorClassesAfter)
            .as("No Reactor classes should be loaded when using sync API")
            .isEqualTo(reactorClassesBefore);
    }

    private Set<String> getLoadedReactorClasses() {
        // Use instrumentation or class loader inspection
        // to find classes matching "reactor.*"
    }
}
```

**Why this matters:**
- Passing tests don't guarantee isolation
- A class could load without causing `NoClassDefFoundError`
- This test explicitly verifies the isolation goal

---

### Pattern 3: Maven Profiles

Two profiles for the two test scenarios:

```xml
<!-- Profile 1: No Reactor (default for CI) -->
<profile>
    <id>no-reactor</id>
    <build>
        <plugins>
            <plugin>
                <artifactId>maven-failsafe-plugin</artifactId>
                <configuration>
                    <classpathDependencyExcludes>
                        <exclude>io.projectreactor:reactor-core</exclude>
                        <exclude>io.projectreactor:reactor-test</exclude>
                    </classpathDependencyExcludes>
                    <!-- Run all tests EXCEPT those requiring Reactor -->
                    <excludedGroups>requires-reactor</excludedGroups>
                </configuration>
            </plugin>
        </plugins>
    </build>
</profile>

<!-- Profile 2: Reactor Only -->
<profile>
    <id>reactor-only</id>
    <build>
        <plugins>
            <plugin>
                <artifactId>maven-failsafe-plugin</artifactId>
                <configuration>
                    <!-- Run ONLY tests requiring Reactor -->
                    <groups>requires-reactor</groups>
                </configuration>
            </plugin>
        </plugins>
    </build>
</profile>
```

Run with:
- `mvn verify -Pno-reactor` - All tests except reactive (Reactor OFF)
- `mvn verify -Preactor-only` - Only reactive tests (Reactor ON)

---

## Test Organization

Existing reactor-specific tests need `@Tag("requires-reactor")`:

```
src/test/java/io/lettuce/core/
├── commands/
│   └── reactive/                           # All @Tag("requires-reactor")
│       ├── StringReactiveCommandIntegrationTests.java
│       └── ...
├── event/
│   ├── EventBusContractTest.java           # Abstract contract (no tag)
│   ├── ReactorEventBusContractTest.java    # @Tag("requires-reactor")
│   └── CallbackEventBusContractTest.java   # No tag - runs without Reactor
├── credentials/
│   ├── CredentialsProviderContractTest.java
│   └── ...
└── isolation/
    └── ReactorIsolationIntegrationTests.java  # No tag - verifies isolation
```

---

## CI Configuration

```yaml
jobs:
  test-no-reactor:
    runs-on: ubuntu-latest
    steps:
      - run: mvn verify -Pno-reactor  # All tests EXCEPT requires-reactor

  test-reactor-only:
    runs-on: ubuntu-latest
    steps:
      - run: mvn verify -Preactor-only  # Only requires-reactor tests

  # Gate job
  all-tests-passed:
    needs: [test-no-reactor, test-reactor-only]
    runs-on: ubuntu-latest
    steps:
      - run: echo "All tests passed"
```

Both jobs must pass for CI to be green.

---

## Execution Commands

```bash
# Run tests without Reactor (most tests)
mvn verify -Pno-reactor

# Run reactor-specific tests only
mvn verify -Preactor-only

# Run both sequentially (local development)
mvn verify -Pno-reactor && mvn verify -Preactor-only

# Run specific contract test
mvn test -Dtest=EventBusContractTest
```

---

## Testing by Release Phase

Testing requirements evolve across releases:

### 7.x ASAP (Deprecation Release)

| What changes | Testing requirement |
|--------------|---------------------|
| New APIs added (G2, G5, G6, G7) | Add contract tests for new APIs |
| Old APIs deprecated | Keep existing tests running (no changes) |
| Tag existing reactor tests | Add `@Tag("requires-reactor")` to ~95 files |
| Maven profiles created | Add `no-reactor` and `reactor-only` profiles |

**CI at this phase:**
```
┌──────────────────────┐     ┌──────────────────────┐
│ mvn -Pno-reactor     │     │ mvn -Preactor-only   │
│ (exclude requires-   │     │ (only requires-      │
│  reactor tests)      │     │  reactor tests)      │
│ Reactor OFF          │     │ Reactor ON           │
└──────────────────────┘     └──────────────────────┘
           ↓                            ↓
      Must pass                    Must pass
```

### 7.x Later (Internal Refactoring)

| What changes | Testing requirement |
|--------------|---------------------|
| Internal Mono to CF refactoring (G3, G4, G8) | Existing tests must still pass |
| No public API changes | No new tests needed |
| Performance-sensitive paths (G4) | Add benchmarks for hot paths |

**Key:** If existing tests pass after internal refactoring, behavior is preserved.

### 8.0 (Breaking Release)

| What changes | Testing requirement |
|--------------|---------------------|
| Deprecated APIs removed | Delete deprecated API tests |
| `reactive()` moved to separate interface | Update tests to use `connectReactive()` |
| Reactor truly optional | Both CI profiles required to pass |

---

## Tagging Approach

**TDD-style tagging process:**

1. Run `mvn verify -Pno-reactor` (with Reactor excluded from classpath)
2. Tests that fail with `NoClassDefFoundError` or `ClassNotFoundException` need tagging
3. Add `@Tag("requires-reactor")` to those test classes
4. Repeat until `mvn verify -Pno-reactor` passes

**Files to tag (from research):**

| Location | Pattern |
|----------|---------|
| `src/test/java/**/reactive/**` | All files in reactive folders |
| `src/test/java/**/*Reactive*` | All files with Reactive in name |
| Tests using `StepVerifier` | Requires `reactor-test` |
| Tests using `eventBus.get()` | Returns `Flux` |
| Tests using `resolveCredentials()` | Returns `Mono` |

---

## Constraints

| Constraint | Reason |
|------------|--------|
| No `StepVerifier` in untagged tests | `reactor-test` won't be on classpath |
| No `Flux`/`Mono` in contract test base class | Base class must compile without Reactor |
| Use `BlockingQueue` for async assertions | Works without Reactor |
| Use `@Tag("requires-reactor")` for Reactor tests | Excluded by no-reactor profile |

---

## References

- [Release Roadmap](RELEASE_ROADMAP.md) - What ships in each release
- [G2 - Connection Interfaces](G2_CONNECTION_INTERFACES.md) - Public API changes requiring contract tests
- [G5 - EventBus](G5_EVENTBUS.md) - EventBus contract test example
- [G6 - Credentials](G6_CREDENTIALS.md) - Credentials contract test example
