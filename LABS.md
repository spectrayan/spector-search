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
2. Set `JAVA_HOME` to the Valhalla EA build:

```bash
export JAVA_HOME=/path/to/valhalla-ea-jdk
export PATH=$JAVA_HOME/bin:$PATH

# Verify
java -version
# Should show something like: openjdk version "25-valhalla+XX-XX"
```

### Build

```bash
mvn clean compile -DskipTests
```

### Run Tests

```bash
mvn test
```

> ⚠️ **Note:** This branch will NOT compile with a standard JDK 25/26/27.
> The `value` keyword is only recognized by the Valhalla EA builds.

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
- [Project Valhalla](https://openjdk.org/projects/valhalla/)
- [Valhalla EA Builds](https://jdk.java.net/valhalla/)
- [Spector JDK API Status](docs/docs/getting-started/jdk-api-status.md)
