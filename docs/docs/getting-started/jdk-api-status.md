# ☕ JDK API Status & Compatibility

> **Spector deliberately adopts the latest JDK innovations for maximum hardware utilization.** This page explains which APIs are finalized, which are still incubating or in preview, what that means in practice, and how to handle the required JVM flags.

---

## API Status Summary

| API | JDK Status | Since | JVM Flag | Risk Level |
|:---|:---|:---|:---|:---|
| **Panama FFM** (MemorySegment, Arena) | ✅ **Finalized** | JDK 22 (JEP 454) | `--enable-native-access` | **None** — stable, production-ready |
| **Virtual Threads** (Project Loom) | ✅ **Finalized** | JDK 21 (JEP 444) | None | **None** — stable, production-ready |
| **Vector API** (`jdk.incubator.vector`) | 🔬 **Incubator** | JDK 16 (JEP 338) | `--add-modules jdk.incubator.vector` | **Low** — API stable across 10 rounds |
| **Structured Concurrency** | 🔬 **Preview** | JDK 21 (JEP 505) | `--enable-preview` | **Low** — Spector has fallback mode |

> [!IMPORTANT]
> **Two of Spector's four core JDK technologies are already finalized** — Panama FFM (off-heap memory) and Virtual Threads (concurrency). The remaining two (Vector API, Structured Concurrency) are in incubator/preview but have been stable in practice.

---

## What "Incubator" and "Preview" Mean

### Incubator Modules

Incubator modules are **non-final APIs** shipped in the JDK for real-world feedback. They:

- Require an explicit opt-in flag: `--add-modules jdk.incubator.vector`
- Emit a startup warning: `WARNING: Using incubator modules: jdk.incubator.vector`
- May change API surface between JDK releases
- Are **not enabled by default** — they won't interfere with other applications

### Preview Features

Preview features are **language or VM features** that are functionally complete but seeking final feedback. They:

- Require `--enable-preview` at both compile and runtime
- Are expected to be finalized in a near-future JDK release
- May have minor signature changes before finalization

> [!NOTE]
> Both incubator and preview APIs are **fully functional and performant** — they are not experimental prototypes. The designation means the API surface could evolve, not that the implementation is unreliable.

---

## Vector API — Detailed Assessment

The Vector API (`jdk.incubator.vector`) has been incubating since **JDK 16 (March 2021)** — over 5 years and 10 incubation rounds. Despite its incubator status, it is the most mature incubator module in JDK history.

### Why It's Still Incubating

The Vector API's finalization is blocked by **Project Valhalla** (value types). The JDK team wants `FloatVector`, `IntVector`, etc. to be value types for optimal JIT behavior. Until Valhalla delivers value types, the Vector API remains in incubator — not because of instability, but because the JDK team wants the finalized version to have optimal memory layout.

### Stability in Practice

| Aspect | Assessment |
|:---|:---|
| **API surface** | Stable since JDK 19. No breaking changes in 6+ rounds. |
| **Performance** | Fully JIT-optimized. HotSpot intrinsics compile to native AVX/NEON. |
| **Adoption** | Used internally by OpenJDK itself and major open-source projects. |
| **ISA support** | AVX2, AVX-512, NEON — all production-grade. |

### Spector's Usage Pattern

Spector uses the **most stable subset** of the Vector API:

```java
// ISA-agnostic — works on any platform
FloatVector.SPECIES_PREFERRED

// Standard operations — unlikely to change
vector.mul(other).reduceLanes(VectorOperators.ADD)
```

We avoid experimental or niche operations, sticking to arithmetic (mul, add, sub, fma) and reductions (reduceLanes) — the core operations that have been stable across all incubation rounds.

### Migration Path When Finalized

When the Vector API is finalized (expected when Project Valhalla matures):

1. Remove `--add-modules jdk.incubator.vector` from JVM flags
2. Change imports from `jdk.incubator.vector` to `java.util.vector` (or wherever the final package lands)
3. No algorithmic changes expected — the math operations are stable

> [!TIP]
> Spector centralizes all Vector API usage in `spector-core`, so migration will require changes in a single module.

---

## Structured Concurrency — Detailed Assessment

Structured Concurrency (JEP 505) is a **preview feature** that Spector uses for safe parallel task management. It has been in preview since JDK 21.

### Spector's Fallback Mode

Spector includes a **runtime fallback** to classic virtual threads:

