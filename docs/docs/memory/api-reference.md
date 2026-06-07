---
title: API Reference
description: "Complete API reference for SpectorMemory, RecallOptions, CognitiveResult, and related types."
---

# 📖 API Reference

---

## SpectorMemory

The main façade for all cognitive memory operations.

### Builder

```java
SpectorMemory memory = SpectorMemory.builder()
    .dimensions(int)                        // Vector dimensionality (required)
    .embeddingProvider(EmbeddingProvider)    // Embedding provider (required)
    .workingCapacity(int)                   // Working memory slots (default: 100)
    .episodicPartitionCapacity(int)         // Records per episodic partition (default: 10,000)
    .nodesPerPartition(int)                 // Records per semantic partition file (default: 10,000)
    .semanticCapacity(int)                  // Single-file semantic capacity (default: 5,000)
    .proceduralCapacity(int)                // Procedural memory slots (default: 500)
    .quantizer(ScalarQuantizer)             // Custom quantizer (default: identity)
    .persistenceDir(Path)                   // Episodic mmap directory (default: temp dir)
    .build();
```

### Core Methods

| Method | Return Type | Description |
|---|---|---|
| `remember(id, text, type, source, tags...)` | `CompletableFuture<Void>` | Async ingestion — embeds, quantizes, stores, indexes |
| `recall(queryText, options)` | `List<CognitiveResult>` | Parallel SIMD-accelerated recall with cognitive scoring |
| `forget(id)` | `void` | Tombstones a memory (permanent, excluded from all scans) |
| `suppress(id, reason)` | `void` | Suppresses from recall results (reversible) |
| `unsuppress(id)` | `void` | Removes suppression |
| `whyNot(memoryId, query, options)` | `WhyNotExplanation` | Explains why a memory was not recalled |
| `reflect()` | `ReflectReport` | Triggers sleep consolidation cycle |
| `introspect(topic)` | `MemoryInsight` | Metamemory self-analysis on a topic |
| `totalMemories()` | `int` | Total record count across all tiers |
| `close()` | `void` | Releases all off-heap memory and file handles |

---

## RecallOptions

Builder for recall query configuration.

```java
RecallOptions options = RecallOptions.builder()
    .topK(int)                              // Max results (default: 10)
    .synapticFilter(String... tags)         // Bloom filter pre-screen
    .minImportance(float)                   // Minimum importance [0.0-1.0] (default: 0.0)
    .memoryTypes(MemoryType... types)       // Tier filter (default: all)
    .minValence(byte)                       // Min emotional valence (default: -128)
    .maxValence(byte)                       // Max emotional valence (default: +127)
    .alpha(float)                           // Similarity weight (default: 0.6)
    .beta(float)                            // Importance × decay weight (default: 0.4)
    .build();
```

### Default Options

```java
RecallOptions.DEFAULT  // topK=10, no filters, alpha=0.6, beta=0.4
```

### Scoring Formula

$$\text{FinalScore} = \alpha \cdot \text{Similarity} + \beta \cdot \text{Importance} \cdot \text{Decay}$$

Where:

- **Similarity** = `1 / (1 + L2_distance)` — semantic relevance
- **Importance** = `[0.0 - 1.0]` — computed by SurpriseDetector at ingestion
- **Decay** = precomputed bucket lookup based on memory age

---

## CognitiveResult

Immutable record returned by `recall()`:

```java
public record CognitiveResult(
    String id,                // Unique memory identifier
    String text,              // Raw text content
    float score,              // Final cognitive score (after habituation)
    float importance,         // Original importance at ingestion
    float ageDays,            // Age in fractional days
    short recallCount,        // Times previously recalled
    byte valence,             // Emotional coloring [-128 to +127]
    MemoryType memoryType,    // Cognitive tier (WORKING/EPISODIC/SEMANTIC/PROCEDURAL)
    MemorySource source,      // Provenance (USER_STATED/OBSERVED/PROCEDURAL/...)
    String[] synapticTags,    // Decoded tag labels
    float decayFactor,        // Current temporal decay multiplier
    float ltpAdjustedDecay    // Decay after reconsolidation adjustment
) {}
```

