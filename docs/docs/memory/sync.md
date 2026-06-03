---
title: "Sync — Persistence & Replication"
description: "Write-Ahead Log for durability and CRDT merge strategy for distributed memory synchronization."
---

# 🔄 Sync — Persistence & Replication

> **Package**: `com.spectrayan.spector.memory.sync`
>
> **Biological Analog**: Memory consolidation doesn't happen in isolation. During sleep, the brain replays memories and transfers them between regions (hippocampus → neocortex). The sync package provides the infrastructure for **durable persistence** and **distributed memory merge**.

---

## MemoryWal — Write-Ahead Log

The `MemoryWal` provides crash-safe durability for cognitive memory operations:

```java
public final class MemoryWal implements AutoCloseable {

    /**
     * Appends a REMEMBER event to the WAL.
     */
    public void appendRemember(String id, MemoryType type, byte[] quantizedVec,
                                CognitiveHeader header, String text,
                                MemorySource source, String[] tags) { ... }

    /**
     * Appends a FORGET event to the WAL.
     */
    public void appendForget(String id) { ... }

    /**
     * Replays all WAL events to rebuild memory state after restart.
     */
    public void replay(WalEventHandler handler) { ... }

    /**
     * Returns the number of events in the WAL.
     */
    public long eventCount() { ... }

    /**
     * Returns the high-water mark (latest event offset).
     */
    public long highWaterMark() { ... }
}
```

**Two modes**:

| Mode | Storage | Use Case |
|---|---|---|
| **File-backed** | Append-only log file | Production — survives JVM restarts |
| **In-memory** | `ArrayList<WalEvent>` | Testing — fast, no disk I/O |

---

## CrdtMergeStrategy — Distributed Merge

For multi-agent or distributed deployments, the `CrdtMergeStrategy` resolves conflicts between divergent memory replicas using **Conflict-free Replicated Data Types (CRDTs)**:

```java
public final class CrdtMergeStrategy {

    /**
     * Merges two versions of the same memory record.
     *
     * CRDT merge rules:
     * - timestamp:    max(local, remote)     — Last-Write-Wins
     * - synapticTags: local | remote         — OR-merge (union)
     * - importance:   max(local, remote)     — Highest signal wins
     * - recallCount:  max(local, remote)     — Monotonic counter
     * - flags:        local | remote         — OR-merge (tombstone propagates)
     */
    public CognitiveHeader merge(CognitiveHeader local, CognitiveHeader remote) { ... }

    /**
     * Determines if a remote update should be applied.
     */
    public boolean shouldApply(CognitiveHeader local, CognitiveHeader remote) { ... }
}
```

**Key insight**: Synaptic tags use **bitwise OR** for merge — this is a natural CRDT (G-Set). Tags can only be added, never removed, which guarantees convergence without coordination.

---

## PartitionReplicator — Partition Snapshot Shipping

For clustered deployments, the `PartitionReplicator` handles file-level replication of semantic memory partitions:

```
Mode 1: WAL incremental (real-time, low latency)
  Primary → WAL event → Replica replays locally

Mode 2: Partition snapshot (post-compaction, bulk)
  Primary compacts partition 3 → ships .mem file → Replica downloads + swaps
```

**Key behaviors:**

| Event | Action |
|---|---|
| **Partition rolls** (becomes immutable) | Ship entire file to all replicas (one-time) |
| **Partition compacted** | Re-ship compacted file to all replicas |
| **New replica joins** | Full sync — ship all partition files |

Immutable partitions are shipped exactly once per replica. Only the active (mutable) partition requires WAL-based delta replication via the `ReplicationManager`.

---

## Next Steps

- :material-memory: [**Off-Heap Panama Design**](panama-design.md) — how persistence interacts with mmap
- :material-brain: [**Architecture**](architecture.md) — system overview
- :material-cog: [**Configuration**](../configuration/parameters.md) — cluster and partition settings
