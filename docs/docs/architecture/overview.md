# ðŸ—ï¸ Architecture Overview

> **Spector is a modular, JVM-native AI memory backbone organized as a Maven multi-module project.** This page covers the module structure, dependency graph, data flow, threading model, and memory architecture that make sub-millisecond, agent-native search possible.

---

## ðŸ“¦ Module Diagram

```mermaid
graph LR
    subgraph "ðŸ”¬ Core Layer"
        core["spector-core<br/><i>SIMD kernels</i>"]
        commons["spector-commons<br/><i>Config, chunkers, tokenizer</i>"]
    end

    subgraph "ðŸ’¾ Storage Layer"
        storage["spector-storage<br/><i>Panama MemorySegment stores</i>"]
    end

    subgraph "ðŸ“Š Index Layer"
        index["spector-index<br/><i>HNSW + IVF-PQ + BM25</i>"]
    end

    subgraph "ðŸ” Query Layer"
        query["spector-query<br/><i>Hybrid orchestrator + RRF</i>"]
    end

    subgraph "ðŸ§  Intelligence"
        embedapi["spector-embed-api<br/><i>EmbeddingProvider SPI</i>"]
        embedollama["spector-embed-ollama<br/><i>Ollama provider</i>"]
        gpu["spector-gpu<br/><i>Panama FFM + CUDA</i>"]
    end

    subgraph "ðŸ“¥ Pipelines"
        ingestion["spector-ingestion<br/><i>Ingest orchestration</i>"]
        rag["spector-rag<br/><i>RAG pipeline</i>"]
    end

    subgraph "âš¡ Runtime & Interfaces"
        runtime["spector-runtime<br/><i>Unified context (engine + memory)</i>"]
        engine["spector-engine<br/><i>Search facade + lifecycle</i>"]
        node["spector-node<br/><i>Armeria: REST + gRPC + SSE + cluster</i>"]
        mcp["spector-mcp<br/><i>MCP Server â€” Agent-native</i>"]
        cli["spector-cli<br/><i>spectorctl CLI</i>"]
        client["spector-client<br/><i>Java client SDK</i>"]
        spring["spector-spring<br/><i>Spring AI VectorStore</i>"]
    end

    subgraph "ðŸ§  Cognitive Memory"
        memory["spector-memory<br/><i>Biologically-inspired agent memory</i>"]
    end

    subgraph "ðŸ“ˆ Distribution"
        bench["spector-bench<br/><i>JMH benchmarks</i>"]
        dist["spector-dist<br/><i>Single fat JAR</i>"]
    end
```

> [!NOTE]
> **Index sub-modules:** `hnsw/` (graph-based ANN), `ivf/` (inverted file + posting lists), `pq/` (product quantizer, K-Means++, ADC), `bm25/` (keyword scoring + analyzers)

---

## ðŸ”— Dependency Graph

```mermaid
graph TD
    node["ðŸŒ node"] --> runtime["âš¡ runtime"]
    node --> mcp["ðŸ¤– mcp"]
    node --> metrics["ðŸ“ˆ metrics"]
    mcp --> runtime
    mcp --> ingestion["ðŸ“¥ ingestion"]
    cli["ðŸ–¥ï¸ cli"] --> runtime
    cli --> client["ðŸ“¦ client"]

    runtime --> engine["âš¡ engine"]
    runtime --> memory["ðŸ§  memory"]
    runtime --> ingestion

    engine --> query["ðŸ” query"]
    engine --> rag["ðŸ¤– rag"]
    engine --> ingestion
    engine --> index["ðŸ“Š index"]
    engine --> storage["ðŸ’¾ storage"]
    engine --> embedapi["ðŸ§¬ embed-api"]
    engine -.-> gpu["ðŸŽ® gpu"]

    memory --> index
    memory --> storage
    memory --> ingestion
    memory --> embedapi
    memory --> core["ðŸ”¬ core"]

    metrics --> engine
    metrics --> memory

    ingestion --> config["âš™ï¸ config"]
    ingestion --> embedapi

    rag --> query
    rag --> index
    rag --> storage
    rag --> embedapi
    rag --> commons["ðŸ“„ commons"]

    query --> index
    query --> commons
    index --> storage
    index --> config
    storage --> config
    storage --> core
    config --> core

    embedapi --> commons
    gpu --> core
    gpu --> storage

    dist["ðŸ“¦ dist"] --> mcp
    dist --> cli
    dist --> runtime

    spring["ðŸŒ± spring"] --> engine
    spring --> memory
    spring --> metrics
    bench["ðŸ§ª bench"] --> engine
    bench --> memory
```

> **Legend:** Solid arrows = compile dependency. Dotted arrow (`gpu`) = optional dependency.

**Dependency rules:**

