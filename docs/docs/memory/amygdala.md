---
title: "Amygdala — Emotional Valence"
description: "How ValenceTracker adds emotional coloring to memories — enabling agents to recall by mood, sentiment, and outcome quality."
---

# 😱 Amygdala — Emotional Valence

> **Package**: `com.spectrayan.spector.memory.amygdala`
>
> **Biological Analog**: The **amygdala** is the brain's emotional processor. It assigns emotional significance to experiences — fear, joy, anger, relief — which profoundly influences how memories are encoded, stored, and retrieved. Emotionally charged memories are remembered more vividly and last longer.

---

## The Concept

Every memory in Spector carries a **valence score** — a single byte (`-128` to `+127`) representing its emotional coloring:

| Range | Meaning | Examples |
|---|---|---|
| `-128` to `-50` | **Strongly negative** | Critical errors, data loss, security breaches |
| `-50` to `-10` | **Mildly negative** | Warnings, slow performance, minor bugs |
| `-10` to `+10` | **Neutral** | Factual information, routine operations |
| `+10` to `+50` | **Mildly positive** | Successful deployments, optimizations |
| `+50` to `+127` | **Strongly positive** | Major breakthroughs, user praise, goals achieved |

---

## ValenceTracker

The `ValenceTracker` manages emotional coloring of memories:

```java
public final class ValenceTracker {
    
    /**
     * Computes valence from text content analysis.
     * Uses keyword-based sentiment detection with configurable weights.
     */
    public byte computeValence(String text, MemorySource source) {
        float score = 0f;
        
        // Source-based bias
        if (source == MemorySource.PROCEDURAL) score += 0.1f;  // Rules are slightly positive
        if (source == MemorySource.OBSERVED && containsError(text)) score -= 0.5f;
        
        // Content-based sentiment
        score += sentimentScore(text);
        
        // Clamp and convert to byte range
        return (byte) Math.max(-128, Math.min(127, (int)(score * 127)));
    }
}
```

---

## Valence-Filtered Recall

The most powerful use of valence is in **recall filtering**. The `RecallOptions` builder supports valence range filtering:

```java
// Recall only negative-outcome memories (for debugging)
List<CognitiveResult> errors = memory.recall("database connection",
    RecallOptions.builder()
        .topK(10)
        .maxValence((byte) -10)     // Only negative memories
        .build());

// Recall only positive outcomes (for best practices)
List<CognitiveResult> successes = memory.recall("deployment strategy",
    RecallOptions.builder()
        .topK(5)
        .minValence((byte) 10)      // Only positive memories
        .build());
```

### Phase 3 — Valence Filter in CognitiveScorer

Valence filtering happens at **Phase 3** of the scoring pipeline — before the expensive SIMD vector math:

```java
// Phase 3: Valence Filter (~2 cycles)
byte valence = segment.get(LAYOUT_VALENCE, offset + OFFSET_VALENCE);
if (valence < minValence || valence > maxValence) continue;
```

**Cost**: 2 CPU cycles — a single byte read and two comparisons. Records outside the valence range are eliminated before Phase 5's ~200-cycle SIMD computation.

---

## Use Cases

### 1. Debugging: "What Went Wrong?"

An agent can filter for negative-valence memories when debugging:

```java
// "Show me only memories associated with failures"
memory.recall("connection timeout",
    RecallOptions.builder()
        .maxValence((byte) -10)
        .synapticFilter("database", "error")
        .build());
```

### 2. Best Practices: "What Worked Well?"

```java
// "Show me successful approaches"
memory.recall("deployment strategy",
    RecallOptions.builder()
        .minValence((byte) 10)
        .synapticFilter("deployment")
        .build());
```

### 3. Balanced Recall: Full Emotional Range

By default, no valence filter is applied — the agent sees the full emotional spectrum. The valence still influences recall indirectly because the `FlashbulbPolicy` pins emotionally intense memories at higher importance.

---

## Storage

Valence is stored in the 64-byte synaptic header at **offset 2** as a single signed byte:

```
Offset 30: [1B valence] — signed byte [-128 to +127]
```

This costs exactly **1 byte per memory** — negligible overhead for a powerful filtering dimension.

---

## Next Steps

- :material-link: [**Hebbian — Association Learning**](hebbian.md) — "neurons that fire together wire together"
- :material-head-cog: [**Dopamine — Surprise Detection**](dopamine.md) — auto-importance scoring
- :material-lightning-bolt: [**6-Phase Scoring Pipeline**](scoring-pipeline.md) — where valence filtering happens
