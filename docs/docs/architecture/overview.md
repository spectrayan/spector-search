# 🏗️ Architecture Overview

> **Spector Search is a modular, JVM-native vector search engine organized as a Maven multi-module project.** This page covers the module structure, dependency graph, data flow, threading model, and memory architecture that make sub-millisecond search possible.

---

## 📦 Module Diagram

```mermaid
graph LR
    subgraph "🔬 Core Layer"
        core["spector-core<br/><i>SIMD kernels</i>"]
        commons["spector-commons<br/><i>Chunkers, tokenizer, readers</i>"]
    end

    subgraph "💾 Storage Layer"
        storage["spector-storage<br/><i>Panama MemorySegment stores</i>"]
    end

    subgraph "📊 Index Layer"
        index["spector-index<br/><i>HNSW + IVF-PQ + BM25</i>"]
    end

    subgraph "🔍 Query Layer"
        query["spector-query<br/><i>Hybrid orchestrator + RRF</i>"]
    end

    subgraph "🧠 Intelligence"
        embedapi["spector-embed-api<br/><i>EmbeddingProvider SPI</i>"]
        embedollama["spector-embed-ollama<br/><i>Ollama provider</i>"]
        gpu["spector-gpu<br/><i>Panama FFM + CUDA</i>"]
    end

    subgraph "⚡ Engine & Interfaces"
        engine["spector-engine<br/><i>Unified facade + lifecycle</i>"]
        server["spector-server<br/><i>REST API + virtual threads</i>"]
        cluster["spector-cluster<br/><i>Distributed gRPC search</i>"]
        cli["spector-cli<br/><i>spectorctl CLI</i>"]
        client["spector-client<br/><i>Java client SDK</i>"]
        spring["spector-spring<br/><i>Spring AI VectorStore</i>"]
    end

    subgraph "📈 Testing"
        bench["spector-bench<br/><i>JMH benchmarks</i>"]
    end
```

> [!NOTE]
> **Index sub-modules:** `hnsw/` (graph-based ANN), `ivf/` (inverted file + posting lists), `pq/` (product quantizer, K-Means++, ADC), `bm25/` (keyword scoring + analyzers)

---

## 🔗 Dependency Graph

```mermaid
graph TD
    server["🖥️ server"] --> cluster["🌐 cluster"]
    server --> engine["⚡ engine"]

    cluster --> engine
    engine --> query["🔍 query"]
    engine --> commons["📄 commons"]
    engine --> embedapi["🧬 embed-api"]
    engine --> gpu["🎮 gpu"]

    query --> index["📊 index"]
    index --> storage["💾 storage"]
    storage --> core["🔬 core"]

    gpu --> core
    gpu --> storage
```

**Dependency rules:**

| Path | Description |
|------|-------------|
| `cluster → engine → query → index → storage → core` | Main data path |
| `server → engine` | REST API entry point |
| `engine → gpu` | Optional GPU acceleration |
| `engine → commons` | Document processing |
| `engine → embed-api` | Embedding generation |
| `gpu → core, storage` | GPU operates on vectors and storage |

> [!IMPORTANT]
> No circular dependencies. Each module defines clear interfaces at its boundary. You can depend on any module independently.

---

## 📥 Data Flow: Ingest Path

```mermaid
sequenceDiagram
    participant Client as 👤 Client (REST/SDK/CLI)
    participant Engine as ⚡ SpectorEngine
    participant BM25 as 📝 BM25 Index
    participant HNSW as 🧠 HNSW Index
    participant SIMD as 🔬 SIMD Kernels
    participant Store as 💾 Storage (mmap)

    Client->>Engine: Ingest (ID, content, vector)
    par Parallel indexing via virtual threads
        Engine->>BM25: Tokenize + update inverted index
        Engine->>HNSW: Insert vector into graph
        HNSW->>SIMD: Distance computation
        Engine->>Store: Persist vector (zero-copy mmap)
    end
    Store-->>Client: ✅ Indexed
```

1. **Engine** receives the document (ID, content, vector)
2. **BM25 Index** tokenizes content and updates the inverted index
3. **HNSW Index** inserts the vector into the graph using SIMD distance kernels
4. **Storage** persists the raw vector to a memory-mapped file (zero-copy)
5. If IVF-PQ is configured, the vector is also added to the IVF partition

---

## 🔍 Data Flow: Search Path

