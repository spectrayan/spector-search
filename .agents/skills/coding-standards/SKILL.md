# Skill: Coding Standards Reference

This skill provides the comprehensive coding standards for the Spector project. Agents should reference this document when writing or reviewing Java code in any `spector-*` module.

## Trigger

Reference this document when:
- Writing new Java classes or methods
- Reviewing code changes for standards compliance
- Creating new modules or packages
- Adding or auditing exception handling
- Creating new ErrorCode constants or SpectorException subclasses
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

## Error Handling — SpectorException Framework

Spector uses a structured error framework based on `ErrorCode` + `SpectorException`. **Never throw generic exceptions.** All errors go through this system.

### Core Architecture

```
ErrorCode (enum)              — Central registry of SPE-XXX-YYY codes with {} message templates
  ↓
SpectorException (abstract)   — Base class, stores ErrorCode, formats message via errorCode.format(args)
  ├── SpectorValidationException   (SPE-100-xxx)
  ├── SpectorConfigException       (SPE-110-xxx)
  ├── SpectorIndexException        (SPE-200-xxx)
  ├── SpectorStorageException      (SPE-210-xxx)
  ├── SpectorEmbeddingException    (SPE-300-xxx)
  ├── SpectorMemoryException       (SPE-310-xxx)
  │   ├── SpectorGraphException           (SPE-310-006..011)
  │   │   ├── SpectorHebbianException         (SPE-310-006)
  │   │   ├── SpectorTemporalChainException   (SPE-310-007)
  │   │   ├── SpectorEntityGraphException     (SPE-310-008)
  │   │   ├── SpectorCoActivationException    (SPE-310-009)
  │   │   ├── SpectorGraphPersistenceException(SPE-310-010)
  │   │   └── SpectorGraphDecayException      (SPE-310-011)
  │   ├── SpectorMemoryRecallException    (SPE-310-002)
  │   ├── SpectorMemoryConsolidationException (SPE-310-003)
  │   └── SpectorMemoryTierFullException  (SPE-310-001)
  ├── SpectorGpuException             (SPE-400-xxx)
  ├── SpectorServerException          (SPE-500-xxx)
  ├── SpectorClientException          (SPE-510-xxx)
  ├── SpectorIngestionException       (SPE-600-xxx)
  ├── SpectorClusterException         (SPE-700-xxx)
  └── SpectorInternalException        (SPE-900-xxx)
```

**Key files:**
- `spector-commons/src/main/java/com/spectrayan/spector/commons/error/ErrorCode.java`
- `spector-commons/src/main/java/com/spectrayan/spector/commons/error/SpectorException.java`
- Module-specific errors: `spector-{module}/src/main/java/.../error/`

### Adding a New Error Code

1. Add the constant to `ErrorCode.java` under the correct category section:

```java
// In ErrorCode.java — under the correct category section
/** Brief description of when this error occurs. */
MY_OPERATION_FAILED  (310_012, ErrorCategory.MEMORY,
        "My operation failed for {}: {}"),
```

- Code format: `{category_prefix}_{sequence}` → e.g., `310_012` → `SPE-310-012`
- Message template uses `{}` placeholders (SLF4J-style)
- **Codes are immutable once assigned — never reuse or renumber**

### Creating a Granular Exception

Create a domain-specific exception that binds a default `ErrorCode` and captures typed context:

```java
// ✅ CORRECT — granular exception with typed constructor
public class SpectorHebbianException extends SpectorGraphException {

    private final String operation;

    public SpectorHebbianException(String operation) {
        super(ErrorCode.GRAPH_HEBBIAN_FAILED, operation);   // format via errorCode.format(args)
        this.operation = operation;
    }

    public SpectorHebbianException(String operation, Throwable cause) {
        super(ErrorCode.GRAPH_HEBBIAN_FAILED, cause, operation);
        this.operation = operation;
    }

    public String getOperation() { return operation; }
}
```

**Rules:**
- Constructor args map 1:1 to `{}` placeholders in the ErrorCode template
- **No string concatenation at construction site** — formatting happens inside `SpectorException` via `errorCode.format(args)`
- Store domain-specific context as fields (e.g., `operation`, `path`, `graphType`)
- Follow the naming pattern: `Spector{Domain}Exception`

### Throw Sites — Throwing Exceptions

```java
// ✅ CORRECT — typed exception, no string concatenation
throw new SpectorGraphPersistenceException("HebbianGraph", filePath, e);
// getMessage() → "[SPE-310-010] Graph persistence failed for HebbianGraph: /path/to/file"

// ✅ CORRECT — ErrorCode with typed args
throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "listener");
// getMessage() → "[SPE-100-007] listener must not be null"

// ❌ WRONG — string concatenation at throw site
throw new SpectorGraphException(ErrorCode.GRAPH_PERSISTENCE_FAILED, e,
        "HebbianGraph save to " + filePath + " failed: " + e.getMessage());

// ❌ WRONG — generic exception
throw new RuntimeException("something failed");

// ❌ WRONG — UncheckedIOException (use SpectorException subtypes)
throw new UncheckedIOException("write failed", e);
```

### Catch Sites — Graceful Degradation

For enrichment steps that should **not** crash the main pipeline:

```java
// ✅ CORRECT — create exception for formatted message, log, continue
} catch (RuntimeException e) {
    SpectorHebbianException ex = new SpectorHebbianException("edge strengthening", e);
    log.warn(ex.getMessage());
}

// ✅ CORRECT — catch and rethrow as domain exception
} catch (IOException e) {
    throw new SpectorGraphPersistenceException("EntityGraph", filePath, e);
}

// ❌ WRONG — catch generic Exception
} catch (Exception e) { ... }

// ❌ WRONG — ErrorCode.format() with string concatenation at call site
log.warn(ErrorCode.GRAPH_HEBBIAN_FAILED.format(
        "edge strengthening for '" + id + "': " + e.getMessage()));

// ❌ WRONG — swallowing exceptions
} catch (Exception e) { /* ignored */ }
```

### Pattern Summary

| Scenario | Pattern |
|---|---|
| **New domain error** | Add `ErrorCode` constant → create `Spector{Domain}Exception` subclass |
| **Throw on failure** | `throw new Spector{Domain}Exception(args, cause)` |
| **Graceful degradation** | `catch(RuntimeException) → new Exception(args, e) → log(ex.getMessage())` |
| **IO failure** | `catch(IOException) → throw new SpectorGraphPersistenceException(type, path, e)` |
| **Validation** | `throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "paramName")` |
| **Never** | `catch(Exception)`, `throw new RuntimeException()`, string concat in ErrorCode.format() |

---

## Testing Standards

- **Framework:** JUnit 5 + AssertJ only (never JUnit 4 or Hamcrest)
- **File tests:** Always use `@TempDir`, never hardcode paths
- **Assertions:** Fluent AssertJ: `assertThat(x).isEqualTo(y)`, never `assertEquals`
- **Naming:** Test methods describe behavior: `walRecovery_truncatesIncompleteRecord()`
- **Coverage:** All new public API methods require at least 1 test