```bash
# Use structured concurrency (default)
java --enable-preview -jar spector.jar

# Fall back to classic ExecutorService + virtual threads
java -Dspector.concurrency.structured=false -jar spector.jar
```

The fallback mode uses `Executors.newVirtualThreadPerTaskExecutor()` — fully finalized, production-ready, and functionally equivalent for all Spector use cases.

### Where Spector Uses Structured Concurrency

| Site | Module | Benefit |
|:---|:---|:---|
| Hybrid search fan-out | spector-query | Auto-cancel sibling on failure |
| Distributed shard fan-out | spector-node | Auto-cancel all on shard failure |
| Batch embedding | spector-embed-api | Scope-per-call lifecycle |
| PQ subspace training | spector-index | All-or-nothing structured scope |
| BM25 parallel scoring | spector-index | Auto-cancel with sequential fallback |

> [!NOTE]
> All structured concurrency usage is centralized in `ConcurrentTasks` (spector-commons). When the API finalizes, updates are needed in a single file.

---

## Panama FFM — Finalized ✅

The Foreign Function & Memory API was **finalized in JDK 22** (JEP 454). This is the foundation of Spector's off-heap memory management.

The `--enable-native-access=ALL-UNNAMED` flag is still recommended to suppress warnings about native memory access, but the API itself is stable and will not change.

| Component | Used For |
|:---|:---|
| `MemorySegment` | Off-heap vector storage (zero-copy, zero-GC) |
| `Arena` | Scoped memory lifecycle management |
| `ValueLayout.JAVA_FLOAT` | Type-safe memory access for vector data |
| `MemorySegment.ofBuffer()` | Memory-mapped file I/O |

---

## Virtual Threads — Finalized ✅

Virtual Threads (Project Loom) were **finalized in JDK 21** (JEP 444). Spector uses them throughout:

- REST API request handling (one virtual thread per request)
- MCP server tool dispatch
- Hybrid search fan-out
- Bulk ingestion pipelines

No JVM flags are required — virtual threads are a standard JDK feature.

---

## Required JVM Flags

Here is the complete set of JVM flags Spector requires and why:

```bash
java \
  --add-modules jdk.incubator.vector \     # Vector API (incubator)
  --enable-native-access=ALL-UNNAMED \     # Panama FFM native access (finalized, but flag suppresses warnings)
  --enable-preview \                        # Structured Concurrency (preview)
  -jar spector.jar
```

### Flag Compatibility Matrix

| Flag | Required? | What Happens Without It |
|:---|:---|:---|
| `--add-modules jdk.incubator.vector` | ✅ Required | `ClassNotFoundException` — Vector API classes not available |
| `--enable-native-access=ALL-UNNAMED` | ⚠️ Recommended | Works, but emits native access warnings on stderr |
| `--enable-preview` | ⚠️ Optional | Works if `spector.concurrency.structured=false` (fallback mode) |

> [!TIP]
> **Minimum viable flags:** If you want to avoid preview features entirely, you can run with just `--add-modules jdk.incubator.vector` and `-Dspector.concurrency.structured=false`. This disables structured concurrency but everything else works normally.

---

## FAQ

### Is it safe to use incubator modules in production?

Yes, with awareness. The "incubator" label means the API *surface* could change, not that the implementation is unstable. The Vector API has been functionally stable across 10 JDK releases. Many organizations run incubator modules in production.

### What happens when I upgrade JDK versions?

When upgrading from one JDK version to the next, check the release notes for Vector API changes. In practice, no breaking changes have occurred since JDK 19. Spector's test suite (331+ tests) will catch any issues during a JDK upgrade.

### Will the startup warnings affect my application?

No. The `WARNING: Using incubator modules: jdk.incubator.vector` message goes to stderr and has zero performance impact. It's purely informational. In MCP mode, all logging goes to stderr by design, so the warning doesn't affect the JSON-RPC protocol stream.

### Can I use Spector without any incubator/preview features?

Not currently — the Vector API is fundamental to Spector's SIMD acceleration. However, you can avoid preview features by using the structured concurrency fallback (`-Dspector.concurrency.structured=false`).

---

## See Also

- [Quick Start](quickstart.md) — Build and run with all required JVM flags
- [Installation](installation.md) — JDK setup and verification
- [Architecture Overview](../architecture/overview.md) — How these APIs fit into the architecture
