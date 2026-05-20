# Architecture

## System Overview

Spector Search is a multi-module Maven project built on four foundational Java technologies:

- **Java Vector API** (jdk.incubator.vector) — SIMD-accelerated similarity kernels
- **Panama FFM** — Zero-copy memory-mapped storage and GPU interop
- **Virtual Threads** (Project Loom) — Massive concurrency without thread pool tuning
- **Memory-mapped indexes** — Instant startup, zero GC pressure

## Module Structure

```
spector-search/
├── spector-core/         # SIMD kernels (DotProduct, Cosine, Euclidean)
├── spector-commons/      # Text chunkers, tokenizer, document readers
├── spector-storage/      # Panama MemorySegment stores (InMemory + Mmap)
├── spector-index/        # HNSW + IVF-PQ + BM25 indexes
│   ├── hnsw/             # HNSW graph ANN (standard + quantized INT8/INT4/INT2)
│   ├── ivf/              # IVF inverted file index + quantized IVF-PQ
│   ├── pq/               # Product quantizer (K-Means++, ADC)
│   ├── text/             # BM25 keyword scoring + analyzers
│   └── fuzz/             # Index fuzz testing framework
├── spector-query/        # Hybrid orchestrator + RRF fusion + reranking
├── spector-embed-api/    # EmbeddingProvider SPI
├── spector-embed-ollama/ # Ollama embedding provider
├── spector-gpu/          # GPU acceleration (CUDA via Panama FFM)
├── spector-engine/       # Unified engine facade + lifecycle
├── spector-server/       # REST API (Javalin + virtual threads)
├── spector-cluster/      # Distributed gRPC search
├── spector-client/       # Java client SDK
├── spector-cli/          # spectorctl CLI tool
└── spector-bench/        # JMH benchmarks
```

## Dependency Flow

```
server → engine → query → index → core
                       → index → storage → core
cluster → engine
client  → (HTTP) → server
cli     → (HTTP) → server
gpu     → core, storage
engine  → commons, embed-api
```

## Data Flow

### Ingestion Path

1. REST request arrives at `spector-server`
2. `SpectorEngine` routes to appropriate handler
3. Vector stored in off-heap `VectorStore` (Panama MemorySegment)
4. HNSW graph updated with new node connections
5. BM25 inverted index updated with text tokens
6. Document metadata stored for retrieval

### Search Path

1. Query arrives at `spector-server`
2. `SpectorEngine` delegates to `QueryOrchestrator`
3. Parallel execution:
    - **Vector leg**: HNSW traversal with SIMD distance computation
    - **Keyword leg**: BM25 scoring across inverted index
4. Results fused via Reciprocal Rank Fusion (RRF)
5. Optional: LLM re-ranking via Ollama
6. Top-K results returned with scores

### RAG Path

1. Documents read by `DocumentReader` (PDF, HTML, Markdown)
2. Text split by `TokenAwareChunker` respecting sentence boundaries
3. Chunks embedded in parallel via `EmbeddingPipeline`
4. On query: relevant chunks retrieved and scored
5. `ContextBuilder` assembles context within token limit
6. Context returned with source attributions

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| Off-heap vectors (Panama) | Avoids GC pressure, enables mmap for instant load |
| Virtual threads | Scales to thousands of concurrent queries without pool tuning |
| SIMD via Vector API | 10-100× faster distance computation than scalar Java |
| HNSW for ANN | Proven recall/latency tradeoff, logarithmic search time |
| IVF-PQ for scale | 32× memory compression enables billion-scale on commodity hardware |
| Multi-level quantization | INT8/INT4/INT2 with non-uniform calibration covers 4×–16× compression |
| Configurable rescore | Oversampling-based rescore recovers recall lost to quantization |
| Consistent hashing | Minimal data movement on cluster topology changes |
| gRPC for cluster | Low-latency binary protocol for shard fan-out |
