# 🔬 Labs Branch: Project Valhalla Value Classes

> **Branch:** `labs/valhalla`
> **Base:** `main`
> **JDK Required:** [Project Valhalla Early-Access Build](https://jdk.java.net/valhalla/)
> **Status:** Experimental — not for production use

---

## What This Branch Does

This branch converts Spector's hot-path records to [JEP 401 Value Classes](https://openjdk.org/jeps/401),
enabling the JVM to perform **heap flattening**, **scalarization**, and **object header elimination**
on the most allocation-heavy types in the search engine.

### What Are Value Classes?

Value classes are identity-free objects introduced by [Project Valhalla](https://openjdk.org/projects/valhalla/).
They give you the abstraction of classes with the performance of primitives:

```java
// Standard record: 12–16 byte object header + pointer indirection in arrays
public record ScoredResult(String id, int index, float score) { }

// Value record: zero headers, flattened in arrays, scalarized on stack
public value record ScoredResult(String id, int index, float score) { }
```

**Key benefits:**
- **Heap flattening** — `ScoredResult[]` stores fields contiguously (no pointer chasing)
- **Scalarization** — JIT decomposes short-lived value objects into CPU registers
- **Header elimination** — removes 12–16 byte JVM object header per array element
- **Cache locality** — contiguous memory layout improves L1/L2 cache hit rates

### Converted Types

| Type | Module | Hot-Path Frequency | Why |
|------|--------|--------------------|-----|
| `ScoredResult` | spector-index | 🔴 CRITICAL | Millions per HNSW search — the #1 candidate |
| `BatchSearchResult` | spector-gpu | 🔴 CRITICAL | Bulk-allocated during GPU batch similarity |
| `TurboCode` | spector-core | 🔴 CRITICAL | Created per vector in quantization pipeline |
| `CognitiveResult` | spector-memory | 🟠 HIGH | Created per recall hit in cognitive memory |
| `VectorStoreLayout` | spector-storage | 🟠 HIGH | Single-field record, every vector read/write |
| `ScoredChunk` | spector-rag | 🟠 HIGH | Per-result allocation in RAG pipeline |

All converted types are annotated with `@ValueCandidate` (from `spector-commons`)
which documents the rationale and hot-path frequency.

---

## Building This Branch

### Prerequisites

1. **Download the Valhalla EA JDK** from [jdk.java.net/valhalla](https://jdk.java.net/valhalla/)
2. Extract it somewhere (your default JDK stays untouched):

```bash
mkdir -p ~/jdks
tar -xzf openjdk-27-jep401ea*.tar.gz -C ~/jdks

# Verify
~/jdks/jdk-27/bin/java -version
# Should show: openjdk version "27-jep401ea3" 2026-09-15
```

### Build

Set `VALHALLA_HOME` to point to the EA JDK — no need to change `JAVA_HOME` or `PATH`:

```bash
VALHALLA_HOME=~/jdks/jdk-27 mvn clean compile -DskipTests
```

Maven runs on your normal JDK 25. Only the compiler forks to the Valhalla javac via
`<fork>true</fork>` + `<executable>${env.VALHALLA_HOME}/bin/javac</executable>` in the POM.

### Run Tests

```bash
VALHALLA_HOME=~/jdks/jdk-27 mvn test
```

> ⚠️ **Note:** This branch will NOT compile without the Valhalla EA JDK.
> The `value` keyword is only recognized by the Valhalla EA builds, not standard JDK 25/26/27.

---

## Benchmarking Value Classes

To measure the impact of value classes on Spector's hot paths:

```bash
# Run the core benchmark suite
mvn exec:exec -pl spector-bench \
  -Dexec.mainClass=com.spectrayan.spector.bench.CorePerformanceBenchmark

# Compare against main branch (standard records)
git stash && git checkout main
mvn exec:exec -pl spector-bench \
  -Dexec.mainClass=com.spectrayan.spector.bench.CorePerformanceBenchmark
```

### Expected Impact

| Metric | Before (records) | Expected (value records) | Why |
|--------|-----------------|-------------------------|-----|
| GC pauses during search | ~0.01% | ~0% | Value objects never enter the GC heap |
| `ScoredResult[]` memory | 12–16 B header + 20 B payload | 20 B payload only | Header elimination |
| HNSW neighbor traversal | Pointer-chase per candidate | Contiguous scan | Array flattening |
| p99 latency jitter | Low | Lower | No safepoint-triggered GC pauses |

> **Note:** Spector already achieves ≤0.01% GC overhead via off-heap Panama storage for
> vector data. Value classes primarily improve the *metadata* path (results, candidates,
> scoring records) rather than the vector storage path.

---

## Merging Back to Main

This branch will be merged to `main` when:

1. JEP 401 enters **preview** in a mainline GA JDK release (not just EA builds)
2. The `value` keyword is stable and backward-compatible with `--enable-preview`
3. Benchmarks confirm measurable improvement on Spector workloads

Until then, this branch serves as:
- A **proof-of-concept** for Valhalla readiness
- A **benchmark testbed** for measuring value class impact
- A **showcase** that Spector stays at the cutting edge of JVM innovation

---

## JDK 27 / Valhalla Feature Roadmap

This section tracks all JDK 27 and Valhalla features relevant to Spector,
their implementation status on this branch, and expected impact.

### ✅ Implemented

#### Value Classes (JEP 401) — Valhalla EA

| Type | Module | Status | Impact |
|------|--------|--------|--------|
| `ScoredResult` | spector-index | ✅ Converted | Critical — HNSW traversal hot path |
| `BatchSearchResult` | spector-gpu | ✅ Converted | Critical — GPU batch results |
| `TurboCode` | spector-core | ✅ Converted | Critical — quantization pipeline |
| `CognitiveResult` | spector-memory | ✅ Converted | High — memory recall results |
| `VectorStoreLayout` | spector-storage | ✅ Converted | High — storage layout reads |
| `ScoredChunk` | spector-rag | ✅ Converted | High — RAG pipeline results |

**What it does:** The `value` keyword eliminates object identity, enabling:
- **Heap flattening** — arrays of value records store inline (no pointer chasing)
- **Scalarization** — JIT decomposes value records into raw fields in registers
- **No GC pressure** — value types on the stack never touch the garbage collector

#### Lazy Constants (JEP 531, Third Preview) — JDK 27

| Type | Module | Status | Pattern Replaced |
|------|--------|--------|-----------------|
| `GpuCapability.GPU_INFO` | spector-gpu | ✅ Converted | `volatile` + double-checked locking |

**What it does:** `LazyConstant<T>` provides deferred initialization that the JIT can
constant-fold after first access. Unlike `volatile` fields, the JIT compiler trusts that
a `LazyConstant` value is effectively `final` after initialization, enabling aggressive
inlining and constant propagation.

```java
// BEFORE: volatile + DCL — JIT cannot constant-fold, volatile read every call
private static volatile GpuInfo cachedInfo;
public static GpuInfo detect() {
    if (cachedInfo != null) return cachedInfo;
    synchronized (GpuCapability.class) { ... }
}

// AFTER: LazyConstant — JIT treats as true constant after init
private static final LazyConstant<GpuInfo> GPU_INFO = LazyConstant.of(() -> doDetect());
public static GpuInfo detect() { return GPU_INFO.get(); }
```

---

### 🟢 Free Improvements (Zero Code Changes)

#### Compact Object Headers (JEP 534) — Default in JDK 27

Object headers shrink from **12 bytes → 8 bytes** (4 bytes saved per object).

| Spector Impact | Estimated Savings |
|---|---|
| HNSW graph nodes (millions) | ~4 MB per million nodes |
| All records/DTOs in result sets | 10-20% heap reduction |
| Array headers | Better cache line utilization |
| GPU wrapper objects | Reduced overhead for off-heap wrappers |

> Already standard in JDK 25 (JEP 519) but required `-XX:+UseCompactObjectHeaders`.
> In JDK 27, it becomes the **default** — no flags needed.

#### G1 Default GC in All Environments (JEP 523) — JDK 27

G1 becomes the default GC everywhere (including single-CPU containers).
Spector already assumes G1, so this just removes the need for explicit
`-XX:+UseG1GC` in deployment configs.

---

### 🟡 Future Candidates

#### Null-Restricted Types — Valhalla (Future)

When available, types like `ScoredResult!` guarantee non-null at the type level:

```java
ScoredResult! result;       // compile-time guarantee: never null
ScoredResult![] results;    // fully flat array — no null slots, no indirection
```

**Spector benefit:** The off-heap `MemorySegment` storage already guarantees non-null
layouts. Null-restricted types would let the on-heap side match, eliminating null checks
in the HNSW neighbor traversal and RAG pipeline.

**Tracked by:** All `@ValueCandidate`-annotated types are candidates for `!` when it lands.

#### Vector API Finalization — Waiting for Valhalla

The Vector API (JEP 537, 12th Incubator) remains in incubation specifically because
it depends on Valhalla's value classes for its generic type shape (`Vector<Float>` needs
to be a value type). Once Valhalla value classes reach preview in a GA JDK:

1. Vector API will move to preview
2. The `jdk.incubator.vector` module will become `java.lang.vector` (or similar)
3. Spector's 20+ files using the Vector API will need import updates

**No action needed now.** The API surface is stable; only the module name will change.

#### Structured Concurrency (JEP 533, 7th Preview)

Already used in 2 Spector files. Minor API refinements in each preview round.
Will become standard when finalized.

#### Primitive Types in Patterns (JEP 532, 5th Preview)

Enables `switch` on primitive types and `instanceof` for primitives. Could simplify
distance metric dispatch in `spector-core` (e.g., switching on `float` similarity
thresholds).

---

## `@ValueCandidate` Annotation

All types converted (or planned for conversion) are annotated with:

```java
@ValueCandidate(
    reason = "Millions of allocations per HNSW search",
    hotPathFrequency = ValueCandidate.Frequency.CRITICAL
)
public value record ScoredResult(...) { }
```

This annotation is defined in `spector-commons` and is available on both
`main` and `labs/valhalla`. On `main`, it serves as documentation; on this
branch, annotated types are actually converted to `value record`.

---

## Related

- [JEP 401: Value Classes and Objects](https://openjdk.org/jeps/401)
- [JEP 531: Lazy Constants](https://openjdk.org/jeps/531)
- [JEP 534: Compact Object Headers](https://openjdk.org/jeps/534)
- [JEP 537: Vector API (12th Incubator)](https://openjdk.org/jeps/537)
- [JEP 533: Structured Concurrency](https://openjdk.org/jeps/533)
- [Project Valhalla](https://openjdk.org/projects/valhalla/)
- [Valhalla EA Builds](https://jdk.java.net/valhalla/)
- [Spector JDK API Status](docs/docs/getting-started/jdk-api-status.md)
