# ðŸŒŸ What is Spector?

> **The Zero-Overhead, Agent-Ready AI Memory Backbone.**
>
> Legacy search engines bolted vectors onto text databases. Spector is designed from the ground up for modern AI â€” combining vector similarity, keyword search, and hybrid ranking in a single embeddable library with zero external dependencies. Connect any AI agent via the built-in MCP server, or embed directly in your application.

Spector is an open-source, high-performance search engine built entirely on modern Java 25. It's designed for developers who want sub-millisecond search, native AI agent integration, and zero infrastructure complexity. Drop in a JAR, write a few lines of code, and you have production-grade hybrid search with built-in agent support.

---

## ðŸŽ¯ What It Does

Spector indexes documents with their vector embeddings and text content, then retrieves them using multiple strategies â€” directly from AI agents or your application code:

```mermaid
graph LR
    subgraph Clients
        MCP["ðŸ¤– AI Agent (MCP)"]
        REST["ðŸŒ REST API"]
        SDK["ðŸ“¦ Java SDK"]
    end
    
    subgraph Search Modes
        A[Vector Search] --> D[Results]
        B[Keyword Search] --> D
        C[Hybrid Search] --> D
    end
    
    subgraph Engines
        A --> E[HNSW ANN]
        B --> F[BM25 Scoring]
        C --> E
        C --> F
        C --> G[RRF Fusion]
    end
    
    MCP --> A & B & C
    REST --> A & B & C
    SDK --> A & B & C
```

| Mode | How It Works | Best For |
|------|-------------|----------|
| **ðŸ§  Vector Search** | HNSW approximate nearest neighbor graphs | Semantic similarity |
| **ðŸ“ Keyword Search** | BM25 scoring with term frequency saturation | Exact term matching |
| **ðŸ§¬ Hybrid Search** | Combines both via Reciprocal Rank Fusion | Best-of-both-worlds |
| **ðŸ¤– RAG Pipeline** | Ingest â†’ chunk â†’ embed â†’ retrieve â†’ context assembly | LLM applications |
| **ðŸ›ï¸ SpectorIndex** | IVF-HNSW-SVASQ adaptive hybrid index | Scale + recall |

---

## ðŸ’Ž Key Differentiators

### ðŸ¤– Agent-Native (MCP Protocol)

