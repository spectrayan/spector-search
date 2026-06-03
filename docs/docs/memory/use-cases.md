# ðŸŽ¯ Use Cases & Configuration Guide

Practical recipes for configuring Spector Memory across real-world scenarios. Each use case shows the **profile**, **knobs**, and **code** you need â€” both via the Java API and the MCP tool interface.

---

## Quick Reference: Which Profile?

| I want to... | Profile | Key Knobs |
|:---|:---|:---|
| General-purpose recall | `BALANCED` | Default â€” no config needed |
| Find creative connections | `EXPLORING` | `topK=20` for broader discovery |
| Debug an error | `DEBUGGING` | Negative valence filter auto-applied |
| Retrieve proven solutions | `RECALLING` | Positive valence filter auto-applied |
| Make a critical decision | `CRITICAL` | Importance-dominated scoring |
| Deep-dive one topic | `HYPERFOCUS` | `hyperfocusMask` required |
| Cross-domain brainstorm | `DIVERGENT` | `lateralMode=true` auto-applied |
| Retain encyclopedic detail | `SYSTEMATIZER` | Pins source episodes during consolidation |
| Hunt for threats | `PARANOID_SENTINEL` | Negative-only valence + threat alignment |
| Execute tasks precisely | `THE_EXECUTOR` | Heaviside Cliff + no lateral retrieval |
| Catch subtle signals | `HIGHLY_SENSITIVE` | Lower flashbulb threshold |
| Surface deep knowledge | `DEFAULT_MODE_NETWORK` | Skips Working + Episodic tiers |

---

## Use Case 1: Personal Assistant â€” Daily Journaling

**Scenario:** A user journals daily. The assistant should remember preferences, recall emotional memories when appropriate, and let transient details fade naturally.

### Ingestion

```java
// Important life event â€” high arousal ensures slow decay
memory.remember("mem-wedding", "Married Sarah on June 15, 2024 at the beach",
    MemoryType.EPISODIC, MemorySource.USER_STATED,
    IngestionHints.builder()
        .interest(1.0f).urgency(0.2f).challenge(0.1f)
        .valence((byte) 120)    // very positive
        .arousal((byte) 200)    // high arousal â†’ extreme decay resistance
        .build(),
    "family", "wedding", "sarah");

// Transient detail â€” will naturally decay
memory.remember("mem-coffee", "User wanted oat milk latte at 3pm",
    MemoryType.EPISODIC, MemorySource.OBSERVED,
    "preference", "coffee");

// Permanent fact â€” pin it
memory.remember("mem-daughter-bday", "Daughter Emma's birthday is March 15",
    MemoryType.SEMANTIC, MemorySource.USER_STATED,
    "family", "emma", "birthday");
```

### Recall: "Tell me about happy times"

**Java API:**

```java
var results = memory.recall("happy family memories",
    RecallOptions.builder()
        .profile(CognitiveProfile.RECALLING)  // positive valence only
        .topK(10)
        .synapticFilter("family")
        .build());
```

**MCP Tool:**

```json
{
  "name": "memory_recall",
  "arguments": {
    "query": "happy family memories",
    "profile": "RECALLING",
    "top_k": 10,
    "synaptic_filter": "family"
  }
}
```

**What happens:** `RECALLING` filters to valence â‰¥ +10, so only positive memories surface. The wedding (valence=+120, high arousal) will score highest because importance Ã— decay resistance is maximized. The coffee preference (low importance, no family tag) is invisible.

---

## Use Case 2: Coding Agent â€” Debugging a Production Issue

**Scenario:** An AI coding agent encounters a database timeout. It needs to recall past failures, not past successes.

**Java API:**

```java
var results = memory.recall("database connection timeout",
    RecallOptions.builder()
        .profile(CognitiveProfile.DEBUGGING)
        .synapticFilter("database", "error")
        .topK(5)
        .build());
```

**MCP Tool:**

```json
{
  "name": "memory_recall",
  "arguments": {
    "query": "database connection timeout",
    "profile": "DEBUGGING",
    "synaptic_filter": "database,error",
    "top_k": 5
  }
}
```

**What happens:** `DEBUGGING` sets valence â‰¤ -10, so only negative-outcome memories surface. The Bloom filter pre-screens for `database` + `error` tags. Recent failures score higher (Î²=0.7 importance-dominated).

### When the bug is fixed â€” reinforce the solution

```java
memory.reinforce("mem-timeout-fix", (byte) 80);  // positive outcome
```

Next time a similar timeout occurs, the fix memory has positive valence and will surface under `RECALLING` or `BALANCED`.

---

## Use Case 3: Security Auditor â€” Threat Hunting

**Scenario:** An SRE agent needs to surface only threats, vulnerabilities, and past incidents.

**Java API:**

```java
var results = memory.recall("deployment configuration",
    CognitiveProfile.PARANOID_SENTINEL);
```

**MCP Tool:**

```json
{
  "name": "memory_recall",
  "arguments": {
    "query": "deployment configuration",
    "profile": "PARANOID_SENTINEL"
  }
}
```