| Path | Description |
|------|-------------|
| `runtime â†’ engine + memory + ingestion` | Composition root â€” wires all subsystems |
| `cli â†’ runtime + client` | CLI with local batch (runtime) and remote (client) modes |
| `node â†’ runtime` | Unified Armeria node: REST + gRPC + cluster coordination |
| `mcp â†’ runtime + ingestion` | MCP agent entry point (in-process, zero network) |
| `engine â†’ ingestion` | `EngineIngestionTarget` implements `IngestionTarget` |
| `memory â†’ ingestion` | `CognitiveIngestionTarget` implements `IngestionTarget` |
| `engine â†’ rag` | RAG context assembly pipeline |
| `engine -.-> gpu` | Optional GPU acceleration |
| `memory â†’ index, storage, core, embed-api` | Cognitive memory (independent of engine) |
| `dist â†’ mcp + cli + runtime` | Fat JAR distribution |

!!! important
    **No circular dependencies.** `spector-memory` and `spector-engine` are **peers** â€” both depend on `spector-ingestion` for the `IngestionTarget` interface, but neither depends on the other. `SpectorRuntime` is the single composition root that wires them together.

---

## ðŸ“¥ Data Flow: Ingest Path

```mermaid
sequenceDiagram
    participant Client as ðŸ‘¤ Client (CLI/MCP/REST)
    participant Runtime as âš¡ SpectorRuntime
    participant Handler as ðŸ“¥ IngestionHandler
    participant Pipeline as ðŸ”„ IngestionPipeline
    participant Embed as ðŸ§  ParallelEmbeddingPipeline
    participant Target as ðŸ’¾ IngestionTarget
    participant Store as ðŸ’¾ Storage (mmap)

    Client->>Runtime: runtime.ingestion().ingest(dir, pattern)
    Runtime->>Handler: Pre-configured pipeline + target
    Handler->>Handler: FileDiscoveryService.discover()
    loop Each file
        Handler->>Pipeline: pipeline.ingest(id, content)
        Pipeline->>Pipeline: TextChunker.chunk(content)
        Pipeline->>Embed: embed(chunkTexts) via virtual threads
        Embed-->>Pipeline: List<vector>
        loop Each chunk
            Pipeline->>Target: target.ingest(id, text, vector)
            Target->>Store: VectorStore + VectorIndex + KeywordIndex
        end
    end
    Store-->>Client: âœ… Indexed
```

1. **Client** calls `runtime.ingestion().ingest()` â€” all entry points use this
2. **IngestionHandler** delegates to a pre-configured `IngestionPipeline`
3. **IngestionPipeline** handles chunking (from config) and parallel embedding
4. **IngestionTarget** receives pre-embedded chunks â€” `EngineIngestionTarget` for SEARCH, `CognitiveIngestionTarget` for MEMORY
5. Each target handles its own downstream storage (VectorStore/HNSW or Quantize/TierRoute/WAL)

> [!TIP]
> `FileDiscoveryService` can be used independently for file discovery without any engine or runtime dependency.

---

## ðŸ” Data Flow: Search Path

```mermaid
sequenceDiagram
    participant Client as ðŸ‘¤ Client
    participant Engine as âš¡ SpectorEngine
    participant QB as ðŸ§­ Query Builder
    participant BM25 as ðŸ“ BM25 Search
    participant HNSW as ðŸ§  HNSW Search
    participant RRF as ðŸ§¬ RRF Fusion
    participant LLM as ðŸ¤– LLM Reranker

    Client->>Engine: Search (text + vector + topK)
    Engine->>QB: Auto-detect mode
    Note over QB: text only â†’ KEYWORD<br/>vector only â†’ VECTOR<br/>both â†’ HYBRID
    par Parallel search on virtual threads
        QB->>BM25: Keyword search
        QB->>HNSW: Vector search
    end
    BM25->>RRF: Ranked results
    HNSW->>RRF: Ranked results
    RRF->>LLM: Fused top candidates
    LLM-->>Client: âœ¨ Final ranked results
```

1. **Query Builder** determines search mode from provided fields
2. **BM25** and **HNSW** searches run in parallel on virtual threads
3. **RRF Fusion** merges both ranked lists using `1/(k + rank)` scoring
4. Optional **LLM Reranker** rescores top candidates via Ollama

---

## ðŸ¤– Data Flow: MCP Agent Path

```mermaid
sequenceDiagram
    participant Agent as ðŸ¤– AI Agent (Claude/Cursor)
    participant MCP as ðŸ“¡ MCP Transport (stdio)
    participant Handler as ðŸ”§ McpToolHandler
    participant Runtime as âš¡ SpectorRuntime
    participant Engine as ðŸ”§ SpectorEngine
    participant SIMD as ðŸ”¬ SIMD Kernels

    Agent->>MCP: tools/call {"name": "engine_search", "arguments": {"query": "..."}}
    MCP->>Handler: EngineSearchTool.execute(runtime, args)
    Handler->>Runtime: runtime.search().query(text, topK)
    Runtime->>Engine: engine.search(query, topK)
    Engine->>SIMD: HNSW traversal (off-heap MemorySegment)
    SIMD-->>Engine: ScoredResult[] (~100Âµs)
    Engine-->>Runtime: SearchResponse
    Runtime-->>Handler: SpectorResult[]
    Handler-->>MCP: CallToolResult
    MCP-->>Agent: JSON-RPC response with search results
```

