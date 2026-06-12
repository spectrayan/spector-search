# Skill: Cognitive Memory Development Standards

This skill defines the technical standards, architectural rules, and mathematical principles for developing, optimizing, and maintaining the **Spector Memory** subsystem (`spector-memory` module).

---

## 1. Trigger

Reference this skill whenever you are:
*   Modifying or adding a feature to the `spector-memory` module.
*   Writing or debugging neural pipelines (`pipeline/` package), cortex stores (`cortex/` package), or synaptic algorithms (`synapse/` or `hebbian/` packages).
*   Refactoring off-heap layouts, vector distance kernels, or decay strategies.
*   Working with memory consolidation, k-means clustering, or sleep cycles (`hippocampus/` package).

---

## 2. Core Concepts: The 4-Tier Cortex

Spector Memory models biological brain regions using four distinct storage tiers:

| Tier | Brain Analogy | Store Class | Backend | Retention Policy | Typical Use Cases |
|---|---|---|---|---|---|
| **Working** | Prefrontal Cortex | `WorkingMemoryStore` | Volatile circular buffer in-memory | LIFO / FIFO eviction | Active conversation context, prompt gating |
| **Episodic** | Hippocampus | `EpisodicMemoryStore` | Mapped off-heap files, partitioned by time | Linear temporal decay | Individual sensory logs, daily experiences |
| **Semantic** | Neocortex | `PartitionedSemanticStore` | Off-heap vector partitions, consolidated | Pinned / high-importance, no default decay | Core concepts, facts, generalized knowledge |
| **Procedural** | Basal Ganglia | `ProceduralMemoryStore` | Key-value associative memory | Explicit retrieval by function key | Executable schemas, AI tool calling rules |

### Architectural Rules for Cortex Stores
1.  **Polymorphic Dispatch**: All stores must implement the `TierStore` strategy interface. Any new storage store must register under `TierRouter.java`.
2.  **Thread Concurrency**: Stores must support parallel scans. Do NOT lock the entire store during search. Use read/write locks (`ReentrantReadWriteLock`) only for writes or deletions, allowing lock-free parallel reads.
3.  **Strict Isolation**: Cortex stores must remain decoupled from the retrieval engine. The ingestion and recall coordinates exist in the `pipeline/` package.

---

## 3. The 64-Byte Synaptic Header Layout

To ensure microsecond-level scanning and zero garbage collection pressure, Spector stores episodic and semantic memories in off-heap memory slices. Each record contains a **64-byte cache-line-aligned header** preceding the raw float embedding vector, which eliminates split-line reads during sequential scans.

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                 64-BYTE COGNITIVE HEADER                               │
├──────────────┬──────────────┬──────────────┬──────────────┬────────────────────────────┤
│ Version (1B) │ Flags (1B)   │ Valence (1B) │ Arousal (1B) │ Importance (4B float)      │
├──────────────┴──────────────┴──────────────┴──────────────┼────────────────────────────┤
│ Timestamp (8B long)                                       │ Agent Recall Count (4B int)│
├────────────────────────────┬──────────────────────────────┴────────────────────────────┤
│ Exact Norm (4B float)      │ Synaptic Tags Bloom Filter (8B long)                      │
├────────────────────────────┼──────────────────────────────┬────────────────────────────┤
│ Centroid ID (2B short)     │ Padding (2B)                 │ Storage Strength (4B float)│
├────────────────────────────┼──────────────────────────────┼────────────────────────────┤
│ Spector Recall Count (4B)  │ Reserved Float F1 (4B)       │ Last Auto LTP (8B long)    │
├────────────────────────────┴──────────────────────────────┴────────────────────────────┤
│ Reserved Long L1 (8B long)                                                             │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

### Synaptic Header Specification (`SynapticHeaderConstants.java`)

#### Core Fields (Bytes 0–31)
*   **Version (byte)**: Offset `0`. Header format version (always `1` for the active layout).
*   **Flags (byte)**: Offset `1`. Decision bitfield (tombstone, memory type, consolidated, pinned, resolved).
*   **Valence (byte)**: Offset `2`. Signed emotional valence value (-128 to +127).
*   **Arousal (byte)**: Offset `3`. Unsigned emotional intensity value (0 to 255).
*   **Importance (float)**: Offset `4`. Base significance score (4B-aligned).
*   **Timestamp (long)**: Offset `8`. Unix Epoch millisecond timestamp of memory creation (8B-aligned).
*   **Agent Recall Count (int)**: Offset `16`. Long-term potentiation reinforcement counter (4B-aligned).
*   **Exact Norm (float)**: Offset `20`. L2 norm used for cosine normalizations.
*   **Synaptic Tags (long)**: Offset `24`. 64-bit Bloom filter encoding 2-6 lowercase tag keywords (positioned at end of core for 128-bit growth).

#### Extended Fields (Bytes 32–63)
*   **Centroid ID (short)**: Offset `32`. IVF partition routing identifier.
*   **Padding (short)**: Offset `34`. Internal alignment spacing (`_pad0`).
*   **Storage Strength (float)**: Offset `36`. Two-factor memory strength tracking ($S_t$).
*   **Spector Recall Count (int)**: Offset `40`. Internal auto-LTP passive counter.
*   **Reserved Float (float)**: Offset `44`. Future floats container (`_reserved_f1`).
*   **Last Auto LTP (long)**: Offset `48`. Passive retrieval timestamp (8B-aligned).
*   **Reserved Long (long)**: Offset `56`. Future 128-bit upper Bloom tag upper half (`_reserved_l1`).

