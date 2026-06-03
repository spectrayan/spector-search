# ðŸ—ºï¸ Roadmap

Spector is under active development. This page details planned improvements, their projected impact, and implementation status.

---

## ðŸ”œ Priority: OpenClaw Integration

### ðŸ”œ OpenClaw Integration â€” Spector as Main Memory {#openclaw}

!!! info "Status: Planned"
    Medium effort. First-class integration with the OpenClaw autonomous agent framework.

Integrate Spector Memory as the **primary long-term memory backend** for [OpenClaw](https://openclaw.ai) â€” the open-source autonomous AI agent framework that runs as a local gateway across WhatsApp, Telegram, Slack, and Discord.

**Why OpenClaw + Spector:**

OpenClaw provides the agentic loop (observe â†’ reason â†’ act) and multi-channel interface, but lacks a biologically-inspired long-term memory system. Currently, OpenClaw agents lose context across sessions or rely on simple key-value stores. Spector Memory gives OpenClaw agents:

- **Cross-session persistence** â€” memories survive across conversations and channels
- **Cognitive recall** â€” biologically-inspired scoring (decay, importance, valence) instead of naive vector search
- **Emotional context** â€” valence-filtered recall for empathetic responses
- **Anti-repetition** â€” habituation prevents the agent from repeating the same responses
- **Associative recall** â€” Hebbian + Temporal + Entity graphs surface connected memories

**Architecture:**

```
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚  OpenClaw Agent (Python)                      â”‚
â”‚  Observe â†’ Reason â†’ Act loop                  â”‚
â”‚  Multi-channel: WhatsApp, Telegram, Slack     â”‚
â”œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤
â”‚  â†•  MCP stdio / HTTP transport                â”‚
â”œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤
â”‚  Spector MCP Server (Java)                    â”‚
â”‚  memory_recall Â· memory_remember          â”‚
â”‚  memory_reinforce Â· memory_introspect         â”‚
â”‚  memory_why_not Â· memory_status               â”‚
â”œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤
â”‚  Spector Memory Engine                        â”‚
â”‚  4-tier storage Â· SIMD scoring Â· Bloom filter â”‚
â”‚  Cognitive graph Â· Decay Â· Habituation        â”‚
â”‚  âš¡ Local, private, off-heap, sub-ms recall    â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
```

**Integration points:**

| OpenClaw Concept | Spector Mapping |
|:---|:---|
| Skill execution result | `memory_remember` with tags from skill name |
| User message (any channel) | Ingest as `EPISODIC` with channel + user tags |
| Agent observation | Ingest as `WORKING` (ephemeral scratchpad) |
| Task planning | `THE_EXECUTOR` profile with Zeigarnik tracking |
| Conversation context | `memory_recall` with `BALANCED` profile |
| User preference learning | `memory_reinforce` on confirmed preferences |
| Security-sensitive context | `PARANOID_SENTINEL` for audit/compliance queries |
| Multi-channel persistence | Same Spector instance across all channels â€” WhatsApp memory available in Telegram |

**Profile mapping for OpenClaw modes:**

| OpenClaw Mode | Spector Profile | Why |
|:---|:---|:---|
| General chat | `BALANCED` | Equal weight to similarity and importance |
| Deep research | `HYPERFOCUS` | Zero decay, strict tag gate on research topic |
| Task execution | `THE_EXECUTOR` | Strict matching, no tangents, Zeigarnik tracking |
| Creative brainstorm | `DIVERGENT` | Cross-domain lateral retrieval |
| Error recovery | `DEBUGGING` | Surface past failures and fixes |
| Security audit | `PARANOID_SENTINEL` | Threat-only recall |

**Implementation plan:**

1. **MCP Bridge**: OpenClaw connects to Spector via MCP stdio transport (local) or HTTP transport (remote)
2. **Memory Lifecycle Hook**: OpenClaw's agentic loop calls `memory_remember` after each skill execution and `memory_recall` before each reasoning step
3. **Channel Metadata**: Channel (WhatsApp/Telegram/Slack) and user ID encoded as synaptic tags for cross-channel memory isolation or sharing
4. **Consolidation**: Spector's `reflect()` daemon runs on a schedule, consolidating episodic memories into semantic knowledge across all channels
5. **Python SDK**: The planned [Python SDK](#python-sdk) wraps the MCP transport for native OpenClaw integration

**Privacy advantage:** Both OpenClaw and Spector run locally â€” the most personal conversations never leave the user's machine.

---

## ðŸ”œ Planned â€” Client SDKs & Documentation

### ðŸ”œ Python SDK â€” MCP Client Wrapper {#python-sdk}

!!! info "Status: Planned"
    Low-medium effort. Thin wrapper over MCP stdio transport.

A lightweight Python package that wraps Spector's MCP server via subprocess/stdio transport, giving Python developers a native-feeling API without touching Java:

```python
from spector import Memory

mem = Memory()
mem.remember("user likes dark mode", tags=["pref", "ui"])
results = mem.recall("theme preference?")
# â†’ [MemoryResult(text="user likes dark mode", score=0.92, confidence=HIGH)]

explanation = mem.why_not("mem-42", "theme preference?")
# â†’ WhyNot(reason=OUTRANKED, score_gap=0.15, ...)
```

**Implementation:**

- Thin `subprocess.Popen` wrapper for MCP stdio transport
- Maps all MCP tools to Pythonic methods (`remember`, `recall`, `forget`, `reinforce`, `why_not`, `introspect`)
- Returns typed dataclasses (`MemoryResult`, `WhyNotExplanation`, `MemoryInsight`)
- Published to PyPI as `spector-memory`

**Why:** The Python-first AI ecosystem (LangChain, LlamaIndex, AutoGen) is the largest potential audience. Eliminates the "language barrier" concern entirely.

---

### ðŸ”œ Documentation Split â€” User Guide vs Architecture Guide {#docs-split}

!!! info "Status: Planned"
    Low effort. Critical for reducing complexity-intimidation.

Separate documentation into two tracks to prevent the "19 packages overwhelm developers" perception:

| Track | Audience | Content |
|---|---|---|
| **User Guide** | Agent developers, MCP users | 5-minute quickstart, MCP tool reference, Python/JS SDK, RecallOptions presets |
| **Architecture Guide** | Spector contributors | Off-heap layouts, SIMD scoring, Bloom filter encoding, Panama internals |

**Key principle:** A developer using `memory.recall("query")` should never see `SynapticHeaderConstants` or `CognitiveRecordLayout` in the getting-started docs.

---

## ðŸ”œ Planned â€” Agentic AI

### ðŸ”œ ProfileAdaptor â€” Self-Tuning Cognitive Profiles {#profile-adaptor}

!!! info "Status: Planned"
    Low-medium effort. High impact for autonomous agent deployments.

A lightweight **contextual bandit** that learns which `CognitiveProfile` performs best for each context (tag combination), using the existing `memory_reinforce` feedback signal. Instead of requiring AI agents to manually select `DEBUGGING` vs `EXPLORING` vs `BALANCED`, the system auto-selects the optimal profile based on historical reinforcement rates.

**How it works:**

1. Agent calls `memory_recall` with `profile=BALANCED` (or no profile)
2. Agent calls `memory_reinforce` on useful results (positive valence) or unhelpful results (negative valence)
3. `ProfileAdaptor` tracks reinforcement rates per `(tag-context, profile)` pair via an exponential moving average
4. On subsequent recalls with matching tags, `ProfileAdaptor.suggest()` returns the profile with the highest historical hit rate

**API:**

```java
public class ProfileAdaptor {
    // Track reinforcement rates per (tag-context, profile) pair
    private final Map<String, Map<CognitiveProfile, RunningStats>> stats;

    /** Called after memory_reinforce. Updates profile effectiveness. */
    public void recordOutcome(CognitiveProfile profile, String[] tags, boolean positive);

    /** Suggests the best profile for a given tag context. */
    public CognitiveProfile suggest(String... tags);
}
```

**MCP integration:**

```json
{
  "query": "why did the payment service crash?",
  "profile": "auto",
  "synaptic_filter": "payments,errors"
}
```

When `profile=auto`, the system queries `ProfileAdaptor.suggest("payments", "errors")` and transparently selects the best-performing profile for that tag context.

**Design considerations:**

- **Cold start**: Falls back to `BALANCED` until â‰¥10 reinforcement signals are recorded for a context
- **Exploration**: Îµ-greedy strategy (10% random profile selection) to avoid local optima
- **Persistence**: Stats serialized to WAL for cross-session learning
- **Multi-tenant**: Per-user stats isolation via the planned `userId` field

---

## ðŸ”œ Planned â€” Compute & Hardware

### ðŸ”œ GPU Kernel Dispatch {#gpu-dispatch}

!!! info "Status: Infrastructure Ready"
    CUDA context management and Panama FFM bridge are implemented. The compute kernel dispatch is pending.

Ship actual CUDA compute kernels for batch cosine similarity and HNSW neighbor selection. The existing `spector-gpu` module provides context management, memory allocation, and kernel loading via Panama FFM â€” the remaining work is the CUDA kernel code itself.

**Prerequisites:** CUDA Toolkit 12+ on the host machine.

**Expected impact:** 10â€“100Ã— throughput improvement for batch similarity computation on large datasets (> 100K vectors).

---

### ðŸ”„ Project Valhalla Value Classes {#valhalla}

!!! tip "Status: Prepared â€” Awaiting JDK 28+"
    Migration TODOs added to all 5 hot-path records. Manual flat-array optimizations serve as bridge patterns until value classes are available.

Migrate hot-path intermediate records to `value class` (or `value record`). JDK 25 does not include JEP 401 â€” Valhalla value classes are expected in JDK 28+.

**Current preparation:**

- **Javadoc TODOs** added to all 5 hot-path records: `CognitiveHeader`, `ScoredRecord`, `HebbianEdge`, `EntityEdge`, `TraversalResult`
- **Manual flat-array optimization** (`FlatMinHeap`) serves as the bridge pattern â€” will be replaceable with `PriorityQueue<value ScoredRecord>` once specialized generics land
- **Performance optimizations** implemented as stop-gap: autoboxing elimination (`int[]` vs `List<Integer>`), `boolean[]` vs `HashSet<Integer>`, LUT-based `Math.pow` replacement

**Benefits (when JDK 28+ lands):**
- **Zero-GC Hot Path**: Short-lived search results and option records are stack-allocated, avoiding the JVM heap.
- **Cache Locality**: Contiguous storage of value structures inside arrays prevents pointer chasing.
- **Header Elimination**: Removes standard 12-to-16-byte JVM object headers for inline arrays.

---

## ðŸ”¬ Research & Future

### ðŸ”¬ TypeScript/JavaScript SDK {#typescript-sdk}

!!! note "Status: Future"
    Medium effort. Wraps MCP HTTP transport.

A TypeScript SDK for Node.js and browser-adjacent environments, wrapping the Streamable HTTP MCP transport:

```typescript
import { SpectorMemory } from '@spector/memory';

const mem = new SpectorMemory({ url: 'http://localhost:8080' });
await mem.remember('user prefers dark mode', { tags: ['pref', 'ui'] });
const results = await mem.recall('theme preference?');
```

**Use cases:** Next.js agents, Vercel AI SDK integration, Electron desktop assistants.

---

### ðŸ”¬ RecallMode.REPLAY â€” WAL Time-Travel Recall {#recall-replay}

!!! note "Status: Future Research"
    High effort. Requires WAL event replay and off-heap snapshot reconstruction.

Replay recall from a frozen point-in-time state by reconstructing memory state from WAL events. Answers the question: *"Why did the agent retrieve X instead of Y at 2pm yesterday?"*

**How it works:**

1. User calls `memory_recall` with `recall_mode=REPLAY` and a target `replay_timestamp`
2. System reads WAL events (ingestions, reinforcements, suppressions) up to the target timestamp
3. Reconstructs a temporary off-heap `MemorySegment` representing the memory state at time T
4. Runs the standard recall pipeline against the frozen snapshot (no mutations)
5. Returns results with a `[REPLAY @ 2025-06-02T14:00:00Z]` provenance marker

**Implementation options:**

- **Option A â€” Full replay**: Read all WAL events from start to target timestamp, apply each mutation to a temporary segment. Expensive compute, but minimal storage overhead.
- **Option B â€” Periodic snapshots**: Periodically snapshot the full off-heap state. Replay only needs events between the last snapshot and the target timestamp. More storage, faster replay.

**Prerequisites:**

- WAL must record all header mutations (recallCount, valence, storageStrength changes)
- Snapshot serialization for off-heap `MemorySegment` state
- Temporary segment allocation + cleanup lifecycle

**Use cases:** Debugging agent behavior, audit trails, compliance (regulated industries), algorithm comparison ("did the new scoring function improve recall quality?").

---

### ðŸ”¬ LoRA Adapter Routing {#lora-routing}

!!! note "Status: Future Research"
    Requires LoRA weight format specification and SIMD matrix multiply implementation.

Multi-tenant query projection via SIMD matrix multiply. Instead of creating separate indexes per tenant, store one base index and apply per-tenant LoRA weight matrices at query time using Panama FMA loops.

**How it works:**
- Ingest base model embeddings once
- Each tenant uploads a small LoRA matrix ($W_A$, typically 768Ã—32 or similar)
- At query time: $q_{tenant} = q_{base} \times W_A$ (microseconds via Panama SIMD)
- Search the same index with the projected query

**Expected impact:** Zero-downtime multi-tenant customization without index duplication.

---

### ðŸ”¬ ColBERT Late Interaction Reranking {#colbert}

!!! note "Status: Future Research"
    Requires token-level vector storage and MaxSim SIMD kernel.

Native ColBERT reranking using Panama FMA loops. ColBERT stores a vector for every token in a document, then computes relevance via MaxSim (maximum similarity per query token). Python struggles with this due to GIL contention when routing massive matrices between C++ and Python memory.

**Spector advantage:** Off-heap `MemorySegment` arrays and Fused-Multiply-Add Panama loops can natively execute ColBERT MaxSim reranking faster than almost any competitor.

---

### ðŸ”¬ SVASQ-PQ Hybrid â€” Product Quantization of SVASQ Residuals {#svasq-pq}

!!! note "Status: Future Research"
    Very high implementation effort. Most aggressive compression option.

After FWHT rotation, instead of scalar INT8/INT4 quantization, apply **Product Quantization** to the rotated coordinates. The FWHT rotation makes coordinates near-independent (isotropized), which is the ideal input distribution for PQ â€” similar to how Optimized PQ (OPQ) works with learned rotations, but using FWHT instead of an expensive SVD-based rotation matrix.

**Memory layout:**
```
[float32 normSq (4 bytes)] [PQ codes: M bytes (one centroid ID per subspace)]
```

With M=16 subspaces, K=256 centroids:

| Dims | Float32 | SVASQ-8 | SVASQ-PQ (M=16) | Compression vs float32 |
|------|---------|--------|----------------|----------------------|
| 768 | 3,072 B | 1,028 B | 20 B | **154Ã—** |
| 4096 | 16,384 B | 4,100 B | 68 B | **241Ã—** |

**Recall impact:**

- PQ on FWHT-rotated residuals: ~85â€“93% recall@10
- FWHT rotation gives ~3â€“5% recall advantage over naive PQ (pre-decorrelates dimensions)
- Rescore with exact float32 residuals pushes recall to 95%+

**Why it works:** The FWHT rotation is essentially a free, lossless "Optimized PQ" rotation â€” it decorrelates dimensions without requiring an expensive SVD or learned rotation matrix. This means PQ subspaces can be independent slices of the rotated vector, which is information-theoretically optimal.

**Implementation scope:**

- Train PQ codebooks per shard (or globally after FWHT rotation)
- Asymmetric Distance Computation (ADC) lookup tables during search
- New SIMD kernel for PQ distance computation
- Integration with existing `ProductQuantizer` in `spector-index`

!!! danger "Complexity Warning"
    This is essentially building a new quantization mode. The existing `ProductQuantizer` could be adapted, but integrating it with the FWHT rotation pipeline is non-trivial. Estimated effort: 2â€“4 weeks.

---

### ðŸ”¬ Flat-Mode SVASQ â€” Compress Flat-Shard Storage {#flat-svasq}

!!! note "Status: Future Research"
    Medium effort, good payoff for large flat shards.

In `SpectorShard`'s flat mode, residuals are stored as raw `float32[]`. Since all residuals in a shard share the same centroid, they have similar statistical distributions. **SVASQ quantization of flat residuals** could compress flat-mode storage by ~3Ã— without changing the shard architecture.

**Savings:**

| Scenario | Current (float32) | With SVASQ | Savings |
|----------|-------------------|-----------|---------
| 10K vectors Ã— 768 dims | 30 MB/shard | 10 MB/shard | **3Ã—** |
| 50K vectors Ã— 4096 dims | 781 MB/shard | 195 MB/shard | **4Ã—** |

**Recall impact:**

- If applied only to storage (decode for search): **None** â€” search uses decoded float32
- If applied to search (scan quantized codes directly): Same as SVASQ-8 (~99.5%)

**Implementation scope:**

- Integrate SVASQ encoding into the flat-mode ingestion path
- Modify `SpectorShard.flatScan()` to use the SVASQ SIMD kernel directly
- Per-shard calibration using the shard's centroid residuals

---

### ðŸ”¬ NPU Acceleration {#npu}

!!! note "Status: Exploratory"
    Depends on Intel/AMD NPU SDK maturity.

Leverage Intel NPU (via OpenVINO) or AMD XDNA (via DirectML) for INT8 batch operations. NPUs are optimized for low-precision matrix operations, making them ideal for quantized SVASQ distance computation.

**Target workloads:** INT8/INT4 batch similarity, SVASQ kernel offload.

---

### ðŸ”¬ WASM Runtime for Edge Deployment {#wasm}

!!! note "Status: Exploratory"
    Depends on GraalWasm or Chicory maturity for JVM â†’ WASM compilation.

Compile the core SIMD kernels and HNSW index to WebAssembly for browser-based or edge deployment. This would enable client-side semantic search without a server round-trip.

---

### ðŸ”´ Adaptive Bit-Width SVASQ {#adaptive-bw}

!!! warning "Status: Not Recommended"
    Very high effort, marginal benefit due to FWHT already equalizing variance.

Instead of uniform INT8 across all dimensions, assign more bits to high-variance dimensions and fewer to low-variance ones (after FWHT rotation):

- Dimensions with Ïƒ > 2Ã— median: 8 bits
- Dimensions with Ïƒ < 0.5Ã— median: 4 bits
- Others: 6 bits

**Projected savings:** ~10â€“15% additional compression.

**Recall impact:** Minimal (< 0.5%) â€” allocating bits proportionally to variance is information-theoretically optimal.

**Why it's not recommended:** FWHT already equalizes variance by design, so the marginal gain from adaptive bit-widths is small. The implementation requires variable-length encoding, non-aligned SIMD reads, and per-dimension bit-width bookkeeping â€” the worst effort-to-benefit ratio of all proposed improvements.

---

## âœ… Completed

### âœ… Native MCP Server {#mcp-server}

!!! success "Completed"
    Implemented in `spector-mcp` module. 6 tools, stdio transport, agent-native search.

Built-in [Model Context Protocol](https://modelcontextprotocol.io/) server that gives AI agents (Claude Desktop, Cursor, autonomous agents) direct, in-process access to Spector's search engine. Zero network overhead â€” tool handlers call `SpectorEngine` directly via virtual threads.

**Tools:** `semantic_search`, `hybrid_search`, `rag_query`, `ingest_document`, `delete_document`, `engine_status`

**Architecture:**
- `McpToolHandler` abstract base class (common timing, error handling, arg parsing)
- `ToolSchemaBuilder` fluent JSON schema construction
- `SpectorToolRegistry` for extensible tool registration
- `SpectorResourceProvider` + `SpectorPromptProvider` for MCP resources/prompts
- `ResultFormatter` shared formatting utilities

---

### âœ… Streamable HTTP Transport {#mcp-http}

!!! success "Completed"
    `TransportMode` enum with `STDIO` and `HTTP` modes. CLI: `--transport=http --port=8080`.

HTTP-based MCP transport for remote/cloud deployments. Same 6 tools exposed over an HTTP endpoint.

**Implementation:**

- `TransportMode` enum: `STDIO` (default), `HTTP`
- CLI flags: `--transport=http`, `--port=8080`
- `SpectorMcpMain`: Parses transport mode and configures server accordingly
- Graceful shutdown on SIGTERM for container readiness

**Use cases:** Cloud deployments, remote agent connections, multi-agent architectures.

---

### âœ… 3-Layer Cognitive Graph {#cognitive-graph}

!!! success "Completed"
    All four phases implemented and merged. 357 tests pass, 0 failures.

Full graph augmentation layer for `spector-memory` â€” three biologically-inspired graph structures that augment vector recall with associative, temporal, and relational signals.

**Architecture:**
```
RecallPipeline
  Step 5a: Habituation + Inhibition of Return
  Step 5b: STDP causal boost (CoActivationTracker)
  Step 5c: Hebbian spreading activation (HebbianGraph, depth=2)
  Step 5d: Temporal chain extension (TemporalChain, maxHops=3)
  Step 5e: Entity graph traversal (EntityGraph, 2-hop BFS)
```

**Layer 1 â€” Hebbian Association Graph:**

- Off-heap adjacency list (164B/node, MAX_DEGREE=20) via Panama `MemorySegment`
- Edge strengthening, decay (0.9 factor per consolidation), spreading activation
- Persistence via `HGPH` magic header, chunked 64KB FileChannel I/O
- CoActivationTracker migrated to off-heap: `OffHeapPairTable` (32B/slot) + `OffHeapEdgeTable` (40B/slot)
- Persistence via `COAX` magic header with hashâ†’tag reverse map

**Layer 2 â€” Entity-Relationship Graph:**

- Off-heap entity store (48B/entity, 16B/edge), BFS traversal with typed edge filtering
- 22 entity types Ã— 21 relation types
- `EntityExtractor` SPI with `LlmEntityExtractor` (externalized prompt template) and `NoOpEntityExtractor`
- Persistence via `ENTG` magic header with nameIndex reconstruction

**Layer 3 â€” Temporal Causal Chain:**

- Off-heap linked list (16B/node: prevIdx + nextIdx + sessionId + pad)
- Session-local memory linking at ingestion, forward/backward traversal at recall
- Persistence via `TPCH` magic header

**Error framework:** 6 error codes (`SPE-310-006..011`), 7 granular exception classes extending `SpectorGraphException`. All catch sites use `catch(RuntimeException)` â†’ create exception â†’ `log(ex.getMessage())`. No string concatenation.

**Each graph step is additive and gracefully degrading** â€” if the graph is null/empty or the operation throws, the step is a no-op.

---

### âœ… Temporal Chain Pruning {#temporal-pruning}

!!! success "Completed"
    `pruneOlderThan(long cutoffEpochMs)` implemented. Integrated into `reflect()` cycle with configurable `temporalRetentionDays(int)`.

Temporal chain links now support automatic pruning during the `reflect()` consolidation cycle.

**Implementation:**

- `TemporalChain.pruneOlderThan(long cutoffEpochMs)`: Scans all nodes, unlinks stale entries, re-stitches prev â†’ next pointers
- `pad:4B` field in node layout replaced with `epochSec:4B` (seconds since epoch)
- Configurable via Builder: `temporalRetentionDays(int)` (default: 7)
- Pruned count reported in `ReflectReport`

---

### âœ… Cross-Layer Promotion (Hebbian â†’ Entity) {#cross-layer-promotion}

!!! success "Completed"
    `promoteHebbianToEntity()` implemented in `DefaultSpectorMemory.reflect()`. Uses reverse index for O(1) entity lookup per memory.

Strong statistical Hebbian associations are automatically promoted to explicit entity relations during the `reflect()` consolidation cycle â€” analogous to hippocampal replay.

**Implementation:**

- During `reflect()`, scans HebbianGraph for edges with weight â‰¥ threshold
- Builds `memoryIdx â†’ entityIds` reverse index for O(1) lookup (vs previous O(EÃ—R) scan)
- If shared entities exist, strengthens the entity relation edge; if none, creates a `RELATED_TO` relation
- Cross-promotion count reported in `ReflectReport`

---

### âœ… Entity Graph Decay + Node Merging {#entity-decay}

!!! success "Completed"
    `decayRelations()`, `mergeSimilarEntities()`, and `levenshteinDistance()` implemented with off-heap optimizations.

Entity graph edges now decay during consolidation, and near-duplicate entities are automatically merged.

**Implementation:**

- `EntityGraph.decayRelations(float factor)`: Multiplicative decay, prunes edges below threshold
- `EntityGraph.mergeSimilarEntities(int maxEditDistance)`: Levenshtein-based fuzzy matching with `ThreadLocal` reusable int[] arrays (zero GC after warmup)
- Integrated into `reflect()` cycle after cross-layer promotion
- Decay/merge counts reported in `ReflectReport`

---

### âœ… Graph-Aware Scoring Weights {#graph-scoring}

!!! success "Completed"
    `GraphScoringPolicy` record implemented. Configurable via `DefaultSpectorMemory.Builder.graphScoringPolicy()`.

All hardcoded graph score attenuation factors are now extracted into a configurable `GraphScoringPolicy` record:

```java
public record GraphScoringPolicy(
    float causalBoostWeight,       // default 0.3
    float hebbianBoostFactor,      // default 0.3
    float temporalForwardFactor,   // default 0.8
    float temporalBackwardFactor,  // default 0.7
    float entityHopAttenuation,    // default 0.25
    int hebbianMaxDepth,           // default 2
    int temporalMaxHops,           // default 3
    int entityMaxHops              // default 2
) {
    public static final GraphScoringPolicy DEFAULT = ...;
}
```

- Configurable via Builder: `graphScoringPolicy(GraphScoringPolicy)`
- All 8 constants replaced with policy accessors in `RecallPipeline`
- Future: online tuning based on user reinforcement/suppression feedback

---

### âœ… SVASQ-4 â€” Half-Precision SVASQ (INT4 Codes) {#svasq-4}

!!! success "Completed"
    Implemented and merged. Available via `SpectorEngine.builder().svasq4()` or `QuantizedHnswIndex.svasq4(...)`.

Replace INT8 `[-127, 127]` codes with INT4 `[-7, 7]` codes in the SVASQ pipeline. The FWHT rotation still equalizes variance, so INT4 quantization error remains uniformly distributed â€” just at a coarser granularity (15 levels vs 255).

**Memory layout:**
```
[float32 normSq (4 bytes)] [INT4 Ã— paddedDim nibble-packed (paddedDim/2 bytes)]
```

| Dims | Current SVASQ-8 | SVASQ-4 | Compression vs float32 |
|------|---------------|--------|----------------------|
| 384 â†’ 512 | 516 B | 260 B | **5.9Ã—** |
| 768 â†’ 1024 | 1028 B | 516 B | **6.0Ã—** |
| 4096 | 4100 B | 2052 B | **8.0Ã—** |

**Recall:**

- Without rescore: ~95â€“97% recall@10
- With 3Ã— oversampling rescore: **~97â€“99% recall@10**

**Key design decisions:**

- Separate `Svasq4Encoder` / `Svasq4SimdKernel` classes (not parameterizing SVASQ-8) to avoid impacting existing code
- Offset encoding `[0, 14]` keeps byte values non-negative for correct `castShape` sign extension
- Deinterleaved hi/lo query arrays match nibble layout for natural SIMD ILP
- Tighter clipping (2.5Ïƒ vs 3.0Ïƒ) optimizes for 15 quantization levels

---

### âœ… Padding-Aware Storage â€” Skip Zero Dimensions {#padding-aware}

!!! success "Completed"
    Implemented in `SvasqEncoder`, `Svasq4Encoder`, `SvasqParams.storedDim()`. SIMD-aligned to 16-byte boundary (Option A).

SVASQ pads vectors to the next power-of-two dimensionality (e.g., 768 â†’ 1024), adding wasted bytes. The padded dimensions are zero-filled before FWHT, so their rotated codes are predictable. We now **store only the first `originalDim` codes** (aligned to the next SIMD boundary) and reconstruct padded codes at query time.

| Dims | paddedDim | Before | After (Padding-Aware) | Savings |
|------|-----------|---------------|---------------|---------
| 384 | 512 | 516 B | 388 B | **25%** |
| 768 | 1024 | 1028 B | 772 B | **25%** |
| 1536 | 2048 | 2052 B | 1540 B | **25%** |
| 4096 | 4096 | 4100 B | 4100 B | 0% (already pow2) |

**Recall impact:** **None** for L2 distance â€” padded dimensions contribute a constant offset that doesn't affect ranking.

**Implementation:** SIMD-aligned stored codes (Option A â€” aligns `storedDim` to next 16-byte boundary). Zero SIMD tail loop overhead.

**Changes:**

- `SvasqParams.storedDim()`: Returns SIMD-aligned `originalDim`
- `SvasqEncoder` / `Svasq4Encoder`: Store only `storedDim` codes
- `SvasqSimdKernel` / `Svasq4SimdKernel`: Loop over `storedDim` instead of `paddedDim`

---

### âœ… Norm Header Compression â€” float32 â†’ float16 {#norm-f16}

!!! success "Completed"
    Implemented via `Float.floatToFloat16()` / `Float.float16ToFloat()` in all SVASQ encoders and kernels.

The 4-byte `float32 exactNormSq` header is now compressed to 2 bytes using `float16` (half-precision).

**Savings:** 2 bytes per vector (combined with padding-aware storage for maximum effect).

| Combined with | Before | After | Savings |
|---------------|--------|-------|---------|
| SVASQ-8 (768-dim) | 1028 B | 770 B | **25%** |
| SVASQ-4 (768-dim) | 516 B | 386 B | **25%** |

**Recall impact:** < 0.01% â€” `float16` has ~3 decimal digits of precision.

**Changes:**

- `SvasqEncoder` / `Svasq4Encoder`: Write norm via `Float.floatToFloat16()`
- `SvasqSimdKernel` / `Svasq4SimdKernel`: Read via `Float.float16ToFloat()`
- `SvasqParams.bytesPerVector()`: Uses 2-byte norm header

---

### âœ… Structured Concurrency (JEP 505) {#structured-concurrency}

!!! success "Completed"
    Implemented via `ConcurrentTasks` in `spector-commons`. Dual-mode: structured concurrency (default) with classic `ExecutorService` fallback via `-Dspector.concurrency.structured=false`.

Migrated all 6 concurrency sites from unstructured `ExecutorService` + `Future` to the JEP 505 `StructuredTaskScope` API, centralized in `ConcurrentTasks`:

| Site | Module | Pattern | Benefit |
|------|--------|---------|---------|
| `HybridSearchOrchestrator` | spector-query | 2-way fan-out (keyword âˆ¥ vector) | Auto-cancel sibling on failure |
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

### ðŸ”œ Active & Planned

| # | Improvement | Category | Effort | Status |
|---|------------|----------|--------|--------|
| 1 | **OpenClaw integration** | Agentic AI | Medium | ðŸ”œ Planned |
| 2 | **Python SDK (MCP wrapper)** | Client SDKs | Low-Medium | ðŸ”œ Planned |
| 3 | **ProfileAdaptor (contextual bandit)** | Agentic AI | Low-Medium | ðŸ”œ Planned |
| 4 | **Documentation split** | Documentation | Low | ðŸ”œ Planned |
| 5 | **GPU kernel dispatch** | Compute | Medium | ðŸ”œ Infra ready |
| 6 | **Project Valhalla** | Runtime | Medium | ðŸ”„ Prepared |

### ðŸ”¬ Research & Future

| # | Improvement | Category | Effort | Status |
|---|------------|----------|--------|--------|
| 7 | **TypeScript/JS SDK** | Client SDKs | Medium | ðŸ”¬ Future |
| 8 | **RecallMode.REPLAY (WAL time-travel)** | Agentic AI | High | ðŸ”¬ Research |
| 9 | **LoRA adapter routing** | Agentic AI | High | ðŸ”¬ Research |
| 10 | **ColBERT late interaction** | Agentic AI | High | ðŸ”¬ Research |
| 11 | **SVASQ-PQ hybrid** | Compression | Very High | ðŸ”¬ Research |
| 12 | **Flat-mode SVASQ** | Compression | Medium | ðŸ”¬ Research |
| 13 | **NPU acceleration** | Compute | High | ðŸ”¬ Exploratory |
| 14 | **WASM edge runtime** | Runtime | High | ðŸ”¬ Exploratory |
| 15 | **Adaptive bit-width** | Compression | Very High | ðŸ”´ Not planned |

### âœ… Completed

| # | Improvement | Category | Effort |
|---|------------|----------|--------|
| 16 | **Native MCP Server** | Agentic AI | Medium |
| 17 | **Streamable HTTP transport** | Agentic AI | Medium |
| 18 | **3-Layer Cognitive Graph** | Graph Memory | High |
| 19 | **Cross-layer promotion** | Graph Memory | Medium |
| 20 | **Entity graph decay + merging** | Graph Memory | Medium |
| 21 | **Graph scoring weights** | Graph Memory | Low |
| 22 | **Temporal chain pruning** | Graph Memory | Low |
| 23 | **SVASQ-4** | Compression | Medium |
| 24 | **Padding-aware storage** | Compression | Low |
| 25 | **Norm header f16** | Compression | Very Low |
| 26 | **Structured Concurrency** | Runtime | Low |

