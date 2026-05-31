# 🗺️ Roadmap

Spector is under active development. This page details planned improvements, their projected impact, and implementation status.

---

## Compression & Quantization

### ✅ SVASQ-4 — Half-Precision SVASQ (INT4 Codes) {#svasq-4}

!!! success "Completed"
    Implemented and merged. Available via `SpectorEngine.builder().svasq4()` or `QuantizedHnswIndex.svasq4(...)`.

Replace INT8 `[-127, 127]` codes with INT4 `[-7, 7]` codes in the SVASQ pipeline. The FWHT rotation still equalizes variance, so INT4 quantization error remains uniformly distributed — just at a coarser granularity (15 levels vs 255).

**Memory layout:**
```
[float32 normSq (4 bytes)] [INT4 × paddedDim nibble-packed (paddedDim/2 bytes)]
```

| Dims | Current SVASQ-8 | SVASQ-4 | Compression vs float32 |
|------|---------------|--------|----------------------|
| 384 → 512 | 516 B | 260 B | **5.9×** |
| 768 → 1024 | 1028 B | 516 B | **6.0×** |
| 4096 | 4100 B | 2052 B | **8.0×** |

**Recall:**

- Without rescore: ~95–97% recall@10
- With 3× oversampling rescore: **~97–99% recall@10**

**Key design decisions:**

- Separate `Svasq4Encoder` / `Svasq4SimdKernel` classes (not parameterizing SVASQ-8) to avoid impacting existing code
- Offset encoding `[0, 14]` keeps byte values non-negative for correct `castShape` sign extension
- Deinterleaved hi/lo query arrays match nibble layout for natural SIMD ILP
- Tighter clipping (2.5σ vs 3.0σ) optimizes for 15 quantization levels

---

### 🔜 Padding-Aware Storage — Skip Zero Dimensions {#padding-aware}

!!! info "Status: Planned (next)"
    Low effort, zero recall loss for L2 distance. Highest ROI pending improvement.

SVASQ pads vectors to the next power-of-two dimensionality (e.g., 768 → 1024), adding wasted bytes. The padded dimensions are zero-filled before FWHT, so their rotated codes are predictable. We can **store only the first `originalDim` codes** and reconstruct padded codes at query time.

| Dims | paddedDim | Current SVASQ-8 | Padding-Aware | Savings |
|------|-----------|---------------|---------------|---------|
| 384 | 512 | 516 B | 388 B | **25%** |
| 768 | 1024 | 1028 B | 772 B | **25%** |
| 1536 | 2048 | 2052 B | 1540 B | **25%** |
| 4096 | 4096 | 4100 B | 4100 B | 0% (already pow2) |

**Recall impact:** **None** for L2 distance — padded dimensions contribute a constant offset that doesn't affect ranking.

!!! warning "SIMD Tail Loop"
    The current SIMD kernel exploits `paddedDim % VL == 0` to avoid tail loops. Storing only `originalDim` codes breaks this, requiring either a scalar tail loop or alignment padding to the next SIMD boundary (e.g., round up to multiple of 16 bytes).

**Changes required:**

- `SvasqEncoder` / `Svasq4Encoder`: Store only `originalDim` codes, update `bytesPerVector()`
- `SvasqSimdKernel` / `Svasq4SimdKernel`: Handle non-power-of-2 loop bound (SIMD-aligned padding recommended)

---

### 🔜 Norm Header Compression — float32 → float16 {#norm-f16}

!!! info "Status: Planned (next)"
    Very low effort. Negligible recall impact.

The 4-byte `float32 exactNormSq` header can be compressed to 2 bytes using `float16` (half-precision). Java 21+ provides `Float.floatToFloat16()` and `Float.float16ToFloat()` for lossless conversion.

**Savings:** 2 bytes per vector. Small absolute savings but trivial to implement.

