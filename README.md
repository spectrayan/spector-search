# Spector-Search ⚡

> Ultra-fast, SIMD-accelerated semantic search engine built on Java Vector API + modern JVM technologies.

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/)
[![Build](https://img.shields.io/github/actions/workflow/status/spectrayan/spector-search/ci.yml?branch=main)](https://github.com/spectrayan/spector-search/actions)

## ✨ Features

- **🔥 SIMD-Accelerated** — Hardware-accelerated vector math via Java Vector API (AVX2/AVX-512/NEON)
- **🧠 Hybrid Search** — Combines semantic vector search (HNSW) with keyword search (BM25) via Reciprocal Rank Fusion
- **💾 Zero-Copy Storage** — Off-heap vector storage using Panama Foreign Function & Memory API
- **🧵 Virtual Thread Native** — Designed for Project Loom's virtual threads, no `synchronized` blocks
- **🎯 High Recall** — HNSW approximate nearest-neighbor search with configurable recall@K ≥ 80%
- **⚡ Sub-Millisecond Queries** — Branchless SIMD kernels with masked tail handling
- **🗜️ Multi-Level Quantization** — INT8 (4×), INT4 (8×), and INT2 (16×) scalar quantization with non-uniform calibration and configurable rescore
- **🗜️ VASQ Quantization** — FWHT-rotated affine INT8 quantization with exact-norm header for high-accuracy zero-copy compression (retaining 99.5%+ recall)
- **🗜️ VASQ-4 Quantization** — INT4 nibble-packed variant of VASQ achieving 6–8× compression vs float32 with 97–99% recall (with 3× rescore)
- **🎯 SpectorIndex (IVF-HNSW-VASQ)** — Multi-level adaptive vector index yielding 99.5%–100% recall on real text embeddings at aggressive 3% partition scanning rates
- **🗜️ IVF-PQ Index** — Inverted file with product quantization for 32× memory compression at billion scale
- **🤖 LLM Re-ranking** — Listwise relevance scoring via Ollama for precision-critical retrieval
- **🖥️ GPU Acceleration** — CUDA kernel loader + SIMD batch similarity via Panama FFM
- **🌐 Distributed Search** — gRPC-based coordinator/shard fan-out with consistent hash partitioning
- **🧬 Embedding SPI** — Pluggable embedding providers (Ollama included out-of-the-box)
- **📄 Chunked Ingestion** — Text, token-level, and streaming chunkers for large document support

## 🏗 Architecture

```
spector-search/
├── [spector-core/](spector-core/)         # SIMD kernels (DotProduct, Cosine, Euclidean, VectorOps)
├── [spector-commons/](spector-commons/)      # Text chunkers, tokenizer, content extractor
├── [spector-storage/](spector-storage/)      # Panama MemorySegment stores (InMemory + Mmap + Quantized)
├── [spector-index/](spector-index/)        # HNSW + IVF-PQ vector indexes + BM25 keyword index
│   ├── hnsw/             # HNSW graph-based ANN index (standard + quantized INT8/INT4/INT2)
│   ├── ivf/              # IVF inverted file index + quantized IVF-PQ
│   ├── pq/               # Product quantizer (K-Means++, ADC)
│   ├── text/             # BM25 keyword scoring + analyzers
│   └── fuzz/             # Index fuzz testing framework
├── [spector-query/](spector-query/)        # Hybrid orchestrator + RRF fusion + LLM re-ranking
├── [spector-embed-api/](spector-embed-api/)    # EmbeddingProvider SPI
├── [spector-embed-ollama/](spector-embed-ollama/) # Ollama embedding provider implementation
├── [spector-gpu/](spector-gpu/)          # GPU acceleration (Panama FFM + CUDA)
├── [spector-engine/](spector-engine/)       # Unified engine facade + lifecycle
├── [spector-server/](spector-server/)       # REST API (Javalin + virtual threads)
├── [spector-cluster/](spector-cluster/)      # Distributed gRPC search (coordinator + shards)
├── [spector-client/](spector-client/)       # Developer-facing Java SDK client
├── [spector-cli/](spector-cli/)          # Terminal-based admin CLI (spectorctl)
├── [spector-ingestion/](spector-ingestion/)    # High-throughput streaming document processing pipelines
├── [spector-rag/](spector-rag/)          # Zero-dependency Retrieval-Augmented Generation pipeline
├── [spector-spring/](spector-spring/)       # Spring Boot starter and Spring AI integration auto-configurations
└── [spector-bench/](spector-bench/)        # JMH benchmarks
```

### Module Dependency Graph

```
cluster   → engine    → query   → index → core
                                → index → storage → core
server    → engine
client    → server (HTTP)
cli       → server (HTTP)
spring    → engine
rag       → engine
ingestion → engine
engine    → gpu (optional)
engine    → commons
engine    → embed-api
gpu       → core, storage
```

## 🚀 Quick Start

### Prerequisites

- **JDK 25+** (OpenJDK with Vector API incubator)
- **Maven 3.9+**

### Build & Test

```bash
# Clone the repository
git clone https://github.com/spectrayan/spector-search.git
cd spector-search

# Build and run all tests (316+ tests)
mvn clean test

# Start the REST server
mvn exec:java -pl spector-server \
  -Dexec.mainClass="com.spectrayan.spector.server.SpectorServer"

# Start with API key authentication
mvn exec:java -pl spector-server \
  -Dexec.mainClass="com.spectrayan.spector.server.SpectorServer" \
  -Dexec.args="7070 384 my-secret-key"
```

### REST API

```bash
# Health check
curl http://localhost:7070/health

# Engine status (includes SIMD capability, GPU, reranker)
curl http://localhost:7070/api/v1/status

# Ingest a document (with vector)
curl -X POST http://localhost:7070/api/v1/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "id": "doc-1",
    "title": "Java Vector API",
    "content": "SIMD-accelerated search engine on modern JVM",
    "vector": [0.1, 0.2, 0.3, ...]
  }'

# Auto-embed ingest (requires embedding provider)
curl -X POST http://localhost:7070/api/v1/ingest/auto \
  -H "Content-Type: application/json" \
  -d '{
    "id": "doc-2",
    "title": "Panama FFM",
    "content": "Foreign Function & Memory API for zero-copy storage"
  }'

# Bulk ingest
curl -X POST http://localhost:7070/api/v1/ingest/bulk \
  -H "Content-Type: application/json" \
  -d '{
    "documents": [
      {"id": "d1", "content": "first doc", "vector": [...]},
      {"id": "d2", "content": "second doc", "vector": [...]}
    ]
  }'

# Search (auto-detects mode: keyword/vector/hybrid)
curl -X POST http://localhost:7070/api/v1/search \
  -H "Content-Type: application/json" \
  -d '{
    "text": "vector search engine",
    "vector": [0.1, 0.2, 0.3, ...],
    "topK": 10
  }'

# Delete a document
curl -X DELETE http://localhost:7070/api/v1/documents/doc-1

# Request metrics
curl http://localhost:7070/api/v1/metrics
```

## 🧩 Programmatic API

```java
var config = SpectorConfig.DEFAULT
    .withDimensions(384)
    .withCapacity(100_000)
    .withQuantization(QuantizationType.SCALAR_INT4)  // 8× compression
    .withRescore(3)                                   // 3× oversampling for recall recovery
    .withGpu(true)                                    // GPU auto-detection
    .withReranker("http://localhost:11434", "llama3.2", 20);    // LLM re-ranking

try (var engine = new SpectorEngine(config)) {
    // Ingest
    engine.ingest("doc-1", "Hello world", embedding);

    // Search
    SearchResponse response = engine.hybridSearch("hello", queryVector, 10);

    for (ScoredResult result : response.results()) {
        System.out.printf("%s → %.4f%n", result.id(), result.score());
    }

    // Delete
    engine.delete("doc-1");
}
```

### VASQ-4 Quantization (6–8× Compression)

```java
// Fluent builder with VASQ-4 quantization
var engine = SpectorEngine.builder()
    .dimensions(4096)           // e.g., qwen3-embedding
    .capacity(500_000)
    .vasq4()                    // INT4 FWHT-rotated, 3× rescore default
    .build();

// Or with explicit oversampling
var config = SpectorConfig.DEFAULT
    .withDimensions(768)
    .withVasq4(5);              // 5× oversampling for higher recall
```

## ⚙️ Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| `dimensions` | 384 | Vector dimensionality |
| `capacity` | 100,000 | Max documents |
| `similarityFunction` | COSINE | COSINE, DOT_PRODUCT, or EUCLIDEAN |
| `M` | 16 | HNSW max connections per node |
| `efConstruction` | 200 | HNSW construction beam width |
| `efSearch` | 50 | HNSW search beam width |
| `k1` | 1.2 | BM25 term frequency saturation |
| `b` | 0.75 | BM25 document length normalization |
| `RRF k` | 60 | Reciprocal Rank Fusion constant |
| `gpuEnabled` | false | Enable CUDA GPU acceleration |
| `quantization` | NONE | Quantization type: NONE, SCALAR_INT8, SCALAR_INT4, SCALAR_INT2, VASQ, VASQ_4 |
| `oversamplingFactor` | auto | Rescore oversampling (INT4→3, INT2→5, INT8→1). Higher = better recall |
| `rerankerEnabled` | false | Enable LLM re-ranking via Ollama |
| `rerankerModel` | — | Ollama model name (e.g., "llama3.2") |
| `rerankerMaxCandidates` | 20 | Max docs sent to LLM for re-ranking |

## 🏎 Performance

SIMD auto-detection adapts to your hardware:

| ISA | Width | Lanes (float) | Platform |
|-----|-------|---------------|----------|
| AVX2 | 256-bit | 8 | Most modern x86 |
| AVX-512 | 512-bit | 16 | Intel Xeon, recent AMD |
| NEON | 128-bit | 4 | Apple Silicon, ARM |

### SIMD Kernel Latency

Sub-microsecond vector math at every dimension:

| Dimension | Cosine P50 | Cosine P99 | Dot Product P50 | Dot Product P99 |
|-----------|-----------|-----------|-----------------|-----------------| 
| 32        | 500 ns    | 1,500 ns  | 200 ns          | 400 ns          |
| 128       | <100 ns   | 100 ns    | 100 ns          | 1,300 ns        |
| 384       | ~100 ns   | 100 ns    | ~100 ns         | 100 ns          |
| 768       | ~100 ns   | 100 ns    | ~100 ns         | 100 ns          |

> Measured on 24-core Intel Core Ultra 9 285K x86, AVX2 256-bit (8 lanes), Java 25, ZGC. Values at 384+ dimensions are at `System.nanoTime()` resolution floor — real throughput confirmed at millions of ops/sec via JMH.

### Search Latency (128-dim, top-10)

| Scale | Keyword (BM25) | Vector (HNSW) | Hybrid (RRF) |
|-------|---------------|---------------|--------------| 
| **10K docs** | **0.18 ms** avg / 0.33 ms p99 | **0.04 ms** avg / 0.07 ms p99 | **0.17 ms** avg / 0.26 ms p99 |
| **50K docs** | **0.44 ms** avg / 0.59 ms p99 | **0.08 ms** avg / 0.11 ms p99 | **0.51 ms** avg / 0.84 ms p99 |
| **100K docs** | **1.53 ms** avg / 1.94 ms p99 | **0.10 ms** avg / 0.22 ms p99 | **1.76 ms** avg / 2.81 ms p99 |

### Search Throughput (queries/sec)

| Scale | Keyword | Vector | Hybrid |
|-------|---------|--------|--------|
| **10K docs** | **5,490** | **23,726** | **5,993** |
| **50K docs** | **2,264** | **13,287** | **1,958** |
| **100K docs** | **653** | **9,925** | **569** |

### Ingestion Throughput

| Dataset Size | Time | Rate | Memory |
|-------------|------|------|--------|
| 10,000 | 2.1s | **4,679 docs/s** | +48 MB |
| 50,000 | 20.5s | **2,430 docs/s** | +86 MB |
| 100,000 | 1m 2s | **1,597 docs/s** | +202 MB |

### Concurrency Scaling (50K docs, 128-dim, Hybrid Search)

| Threads | Throughput | Avg Latency | Scaling Factor |
|---------|-----------|-------------|----------------|
| 1 | 1,231 ops/s | 0.81 ms | 1.0× |
| 4 | 2,894 ops/s | 1.38 ms | **2.3×** |
| 8 | 5,466 ops/s | 1.46 ms | **4.4×** |
| 16 | 7,635 ops/s | 1.99 ms | **6.2×** |

> Run the full benchmark suite: `mvn -pl spector-bench exec:java`
> HTML report generated at `spector-bench/target/performance-report.html`
>
> [!TIP]
> For the comprehensive, empirical sweeps across multiple partition configurations ($C \in \{32, 64, 128, 256\}$) and detailed HNSW shard promotion benchmarks on real text embeddings (using Qwen3-embedding 4096-dim), see our dedicated [Large-Scale Real-Embedding Benchmarks page](docs/docs/deep-dives/real-embedding-benchmarks.md).

---

## 📊 Comparison with Other Search Engines

All comparisons below use **100K documents, 128 dimensions, top-10 retrieval** as the reference point. Numbers for external systems are sourced from published benchmarks, official documentation, and [ann-benchmarks.com](https://ann-benchmarks.com). Hardware and configuration differences apply — these are directional comparisons, not controlled A/B tests.

### Vector Search Latency (ANN, 100K docs)

| Engine | Language | Avg Latency | P99 Latency | Notes |
|--------|----------|------------|------------|-------|
| **Spector Search** | Java 25 | **0.10 ms** | **0.22 ms** | SIMD via Vector API, pure in-process, 100K docs |
| hnswlib | C++ | ~0.1–0.5 ms | ~1 ms | Fastest native HNSW; single-threaded |
| FAISS (HNSW) | C++/Python | ~0.2–0.8 ms | ~1–2 ms | Versatile; GPU support available |
| Apache Lucene 9+ | Java | ~1–5 ms | ~5–10 ms | Segment-based; force-merge helps |
| Elasticsearch 8+ | Java/Lucene | ~2–10 ms | ~10–25 ms | Distributed overhead; REST layer |
| Qdrant | Rust | ~2–5 ms | ~10–25 ms | Payload filtering optimized |
| Milvus | Go/C++ | ~3–10 ms | ~10–35 ms | Scales to billions; DiskANN support |
| Weaviate | Go | ~5–15 ms | ~25–40 ms | Built-in vectorization modules |

> [!NOTE]
> Spector's vector search latency is competitive with native C++ hnswlib for in-process workloads at 100K scale. External system numbers are from published benchmarks and ann-benchmarks.com. Hardware/configuration differences apply.

### Keyword Search (BM25, 100K docs)

| Engine | Avg Latency | Notes |
|--------|------------|-------|
| **Spector Search** | **1.53 ms** | float[] scoring, min-heap top-K, virtual-thread parallel terms |
| Elasticsearch | <1–5 ms | Inverted index + skip lists, highly optimized |
| Apache Lucene | <1–3 ms | Raw engine, no network overhead |
| Weaviate (BM25) | ~10–30 ms | Go-based BM25 for hybrid search |

### Hybrid Search (Keyword + Vector, 100K docs)

| Engine | Approach | Avg Latency | Notes |
|--------|----------|------------|-------|
| **Spector Search** | RRF (parallel virtual threads) | **1.76 ms** | Both legs sub-ms at 10K; parallel via virtual threads |
| Elasticsearch | RRF / linear combination | ~10–30 ms | Mature query planner, skip-list BM25 |
| Qdrant | Sparse+Dense fusion | ~15–30 ms | Rust-based sparse vectors |
| Weaviate | Hybrid BM25+HNSW | ~25–40 ms | Unified API, built-in vectorization |

### Ingestion Throughput

| Engine | Rate (100K docs) | Notes |
|--------|-----------------|-------|
| **Spector Search** | **1,597 docs/s** | In-process, HNSW graph build included |
| Elasticsearch | ~2,000–5,000 docs/s | Bulk API, depends on mapping & replicas |
| Milvus | ~3,000–8,000 docs/s | Batch insert optimized |
| Qdrant | ~2,000–5,000 docs/s | Payload indexing included |

### Architecture Differentiators

| Feature | Spector | Elasticsearch | Lucene | hnswlib | Qdrant | Milvus |
|---------|---------|--------------|--------|---------|--------|--------|
| **Deployment** | Embedded library | Distributed cluster | Embedded library | Embedded library | Standalone server | Distributed cluster |
| **Language** | Java 25 | Java | Java | C++ | Rust | Go/C++ |
| **SIMD Accel.** | ✅ Vector API | ✅ Panama (9.x+) | ✅ Panama (9.x+) | ✅ AVX/SSE native | ✅ Native SIMD | ✅ AVX/NEON |
| **Hybrid Search** | ✅ RRF | ✅ RRF/Linear | ❌ Manual | ❌ None | ✅ Sparse+Dense | ✅ RRF |
| **Off-Heap Vectors** | ✅ Panama MemorySegment | ✅ Lucene MMapDir | ✅ MMapDir | ❌ Heap-only | ✅ Mmap | ✅ Mmap |
| **Virtual Threads** | ✅ Native Loom | ❌ Platform threads | N/A | N/A | N/A | N/A |
| **Zero Dependencies** | ✅ JDK only | ❌ Heavy stack | ✅ Standalone | ✅ Header-only | ❌ Tokio runtime | ❌ etcd, MinIO, Pulsar |
| **Quantization** | ✅ Scalar INT8/INT4/INT2 + VASQ/VASQ-4 + PQ | ✅ BBQ/Scalar | ✅ Scalar | ❌ None | ✅ Scalar/Binary | ✅ PQ/SQ |
| **Disk-based Index** | ✅ HNSW serialization | ✅ Segment merge | ✅ MMap | ❌ In-memory | ✅ On-disk HNSW | ✅ DiskANN |
| **IVF-PQ** | ✅ 32× compression | ❌ None | ❌ None | ❌ None | ❌ None | ✅ IVF_PQ |
| **GPU Acceleration** | ✅ CUDA (Panama FFM) | ❌ None | ❌ None | ❌ None | ❌ None | ✅ GPU |
| **LLM Re-ranking** | ✅ Ollama | ❌ None | ❌ None | ❌ None | ❌ None | ❌ None |
| **Distributed Search** | ✅ gRPC fan-out | ✅ Built-in | ❌ None | ❌ None | ✅ Raft | ✅ gRPC |

### Where Spector Excels

- **🚀 Sub-millisecond vector search**: 0.04ms at 10K, 0.10ms at 100K (128-dim), competitive with native C++ implementations
- **🔥 Fast BM25**: Sub-millisecond keyword search at 10K/50K scale — comparable to raw Lucene
- **🧵 Modern JVM**: Only search engine built on Java 25 virtual threads + Vector API
- **📦 Zero-dependency embedded**: Drop-in JAR, no external infrastructure needed
- **⚡ 7.6K+ ops/sec concurrent**: 7,635 hybrid searches/sec at 16 threads (128-dim)
- **🎯 23K+ vector QPS**: 23,726 vector queries/sec at 10K docs
- **🗜️ IVF-PQ + VASQ + VASQ-4 + TurboQuant**: 6–32× memory reduction for large-scale datasets with high-accuracy calibration
- **🔬 99.5%+ Recall**: IVF-HNSW-VASQ (`SpectorIndex`) achieves near-perfect recall on real semantic embeddings scanning just 3% of the clusters
- **🤖 LLM re-ranking**: Listwise Ollama-powered relevance scoring
- **🖥️ GPU acceleration**: CUDA kernel launcher + SIMD batch similarity via Panama FFM
- **🌐 Distributed search**: gRPC-based fan-out/merge with consistent hash sharding

---

## 📊 Test Suite

| Module | Tests | Coverage |
|--------|-------|----------|
| spector-core | 117 | SIMD kernels, similarity functions, scalar quantization |
| spector-commons | 28 | Text chunkers, token chunker, streaming chunker, content extractor |
| spector-storage | 38 | Off-heap stores, mmap persistence, quantized vector store |
| spector-index | 79 | HNSW recall, BM25 scoring, IVF-PQ, PQ encode/decode |
| spector-query | 29 | RRF fusion, hybrid orchestration, LLM re-ranking |
| spector-embed-api | 9 | Embedding SPI contracts |
| spector-embed-ollama | 7 | Ollama provider, fallback behavior |
| spector-gpu | 14 | GPU detection, SIMD batch similarity, CUDA launcher |
| spector-engine | 12 | End-to-end ingestion, IVF-PQ auto-training |
| spector-server | 6 | REST API endpoints |
| spector-cluster | 5 | Shard routing, hash consistency |
| **Total** | **316+** | **All passing ✅** |

## 📈 Roadmap

- [x] HNSW vector index with SIMD acceleration
- [x] BM25 keyword search
- [x] Hybrid search with RRF fusion
- [x] Scalar quantization (INT8, INT4, INT2) with non-uniform calibration and configurable rescore
- [x] TurboQuant quantization (rotation + optimal scalar, 8× compression)
- [x] Disk-based HNSW persistence
- [x] Embedding provider SPI (Ollama)
- [x] IVF-PQ vector index (32× compression)
- [x] LLM-powered re-ranking
- [x] GPU infrastructure (CUDA context, memory management via Panama FFM)
- [x] Distributed search (gRPC coordinator/shards)
- [x] REST API with CORS, auth, metrics, SSE streaming
- [x] Standalone ingestion pipeline (`spector-ingestion`)
- [x] Standalone RAG pipeline (`spector-rag`)
- [x] Document deletion
- [x] Auto-embed + bulk ingest endpoints
- [x] gRPC TLS support
- [x] VASQ-4 quantization (FWHT-rotated INT4, nibble-packed — 6–8× compression vs float32)
- [ ] Padding-aware storage (skip zero-padded dims — 25% savings for non-pow2 dimensions)
- [ ] Norm header compression (float32 → float16 — 2 bytes/vector savings)
- [ ] VASQ-PQ hybrid (FWHT rotation + product quantization — 16–32× compression)
- [ ] Flat-mode VASQ (VASQ compression of flat-shard residuals — 3× on flat shards)
- [ ] Adaptive bit-width VASQ (per-dimension variable bit allocation)
- [ ] GPU kernel dispatch (CUDA compute for batch similarity — requires CUDA Toolkit)
- [ ] NPU acceleration (Intel/AMD NPU for INT8 batch operations via OpenVINO or DirectML)
- [ ] WASM runtime for edge deployment
- [ ] Structured concurrency (JEP 462) for fan-out search with cancellation propagation

> See the [detailed Roadmap](docs/docs/roadmap.md) for in-depth descriptions, projected savings, and implementation plans.

## 🤝 Contributing

We welcome contributions! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## 📄 License

This project is licensed under the Apache License 2.0 — see [LICENSE](LICENSE) for details.

## 🔒 Security

Please see [SECURITY.md](SECURITY.md) for our security policy and how to report vulnerabilities.

---

**Built with ⚡ by [Spectrayan](https://www.spectrayan.com/)**
