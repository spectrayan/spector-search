# Acknowledgments

Spector stands on the shoulders of decades of cognitive science research and modern AI tooling. This file credits the researchers, frameworks, and tools whose work shaped the project.

If you believe something here is mis-attributed or missing, please open an issue — it will be corrected promptly.

---

## Cognitive Science & Memory Research

Spector Memory's scoring pipeline is a hardware-optimized approximation of established cognitive models. We gratefully acknowledge the foundational research:

### ACT-R Architecture

- **John R. Anderson** — *Rules of the Mind* (1993). Spector's scoring formula is a simplified, SIMD-friendly adaptation of the ACT-R activation equation: base-level activation + spreading activation. Anderson's work on the power law of practice and memory retrieval is the theoretical backbone of our cognitive scorer.
- **John R. Anderson & Christian Lebiere** — *The Atomic Components of Thought* (1998). Extended ACT-R framework with detailed mechanisms for declarative memory retrieval that informed Spector's decay and recall-count reconsolidation design.

### Two-Factor Memory Theory

- **Robert A. Bjork & Elizabeth L. Bjork** — *A New Theory of Disuse and an Old Theory of Stimulus Fluctuation* (1992). The Bjork & Bjork Two-Factor model (retrieval strength vs. storage strength) is directly implemented in Spector's `TwoFactorConfig` and `StorageStrengthTracker`. The concept of *desirable difficulty* — that hard retrievals build stronger memories — drives our reconsolidation pipeline.

### Forgetting & Decay

- **Hermann Ebbinghaus** — *Über das Gedächtnis* (1885). The original forgetting curve experiments that established memory decay as a quantifiable phenomenon. Spector's 12-bucket power-law decay table is a descendant of Ebbinghaus's pioneering work.
- **John T. Wixted** — *The Psychology and Neuroscience of Forgetting* (2004). Demonstrated that power-law decay fits empirical forgetting data better than exponential decay — the key insight behind Spector's choice of `t^{-d}` over `e^{-λt}`.
- **Harry P. Bahrick** — *Semantic Memory Content in Permastore* (1984). The permastore observation (very old memories stabilize) informed Spector's configurable decay floor parameter.

### Hebbian Learning

- **Donald O. Hebb** — *The Organization of Behavior* (1949). "Neurons that fire together, wire together." Spector's `HebbianGraph` directly implements Hebb's co-activation principle as an off-heap association graph with STDP-inspired weight updates.

### Generative Agents

- **Joon Sung Park et al.** — *Generative Agents: Interactive Simulacra of Human Behavior* (UIST 2023, Stanford). The first demonstration that LLM-powered agents benefit from recency + importance + relevance scoring for memory. Spector's cognitive memory extends this with power-law decay, Two-Factor strengthening, and emotional valence.

### MemoryOS

- **Yifan Hu et al.** — *MemoryOS: Cognitive-Inspired Memory Architecture for AI Agents* (2025). Hierarchical knowledge graph approach to agent memory that informed our comparative analysis and roadmap.

---

## Open-Source Frameworks & Libraries

Spector is built on the Java ecosystem and leverages these key technologies:

| Technology | Usage | License |
|---|---|---|
| [Project Panama (FFM API)](https://openjdk.org/projects/panama/) | Off-heap memory management — zero-GC memory storage via `MemorySegment` and `Arena` | GPL-2.0 (OpenJDK) |
| [Java Vector API (jdk.incubator.vector)](https://openjdk.org/jeps/469) | SIMD-accelerated vector operations (AVX2/AVX-512/NEON) for search and scoring | GPL-2.0 (OpenJDK) |
| [Virtual Threads (Project Loom)](https://openjdk.org/jeps/444) | Lightweight concurrency for I/O-bound operations | GPL-2.0 (OpenJDK) |
| [Spring AI](https://spring.io/projects/spring-ai) | Spring Boot integration via `VectorStore` SPI | Apache-2.0 |
| [MkDocs Material](https://squidfunk.github.io/mkdocs-material/) | Documentation site generation | MIT |
| [Angular](https://angular.dev/) | Cortex Neural Dashboard (Angular 21) | MIT |
| [THREE.js](https://threejs.org/) | 3D neural network visualizations in Cortex Dashboard | MIT |

---

## AI Coding Agents & Development Tools

Spector's development was significantly accelerated by AI-powered coding agents. We acknowledge these tools:

- **[Antigravity](https://deepmind.google/)** (Google DeepMind) — AI coding agent used extensively for architecture design, documentation generation, code review, and test development.
- **[Claude](https://www.anthropic.com/claude)** (Anthropic) — AI assistant used for research synthesis, documentation writing, and cognitive science literature review.
- **[Kiro](https://kiro.dev/)** (Amazon) — AI-powered IDE used for code generation, refactoring, and development workflow automation.

---

## Inspiration

- **[OpenClaw](https://github.com/openclaw/openclaw)** — Documentation structure and README design patterns (Docs by Goal, contributor experience, badge styling).
- **[Odysseus](https://github.com/pewdiepie-archdaemon/odysseus)** — Concise README formatting, collapsible demo sections, and feature sub-tags.

---

## Full Academic References

1. Anderson, J.R. (1993). *Rules of the Mind*. Hillsdale, NJ: Erlbaum.
2. Anderson, J.R. & Lebiere, C. (1998). *The Atomic Components of Thought*. Mahwah, NJ: Erlbaum.
3. Bahrick, H.P. (1984). Semantic memory content in permastore. *Journal of Experimental Psychology: General*, 113(1), 1–29.
4. Bjork, R.A. & Bjork, E.L. (1992). A new theory of disuse and an old theory of stimulus fluctuation. In *From Learning Processes to Cognitive Processes: Essays in Honor of William K. Estes*, 2, 35–67.
5. Ebbinghaus, H. (1885). *Über das Gedächtnis: Untersuchungen zur experimentellen Psychologie*. Leipzig: Duncker & Humblot.
6. Hebb, D.O. (1949). *The Organization of Behavior*. New York: Wiley.
7. Hu, Y. et al. (2025). MemoryOS: Cognitive-Inspired Memory Architecture for AI Agents.
8. Park, J.S. et al. (2023). Generative Agents: Interactive Simulacra of Human Behavior. *UIST '23*.
9. Wixted, J.T. (2004). The psychology and neuroscience of forgetting. *Annual Review of Psychology*, 55, 235–269.
10. Wixted, J.T. & Ebbesen, E.B. (1991). On the form of forgetting. *Psychological Science*, 2(6), 409–415.

---

*If we've missed anyone whose work influenced Spector, please [open an issue](https://github.com/spectrayan/spector/issues) — we want to get this right.*