| Combined with | Before | After | Savings |
|---------------|--------|-------|---------|
| SVASQ-8 (768-dim) | 1028 B | 1026 B | 0.2% |
| SVASQ-4 (768-dim) | 516 B | 514 B | 0.4% |
| Padding-aware SVASQ-8 (768-dim) | 772 B | 770 B | 0.3% |

**Recall impact:** < 0.01% — `float16` has ~3 decimal digits of precision. For L2 ranking, the norm header is a per-vector constant that shifts all distances equally.

**Changes required:**

- `SvasqEncoder` / `Svasq4Encoder`: Use `Float.floatToFloat16()` for 2-byte header write
- `SvasqSimdKernel` / `Svasq4SimdKernel`: Read with `Float.float16ToFloat(segment.get(JAVA_SHORT, offset))`

---

### 🔬 SVASQ-PQ Hybrid — Product Quantization of SVASQ Residuals {#svasq-pq}

!!! note "Status: Future Research"
    Very high implementation effort. Most aggressive compression option.

After FWHT rotation, instead of scalar INT8/INT4 quantization, apply **Product Quantization** to the rotated coordinates. The FWHT rotation makes coordinates near-independent (isotropized), which is the ideal input distribution for PQ — similar to how Optimized PQ (OPQ) works with learned rotations, but using FWHT instead of an expensive SVD-based rotation matrix.

**Memory layout:**
```
[float32 normSq (4 bytes)] [PQ codes: M bytes (one centroid ID per subspace)]
```

With M=16 subspaces, K=256 centroids:

| Dims | Float32 | SVASQ-8 | SVASQ-PQ (M=16) | Compression vs float32 |
|------|---------|--------|----------------|----------------------|
| 768 | 3,072 B | 1,028 B | 20 B | **154×** |
| 4096 | 16,384 B | 4,100 B | 68 B | **241×** |

**Recall impact:**

- PQ on FWHT-rotated residuals: ~85–93% recall@10
- FWHT rotation gives ~3–5% recall advantage over naive PQ (pre-decorrelates dimensions)
- Rescore with exact float32 residuals pushes recall to 95%+

**Why it works:** The FWHT rotation is essentially a free, lossless "Optimized PQ" rotation — it decorrelates dimensions without requiring an expensive SVD or learned rotation matrix. This means PQ subspaces can be independent slices of the rotated vector, which is information-theoretically optimal.

**Implementation scope:**

- Train PQ codebooks per shard (or globally after FWHT rotation)
- Asymmetric Distance Computation (ADC) lookup tables during search
- New SIMD kernel for PQ distance computation
- Integration with existing `ProductQuantizer` in `spector-index`

!!! danger "Complexity Warning"
    This is essentially building a new quantization mode. The existing `ProductQuantizer` could be adapted, but integrating it with the FWHT rotation pipeline is non-trivial. Estimated effort: 2–4 weeks.

---

### 🔬 Flat-Mode SVASQ — Compress Flat-Shard Storage {#flat-svasq}

!!! note "Status: Future Research"
    Medium effort, good payoff for large flat shards.

In `SpectorShard`'s flat mode, residuals are stored as raw `float32[]`. Since all residuals in a shard share the same centroid, they have similar statistical distributions. **SVASQ quantization of flat residuals** could compress flat-mode storage by ~3× without changing the shard architecture.

**Savings:**

| Scenario | Current (float32) | With SVASQ | Savings |
|----------|-------------------|-----------|---------|
| 10K vectors × 768 dims | 30 MB/shard | 10 MB/shard | **3×** |
| 50K vectors × 4096 dims | 781 MB/shard | 195 MB/shard | **4×** |

**Recall impact:**

- If applied only to storage (decode for search): **None** — search uses decoded float32
- If applied to search (scan quantized codes directly): Same as SVASQ-8 (~99.5%)

**Implementation scope:**

- Integrate SVASQ encoding into the flat-mode ingestion path
- Modify `SpectorShard.flatScan()` to use the SVASQ SIMD kernel directly
- Per-shard calibration using the shard's centroid residuals

