# Importance Fusion (ICNU)

The **ICNU Importance Fusion** system computes a memory's importance score at ingestion time by blending four signals: **Interest**, **Challenge**, **Novelty**, and **Urgency**.

---

## The Problem

Without ICNU, importance is determined solely by the [Surprise Detector](dopamine.md) — a statistical outlier test based on how "surprising" a memory's embedding is relative to recent memories. This works well for detecting unusual information, but has blind spots:

- A memory about a user's **urgent deadline** might not be statistically surprising
- A memory about a **challenging technical problem** might have a common embedding
- A memory that the agent finds **interesting** has no way to signal that interest

ICNU adds three LLM-provided signals alongside the existing novelty signal to produce a richer importance score.

---

## The Formula

$$
\text{importance} = 0.05 + \left(\sum_{i \in \{I,C,N,U\}} w_i \cdot x_i\right) \times 9.95
$$

Where:

| Signal | Symbol | Range | Source |
|:---|:---:|:---:|:---|
| Interest | $x_I$ | [0, 1] | LLM-provided hint |
| Challenge | $x_C$ | [0, 1] | LLM-provided hint |
| Novelty | $x_N$ | [0, 1] | Computed from working memory scan |
| Urgency | $x_U$ | [0, 1] | LLM-provided hint |

The weights $w_i$ are configurable and auto-normalize to sum=1.0:

| Weight | Default | Rationale |
|:---|:---:|:---|
| $w_I$ (interest) | 0.30 | Agent engagement is a strong signal |
| $w_C$ (challenge) | 0.10 | Complexity is less important than novelty |
| $w_N$ (novelty) | 0.40 | Novelty is the strongest predictor of future usefulness |
| $w_U$ (urgency) | 0.20 | Time-sensitive information needs priority |

### Output Range

The formula maps to importance ∈ **[0.05, 10.0]**:

- **0.05** — All signals zero (routine, uninteresting, familiar, non-urgent)
- **10.0** — All signals maximal (interesting, challenging, novel, urgent)

---

## Novelty Computation

### How It Works

Novelty is computed using the **nearest-neighbor distance** in working memory — the minimum L2 distance between the incoming embedding and all existing working memory slots:

```java
float nearestDist = workingStore.nearestDistance(quantizedVector, mins, scales);
```

`nearestDistance()` performs a SIMD-accelerated scan of all working memory slots (~0.5ms for 100 slots × 768 dims) and returns the minimum L2 distance. A high distance means the memory is genuinely novel — it's far from everything the agent has seen recently.

### Normalization

The raw distance is normalized to [0, 1] via:

$$
\text{noveltyNorm} = \min\left(\frac{d_{\text{nearest}}}{2.0}, 1.0\right)
$$

Where 2.0 is a configurable threshold representing "maximally novel."

---

## IngestionHints

The LLM provides hints via the `IngestionHints` record:

```java
// At ingestion time
var hints = new IngestionHints(
    0.8f,   // interest: agent finds this very interesting
    0.3f,   // challenge: moderate complexity
    0.9f    // urgency: high time sensitivity
);

// Novelty is computed automatically from working memory
cognitiveTarget.ingestCognitive(id, text, type, tags, source, hints);
```

### Safety Features

- **Clamping**: All values are clamped to [0.0, 1.0] on construction
- **Fallback**: `IngestionHints.NONE` triggers novelty-only mode (backward compatible)
- **Gaming detection**: If all hints are maximal (I=1.0, C=1.0, U=1.0), a WARN is logged

### NONE Fallback

When no hints are provided (`IngestionHints.NONE`), the system falls back to `IcnuWeights.NOVELTY_ONLY` — importance is determined solely by nearest-neighbor distance, matching the pre-ICNU behavior.

---

## Configuration

### Fusion Weights

```java
var memory = SpectorMemory.builder()
    .icnuWeights(new IcnuWeights(0.4f, 0.1f, 0.3f, 0.2f))  // custom weights
    .build();
```

### Built-in Weight Presets

| Preset | I | C | N | U | Use Case |
|:---|:---:|:---:|:---:|:---:|:---|
| `DEFAULT` | 0.30 | 0.10 | 0.40 | 0.20 | General-purpose |
| `NOVELTY_ONLY` | 0.00 | 0.00 | 1.00 | 0.00 | Backward-compatible |

### Weight Auto-Normalization

Weights are automatically normalized on construction:

```java
var w = new IcnuWeights(1f, 1f, 1f, 1f);
// → interest=0.25, challenge=0.25, novelty=0.25, urgency=0.25
```

---

## Worked Example

Agent ingests: *"User has a production outage — database connections exhausted"*

| Signal | Value | Source |
|:---|:---:|:---|
| Interest | 0.7 | LLM hint — agent finds this relevant |
| Challenge | 0.5 | LLM hint — moderate complexity |
| Novelty | 0.9 | Working memory scan — nothing like this recently |
| Urgency | 1.0 | LLM hint — production outage |

With default weights:

$$
\text{weighted} = 0.30 \times 0.7 + 0.10 \times 0.5 + 0.40 \times 0.9 + 0.20 \times 1.0 = 0.81
$$

$$
\text{importance} = 0.05 + 0.81 \times 9.95 = \mathbf{8.11}
$$

This is a high-importance memory (8.11 / 10.0) — it will be prioritized in future recalls and resist time decay.

---

## MCP Integration

When using the MCP tools, importance fusion happens automatically if the ingestion tool provides hints:

```json
{
  "name": "core_memory_append",
  "arguments": {
    "id": "outage-2024-01",
    "text": "Production database connections exhausted at 2AM",
    "tags": "production,database,outage",
    "hints": {
      "interest": 0.7,
      "challenge": 0.5,
      "urgency": 1.0
    }
  }
}
```

!!! note "Backward Compatibility"
    The `hints` field is optional. When omitted, importance is computed using novelty-only mode — identical to the pre-ICNU behavior.