---

## MemoryType

Enum representing the four cognitive tiers:

```java
public enum MemoryType {
    WORKING,      // Prefrontal Cortex — volatile circular buffer
    EPISODIC,     // Hippocampus — time-partitioned mmap
    SEMANTIC,     // Neocortex — permanent knowledge
    PROCEDURAL    // Basal Ganglia — learned procedures
}
```

---

## MemorySource

Provenance tracking for memory origin:

```java
public enum MemorySource {
    USER_STATED,   // Explicit user input
    OBSERVED,      // System observation (logs, events)
    INFERRED,      // AI inference
    PROCEDURAL,    // Rule or procedure
    CONSOLIDATED   // Created by sleep consolidation (ReflectDaemon)
}
```

---

## SynapticTagEncoder

64-bit inline Bloom filter encoder:

```java
// Encode tags into a Bloom filter
long mask = SynapticTagEncoder.encode("java", "debugging", "performance");

// Check if a record matches (containment check)
long recordTags = layout.readSynapticTags(segment, offset);
boolean matches = (recordTags & mask) == mask;

// Match individual tag
boolean hasJava = SynapticTagEncoder.matches(recordTags, "java");
```

---

## CognitiveRecordLayout

Binary layout for the 64-byte header + quantized vector:

```java
CognitiveRecordLayout layout = new CognitiveRecordLayout(quantizedVecBytes);

// Record stride (header + vector)
int stride = layout.stride();            // e.g., 832 for 768-dim INT8

// Read/write header
CognitiveHeader header = layout.readHeader(segment, offset);
layout.writeHeader(segment, offset, header);

// Read individual fields
long tags = layout.readSynapticTags(segment, offset);
float importance = layout.readImportance(segment, offset);

// Merge tags (OR operation for co-activation)
layout.mergeSynapticTags(segment, offset, additionalTags);
```

### CognitiveHeader

```java
public record CognitiveHeader(
    long timestampMs,       // Unix epoch milliseconds
    long synapticTags,      // 64-bit Bloom filter
    float exactNorm,        // L2 norm of original float vector
    float importance,       // Cognitive importance [0.0 – 1.0]
    int centroidId,         // IVF centroid assignment
    short recallCount,      // Reconsolidation counter
    byte valence,           // Emotional coloring
    byte flags              // Bit flags: [0] tombstone, [1] pinned
) {}
```

---

## ReflectReport

Summary of a sleep consolidation cycle:

```java
public record ReflectReport(
    int partitionsProcessed,
    int memoriesConsolidated,
    int semanticMemoriesCreated,
    long durationMs
) {}
```

---

## EpisodicPartition

A single time-partitioned episodic memory file:

```java
// Access partition data
int count = partition.count();
int tombstoneCount = partition.tombstoneCount();
float tombstoneRatio = partition.tombstoneRatio();
PartitionState state = partition.state();
MemorySegment segment = partition.segment();
CognitiveRecordLayout layout = partition.layout();

// Lifecycle operations
partition.seal();                          // Prevent further writes
partition.setState(PartitionState.REFLECTABLE);
partition.force();                          // Flush to disk
partition.close();                          // Release resources
```

### PartitionState

```java
public enum PartitionState {
    ACTIVE,       // Accepting writes
    SEALED,       // Read-only, awaiting consolidation
    REFLECTABLE,  // Consolidation complete, eligible for pruning
    TOMBSTONED,   // High tombstone ratio, queued for compaction
    COMPACTED     // Rebuilt as dense partition
}
```

---

## Next Steps

- :material-rocket: [**Getting Started**](getting-started.md) — set up in 5 minutes
- :material-brain: [**Architecture**](architecture.md) — how it all fits together
- :material-speedometer: [**Performance**](performance.md) — benchmark results