**What happens:** Only memories with negative valence surface. The query valence is set to -128 (maximum threat), triggering mood-congruent recall amplification. A `BALANCED` query for "deployment configuration" would return general docs â€” `PARANOID_SENTINEL` returns only the config-related incidents.

---

## Use Case 4: Research Agent â€” Deep-Dive with Hyperfocus

**Scenario:** Agent identifies "database deadlock" as the core topic and needs absolute depth, ignoring time decay.

**Java API:**

```java
var results = memory.recall("database deadlock resolution",
    RecallOptions.builder()
        .profile(CognitiveProfile.HYPERFOCUS)
        .hyperfocusMask("database", "deadlock")  // strict tag gate
        .topK(15)
        .build());
```

**MCP Tool:**

```json
{
  "name": "memory_recall",
  "arguments": {
    "query": "database deadlock resolution",
    "profile": "HYPERFOCUS",
    "synaptic_filter": "database,deadlock",
    "top_k": 15
  }
}
```

**What happens:** `HYPERFOCUS` sets Î±=1.0 (pure similarity), Î²=0.0 (no importance Ã— decay). Time ceases to matter â€” a 6-month-old deadlock analysis scores as if it was just written. The `hyperfocusMask` acts as a strict equality gate: only memories with **both** `database` AND `deadlock` tags pass.

---

## Use Case 5: Creative Agent â€” Cross-Domain Innovation

**Scenario:** Agent is stuck on a performance problem. Use lateral retrieval to find unexpected connections.

**Java API:**

```java
var results = memory.recall("optimize query throughput",
    RecallOptions.builder()
        .profile(CognitiveProfile.DIVERGENT)
        .lateralDistanceThreshold(1.5f)  // find distant memories
        .lateralMaxResults(5)            // blend 5 lateral results
        .topK(15)
        .build());
```

**MCP Tool:**

```json
{
  "name": "memory_recall",
  "arguments": {
    "query": "optimize query throughput",
    "profile": "DIVERGENT",
    "top_k": 15
  }
}
```

**What happens:** `DIVERGENT` enables lateral retrieval â€” the dual-heap system finds memories that are **tag-matched but semantically distant**. You might query about "query throughput" and get a memory about "batching HTTP requests" that shares the `performance` tag but is semantically unrelated. These cross-domain insights are the engine of innovation.

**Result metadata tells you which results are lateral:**

```java
for (CognitiveResult r : results) {
    if (r.isLateral()) {
        System.out.println("ðŸ’¡ Lateral insight: " + r.text());
    }
}
```

---

## Use Case 6: Task Runner â€” Precise Execution with Zeigarnik Effect

**Scenario:** A Devin-style agent executing a multi-step task. Needs strict matching and unresolved task tracking.

### Track open tasks

```java
memory.remember("task-deploy", "Deploy v2.3 to staging and run integration tests",
    MemoryType.WORKING, MemorySource.USER_STATED, "deploy", "task");
memory.markUnresolved("task-deploy");  // Zeigarnik: resists decay
```

### Recall with strict matching

**Java API:**

```java
var results = memory.recall("deployment tasks",
    CognitiveProfile.THE_EXECUTOR);
```

**What happens:** `THE_EXECUTOR` uses Heaviside Cliff scoring (strictness=10.0) â€” only near-exact matches survive. Lateral retrieval is disabled. The unresolved task (`task-deploy`) has its decay clamped to 0, so it floats to the top regardless of age.

### Complete the task

```java
memory.markResolved("task-deploy");  // Now decays normally
```

---

## Use Case 7: Read-Only Analysis â€” OBSERVE Mode

**Scenario:** You want to query memories without any side effects â€” no LTP reinforcement, no habituation updates, no Hebbian co-activation.

**Java API:**

```java
var results = memory.recall("project architecture",
    RecallOptions.builder()
        .recallMode(RecallMode.OBSERVE)
        .topK(10)
        .build());
// Same query always returns the same results â€” no state changes
```

**MCP Tool:**

```json
{
  "name": "memory_recall",
  "arguments": {
    "query": "project architecture",
    "recall_mode": "OBSERVE",
    "top_k": 10
  }
}
```

**When to use OBSERVE:**

- Automated testing / CI pipelines
- Monitoring dashboards
- Analytics / reporting queries
- Any read path where deterministic results matter

**When to use LEARN (default):**

- Interactive agent conversations (recall strengthens relevant memories)
- User-facing assistants (habituation prevents repetitive answers)

---

## Use Case 8: Debugging Why a Memory Was Missed

**Scenario:** You expected memory `mem-42` to appear in results for "database timeout" but it didn't.

**Java API:**

```java
WhyNotExplanation explanation = memory.whyNot(
    "mem-42", "database timeout",
    RecallOptions.builder().topK(5).build());

System.out.println(explanation.reason());   // OUTRANKED, SUPPRESSED, etc.
System.out.println(explanation.summary());   // Human-readable diagnosis
if (explanation.breakdown() != null) {
    System.out.println(explanation.breakdown().trace());  // Full score decomposition
}
```

