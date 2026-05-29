---
title: System Architecture
description: "Package hierarchy, data flow, and extensibility model for Spector Memory."
---

# System Architecture

Spector Memory is organized around a **biological metaphor** where each Java package corresponds to a brain region or cognitive mechanism. This isn't just naming — the architecture genuinely mirrors how biological memory systems interact.

---

## Extensibility

| Component | Extension point | What you can customize |
|---|---|---|
| `SpectorMemory` | Single entry point for all operations | Configure tiers, capacities, embedding providers |
| `TierStore` interface | Add new memory tiers | Implement the interface + register in `TierRouter` — no other changes needed |
| `AbstractTierStore` | Common tier lifecycle | Extend for new off-heap tier stores with Arena/segment management |
| `RecallListener` | Post-recall hooks | Add async listeners for co-activation tracking, logging, metrics |
| `CognitiveIngestionTarget` / `RecallPipeline` | Discrete processing steps | Each step is independently testable and replaceable |

---

## Data Flow: Ingestion

The ingestion pipeline is split across two layers:

- **`IngestionPipeline`** (in `spector-ingestion`) — handles step 1 (embed) and chunking for large documents
- **`CognitiveIngestionTarget`** (in `spector-memory`) — handles steps 2–9 (synaptic encoding → WAL)

```mermaid
sequenceDiagram
    participant App as Application
    participant SM as SpectorMemory
    participant CT as CognitiveIngestionTarget
    participant EP as EmbeddingProvider
    participant SD as SurpriseDetector
    participant FP as FlashbulbPolicy
    participant SQ as ScalarQuantizer
    participant TR as TierRouter
    participant MI as MemoryIndex
    participant WAL as MemoryWal

    App->>SM: remember(id, text, type, tags)
    SM->>CT: ingestCognitive(id, text, vector, type, tags, ...)
    
    Note over CT: Step 1: Embed (done by unified IngestionPipeline)
    Note over CT: or via CognitiveIngestionTarget.ingestCognitive()
    CT->>EP: embed(text)
    EP-->>CT: float[4096]
    
    Note over CT: Step 2: Encode tags
    CT->>CT: SynapticTagEncoder.encode(tags) → 64-bit Bloom
    
    Note over CT: Step 3: Surprise detection
    CT->>SD: computeImportance(l2Norm)
    SD-->>CT: importance (0.0 – 1.0)
    
    Note over CT: Step 4: Flashbulb check
    CT->>FP: evaluate(zScore)
    FP-->>CT: flashbulb? → pin + max importance
    
    Note over CT: Step 5: Quantize
    CT->>SQ: encode(float[]) → byte[]
    
    Note over CT: Step 6: Build header
    CT->>CT: CognitiveHeader(timestamp, tags, importance, ...)
    
    Note over CT: Step 7: Route & write
    CT->>TR: write(type, header, quantized)
    TR-->>CT: byte offset
    
    Note over CT: Step 8: Index
    CT->>MI: register(id, location, text, source, tags)
    
    Note over CT: Step 9: WAL
    CT->>WAL: appendRemember(id, quantized)
    
    Note over CT: Step 10: Circadian check
    CT->>CT: triggerReflectIfDue()
```

> [!NOTE]
> When ingestion comes through the unified `IngestionPipeline` (e.g., file ingestion), embedding (step 1) is handled by the pipeline itself. `CognitiveIngestionTarget.ingest()` receives a pre-embedded vector and executes steps 2–9. When called via `SpectorMemory.remember()`, `CognitiveIngestionTarget.ingestCognitive()` handles embedding internally.

---

## Data Flow: Recall

The recall pipeline executes parallel tier scans using Virtual Threads:

