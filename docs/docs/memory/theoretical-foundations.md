---
title: "Theoretical Foundations"
description: "How Spector Memory's scoring formula relates to ACT-R, Bjork's Two-Factor model, and the power law of forgetting — with an honest assessment of simplifications made for performance."
---

# 🎓 Theoretical Foundations

Spector Memory's cognitive scoring pipeline is a **simplified, hardware-optimized approximation** of established cognitive science models. This page maps each component of the scoring formula to its theoretical origin, documents the simplifications made for performance, and identifies where future work could close the gap.

---

## The Scoring Formula

Spector's core scoring formula (from [CognitiveScorer.java](https://github.com/spectrayan/spector)):

$$\text{score} = \alpha \cdot \text{similarity} + \beta \cdot \text{importance} \cdot \text{decay}(t) \cdot S^{0.3}$$

Where:

- $\alpha = 0.6$ — weight for context relevance (spreading activation)
- $\beta = 0.4$ — weight for base-level activation
- $\text{similarity}$ — cosine similarity between query and memory embeddings
- $\text{importance}$ — Z-score surprise detection + ICNU hints from the LLM
- $\text{decay}(t)$ — power-law temporal decay from 12-bucket lookup table
- $S^{0.3}$ — storage strength boost from Bjork's Two-Factor model

---

## ACT-R Lineage

### The Full ACT-R Activation Equation

Anderson's ACT-R architecture (1993, 1998) defines memory activation as:

$$A_i = \underbrace{B_i}_{\text{base-level}} + \underbrace{\sum_{j} W_j \cdot S_{ji}}_{\text{spreading activation}} + \underbrace{\epsilon}_{\text{noise}}$$

Where:

- **$B_i$ (Base-level activation)**: How accessible the memory is based on recency and frequency of use
- **$\sum_j W_j \cdot S_{ji}$ (Spreading activation)**: How much the current context activates this memory
- **$\epsilon$ (Noise)**: Stochastic variability in retrieval

The base-level activation is computed as:

$$B_i = \ln\left(\sum_{j=1}^{n} t_j^{-d}\right)$$

Where $t_1, t_2, \ldots, t_n$ are the times since each of the $n$ past retrievals of item $i$, and $d$ is the decay exponent (typically ~0.5 in ACT-R).

### What Spector Implements (Simplified ACT-R)

| ACT-R Component | Full ACT-R | Spector's Approximation | Why Simplified |
|---|---|---|---|
| **Base-level $B_i$** | $\ln(\sum t_j^{-d})$ — sums over ALL past recall timestamps | `importance × decay(bucket)` — single bucket lookup + recall-count reconsolidation | Storing per-recall timestamps would require variable-length off-heap records, breaking SIMD alignment. The bucket + reconsolidation approach captures the same principle (recent + frequent = stronger) in O(1). |
| **Decay function** | Continuous power law $t^{-d}$ computed per recall event | 12-bucket precomputed lookup table derived from $t^{-0.15}$ | `Math.pow()` costs ~150 cycles/vector — unacceptable in the SIMD hot loop at 1M memories. Bucketed lookup is ~7 cycles. |
| **Spreading activation** | $\sum_j W_j \cdot S_{ji}$ — weighted sum of source activations from current context | $\alpha \cdot \text{cosineSimilarity}(\vec{q}, \vec{m})$ — embedding similarity | Embedding similarity is a strong proxy for spreading activation. Both measure "how much does the current context activate this memory?" |
| **Noise $\epsilon$** | Logistic noise added to activation | Not implemented — determinism within a timestamp | Noise is unnecessary because temporal dynamics (decay, habituation, satiation) already produce non-deterministic behavior across queries. |
| **Recall history** | Stores every timestamp $t_1, t_2, \ldots, t_n$ | Stores only `recallCount` (uint16) | Full history would require variable-length records. `recallCount` + bit-shift reconsolidation captures the key insight: more recalls → slower forgetting. |

### The Gap: What We Lose

The primary simplification is in **base-level activation**. Full ACT-R's $\ln(\sum t_j^{-d})$ captures the *spacing effect* — memories recalled at spaced intervals are stronger than memories recalled in rapid succession. Spector's `recallCount`-based reconsolidation does not distinguish between 3 recalls in 1 minute and 3 recalls over 3 months.

**Planned Phase 4**: Implement recall-timestamp tracking using a compact ring buffer (last 8 recall timestamps per memory, stored in a fixed 64-byte slot). This would enable the full ACT-R base-level computation while maintaining SIMD alignment.

---

## Power Law of Forgetting

### The Research Progression

| Year | Researcher | Model | Key Finding |
|---|---|---|---|
| 1885 | Ebbinghaus[^13] | $R = e^{-\lambda t}$ | Forgetting follows a curve (originally fit as exponential) |
| 1991 | Wixted & Ebbesen | $R = a \cdot t^{-b}$ | Power law fits empirical data better than exponential |
| 2004 | Wixted[^15] | Power law + interference | Forgetting is driven by interference, not passive decay; power law is the correct functional form |
| 1984 | Bahrick[^22] | Permastore | Very old memories stabilize — forgetting curve flattens after years |
| 2023 | FSRS Algorithm | $R = (1 + t/S)^{-w}$ | Modern spaced repetition uses power-law curves, validated on millions of users |

### Why Power Law > Exponential

The exponential curve ($e^{-\lambda t}$) predicts that forgetting is essentially complete within weeks. Empirical data shows this is wrong — memories persist much longer than exponential models predict.

