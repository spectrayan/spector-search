# Skill: Coding Standards Reference

This skill provides the comprehensive coding standards for the Spector Search project. Agents should reference this document when writing or reviewing Java code in any `spector-*` module.

## Trigger

Reference this document when:
- Writing new Java classes or methods
- Reviewing code changes for standards compliance
- Creating new modules or packages
- Resolving code style disagreements

---

## Java Language (JDK 25)

### Modern Features — Required

| Feature | Usage | Example |
|---|---|---|
| **Records** | All immutable data holders | `public record NodeInfo(String id, int port) {}` |
| **Sealed classes** | Closed type hierarchies | `sealed interface VectorIndex permits HnswIndex, BruteForceIndex` |
| **Pattern matching** | `instanceof` checks | `if (index instanceof AbstractHnswIndex hnsw && hnsw.size() > 0)` |
| **Switch expressions** | Exhaustive matching | `return switch (mode) { case SEARCH -> engine; case MEMORY -> memory; };` |
| **`var`** | Local variables when RHS type is obvious | `var config = SpectorConfig.DEFAULT.withDimensions(384);` |
| **Text blocks** | Multi-line strings | `"""SELECT * FROM ..."""` |

### Concurrency — Virtual Thread Safety

```java
// ✅ CORRECT — ReentrantLock (virtual-thread safe)
private final ReentrantLock lock = new ReentrantLock();
public void write(byte[] data) {
    lock.lock();
    try { /* critical section */ }
    finally { lock.unlock(); }
}

// ❌ WRONG — synchronized pins virtual threads to carrier
public synchronized void write(byte[] data) { /* ... */ }
```

- Use `ReentrantLock` for all mutual exclusion
- Use `ReentrantReadWriteLock` when read-heavy
- Use `AtomicReference`, `AtomicInteger` for simple counters
- Use `LockSupport.parkNanos()` instead of `Thread.sleep()`
- Use `ConcurrentHashMap` over `Collections.synchronizedMap()`

### Panama FFM (Foreign Function & Memory)

```java
// ✅ Shared arena for concurrent access
try (Arena arena = Arena.ofShared()) {
    MemorySegment segment = arena.allocate(ValueLayout.JAVA_FLOAT, capacity);
    segment.set(ValueLayout.JAVA_FLOAT, offset, value);
}

// ✅ Zero-copy slice
MemorySegment slice = segment.asSlice(offset, length);

// ❌ WRONG — copying to float[] in hot path
float[] copy = segment.toArray(ValueLayout.JAVA_FLOAT);  // heap allocation!
```

- `Arena.ofShared()` for concurrent access across threads
- `Arena.ofConfined()` for single-thread operations
- Prefer `MemorySegment` slices over array copies
- Use `ValueLayout.JAVA_FLOAT` (not `JAVA_FLOAT_UNALIGNED`) when alignment is guaranteed

### SIMD (Vector API)

```java
// ✅ CORRECT — species-agnostic
static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

public static float dotProduct(float[] a, float[] b) {
    int i = 0;
    FloatVector sum = FloatVector.zero(SPECIES);
    int bound = SPECIES.loopBound(a.length);
    for (; i < bound; i += SPECIES.length()) {
        var va = FloatVector.fromArray(SPECIES, a, i);
        var vb = FloatVector.fromArray(SPECIES, b, i);
        sum = va.fma(vb, sum);
    }
    float result = sum.reduceLanes(VectorOperators.ADD);
    for (; i < a.length; i++) result += a[i] * b[i]; // scalar tail
    return result;
}

// ❌ WRONG — hardcoded lane width
static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_256;
```

---

## Naming Conventions

| Element | Convention | Example |
|---|---|---|
| Module directory | `spector-{name}` | `spector-memory` |
| Package | `com.spectrayan.spector.{name}` | `com.spectrayan.spector.memory.sync` |
| Class | PascalCase, descriptive | `MemoryWal`, `CognitiveRecordLayout` |
| Interface | PascalCase, noun/adjective | `VectorIndex`, `IngestionTarget` |
| Constants | `UPPER_SNAKE_CASE` | `HEADER_MAGIC`, `DEFAULT_CAPACITY` |
| Methods | camelCase, verb-first | `resolveIndex()`, `ingestChunked()` |
| Test class | `{ClassName}Test` | `MemoryWalTest` |
| Integration test | `{Name}IntegrationTest` | `SpectorMemoryIntegrationTest` |
| Builder | Static inner `Builder` class | `SpectorEngine.builder()` |
| Factory | `{Name}Factory` | `EngineComponentFactory` |

---

## Class Structure Template

```java
package com.spectrayan.spector.{module};

import ...;

/**
 * Brief description of what this class does.
 *
 * <p>Detailed explanation of design decisions, usage patterns,
 * and relationship to other classes.</p>
 *
 * <h3>Design Patterns</h3>
 * <ul>
 *   <li><b>Pattern</b> — explanation</li>
 * </ul>
 *
 * @see RelatedClass
 */
public class MyClass implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MyClass.class);

    // ─────────────── Constants ───────────────
    private static final int DEFAULT_CAPACITY = 1000;

    // ─────────────── Fields ───────────────
    private final SpectorConfig config;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean closed;

    // ─────────────── Construction ───────────────
    public MyClass(SpectorConfig config) { ... }

    // ─────────────── Public API ───────────────
    public void doWork() { ... }

    // ─────────────── Internal ───────────────
    private void helper() { ... }

    // ─────────────── Lifecycle ───────────────
    @Override
    public void close() { ... }
}
```

**Section separators:** Use `// ─────────────── Section ───────────────` for visual grouping.

---

## Performance Rules (core, index, storage)

| Rule | Detail |
|---|---|
| **No allocations in hot paths** | Reuse buffers, use offset+length APIs, avoid boxing |
| **Zero-copy** | `MemorySegment` slices, never copy to `float[]` in search |
| **Branchless SIMD** | `VectorMask` for tail handling, minimize scalar fallback |
| **Benchmark gate** | Performance PRs must include JMH before/after results |
| **Profile first** | Use JFR/async-profiler before optimizing |

---

## Error Handling

```java
// ✅ Domain-specific exceptions
throw new WalCorruptionException("CRC mismatch at offset " + offset, cause);

// ✅ Log + rethrow pattern for IO
try {
    channel.write(buffer);
} catch (IOException e) {
    log.error("WAL write failed at position {}", position, e);
    throw new UncheckedIOException("WAL write failed", e);
}

// ❌ WRONG — swallowing exceptions
try { ... } catch (Exception e) { /* ignored */ }

// ❌ WRONG — generic exceptions
throw new RuntimeException("something failed");
```

---

## Testing Standards

- **Framework:** JUnit 5 + AssertJ only (never JUnit 4 or Hamcrest)
- **File tests:** Always use `@TempDir`, never hardcode paths
- **Assertions:** Fluent AssertJ: `assertThat(x).isEqualTo(y)`, never `assertEquals`
- **Naming:** Test methods describe behavior: `walRecovery_truncatesIncompleteRecord()`
- **Coverage:** All new public API methods require at least 1 test