*   **Vector Float Array**: Offset `64` onwards. Raw unaligned float array (dimensions * 4 bytes).

---

## 4. The 6-Phase Fused Scoring Pipeline

Retrieval (`RecallPipeline.java`) must evaluate millions of memories in sub-millisecond times. This is done by fusing filters and distance scores into a single sequential loop sweep inside `CognitiveScorer.java`.

```
Query Vector ────────────────┐
                             ▼
Memory Record ──► [Phase 1: Tombstone Check] ──► [Phase 2: Synaptic Tag Bloom Gate]
                                                             │ Match?
                                                             ▼
[Phase 4: Importance/Decay Skip] ◄── [Phase 3: Valence Filter]
             │ Meets Min Threshold?
             ▼
[Phase 5: SIMD L2 Distance] ──► [Phase 6: Fused Scoring Formula] ──► Result
```

### In-Loop Verification Rules
Every phase of the scorer acts as a gate. **Never allocate memory** inside this pipeline loop.

1.  **Phase 1: Tombstone Check**
    *   Checks the active deletion/suppression set. If the record ID is tombstoned, exit early (1 CPU cycle).
2.  **Phase 2: Synaptic Tag Gating**
    *   Performs a bitwise AND on the query's synaptic tag filter mask against the record's 64-bit Bloom filter.
    *   `if ((queryMask & recordMask) != queryMask) continue;` (1 CPU cycle). Eliminates 99% of candidates before vector math.
3.  **Phase 3: Valence Filter**
    *   If the query specifies a positive, negative, or neutral emotional bias, check if `valence` falls within the target range.
4.  **Phase 4: Importance/Decay Gating**
    *   Calculate raw temporal decay: $Decay = e^{-\lambda \cdot (CurrentTime - RecordTimestamp)}$
    *   Calculate base score estimation: $Est = Importance \cdot Decay$.
    *   If $Est$ is less than the query's `minImportance` parameter, skip vector distance calculations.
5.  **Phase 5: SIMD L2 Distance**
    *   Compute the distance between the query vector and the record's off-heap vector slice.
    *   Uses Project Panama and `FloatVector.SPECIES_PREFERRED` to run AVX2/AVX-512 operations.
6.  **Phase 6: Fused Scoring Formula**
    *   The final cognitive score $S$ is computed as:
        $$S = \alpha \cdot Similarity(V_q, V_r) + \beta \cdot Importance \cdot Decay \cdot (1 + \gamma \cdot RecallCount)$$
    *   $\alpha$, $\beta$, and $\gamma$ are weights configured in the retrieval pipeline options.

---

## 5. Biologically-Inspired Logic Engines

Any developer working on Spector Memory must align with our biological engines:

### A. The Dopamine System (`SurpriseDetector.java`)
*   Calculates the surprise level of a new memory compared to the running statistical distribution of historical memory embeddings.
*   Uses **Welford's algorithm** for online calculation of running mean and variance without keeping all histories in memory.
*   If the Z-score of a memory is high (e.g., $Z > 3.0$), the Dopamine system spikes the record's `importance` score and triggers a **Flashbulb Policy** (retains memory directly in Semantic Cortex, bypassing Working/Episodic decay).

### B. Hebbian Learning (`HebbianGraph.java`)
*   "Neurons that fire together wire together."
*   Keeps track of synaptic tag co-activations across memories. If tag $A$ and tag $B$ are frequently recalled together in the same query session, strengthen the edge connection weight $W_{AB}$ in the Hebbian association graph.
*   Edges decay linearly during sleep cycles if not reinforced.

### C. The Hippocampus (`ReflectDaemon.java`)
*   Runs as a background daemon thread (virtual thread pinned to low-priority schedules).
*   During low-activity windows ("Sleep Cycles"), the daemon consolidates Episodic memory partitions:
    *   Applies **K-Means clustering** to episodic memories to find thematic semantic centers.
    *   Compresses multiple related episodic memories into a single, high-importance **Semantic Memory** record.
    *   Prunes tombstones and rebuilds off-heap memory blocks to avoid memory fragmentation.

---

## 6. Implementation Template

When creating a new memory-processing component, use this template for Panama off-heap integration:

```java
package com.spectrayan.spector.memory.synapse;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.memory.error.SpectorMemoryException;

/**
 * Standard off-heap memory processor layout.
 */
public class MemoryProcessor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MemoryProcessor.class);

    private final Arena arena;
    private final MemorySegment segment;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean closed = false;

    public MemoryProcessor(int capacityBytes) {
        // Shared arena allows concurrent read access from virtual thread pools
        this.arena = Arena.ofShared();
        try {
            this.segment = arena.allocate(capacityBytes, 64); // 64-byte alignment
        } catch (OutOfMemoryError e) {
            this.arena.close();
            throw new SpectorMemoryException("Allocation failed", e);
        }
    }

    public void processMemory(long offset, float value) {
        lock.lock();
        try {
            checkClosed();
            segment.set(ValueLayout.JAVA_FLOAT, offset, value);
        } finally {
            lock.unlock();
        }
    }

    private void checkClosed() {
        if (closed) {
            throw new SpectorMemoryException("Processor is closed");
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (!closed) {
                closed = true;
                arena.close(); // Automatically deallocates off-heap MemorySegment
                log.debug("Off-heap resources safely released.");
            }
        } finally {
            lock.unlock();
        }
    }
}
```