The power law ($t^{-d}$) matches observed human forgetting data because:

1. **Slow tail**: The power law has a "fat tail" — old memories decay very slowly, matching the permastore observation
2. **Initial rapid drop**: Recent memories are still forgotten quickly, consistent with short-term memory dynamics
3. **Scale invariance**: The same functional form works from seconds to decades

### Spector's Implementation

Spector uses a **precomputed 12-bucket lookup table** derived from the power law:

```
R(t) = a · t^{-d}    where d = 0.15 (configurable via DecayConfig)
```

Bucket values are computed at construction time by `DecayConfig.computeBuckets()` and stored as a static `float[]` array. At scoring time, the decay lookup is a single array access — `DECAY_BUCKETS[bucket]` — costing ~7 CPU cycles.

Three presets are available via `DecayConfig`:

| Preset | Exponent | Floor | Use Case |
|---|---|---|---|
| `DEFAULT` | d=0.15 | 0.10 | General-purpose agent memory |
| `SLOW_FORGET` | d=0.08 | 0.15 | Digital legacy, personal assistants |
| `FAST_FORGET` | d=0.30 | 0.05 | Chat assistants, ephemeral contexts |

---

## Two-Factor Memory (Bjork & Bjork, 1992)

### The Theory

Bjork & Bjork's New Theory of Disuse (1992)[^14] proposes that every memory has two independent strengths:

- **Retrieval Strength $R(t)$**: How easily the memory can be accessed *right now*. Decays with time.
- **Storage Strength $S(t)$**: How deeply the memory is encoded. Only increases through successful retrieval.

The key insight is **desirable difficulty**: when retrieval is hard (low $R$), successful recall produces the largest boost to $S$. This is why spaced repetition works — forcing difficult retrievals builds stronger long-term memories.

### Spector's Implementation

```
ΔS = sGain × (1 - R(t))     // max boost when retrieval is hard
S' = min(S + ΔS, sMax)       // bounded growth

Final score modifier: S^{sExponent}
```

Configured via `TwoFactorConfig`:

| Parameter | Default | Effect |
|---|---|---|
| `sGain` | 0.1 | Learning rate per retrieval |
| `sMax` | 5.0 | Maximum storage strength |
| `sExponent` | 0.3 | Score modifier: $S^{0.3}$ |

A memory with $S=5.0$ gets a $5^{0.3} = 1.62\times$ multiplier. The default $S=1.0$ has no effect ($1^{0.3} = 1.0$).

---

## Comparison With Other AI Memory Systems

| System | Scoring Model | Decay | Retrieval-Dependent Strengthening | Emotional Memory |
|---|---|---|---|---|
| **Spector Memory** | Simplified ACT-R (α·sim + β·imp·decay·S^0.3) | Power-law, 12 buckets, configurable | ✅ Two-Factor (Bjork) + reconsolidation | ✅ Valence + arousal |
| **Stanford Generative Agents** (Park et al., 2023)[^18] | Additive: recency + importance + relevance | Exponential ($e^{-\alpha \cdot \Delta t}$) | ❌ No | ❌ No |
| **Mem0** | Vector similarity | ❌ None | ❌ No | ❌ No |
| **Letta/MemGPT** | Agent-managed | ❌ None (agent decides) | ❌ No | ❌ No |
| **MemoryOS** (Hu et al., 2025)[^19] | Hierarchical knowledge graph | Not published | Not published | Not published |
| **Full ACT-R** (Anderson, 1993)[^16] | $B_i + \sum W_j S_{ji} + \epsilon$ | Power law over recall timestamps | ✅ Via base-level activation | ❌ No (not in standard ACT-R) |

### Key Differentiators

1. **Spector is the only system** that combines power-law decay, Two-Factor strengthening, AND emotional valence in a single scoring formula
2. **Stanford Generative Agents** uses additive scoring ($0.99^{\Delta hours}$ for recency) — effectively exponential decay, which drops to near-zero within weeks
3. **Full ACT-R** has the most theoretically rigorous base-level activation, but doesn't model emotional memory. Spector adds valence/arousal as an extension
4. **Mem0/Letta** have no temporal dynamics — every memory is equally accessible regardless of age

---

## References

[^13]: Ebbinghaus, H. (1885). *Über das Gedächtnis: Untersuchungen zur experimentellen Psychologie*. Leipzig: Duncker & Humblot.

[^14]: Bjork, R.A. & Bjork, E.L. (1992). A new theory of disuse and an old theory of stimulus fluctuation. In *From Learning Processes to Cognitive Processes: Essays in Honor of William K. Estes*, 2, 35–67.

[^15]: Wixted, J.T. (2004). The psychology and neuroscience of forgetting. *Annual Review of Psychology*, 55, 235–269.

[^16]: Anderson, J.R. (1993). *Rules of the Mind*. Hillsdale, NJ: Erlbaum.

[^17]: Anderson, J.R. & Lebiere, C. (1998). *The Atomic Components of Thought*. Mahwah, NJ: Erlbaum.

[^18]: Park, J.S. et al. (2023). Generative Agents: Interactive Simulacra of Human Behavior. *UIST '23*.

[^19]: Hu, Y. et al. (2025). MemoryOS: Cognitive-Inspired Memory Architecture for AI Agents.

[^22]: Bahrick, H.P. (1984). Semantic memory content in permastore. *JEP: General*, 113(1), 1–29.