The MCP path routes through `SpectorRuntime` â€” the single composition root that holds both the search engine and optional cognitive memory. The MCP server wraps runtime handler calls with JSON-RPC transport. There is **zero network overhead** because everything runs in the same JVM process.

> [!TIP]
> For full MCP architecture details, tool schemas, and design patterns, see the dedicated [MCP Integration](mcp-integration.md) page.

---

## ðŸ§µ Threading Model: Virtual Threads

Spector is designed from the ground up for Java virtual threads:

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

### ðŸ“ˆ Scaling Results

At 50K docs with hybrid search (384-dim, production-realistic):

| Virtual Threads | Throughput | Scaling |
|-----------------|-----------|---------|
| 1 | 3,739 ops/s | 1.0Ã— |
| 4 | 10,317 ops/s | **2.8Ã—** |
| 8 | 11,812 ops/s | **3.2Ã—** |
| 16 | 14,022 ops/s | **3.7Ã—** |

> [!NOTE]
> Scaling depends on vector dimensions and workload type. 384-dim shows ~3.7Ã— at 16 threads due to higher per-query memory bandwidth. Individual HNSW queries are inherently sequential (graph traversal data dependencies) â€” scaling comes from concurrent queries sharing CPU cores.

---

## ðŸ’¾ Memory Model: Panama Off-Heap

All vector data lives off-heap using the Panama Foreign Function & Memory API:

```mermaid
graph TB
    subgraph "â˜• JVM Heap (minimal)"
        HG["HNSW Graph<br/>(adjacency lists)"]
        BM["BM25 Index<br/>(inverted index)"]
        ES["Engine State<br/>(config, lifecycle)"]
    end

    subgraph "ðŸ§Š Off-Heap (Panama MemorySegment)"
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

- âœ… **Zero GC pressure** â€” Vectors never touch the garbage collector

- âœ… **Instant startup** â€” Memory-mapped files load via `mmap` syscall, no deserialization

- âœ… **SIMD-friendly layout** â€” Contiguous float32 arrays ready for Vector API operations

- âœ… **Explicit lifecycle** â€” `Arena`-scoped memory with deterministic cleanup

- âœ… **Memory efficiency** â€” Store billions of vectors limited only by disk/address space

### ðŸ“Š Storage Types

| Store | Location | Use Case |
|-------|----------|----------|
| `InMemoryVectorStore` | Off-heap (Arena) | Development, small datasets |
| `MmapVectorStore` | Memory-mapped file | Production, persistence |
| `QuantizedVectorStore` | Off-heap (INT8) | Memory-constrained deployments |
| `IvfPqStore` | Off-heap (PQ codes) | Billion-scale (32Ã— compression) |

---

## ðŸŒ API Layer

```mermaid
graph TD
    subgraph "SpectorNode - Armeria Server, single port"
        CORS["CorsService decorator"]
        Auth["API Key decorator"]
        COMPRESS["EncodingService - gzip/brotli"]
        subgraph "ApiModule Registration"
            SE["ðŸ” SearchEndpoint"]
            IE["ðŸ“¥ IngestEndpoint"]
            RE["ðŸ¤– RagEndpoint"]
            DE["ðŸ—‘ï¸ DocumentEndpoint"]
            STE["ðŸ“Š StatusEndpoint"]
            ESE["ðŸ“¡ EventStreamEndpoint"]
        end
        gRPC["gRPC Service<br/>inter-node fan-out"]
        HEALTH["ðŸ’š /health"]
        PROM["ðŸ“Š /metrics"]
    end

    subgraph "Service Facades"
        SS["SearchService"]
        IS["IngestService"]
        RS["RagService"]
    end

    SE --> SS
    IE --> IS
    RE --> RS
    SS & IS --> EB["SpectorEventBus<br/>17 event types"]
    SS --> ENGINE["âš¡ SpectorEngine"]
```

Every request runs on its own virtual thread. The Armeria server handles HTTP REST, gRPC, and SSE events on a single port. API endpoints are registered via the `ApiModule` factory pattern, enabling straightforward API versioning (`/api/v1`, `/api/v2`).

### Streaming via SSE

The `/api/v1/search/stream` endpoint uses Server-Sent Events to emit results progressively. The `/api/v1/events` endpoint provides a live event stream where clients can subscribe to search, ingest, cluster, MCP, and engine events with optional category filtering.

---

## ðŸ”— See Also

- [Core Concepts](core-concepts.md) â€” Algorithms and data structures in detail

- [Distributed Mode](distributed-mode.md) â€” Multi-node clustering architecture

- [GPU Acceleration](gpu-acceleration.md) â€” CUDA kernel integration via Panama

- [Performance Tuning](../operations/performance-tuning.md) â€” Optimizing for your workload
