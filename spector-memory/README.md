# 🧠 Spector Memory

> **The Cognitive Memory Engine for Autonomous AI Agents.**
>
> A biologically-inspired, off-heap memory system that gives AI agents the ability to **remember**, **forget**, **consolidate**, and **associate** — with microsecond latency and zero garbage collection pressure. Built on Java Project Panama, SIMD-accelerated vector math, and Virtual Threads.

[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-BSL%201.1-blue.svg)](LICENSE)
[![Panama](https://img.shields.io/badge/Panama-Off--Heap-blueviolet.svg)](#)
[![SIMD](https://img.shields.io/badge/SIMD-AVX2%2FAVX--512-green.svg)](#)
[![Virtual Threads](https://img.shields.io/badge/Loom-Virtual_Threads-blue.svg)](#)

---

## Why Cognitive Memory?

Traditional vector databases treat memories as static documents in a flat index. Real cognition is fundamentally different:

| Traditional Vector DB | Spector Memory |
|---|---|
| Flat document store | **4-tier cognitive architecture** (Working → Episodic → Semantic → Procedural) |
| Static similarity search | **Fused scoring** — similarity × importance × temporal decay in a single SIMD pass |
| No temporal awareness | **Reconsolidation** — frequently recalled memories resist forgetting |
| No emotional context | **Valence tracking** — memories carry emotional coloring |
| No contextual gating | **Synaptic tags** — 64-bit Bloom filter eliminates 99% of candidates in 1 CPU cycle |
| Python + network hops | **Zero-GC, off-heap Panama** — microsecond latency, no serialization |

---

## Architecture

```
spector-memory/
├── SpectorMemory.java              ← Façade (Builder pattern entry point)
├── pipeline/                       ← "Neural Pathways" — ingestion + recall pipelines
│     ├── IngestionPipeline.java        (10-step remember pipeline)
│     ├── RecallPipeline.java           (parallel tier scanning + scoring)
│     └── HebbianCoActivationListener   (Observer pattern post-recall)
│
├── cortex/                         ← "Cerebral Cortex" — 4 tier stores
│     ├── TierStore.java                (Strategy interface)
│     ├── TierRouter.java               (Registry + polymorphic dispatch)
│     ├── WorkingMemoryStore.java       (Prefrontal Cortex — volatile circular buffer)
│     ├── EpisodicMemoryStore.java      (Hippocampus — time-partitioned mmap)
│     ├── SemanticMemoryStore.java      (Neocortex — permanent knowledge)
│     └── ProceduralMemoryStore.java    (Basal Ganglia — learned procedures)
│
├── synapse/                        ← "Synaptic Machinery" — header layout + scoring
│     ├── CognitiveRecordLayout.java    (32-byte aligned synaptic header)
│     ├── CognitiveScorer.java          (6-phase fused scoring hot-loop)
│     ├── SynapticTagEncoder.java       (64-bit inline Bloom filter)
│     ├── SynapticHeaderConstants.java  (offsets, masks, field sizes)
│     └── DecayStrategy.java            (SIMD-friendly temporal decay)
│
├── dopamine/                       ← "Dopamine System" — surprise & importance
│     ├── SurpriseDetector.java         (Welford online statistics + Z-score)
│     ├── FlashbulbPolicy.java          (extreme surprise → pinned memory)
│     └── WelfordStats.java             (running mean/variance tracker)
│
├── amygdala/                       ← "Amygdala" — emotional valence
│     └── ValenceTracker.java           (emotional coloring of memories)
│
├── hebbian/                        ← "Hebbian Learning" — associations
│     ├── CoActivationTracker.java      (tag co-occurrence tracking)
│     └── HebbianGraph.java             (associative memory network)
│
├── hippocampus/                    ← "Hippocampus" — consolidation & cleanup
│     ├── ReflectDaemon.java            (sleep consolidation K-Means)
│     └── TombstoneCompactor.java       (partition rebuild)
│
├── habituation/                    ← "Habituation" — anti-filter bubble
│     └── HabituationPenalty.java       (frequency-based score decay)
│
├── inhibition/                     ← "Inhibition" — suppression
│     └── SuppressionSet.java           (explicit memory blocking)
│
├── interference/                   ← "Proactive Interference" — deduplication
│     └── SemanticDeduplicator.java     (near-duplicate detection + merge)
│
├── prospective/                    ← "Prospective Memory" — future intents
│     ├── ProspectiveScheduler.java     (time-triggered reminders)
│     └── Reminder.java                 (scheduled memory record)
│
├── metamemory/                     ← "Metamemory" — self-reflection
│     └── MemoryIntrospector.java       (memory health stats & analytics)
│
├── index/                          ← O(1) reverse index
│     └── MemoryIndex.java              (ConcurrentHashMap forward + reverse)
│
└── sync/                           ← Persistence & replication
      ├── MemoryWal.java                (Write-Ahead Log)
      └── CrdtMergeStrategy.java        (CRDT merge for distributed sync)
```

### Biological System → Package Mapping

| Brain Region | Package | Java Classes | Function |
|---|---|---|---|
| 🧠 Cerebral Cortex | `cortex/` | `TierRouter`, `TierStore`, 4 stores | 4-tier memory storage (Working → Episodic → Semantic → Procedural) |
| 🔗 Synapses | `synapse/` | `CognitiveScorer`, `SynapticTagEncoder`, `CognitiveRecordLayout` | 32-byte header, 6-phase scoring, Bloom filter gating |
| ⚡ Dopamine System | `dopamine/` | `SurpriseDetector`, `FlashbulbPolicy` | Surprise detection, auto-importance, flashbulb pinning |
| 😱 Amygdala | `amygdala/` | `ValenceTracker` | Emotional coloring (positive/negative/neutral) |
| 🔄 Hebbian Learning | `hebbian/` | `CoActivationTracker`, `HebbianGraph` | "Neurons that fire together wire together" |
| 🛏️ Hippocampus | `hippocampus/` | `ReflectDaemon`, `TombstoneCompactor` | Sleep consolidation, synaptic pruning, partition rebuild |
| 😴 Habituation | `habituation/` | `HabituationPenalty` | Anti-filter bubble — penalizes repetitive recall |
| 🚫 Inhibition | `inhibition/` | `SuppressionSet` | Explicit memory suppression (user redaction) |
| 🔮 Prospective Memory | `prospective/` | `ProspectiveScheduler`, `Reminder` | Future-oriented intent reminders |
| 🪞 Metamemory | `metamemory/` | `MemoryIntrospector` | Self-reflective memory health analytics |

---

## Quick Start

```java
// 1. Create a cognitive memory with Ollama embeddings
SpectorMemory memory = SpectorMemory.builder()
    .dimensions(4096)
    .embeddingProvider(OllamaEmbeddingProvider.create("qwen3-embedding"))
    .workingCapacity(100)
    .episodicPartitionCapacity(10_000)
    .semanticCapacity(5_000)
    .proceduralCapacity(500)
    .build();

// 2. Remember — 10-step ingestion pipeline
memory.remember("pref-dark-mode",
    "The user strongly prefers dark mode for all IDE editors.",
    MemoryType.EPISODIC, MemorySource.USER_STATED,
    "ui", "preferences", "coding");

// 3. Recall — parallel SIMD-accelerated search with cognitive scoring
List<CognitiveResult> results = memory.recall("dark theme settings",
    RecallOptions.builder()
        .topK(5)
        .synapticFilter("preferences")    // Bloom filter pre-screen
        .minImportance(0.3f)              // Skip low-importance memories
        .build());

for (CognitiveResult r : results) {
    System.out.printf("%.4f [%s] %s%n", r.score(), r.memoryType(), r.text());
}

// 4. Forget — tombstone a memory
memory.forget("pref-dark-mode");

// 5. Suppress — temporarily hide from recall
memory.suppress("noisy-memory-id", "Not relevant right now");

// 6. Close — releases all off-heap memory
memory.close();
```

---

## The 6-Phase Scoring Pipeline

Every recall query executes a SIMD-optimized hot-loop that fuses **six** filtering and scoring phases into a single sequential scan. Each phase eliminates candidates before the expensive vector math:

```
Phase 1: Tombstone Check     (~1 cycle)    → Skip dead memories
Phase 2: Synaptic Tag Gating (~1 cycle)    → Bloom filter eliminates 99% of irrelevant
Phase 3: Valence Filter      (~2 cycles)   → Emotional range filtering
Phase 4: Importance/Decay    (~5 cycles)   → Skip old + low-importance
Phase 5: SIMD L2 Distance   (~200 cycles)  → Quantized INT8 Euclidean via Vector API
Phase 6: Fused Score         (~7 cycles)   → α·similarity + β·importance·decay
```

**The math:**
If an agent has 1,000,000 episodic memories but only 10,000 match the active synaptic tags:
- Phases 1-4 eliminate 990,000 memories in ~990µs (cheap header reads)
- Phase 5 computes SIMD distance on only ~10,000 candidates
- **Total: ~0.13ms for 1M memories vs ~200ms without gating (1,500× improvement)**

---

## Performance

Benchmarked on Intel Core Ultra 9 285K, Java 25, AVX2 256-bit:

| Benchmark | Result |
|---|---|
| **SIMD L2 Distance (768-dim)** | 2.2 µs/vector (1.4M vectors/sec) |
| **SIMD L2 Distance (128-dim)** | 0.8 µs/vector (1.2M vectors/sec) |
| **Reverse Index Lookup** | 180 ns/lookup (O(1) via ConcurrentHashMap) |
| **CognitiveScorer (10K × 128-dim)** | 2.9 ms total |
| **Batch Habituation (1K IDs)** | 101 µs total |
| **Full Pipeline (1K ingest + 100 recall)** | < 50 ms/query |
| **Real Embedding (qwen3-embedding 4096-dim)** | 31 ms/embed via Ollama |

### Test Suite

```
spector-core:   276 tests ✅  (includes 15 SIMD kernel tests)
spector-memory: 167 tests ✅  (includes 33 perf + index tests)
                + 10 Ollama real embedding E2E tests (gated by OLLAMA_LIVE=true)
Total: 443 tests, 0 failures
```

---

## Competitive Landscape

| Feature | Spector Memory | Mem0 | Letta (MemGPT) | Zep |
|---|---|---|---|---|
| Language | **Java 25** | Python | Python | Go/Python |
| Storage | **Off-heap Panama** | Postgres/pgvector | Postgres/Chroma | Postgres |
| Latency | **0.13ms (1M memories)** | ~50-200ms | ~100-500ms | ~20-100ms |
| GC Pressure | **Zero** | Python GC | Python GC | Go GC |
| Temporal Decay | **Fused SIMD** | Post-filter | Post-filter | Post-filter |
| Emotional Valence | **✅ Built-in** | ❌ | ❌ | ❌ |
| Synaptic Tag Gating | **✅ 1-cycle Bloom** | ❌ | ❌ | ❌ |
| Sleep Consolidation | **✅ K-Means** | ❌ | ❌ | ❌ |
| Surprise Detection | **✅ Welford Z-score** | ❌ | ❌ | ❌ |
| Habituation | **✅ Anti-filter bubble** | ❌ | ❌ | ❌ |
| MCP Integration | **✅ Native** | ❌ | ❌ | ❌ |

---

## Documentation

📖 **Full documentation**: See the [Cognitive Memory Guide](../docs/docs/memory/index.md) for:

- [System Architecture](../docs/docs/memory/architecture.md) — package hierarchy, data flow, design patterns
- [6-Phase Scoring Pipeline](../docs/docs/memory/scoring-pipeline.md) — deep dive with math and cycle counts
- [Biological Systems](../docs/docs/memory/cortex.md) — each brain region mapped to code
- [Performance & SIMD](../docs/docs/memory/performance.md) — benchmarks, optimization techniques
- [Off-Heap Panama Design](../docs/docs/memory/panama-design.md) — zero-GC architecture
- [API Reference](../docs/docs/memory/api-reference.md) — full method signatures

---

## License

This module is licensed under the **Business Source License 1.1 (BSL 1.1)**.

- Permits free use for non-production purposes.
- Permits production use for all purposes **except** offering it as a managed service or embedding/integrating it in a competing AI cognitive memory product or service.
- Automatically transitions to the **Apache License 2.0** on **May 27, 2030** (4 years from release).

See the [LICENSE](LICENSE) file for the full terms and conditions.

**Built with ⚡ by [Spectrayan](https://www.spectrayan.com/)**