---

### 🔴 Adaptive Bit-Width SVASQ {#adaptive-bw}

!!! warning "Status: Not Recommended"
    Very high effort, marginal benefit due to FWHT already equalizing variance.

Instead of uniform INT8 across all dimensions, assign more bits to high-variance dimensions and fewer to low-variance ones (after FWHT rotation):

- Dimensions with σ > 2× median: 8 bits
- Dimensions with σ < 0.5× median: 4 bits
- Others: 6 bits

**Projected savings:** ~10–15% additional compression.

**Recall impact:** Minimal (< 0.5%) — allocating bits proportionally to variance is information-theoretically optimal.

**Why it's not recommended:** FWHT already equalizes variance by design, so the marginal gain from adaptive bit-widths is small. The implementation requires variable-length encoding, non-aligned SIMD reads, and per-dimension bit-width bookkeeping — the worst effort-to-benefit ratio of all proposed improvements.

---

## Agentic AI

### ✅ Native MCP Server {#mcp-server}

!!! success "Completed"
    Implemented in `spector-mcp` module. 6 tools, stdio transport, agent-native search.

Built-in [Model Context Protocol](https://modelcontextprotocol.io/) server that gives AI agents (Claude Desktop, Cursor, autonomous agents) direct, in-process access to Spector’s search engine. Zero network overhead — tool handlers call `SpectorEngine` directly via virtual threads.

**Tools:** `semantic_search`, `hybrid_search`, `rag_query`, `ingest_document`, `delete_document`, `engine_status`

**Architecture:**
- `McpToolHandler` abstract base class (common timing, error handling, arg parsing)
- `ToolSchemaBuilder` fluent JSON schema construction
- `SpectorToolRegistry` for extensible tool registration
- `SpectorResourceProvider` + `SpectorPromptProvider` for MCP resources/prompts
- `ResultFormatter` shared formatting utilities

---

### 🔜 Streamable HTTP Transport {#mcp-http}

!!! info "Status: Planned (next)"
    Stdio covers Claude Desktop, Cursor, and all local agents. HTTP needed for cloud/remote deployments.

Add HTTP-based MCP transport for scenarios where the agent and Spector run on different machines. The official MCP SDK supports Streamable HTTP transport — Spector would expose the same 6 tools over an HTTP endpoint.

**Use cases:** Cloud deployments, remote agent connections, multi-agent architectures.

---

### 🔬 LoRA Adapter Routing {#lora-routing}

!!! note "Status: Future Research"
    Requires LoRA weight format specification and SIMD matrix multiply implementation.

Multi-tenant query projection via SIMD matrix multiply. Instead of creating separate indexes per tenant, store one base index and apply per-tenant LoRA weight matrices at query time using Panama FMA loops.

**How it works:**
- Ingest base model embeddings once
- Each tenant uploads a small LoRA matrix ($W_A$, typically 768×32 or similar)
- At query time: $q_{tenant} = q_{base} \times W_A$ (microseconds via Panama SIMD)
- Search the same index with the projected query

**Expected impact:** Zero-downtime multi-tenant customization without index duplication.

---

### 🔬 ColBERT Late Interaction Reranking {#colbert}

!!! note "Status: Future Research"
    Requires token-level vector storage and MaxSim SIMD kernel.

Native ColBERT reranking using Panama FMA loops. ColBERT stores a vector for every token in a document, then computes relevance via MaxSim (maximum similarity per query token). Python struggles with this due to GIL contention when routing massive matrices between C++ and Python memory.

**Spector advantage:** Off-heap `MemorySegment` arrays and Fused-Multiply-Add Panama loops can natively execute ColBERT MaxSim reranking faster than almost any competitor.

---

## Cognitive Graph Memory

### ✅ 3-Layer Cognitive Graph {#cognitive-graph}

!!! success "Completed"
    All four phases implemented and merged. 357 tests pass, 0 failures.

Full graph augmentation layer for `spector-memory` — three biologically-inspired graph structures that augment vector recall with associative, temporal, and relational signals.

**Architecture:**
```
RecallPipeline
  Step 5a: Habituation + Inhibition of Return
  Step 5b: STDP causal boost (CoActivationTracker)
  Step 5c: Hebbian spreading activation (HebbianGraph, depth=2)
  Step 5d: Temporal chain extension (TemporalChain, maxHops=3)
  Step 5e: Entity graph traversal (EntityGraph, 2-hop BFS)
```

**Layer 1 — Hebbian Association Graph:**

- Off-heap adjacency list (164B/node, MAX_DEGREE=20) via Panama `MemorySegment`
- Edge strengthening, decay (0.9 factor per consolidation), spreading activation
- Persistence via `HGPH` magic header, chunked 64KB FileChannel I/O
- CoActivationTracker migrated to off-heap: `OffHeapPairTable` (32B/slot) + `OffHeapEdgeTable` (40B/slot)
- Persistence via `COAX` magic header with hash→tag reverse map

**Layer 2 — Entity-Relationship Graph:**

- Off-heap entity store (48B/entity, 16B/edge), BFS traversal with typed edge filtering
- 22 entity types × 21 relation types
- `EntityExtractor` SPI with `LlmEntityExtractor` (externalized prompt template) and `NoOpEntityExtractor`
- Persistence via `ENTG` magic header with nameIndex reconstruction

**Layer 3 — Temporal Causal Chain:**

- Off-heap linked list (16B/node: prevIdx + nextIdx + sessionId + pad)
- Session-local memory linking at ingestion, forward/backward traversal at recall
- Persistence via `TPCH` magic header

**Error framework:** 6 error codes (`SPE-310-006..011`), 7 granular exception classes extending `SpectorGraphException`. All catch sites use `catch(RuntimeException)` → create exception → `log(ex.getMessage())`. No string concatenation.

**Each graph step is additive and gracefully degrading** — if the graph is null/empty or the operation throws, the step is a no-op.

---

### 🔜 Temporal Chain Pruning {#temporal-pruning}

!!! info "Status: Planned (next)"
    Low effort. Prevents unbounded temporal chain growth.

Temporal chain links are permanent — unlike Hebbian edges which decay via `decayEdges(0.9f)`, temporal links have no homeostasis mechanism. Old session-local links waste slots indefinitely.

**Design:**

- Add `pruneOlderThan(long cutoffEpochMs)` to `TemporalChain`
- Replace the `pad:4B` field in the 16B node layout with `epochSec:4B` (seconds since epoch, ~136 year range)
- Integrate into `DefaultSpectorMemory.reflect()` after Hebbian decay
- Configurable retention period via Builder: `temporalRetentionDays(int)` (default: 7)

**Effort:** ~0.5 day

---

### 🔜 Cross-Layer Promotion (Hebbian → Entity) {#cross-layer-promotion}

!!! info "Status: Planned (next)"
    Medium effort. Enables automatic knowledge graph construction from statistical patterns.

Promote strong statistical Hebbian associations into explicit entity relations during sleep consolidation — analogous to hippocampal replay.

**Design:**

- During `reflect()`, scan HebbianGraph for edges with `weight ≥ 0.8` AND `activationCount ≥ 5`
- For each strong edge, look up shared entities via `EntityGraph.memoriesForEntity()`
- If shared entities exist, strengthen the entity relation edge; if none, create a `RELATED_TO` relation
- Add `promotionThreshold(float)` and `promotionMinActivations(int)` to Builder config
- Add `PromotionReport` record for observability: `promotedCount`, `strengthenedCount`, `skippedCount`

**Effort:** ~1-2 days

---

### 🔜 Entity Graph Decay + Node Merging {#entity-decay}

!!! info "Status: Planned"
    Medium effort. Prevents entity graph bloat.

Entity graph edges accumulate without decay. Near-duplicate entities (e.g., "John Smith" and "J. Smith") should be merged during consolidation.

**Design:**

- Add `decayRelations(float factor)` to `EntityGraph` — multiplicative decay, prune below threshold
- Add `mergeEntities(int sourceId, int targetId)` — redirect all edges and memory links
- Fuzzy name matching via Levenshtein distance during consolidation
- Integrate into `reflect()` cycle

**Effort:** ~1-2 days

---

### 🔜 Graph-Aware Scoring Weights {#graph-scoring}

!!! info "Status: Planned"
    Low effort. Highest ROI among remaining graph improvements.

Extract hardcoded graph score attenuation factors into a configurable `GraphScoringPolicy`.

**Current hardcoded values:**

| Factor | Current Value | Used In |
|---|---|---|
| Hebbian boost | 0.3f | RecallPipeline Step 5c |
| Temporal forward | 0.8f | RecallPipeline Step 5d |
| Temporal backward | 0.7f | RecallPipeline Step 5d |
| Entity hop attenuation | 0.25f | RecallPipeline Step 5e |

**Design:**

```java
public record GraphScoringPolicy(
    float hebbianBoostFactor,     // default 0.3
    float temporalForwardFactor,  // default 0.8
    float temporalBackwardFactor, // default 0.7
    float entityHopAttenuation,   // default 0.25
    int hebbianMaxDepth,          // default 2
    int temporalMaxHops,          // default 3
    int entityMaxHops             // default 2
) {}
```

- Configurable via Builder: `graphScoringPolicy(GraphScoringPolicy)`
- Future: online tuning based on user reinforcement/suppression feedback

**Effort:** ~0.5 day

---

## Compute & Hardware

### 🔜 GPU Kernel Dispatch {#gpu-dispatch}

!!! info "Status: Infrastructure Ready"
    CUDA context management and Panama FFM bridge are implemented. The compute kernel dispatch is pending.

Ship actual CUDA compute kernels for batch cosine similarity and HNSW neighbor selection. The existing `spector-gpu` module provides context management, memory allocation, and kernel loading via Panama FFM — the remaining work is the CUDA kernel code itself.

**Prerequisites:** CUDA Toolkit 12+ on the host machine.

**Expected impact:** 10–100× throughput improvement for batch similarity computation on large datasets (> 100K vectors).

---

### 🔬 NPU Acceleration {#npu}

!!! note "Status: Exploratory"
    Depends on Intel/AMD NPU SDK maturity.

Leverage Intel NPU (via OpenVINO) or AMD XDNA (via DirectML) for INT8 batch operations. NPUs are optimized for low-precision matrix operations, making them ideal for quantized SVASQ distance computation.

**Target workloads:** INT8/INT4 batch similarity, SVASQ kernel offload.

---

## Runtime & Deployment

### 🔬 WASM Runtime for Edge Deployment {#wasm}

!!! note "Status: Exploratory"
    Depends on GraalWasm or Chicory maturity for JVM → WASM compilation.

Compile the core SIMD kernels and HNSW index to WebAssembly for browser-based or edge deployment. This would enable client-side semantic search without a server round-trip.

---

### 🔬 Project Valhalla Value Classes {#valhalla}

!!! note "Status: Future Research"
    Exploratory evaluation of JEP 401 (Value Classes and Objects). Requires Project Valhalla Early-Access builds.

Migrate hot-path intermediate records (e.g., `CognitiveResult`, candidate pairs, search options) to `value class` (or `value record`). This will allow the JVM JIT compiler to perform aggressive scalar replacement and store value arrays contiguously in memory, eliminating garbage collection overhead and pointer-chasing latency during HNSW index traversals.

**Benefits:**
- **Zero-GC Hot Path**: Short-lived search results and option records are stack-allocated, avoiding the JVM heap.
- **Cache Locality**: Contiguous storage of value structures inside arrays prevents pointer chasing.
- **Header Elimination**: Removes standard 12-to-16-byte JVM object headers for inline arrays.

---

### ✅ Structured Concurrency (JEP 505) {#structured-concurrency}

!!! success "Completed"
    Implemented via `ConcurrentTasks` in `spector-commons`. Dual-mode: structured concurrency (default) with classic `ExecutorService` fallback via `-Dspector.concurrency.structured=false`.

Migrated all 6 concurrency sites from unstructured `ExecutorService` + `Future` to the JEP 505 `StructuredTaskScope` API, centralized in `ConcurrentTasks`:

| Site | Module | Pattern | Benefit |
|------|--------|---------|---------|
| `HybridSearchOrchestrator` | spector-query | 2-way fan-out (keyword ∥ vector) | Auto-cancel sibling on failure |
| `ClusterCoordinator` | spector-node | N-way shard fan-out | Auto-cancel all on shard failure |
| `DistributedQueryCoordinator` | spector-node | N-way with timeout + partial results | Clean timeout via `awaitAll()` + `withTimeout()` |
| `ParallelEmbeddingPipeline` | spector-embed-api | N-way batch embedding | Scope-per-call, no executor lifecycle |
| `ParallelPqTrainer` | spector-index | M-way K-Means subspace training | All-or-nothing structured scope |
| `BM25Index` | spector-index | Parallel term scoring | Auto-cancel with sequential fallback |

**Key design decisions:**

- Centralized in `ConcurrentTasks` (spector-commons) for single-point updates when JEP finalizes
- Feature flag: `-Dspector.concurrency.structured=false` for fallback to classic virtual threads
- `forkJoinAll()`: all-or-nothing with auto-cancel (uses `awaitAllSuccessfulOrThrow` Joiner)
- `forkJoinPartial()`: deadline-based with `LabeledTask`/`PartialResult` records (uses `awaitAll` Joiner + `Configuration.withTimeout()`)

---

## Summary Table

| # | Improvement | Category | Effort | Status |
|---|------------|----------|--------|--------|
| 1 | **SVASQ-4** | Compression | Medium | ✅ Done |
| 2 | **Native MCP Server** | Agentic AI | Medium | ✅ Done |
| 3 | **3-Layer Cognitive Graph** | Graph Memory | High | ✅ Done |
| 4 | **Structured Concurrency** | Runtime | Low | ✅ Done |
| 5 | **Padding-aware storage** | Compression | Low | 🔜 Next |
| 6 | **Norm header f16** | Compression | Very Low | 🔜 Next |
| 7 | **Temporal chain pruning** | Graph Memory | Low | 🔜 Next |
| 8 | **Cross-layer promotion** | Graph Memory | Medium | 🔜 Planned |
| 9 | **Entity graph decay + merging** | Graph Memory | Medium | 🔜 Planned |
| 10 | **Graph scoring weights** | Graph Memory | Low | 🔜 Planned |
| 11 | **Streamable HTTP transport** | Agentic AI | Medium | 🔜 Planned |
| 12 | **GPU kernel dispatch** | Compute | Medium | 🔜 Infra ready |
| 13 | **SVASQ-PQ hybrid** | Compression | Very High | 🔬 Research |
| 14 | **Flat-mode SVASQ** | Compression | Medium | 🔬 Research |
| 15 | **LoRA adapter routing** | Agentic AI | High | 🔬 Research |
| 16 | **ColBERT late interaction** | Agentic AI | High | 🔬 Research |
| 17 | **NPU acceleration** | Compute | High | 🔬 Exploratory |
| 18 | **WASM edge runtime** | Runtime | High | 🔬 Exploratory |
| 19 | **Project Valhalla** | Runtime | Medium | 🔬 Research |
| 20 | **Adaptive bit-width** | Compression | Very High | 🔴 Not planned |