```mermaid
sequenceDiagram
    participant Client as 👤 Client
    participant Engine as ⚡ SpectorEngine
    participant QB as 🧭 Query Builder
    participant BM25 as 📝 BM25 Search
    participant HNSW as 🧠 HNSW Search
    participant RRF as 🧬 RRF Fusion
    participant LLM as 🤖 LLM Reranker

    Client->>Engine: Search (text + vector + topK)
    Engine->>QB: Auto-detect mode
    Note over QB: text only → KEYWORD<br/>vector only → VECTOR<br/>both → HYBRID
    par Parallel search on virtual threads
        QB->>BM25: Keyword search
        QB->>HNSW: Vector search
    end
    BM25->>RRF: Ranked results
    HNSW->>RRF: Ranked results
    RRF->>LLM: Fused top candidates
    LLM-->>Client: ✨ Final ranked results
```

1. **Query Builder** determines search mode from provided fields
2. **BM25** and **HNSW** searches run in parallel on virtual threads
3. **RRF Fusion** merges both ranked lists using `1/(k + rank)` scoring
4. Optional **LLM Reranker** rescores top candidates via Ollama

---

## 🧵 Threading Model: Virtual Threads

Spector Search is designed from the ground up for Java virtual threads:

> [!TIP]
> **No `synchronized` blocks** anywhere in the codebase. All coordination uses `ReentrantLock` to avoid virtual thread pinning.

| Operation | Threading Strategy |
|-----------|-------------------|
| REST request handling | One virtual thread per request |
| Hybrid search | Parallel BM25 + HNSW via `StructuredTaskScope` |
| Bulk ingest | Virtual thread per document |
| Embedding generation | Batched across virtual threads |
| HNSW construction (>10K) | Virtual threads per core for parallel insertion |
| Distributed fan-out | Virtual thread per shard query |

### 📈 Scaling Results

At 50K docs with hybrid search:

| Virtual Threads | Throughput | Scaling |
|-----------------|-----------|---------|
| 1 | 4,108 ops/s | 1.0× |
| 4 | 12,344 ops/s | **3.0×** |
| 8 | 17,628 ops/s | **4.3×** |
| 16 | 18,324 ops/s | **4.5×** |

---

## 💾 Memory Model: Panama Off-Heap

All vector data lives off-heap using the Panama Foreign Function & Memory API:

```mermaid
graph TB
    subgraph "☕ JVM Heap (minimal)"
        HG["HNSW Graph<br/>(adjacency lists)"]
        BM["BM25 Index<br/>(inverted index)"]
        ES["Engine State<br/>(config, lifecycle)"]
    end

    subgraph "🧊 Off-Heap (Panama MemorySegment)"
        VS["Vector Store<br/>Contiguous float32, SIMD-aligned<br/>Zero-copy reads, no GC pressure"]
        QS["Quantized Store<br/>INT8 or PQ codes"]
        GM["GPU Device Memory<br/>CUDA via FFM"]
    end

    HG -.-> VS
    BM -.-> VS
    ES -.-> QS
    ES -.-> GM
```

**Benefits:**
- ✅ **Zero GC pressure** — Vectors never touch the garbage collector
- ✅ **Instant startup** — Memory-mapped files load via `mmap` syscall, no deserialization
- ✅ **SIMD-friendly layout** — Contiguous float32 arrays ready for Vector API operations
- ✅ **Explicit lifecycle** — `Arena`-scoped memory with deterministic cleanup
- ✅ **Memory efficiency** — Store billions of vectors limited only by disk/address space

### 📊 Storage Types

| Store | Location | Use Case |
|-------|----------|----------|
| `InMemoryVectorStore` | Off-heap (Arena) | Development, small datasets |
| `MmapVectorStore` | Memory-mapped file | Production, persistence |
| `QuantizedVectorStore` | Off-heap (INT8) | Memory-constrained deployments |
| `IvfPqStore` | Off-heap (PQ codes) | Billion-scale (32× compression) |

---

## 🌐 API Layer

```mermaid
graph TD
    subgraph "🖥️ Javalin Server (Virtual Threads)"
        CORS["CORS Filter"]
        Auth["Auth Filter"]
        JSON["JSON Codec"]
        Routes["Route Handlers<br/>/health  /api/v1/ingest<br/>/api/v1/search  /api/v1/rag<br/>/api/v1/status  /api/v1/metrics"]
    end
    
    CORS --> Auth --> JSON --> Routes
    Routes --> Engine["⚡ SpectorEngine"]
```

Every request runs on its own virtual thread — no thread pool sizing, no blocking concerns. The server can handle thousands of concurrent connections with minimal resource consumption.

---

## 🔗 See Also

- [Core Concepts](core-concepts.md) — Algorithms and data structures in detail
- [Distributed Mode](distributed-mode.md) — Multi-node clustering architecture
- [GPU Acceleration](gpu-acceleration.md) — CUDA kernel integration via Panama
- [Performance Tuning](../operations/performance-tuning.md) — Optimizing for your workload