```mermaid
sequenceDiagram
    participant App as Application
    participant RP as RecallPipeline
    participant EP as EmbeddingProvider
    participant PS as ProspectiveScheduler
    participant CT as ConcurrentTasks
    participant CS as CognitiveScorer
    participant SS as SuppressionSet
    participant HP as HabituationPenalty

    App->>RP: recall("query", options)
    
    Note over RP: Step 1: Embed query
    RP->>EP: embed("query")
    EP-->>RP: float[4096]
    
    Note over RP: Step 2: Prospective reminders
    RP->>PS: collectDue()
    PS-->>RP: due reminders
    
    Note over RP: Step 3: Parallel tier scanning
    RP->>CT: forkJoinAll(scanTasks)
    
    par Working Memory
        CT->>CS: score(workingSegment, ...)
    and Episodic Partition 1
        CT->>CS: score(partition1, ...)
    and Episodic Partition 2
        CT->>CS: score(partition2, ...)
    and Semantic
        CT->>CS: score(semanticSlab, ...)
    and Procedural
        CT->>CS: score(proceduralSegment, ...)
    end
    
    CS-->>RP: List<ScoredRecord>
    
    Note over RP: Step 4: Filter suppressed
    RP->>SS: isSuppressed(id)?
    
    Note over RP: Step 5: Habituation penalty
    RP->>HP: recordAndComputePenalty(id)
    
    Note over RP: Step 6: Sort & return top-K
    RP-->>App: List<CognitiveResult>
    
    Note over RP: Step 7: Async listeners (Virtual Thread)
    RP->>RP: notify(HebbianListener, LtpListener)
```

---

## Package Dependency Graph

```mermaid
graph LR
    SM[SpectorMemory<br/>Façade] --> CT[pipeline/<br/>CognitiveIngestionTarget]
    SM --> RP[pipeline/<br/>RecallPipeline]
    SM --> TR[cortex/<br/>TierRouter]
    SM --> MI[index/<br/>MemoryIndex]
    
    CT --> EP[embed-api/<br/>EmbeddingProvider]
    CT --> SQ[core/<br/>ScalarQuantizer]
    CT --> SD[dopamine/<br/>SurpriseDetector]
    CT --> TR
    CT --> MI
    CT --> WAL[sync/<br/>MemoryWal]
    
    RP --> EP
    RP --> CS[synapse/<br/>CognitiveScorer]
    RP --> TR
    RP --> MI
    RP --> SS[inhibition/<br/>SuppressionSet]
    RP --> HP[habituation/<br/>HabituationPenalty]
    
    CS --> SF[core/<br/>SimilarityFunction]
    CS --> DS[synapse/<br/>DecayStrategy]
    
    TR --> WM[cortex/<br/>WorkingMemoryStore]
    TR --> EM[cortex/<br/>EpisodicMemoryStore]
    TR --> SE[cortex/<br/>SemanticMemoryStore]
    TR --> PR[cortex/<br/>ProceduralMemoryStore]
    
    RP -.->|async| HL[pipeline/<br/>HebbianListener]
    RP -.->|async| LL[pipeline/<br/>LtpListener]
    
    style SM fill:#4a90d9,color:white
    style CS fill:#e74c3c,color:white
    style TR fill:#2ecc71,color:white
```

---

## The 32-Byte Cognitive Record

Every memory is stored as a fixed-size binary record in off-heap memory:

```
┌──────────────────────────────────────────────────────────┐
│                   32-Byte Synaptic Header                 │
├────────────┬──────────┬──────────┬────────┬──────────────┤
│ timestamp  │ synaptic │ exactNorm│ import │ centroidId   │
│ 8 bytes    │ tags     │ 4 bytes  │ ance   │ 4 bytes      │
│ (offset 0) │ 8 bytes  │ (off 16) │ 4 bytes│ (offset 24)  │
│            │ (off 8)  │          │(off 20)│              │
├────────────┴──────────┴──────────┴────────┼──────┬───┬───┤
│                                           │recall│val│flg│
│              (continued)                  │count │enc│s  │
│                                           │2B    │1B │1B │
│                                           │off 28│o30│o31│
├───────────────────────────────────────────┴──────┴───┴───┤
│              Quantized Vector (N bytes)                   │
│              INT8 values, 32-byte aligned                 │
└──────────────────────────────────────────────────────────┘
```

**Total record size** = 32 (header) + N (quantized vector bytes), aligned to 32 bytes.

At 768 dimensions (INT8): **32 + 768 = 800 bytes/memory** — 50,000 memories fit in 40 MB of off-heap RAM.

---

## Next Steps

- :material-lightning-bolt: [**6-Phase Scoring Pipeline**](scoring-pipeline.md) — the SIMD hot-loop that makes it fast
- :material-brain: [**Cortex — Tier Stores**](cortex.md) — the 4-tier memory architecture
- :material-memory: [**Off-Heap Panama Design**](panama-design.md) — zero-GC binary layout