Includes a built-in [Model Context Protocol](https://modelcontextprotocol.io/) server with 6 tools. AI agents connect directly via JSON-RPC â€” no Python frameworks, no network round-trips.

| Feature | Python Vector DB MCP | **Spector MCP** |
|:---|:---|:---|
| Search latency | 2â€“10ms | **88Âµs p50** (23â€“113Ã— faster) â€  |
| Network overhead | HTTP/gRPC round-trip | **Zero** (in-process) |
| Concurrent queries | Limited by Python GIL | **61,000 QPS** â€  |
| Dependencies | Python framework stack | **Single JAR** |

â€  *Measured. See [Benchmarks](../#-benchmarks).*

> [!TIP]
> See the [MCP Server Guide](../sdk-usage/mcp-server.md) to connect Claude Desktop, Cursor, or any MCP client in minutes.

### ðŸ“¦ Pure Java, Zero Dependencies

Unlike most vector databases that rely on C++, Rust, or Python bindings, Spector is 100% Java. It uses the JDK's own Vector API for SIMD acceleration â€” no JNI, no native libraries, no external infrastructure.

> [!TIP]
> Add the JAR to your classpath and you're done. No Docker, no clusters, no ops.

### ðŸš€ Modern JVM Technologies

| Technology | Purpose |
|-----------|---------| 
| Java Vector API | SIMD-accelerated math (AVX2/AVX-512/NEON) |
| Panama FFM | Zero-copy memory-mapped storage, GPU interop |
| Virtual Threads | Millions of concurrent operations without thread pools |
| Structured Concurrency | Safe parallel task management |

### âš¡ Sub-Millisecond at Scale

**HNSW** at 100K documents (128 dimensions, top-10, M=16, efSearch=64):

| Search Type | Average Latency | Throughput |
|-------------|----------------|------------|
| Vector | **0.13 ms** | 7,556 QPS |
| Keyword | **0.98 ms** | 1,019 QPS |
| Hybrid | **1.01 ms** | 994 QPS |

**SpectorIndex (IVF-HNSW-SVASQ)** at 10K documents (4096-dim real Qwen3 embeddings):

| Config | Average Latency | Throughput | Recall@10 |
|--------|----------------|------------|----------|
| nCentroids=128, nProbe=4 | **0.46 ms** | **2,173 QPS** | **1.0000** |
| nCentroids=64, nProbe=4 | **0.62 ms** | 1,601 QPS | **1.0000** |
| nCentroids=128, nProbe=16 | **1.26 ms** | 792 QPS | **1.0000** |

> [!NOTE]
> SpectorIndex achieves **perfect recall while searching only 3.1% of the data** (nProbe=4 out of 128 centroids). Ingestion is 28â€“160Ã— faster than standalone HNSW. Numbers measured on 24-core x86, AVX2, Java 25, ZGC with Qwen3-embedding real vectors. For comprehensive, multi-centroid sweeps and adaptive HNSW shard promotion benchmarks, see the dedicated [Large-Scale Real-Embedding Benchmarks page](deep-dives/real-embedding-benchmarks.md).

### ðŸ  Dual Deployment Modes

| Mode | Description | Best For |
|------|-------------|----------|
| **Embedded** | In-process library, zero network overhead | Microservices, desktop apps, edge |
| **Server** | REST API with CORS, auth, and metrics | Teams, multi-language clients |

### ðŸ—œï¸ Advanced Quantization (SVASQ + IVF-PQ)

Spector offers two quantization paths:

- **SVASQ (Vectorized Affine Scalar Quantization):** Uses the Fast Walsh-Hadamard Transform to spread variance before INT8 quantization, achieving **4Ã— compression with near-lossless recall** (~97â€“99.5%). Used inside SpectorIndex shards.
- **IVF-PQ (Product Quantization):** Provides **32Ã— memory compression** for billion-scale datasets.

> [!IMPORTANT]
> SVASQ gives INT8 the precision of INT12â€“16 by rotating vectors before quantization. See the [SVASQ Deep Dive](deep-dives/svasq-deep-dive.md) for the full theory.

---

## ðŸ“Š How Spector Compares

### Latency Comparison (100K docs, 128-dim, top-10)

| Engine | Language | Vector Avg | Vector P99 |
|--------|----------|-----------|-----------| 
| **âš¡ Spector** | **Java 25** | **0.13 ms** | **0.26 ms** |
| hnswlib | C++ | 0.1â€“0.5 ms | ~1 ms |
| FAISS | C++ | 0.2â€“0.8 ms | 1â€“2 ms |
| Lucene 9+ | Java | 1â€“5 ms | 5â€“10 ms |
| Elasticsearch 8+ | Java | 2â€“10 ms | 10â€“25 ms |
| Qdrant | Rust | 2â€“5 ms | 10â€“25 ms |
| Milvus | Go/C++ | 3â€“10 ms | 10â€“35 ms |

> [!NOTE]
> Spector's vector search latency is competitive with native C++ implementations (hnswlib, FAISS) for in-process workloads. Numbers for external systems are from published benchmarks and ann-benchmarks.com. Hardware and configuration differences apply â€” these are directional comparisons, not controlled A/B tests.

### Feature Comparison

| Feature | Spector | Elasticsearch | Qdrant | Milvus | hnswlib |
|---------|---------|--------------|--------|--------|---------| 
| **Deployment** | Embedded + Server | Cluster only | Server only | Cluster only | Embedded only |
| **MCP Server** | âœ… Built-in (6 tools) | âŒ | âŒ | âŒ | âŒ |
| **Hybrid Search** | âœ… RRF built-in | âœ… RRF | âœ… Sparse+Dense | âœ… RRF | âŒ |
| **Zero Dependencies** | âœ… JDK only | âŒ Heavy stack | âŒ Tokio runtime | âŒ etcd, MinIO, Pulsar | âœ… Header-only |
| **Virtual Threads** | âœ… Project Loom | âŒ Platform threads | N/A (Rust async) | N/A (Go goroutines) | N/A |
| **GPU Acceleration** | âœ… CUDA (Panama FFM) | âŒ | âœ… Vulkan (indexing) | âœ… CUDA (search + indexing) | âŒ |
| **Quantization** | âœ… Scalar INT8 + IVF-PQ | âœ… BBQ + Scalar + DiskBBQ (IVF) | âœ… Scalar + Binary | âœ… IVF-PQ + IVF-SQ | âŒ |
| **Re-ranking** | âœ… LLM via Ollama | âœ… Elastic Rerank + Inference API | âœ… FastEmbed / ColBERT | âœ… vLLM Ranker + Cross-encoder | âŒ |
| **Distributed** | âœ… gRPC fan-out | âœ… Built-in sharding | âœ… Raft consensus | âœ… gRPC + etcd | âŒ |
| **SIMD Acceleration** | âœ… Java Vector API | âœ… simdvec (Panama) | âœ… Native SIMD | âœ… AVX/NEON | âœ… AVX/SSE |

> [!NOTE]
> This comparison reflects publicly available information as of May 2025. Feature availability may vary by version and deployment mode. All products are actively evolving.

---

## ðŸ› ï¸ Use Cases

### ðŸ¤– Agentic AI Memory

Connect AI agents (Claude, Cursor, custom) directly to Spector via the built-in MCP server. The agent autonomously ingests documents, searches for relevant context, and retrieves information â€” all with zero Python glue-code. *"Point your LLM at Spector's MCP port, and it instantly has mathematically-perfect long-term memory."*

### ðŸ¤– Retrieval-Augmented Generation (RAG)

Ingest documents (PDF, HTML, Markdown), chunk them with token awareness, generate embeddings, and retrieve relevant context for LLM prompting â€” all through a single `/api/v1/rag` endpoint or the `engine_rag` MCP tool.

### ðŸ” Semantic Search Applications

Power product search, documentation search, code search, or any application where meaning matters more than exact keywords.

### ðŸ’¡ Recommendation Systems

Use vector similarity to find items similar to what users have engaged with. Sub-millisecond latency makes real-time recommendations practical.

### ðŸ¢ Hybrid Enterprise Search

Combine keyword precision (finding exact product SKUs, error codes) with semantic understanding (finding conceptually related documents).

### ðŸ“± Embedded Analytics

Drop Spector into existing Java applications without infrastructure changes. Perfect for desktop applications, microservices, or edge deployments.

---

## âœ… When to Choose Spector

> [!NOTE]
> **Choose Spector when:**
> - You want AI agents to autonomously search your data (MCP integration)
> - You want sub-millisecond hybrid search without infrastructure complexity
> - Your stack is Java/JVM and you want native integration
> - You need an embedded search library with server-mode option
> - You want GPU acceleration without leaving the JVM
> - Zero external dependencies matters to your deployment

> [!WARNING]
> **Consider alternatives when:**
> - You need a managed cloud service with zero ops
> - Your team primarily works in Python/Rust/Go
> - You need built-in ML model serving

---

## ðŸš€ Next Steps

- [Getting Started](getting-started/quickstart.md) â€” Build and run your first search in 5 minutes

- [MCP Server Guide](sdk-usage/mcp-server.md) â€” Connect an AI agent in 3 steps

- [Architecture Overview](architecture/overview.md) â€” Understand how it works under the hood

- [REST API Reference](api-reference/rest-endpoints.md) â€” Full API documentation

- [Core Concepts](architecture/core-concepts.md) â€” Deep dive into the algorithms
