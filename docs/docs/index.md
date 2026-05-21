# ⚡ Welcome to Spector Search

> **Ultra-fast, SIMD-accelerated semantic search engine built on Java Vector API + modern JVM technologies.**

Welcome to the Spector Search wiki — your central hub for everything about the fastest pure-Java vector search engine on the planet. Whether you're building RAG pipelines, powering recommendation systems, or need sub-millisecond hybrid search with zero infrastructure, you're in the right place.

---

## 🔥 Why Spector Search?

| Metric | Value |
|--------|-------|
| ⚡ Vector Search Latency | **0.05 ms** avg @ 100K docs |
| 🔍 Keyword Search Latency | **0.60 ms** avg @ 100K docs |
| 🧬 Hybrid Search Latency | **0.47 ms** avg @ 100K docs |
| 🚀 Vector Throughput | **20K+ queries/sec** |
| 🧵 Concurrent Hybrid | **18K+ ops/sec** @ 16 threads |
| 🗜️ IVF-PQ Compression | **32× memory reduction** |
| ✅ Test Suite | **316+ tests**, all passing |
| 📦 Dependencies | **Zero** (JDK only) |

---

## 🗺️ Quick Navigation

### 🚀 Getting Started

| Page | Description |
|------|-------------|
| [Getting Started](getting-started/quickstart.md) | Build, run, and search in 5 minutes |
| [What is Spector Search](about.md) | Product overview, use cases, and comparisons |
| [FAQ](faq.md) | Common questions answered |

### 🏗️ Architecture & Concepts

| Page | Description |
|------|-------------|
| [Architecture Overview](architecture/overview.md) | Module diagram, data flow, threading model |
| [Core Concepts](architecture/core-concepts.md) | HNSW, IVF-PQ, BM25, RRF, SIMD deep-dives |
| [Distributed Mode](architecture/distributed-mode.md) | Clustering, sharding, and replication |
| [GPU Acceleration](architecture/gpu-acceleration.md) | CUDA setup and kernel details |
| [RAG Pipeline](architecture/rag-pipeline.md) | End-to-end retrieval-augmented generation |

### 📖 Reference

| Page | Description |
|------|-------------|
| [REST API Reference](api-reference/rest-endpoints.md) | All endpoints with curl examples |
| [Java SDK Guide](sdk-usage/java-client.md) | Programmatic usage (client + embedded) |
| [Spring AI Integration](sdk-usage/spring-ai.md) | Spring AI VectorStore adapter |
| [CLI Reference](cli-reference/spectorctl.md) | `spectorctl` commands |
| [Configuration Guide](configuration/parameters.md) | All parameters with tuning advice |

### ⚙️ Operations & Community

| Page | Description |
|------|-------------|
| [Performance Tuning](operations/performance-tuning.md) | Benchmarks and optimization strategies |
| [Contributing](operations/contributing.md) | Development setup and PR process |

---

## 💡 Highlights at a Glance

```mermaid
graph LR
    A[📄 Document] --> B[🧩 Chunking]
    B --> C[🧠 Embedding]
    C --> D[⚡ HNSW + BM25 Index]
    D --> E[🔍 Hybrid Search]
    E --> F[🎯 RRF Fusion]
    F --> G[🤖 LLM Re-ranking]
    G --> H[✨ Results]
```

!!! tip
    New here? Start with [Getting Started](getting-started/quickstart.md) to build and run your first search in under 5 minutes.

---

## 🌟 Project Stats

| | |
|---|---|
| **Language** | Java 25 |
| **License** | Apache 2.0 |
| **Modules** | 14 Maven modules |
| **Dependencies** | Zero (JDK only) |
| **SIMD** | AVX2 / AVX-512 / NEON |
| **GPU** | CUDA via Panama FFM |
| **Distributed** | gRPC fan-out + consistent hashing |

---

**Built with ⚡ by [Spectrayan](https://www.spectrayan.com/)** · [GitHub](https://github.com/spectrayan/spector-search) · [Apache 2.0 License](https://github.com/spectrayan/spector-search/blob/main/LICENSE)