**MCP Tool:**

```json
{
  "name": "memory_why_not",
  "arguments": {
    "memory_id": "mem-42",
    "query": "database timeout",
    "top_k": 5
  }
}
```

**Possible reasons:**

| Reason | What It Means | Fix |
|:---|:---|:---|
| `NOT_FOUND` | Memory ID doesn't exist | Check the ID |
| `TOMBSTONED` | Memory was deleted | It was `forget()`-ed |
| `SUPPRESSED` | Memory is in suppression set | Call `unsuppress("mem-42")` |
| `OUTRANKED` | Memory scored, but below topK cutoff | Increase `topK` or adjust profile |
| `FILTERED` | Eliminated by pre-filters | Check tags, valence, importance floor |

---

## System-Level Configuration

These knobs are set once when creating `DefaultSpectorMemory` and affect all operations.

### Memory Capacity

```java
DefaultSpectorMemory.builder()
    .dimensions(384)
    .workingCapacity(100)           // Working memory slots (default: 100)
    .episodicPartitionCapacity(10_000)  // Records per episodic partition
    .semanticCapacity(50_000)       // Semantic tier capacity
    .proceduralCapacity(10_000)     // Procedural tier capacity
    .build();
```

### Persistence

```java
DefaultSpectorMemory.builder()
    .persistenceMode(MemoryPersistenceMode.DISK)
    .persistence(Path.of("/data/spector-memory"))
    .persistWorkingMemory(true)     // Also persist working memory (default: false)
    .build();
```

### Importance & Surprise Detection

```java
DefaultSpectorMemory.builder()
    .surpriseWarmup(50)             // Memories before Z-score activates (default: 50)
    .flashbulbThreshold(3.0)        // Z-score threshold for flashbulb (default: 3.0)
    .valenceLearningRate(0.3f)      // EMA rate for valence updates (default: 0.3)
    .deduplicationRadius(0.15f)     // Cosine distance for interference dedup (default: 0.15)
    .build();
```

### Cognitive Graph

```java
DefaultSpectorMemory.builder()
    .hebbianGraphCapacity(10_000)   // Max nodes in Hebbian association graph
    .temporalChainCapacity(50_000)  // Max nodes in temporal causal chain
    .entityGraphCapacity(5_000)     // Max entities in entity-relationship graph
    .maxEntitiesPerMemory(5)        // Entities extracted per ingestion
    .maxRelationsPerMemory(10)      // Relations extracted per ingestion
    .temporalRetentionDays(7)       // Prune temporal links older than N days
    .graphScoringPolicy(GraphScoringPolicy.DEFAULT)  // Graph boost weights
    .build();
```

### Habituation & Inhibition

```java
DefaultSpectorMemory.builder()
    .inhibitionTtlMs(300_000)       // Inhibition-of-return window (default: 5 min)
    .inhibitionFloor(0.5f)          // Minimum penalty (default: 0.5)
    .build();
```

### Graph Scoring Weights

```java
GraphScoringPolicy policy = new GraphScoringPolicy(
    0.3f,   // causalBoostWeight     â€” STDP causal boost
    0.3f,   // hebbianBoostFactor    â€” spreading activation
    0.8f,   // temporalForwardFactor â€” forward chain attenuation
    0.7f,   // temporalBackwardFactor â€” backward chain attenuation
    0.25f,  // entityHopAttenuation  â€” per-hop decay for entity traversal
    2,      // hebbianMaxDepth       â€” max hops in Hebbian graph
    3,      // temporalMaxHops       â€” max hops in temporal chain
    2       // entityMaxHops         â€” max hops in entity BFS
);

DefaultSpectorMemory.builder()
    .graphScoringPolicy(policy)
    .build();
```

---

## MCP Tool Reference â€” Quick List

| Tool | Purpose |
|:---|:---|
| `memory_recall` | Cross-tier fused recall with profiles, filters, confidence band |
| `memory_remember` | Ingest a new memory with type, source, and tags |
| `memory_scratchpad` | Store ephemeral text in working memory |
| `memory_reinforce` | Report positive/negative outcome for a memory |
| `memory_forget` | Tombstone a memory (logical deletion) |
| `memory_suppress` | Suppress a memory from future recall |
| `memory_introspect` | Metamemory self-analysis for a topic |
| `memory_resolve` | Mark a task as resolved (Zeigarnik Effect) |
| `memory_reminder` | Schedule a prospective memory reminder |
| `memory_status` | System stats (counts, tier usage, graph sizes) |
| `memory_why_not` | Diagnose why a specific memory was NOT recalled |

---

## What's Next

- [Cognitive Profiles](cognitive-profiles.md) â€” Deep dive on all 12 profiles with biological analogs
- [Scoring Pipeline](scoring-pipeline.md) â€” The 6-phase SIMD scoring engine
- [Hebbian Association](hebbian.md) â€” Co-activation learning and spreading activation
- [Lateral Retrieval](lateral-retrieval.md) â€” Cross-domain dual-heap mechanics
- [API Reference](api-reference.md) â€” Full Java API documentation

