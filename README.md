<p align="center">
  <img src="docs/docs/assets/spector-logo-full.png" alt="Spector" width="600" />
</p>

<p align="center">
  <strong>The Zero-Overhead, Agent-Ready AI Memory Backbone.</strong>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg?style=for-the-badge" alt="License" /></a>
  <a href="https://openjdk.org/"><img src="https://img.shields.io/badge/Java-25-orange.svg?style=for-the-badge" alt="Java" /></a>
  <a href="https://github.com/spectrayan/spector/actions"><img src="https://img.shields.io/github/actions/workflow/status/spectrayan/spector/ci.yml?branch=main&style=for-the-badge" alt="Build" /></a>
  <a href="spector-mcp/"><img src="https://img.shields.io/badge/MCP-Agent_Ready-blueviolet.svg?style=for-the-badge" alt="MCP" /></a>
  <a href="https://spectrayan.github.io/spector/"><img src="https://img.shields.io/badge/Docs-MkDocs-blue?logo=materialformkdocs&style=for-the-badge" alt="Docs" /></a>
  <a href="https://deepwiki.com/spectrayan/spector"><img src="https://img.shields.io/badge/DeepWiki-spectrayan%2Fspector-blue?style=for-the-badge" alt="DeepWiki" /></a>
</p>

---

Legacy search engines bolted vectors onto text databases. **Spector** is designed from the ground up for modern AI — leveraging Java Project Panama to achieve C++ bare-metal SIMD speeds natively, with a built-in MCP server that turns any AI agent into a search-powered reasoning machine.

---

## 🧠 Cognitive Memory — AI Agents That Actually Remember

Spector Memory is a **biologically-inspired cognitive memory engine** that gives AI agents the ability to **remember**, **forget**, **consolidate**, and **associate** — with microsecond latency and zero garbage collection pressure.

| Capability | What it does |
|:---|:---|
| 🧠 **4-Tier Cortex** | Working → Episodic → Semantic → Procedural memory |
| ⚡ **0.13ms recall** at 1M memories | 15× faster than the 2ms target (vs. 50–200ms for Mem0/Letta/Zep) |
| 🔗 **Fused SIMD Scoring** | Similarity × importance × decay in a single pass — no truncation trap |
| 🛏️ **Sleep Consolidation** | Hippocampus-inspired pruning and partition rebuild |
| 😱 **Emotional Valence** | Amygdala-driven positive/negative/neutral tagging |
| 🚫 **Zero GC** | 100% off-heap Panama storage (≤0.01% overhead measured) |

