# Focus Mode

Focus Mode is a specialized cognitive scoring strategy that simulates **sustained attention** — the ability to deeply concentrate on a single topic while filtering out irrelevant information.

Two profiles use focus-oriented mechanics: **HYPERFOCUS** (strict retrieval tunnel) and **SYSTEMATIZER** (lossless knowledge accumulation).

---

## HYPERFOCUS — Strict Retrieval Tunnel

When an agent activates Focus Mode, three things change in the [6-Phase Scoring Pipeline](scoring-pipeline.md):

### 1. Strict Tag Gate (Phase 2)

Only memories whose synaptic tags **exactly match** the focus mask pass through. This is a bitwise AND check:

```
if (recordTags & hyperfocusMask) != hyperfocusMask → SKIP
```

Unlike normal tag filtering (which accepts partial overlap), Focus Mode requires **all** focus tags to be present. This creates a narrow retrieval tunnel — only deeply relevant memories survive.

### 2. Zero Decay (Phase 4)

For tag-matched memories, the time decay factor is clamped to **1.0**:

```
adjustedBucket = 0  // no time decay for focused memories
```

This means old memories about the focused topic are treated as if they were just created. A 6-month-old memory about "database deadlocks" is equally accessible as one from today — as long as the tags match.

### 3. Post-Score Boost (Phase 6)

After the standard cognitive score is computed, focus-matched memories receive a configurable multiplier:

```
finalScore = score × hyperfocusBoost  // default: 1.5×
```

This ensures focused memories consistently outrank non-focused ones in the final result list.

### Configuration

```java
// Via profile preset
var results = memory.recall("database deadlock", CognitiveProfile.HYPERFOCUS);

// Via explicit options
var options = RecallOptions.builder()
    .profile(CognitiveProfile.HYPERFOCUS)
    .hyperfocusMask("database", "deadlock")  // Bloom filter encoded
    .hyperfocusBoost(2.0f)                   // custom boost
    .build();
```

### TTL and Self-Extension

Focus Mode is governed by `HyperfocusState`, a TTL-based state machine:

```java
// Activate focus for 30 minutes (default)
memory.hyperfocusState().activateFromTags("database", "deadlock");

// Agent extends focus when topic continues
memory.hyperfocusState().extend();         // adds another 30 minutes
memory.hyperfocusState().extend(60_000L);  // adds 1 minute

// Check status
memory.hyperfocusState().isActive();       // true
memory.hyperfocusState().remainingMs();    // milliseconds remaining

// Deactivate manually (or wait for TTL expiry)
memory.hyperfocusState().deactivate();
```

!!! tip "Agent Self-Extension"
    The `extend()` method is designed to be called by the agent itself. When the agent detects that the conversation is still focused on the same topic, it extends the TTL. When the topic naturally shifts, the TTL expires and Focus Mode deactivates automatically.

---

## SYSTEMATIZER — Lossless Knowledge Accumulation {#systemizer}

The Systematizer profile is designed for agents that need to build **comprehensive knowledge bases** — where losing detail during consolidation is unacceptable.

### Scoring Weights

| Parameter | Value | Rationale |
|:---|:---:|:---|
| α (similarity) | 0.3 | Low — details matter more than semantic similarity |
| β (importance) | 0.7 | High — prioritizes learned importance |

### Persistent Memory Pinning

The key feature of SYSTEMATIZER is **lossless consolidation**. During the [sleep consolidation cycle](hippocampus.md) (REM sleep), the system normally clusters similar episodic memories and promotes a summary to semantic memory. The source episodes may then be tombstoned.

With SYSTEMATIZER, source episodes are **pinned** — they receive the `FLAG_PINNED` bit in their record header, which prevents tombstoning:

```
Episodic: [mem-1] [mem-2] [mem-3] → Cluster → Semantic summary created
                                   ↓
                        Standard: mem-1, mem-2, mem-3 tombstoned
                        Systemizer: mem-1, mem-2, mem-3 PINNED ✓
```

### Quota Management

To prevent unbounded memory growth, pinning is governed by a configurable quota:

```java
var memory = SpectorMemory.builder()
    .pinSourceEpisodes(true)   // enable pinning
    .pinnedQuota(10_000)       // max pinned records (default)
    .build();
```

When the quota is reached, the oldest pinned records are eligible for tombstoning during the next consolidation cycle.

### Use Cases

- **Legal/compliance agents** that must retain all original evidence
- **Research agents** building encyclopedic knowledge bases
- **Audit trails** where summarization must not lose detail

---

## Performance Impact

!!! note "Zero Overhead When Disabled"
    All Focus Mode mechanics are gated by `hyperfocusMask != 0` in the hot loop. When no focus is active (the default), the code paths are identical to standard scoring — zero additional cost.

| Mechanic | Hot-Loop Cost | When Active |
|:---|:---|:---|
| Tag gate | ~2 cycles (bitwise AND) | Only when `hyperfocusMask != 0` |
| Decay clamp | ~1 cycle (conditional) | Only for tag-matched records |
| Boost multiply | ~1 cycle (float multiply) | Only for tag-matched records |
| Episode pinning | 0 (off hot loop) | During consolidation only |