> 📖 **[Full Cognitive Memory Documentation →](https://spectrayan.github.io/spector/memory/)**

---

## ✨ Features

- **🤖 Agent-Native (MCP)** — Built-in Model Context Protocol server with 13 tools (6 search + 7 cognitive memory).<br>　<sub>Claude Desktop · Cursor · autonomous agents · stdio transport · zero Python</sub>
- **⚡ SIMD-Accelerated** — Hardware vector math via Java Vector API (AVX2/AVX-512/NEON).<br>　<sub>88µs p50 search · 61K QPS · branchless kernels · masked tail handling</sub>
- **🧊 100% Off-Heap Panama** — Bypasses GC entirely. Maps raw disk bytes directly into SIMD registers.<br>　<sub>zero network tax · zero serialization tax · zero GC pressure</sub>
- **🗜️ SVASQ Quantization** — FWHT-rotated affine quantization. Float32 recall at INT8 memory sizes.<br>　<sub>SVASQ-8 (4×) · SVASQ-4 (6–8×) · IVF-PQ (32×) · 99.5%+ recall</sub>
- **🔍 Hybrid Search** — Semantic vector (HNSW) + keyword (BM25) via Reciprocal Rank Fusion.<br>　<sub>LLM re-ranking · auto-embed · bulk ingest · document chunking</sub>
- **📦 Embedded or Standalone** — Drop-in JAR (the "DuckDB of Vector DBs") or scale with REST/gRPC clustering.<br>　<sub>Spring AI integration · Java SDK · CLI · zero dependencies</sub>
- **🖥️ GPU + Distributed** — CUDA kernel loader via Panama FFM, gRPC fan-out with consistent hashing.<br>　<sub>CUDA · coordinator/shard · TLS · SSE streaming</sub>
- **🧠 Neural Dashboard** — Angular 21 real-time dashboard with 10+ live visualization cards.<br>　<sub>THREE.js · Canvas 2D · SSE · Micrometer metrics</sub>

---

## 📸 Demo

<details>
<summary>Cortex Neural Dashboard</summary>

<p align="center">
  <img src="docs/screenshots/spector-cortex-dashboard.png" alt="Spector Cortex Dashboard" width="800" />
</p>

</details>

---

## 🚀 Quick Start

**Prerequisites:** JDK 25+, Maven 3.9+

```bash
git clone https://github.com/spectrayan/spector.git
cd spector
mvn clean test                                        # Build & run all 685+ tests
mvn package -pl spector-dist -am -DskipTests          # Build the distribution JAR
```

**Start the MCP server** (for AI agents):

```bash
java --add-modules jdk.incubator.vector \
  --enable-native-access=ALL-UNNAMED --enable-preview \
  -jar spector-dist/target/spector.jar \
  --config spector.yml
```

**Claude Desktop config** — add to `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "spector": {
      "command": "java",
      "args": [
        "--add-modules", "jdk.incubator.vector",
        "--enable-native-access=ALL-UNNAMED",
        "--enable-preview",
        "-jar", "/path/to/spector-dist/target/spector.jar",
        "--config", "/path/to/spector.yml"
      ]
    }
  }
}
```

> 📖 **[Full Quick Start Guide →](https://spectrayan.github.io/spector/getting-started/quickstart/)** · **[Configuration Reference →](https://spectrayan.github.io/spector/configuration/parameters/)**

---

## 📊 Benchmarks (Highlights)

All numbers measured on Intel Core Ultra 9 285K, Java 25, AVX2 256-bit.

| Benchmark | Result | Notes |
|:---|:---|:---|
| Vector search p50 | **88–143µs** | 10K–100K docs, HNSW M=16 |
| Cognitive recall at 1M | **0.13ms p50** | 15× better than 2ms target |
| Peak QPS (16 threads) | **61,011** | Concurrent vectorSearch |
| GC overhead | **0.01%** | 1 pause / 100K searches |
| vs. Python MCP servers | **23–113× faster** | In-process SIMD, zero network |

> 📖 **[Full Benchmark Report →](https://spectrayan.github.io/spector/deep-dives/real-embedding-benchmarks/)** · **[Performance Tuning →](https://spectrayan.github.io/spector/operations/performance-tuning/)**

---

## 📖 Docs by Goal

| I want to... | Start here |
|:---|:---|
| **Use Spector** | [Quick Start](https://spectrayan.github.io/spector/getting-started/quickstart/) · [Installation](https://spectrayan.github.io/spector/getting-started/installation/) · [Configuration](https://spectrayan.github.io/spector/configuration/parameters/) |
| **Connect an AI agent** | [MCP Server Guide](https://spectrayan.github.io/spector/sdk-usage/mcp-server/) · [Claude Desktop Config](#claude-desktop-config) |
| **Add cognitive memory** | [Memory Overview](https://spectrayan.github.io/spector/memory/) · [Getting Started](https://spectrayan.github.io/spector/memory/getting-started/) · [Use Cases](https://spectrayan.github.io/spector/memory/use-cases/) |
| **Use the Java SDK** | [Java SDK Guide](https://spectrayan.github.io/spector/sdk-usage/java-client/) · [Spring AI Integration](https://spectrayan.github.io/spector/sdk-usage/spring-ai/) |
| **Understand the internals** | [Architecture Overview](https://spectrayan.github.io/spector/architecture/overview/) · [Core Concepts](https://spectrayan.github.io/spector/architecture/core-concepts/) · [Deep Dives](https://spectrayan.github.io/spector/deep-dives/svasq-deep-dive/) |
| **Contribute** | [Contributing Guide](CONTRIBUTING.md) · [Module Reference](https://spectrayan.github.io/spector/modules/) |
| **Run benchmarks** | [Benchmark Report](https://spectrayan.github.io/spector/deep-dives/real-embedding-benchmarks/) · [Performance Tuning](https://spectrayan.github.io/spector/operations/performance-tuning/) |

> 📖 **[Full Documentation →](https://spectrayan.github.io/spector/)**

---

## 🤝 Contributing

We welcome contributions of all kinds — code, docs, tests, benchmarks, and ideas!

- 🐛 **Found a bug?** → [Open an Issue](https://github.com/spectrayan/spector/issues/new?template=bug_report.md)
- 💡 **Have an idea?** → [Start a Discussion](https://github.com/spectrayan/spector/discussions)
- 🔧 **Want to contribute code?** → See [CONTRIBUTING.md](CONTRIBUTING.md)
- 🤖 **AI-assisted PRs welcome!**

> **Good first areas:** Documentation improvements, additional test coverage, new embedding provider implementations, CLI enhancements, and Spring AI adapter extensions.

---

## ⭐ Star History

[![Star History Chart](https://api.star-history.com/svg?repos=spectrayan/spector&type=Date)](https://star-history.com/#spectrayan/spector&Date)

---

## 📄 License

This repository uses a **split licensing model**:

- **`spector-memory`** — [Business Source License 1.1](spector-memory/LICENSE) (transitions to Apache 2.0 on May 27, 2030)
- **All other modules** — [Apache License 2.0](LICENSE)

For branding and trademark guidelines, see the [NOTICE](NOTICE) file.

## 🔒 Security

See [SECURITY.md](SECURITY.md) for our security policy and vulnerability reporting.

## 🙏 Acknowledgments

See [ACKNOWLEDGMENTS.md](ACKNOWLEDGMENTS.md) for credits to the cognitive science researchers, open-source frameworks, and AI coding tools that made Spector possible.

---

<p align="center"><strong>Built with ⚡ by <a href="https://www.spectrayan.com/">Spectrayan</a></strong></p>
