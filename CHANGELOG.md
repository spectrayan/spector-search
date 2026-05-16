# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **spector-core:** SIMD-accelerated kernels for DotProduct, CosineSimilarity, and EuclideanDistance using Java Vector API
- **spector-core:** `VectorOps` utility (magnitude, normalize, scale, add, subtract) — all SIMD-accelerated
- **spector-core:** `SimilarityFunction` enum with pluggable strategy dispatch
- **spector-core:** `SimdCapability` runtime ISA detection and reporting
- **spector-core:** Scalar INT8 quantization (`ScalarQuantizer`, `QuantizedDotProduct`, `QuantizedCosineSimilarity`)
- **spector-commons:** `TextChunker` for character-level overlapping chunk splitting
- **spector-commons:** `TokenChunker` for token-level chunk splitting with precise token limits
- **spector-commons:** `StreamingChunker` for bounded-memory streaming ingestion of large files
- **spector-commons:** `ContentExtractor` for XML/JSON/Java object text extraction
- **spector-commons:** `WordTokenizer` and `TextUtils` text processing utilities
- **spector-storage:** Off-heap `InMemoryVectorStore` backed by Panama `MemorySegment` + `Arena`
- **spector-storage:** File-backed `MappedVectorStore` via memory-mapped I/O
- **spector-storage:** `QuantizedVectorStore` for INT8-quantized vector storage
- **spector-storage:** `VectorStoreLayout` for contiguous vector memory arithmetic
- **spector-storage:** `DocumentStore` for metadata (title, content, tags) with delete support
- **spector-storage:** `IndexFileFormat` for HNSW disk serialization format
- **spector-index:** HNSW approximate nearest-neighbor index with multi-layer graph
- **spector-index:** `QuantizedHnswIndex` — HNSW with scalar INT8 quantization (4× memory reduction)
- **spector-index:** `DiskHnswIndex` — read-only memory-mapped HNSW for datasets larger than RAM
- **spector-index:** `DiskHnswWriter` — serializes in-memory HNSW to disk format
- **spector-index:** `NeighborQueue` bounded binary heap for candidate tracking
- **spector-index:** BM25 inverted index with Okapi BM25 scoring (k1=1.2, b=0.75) and document deletion
- **spector-index:** `StandardAnalyzer` text pipeline (tokenize → lowercase → stop words)
- **spector-index:** `StemmingAnalyzer` with simplified Porter stemmer
- **spector-index:** IVF-PQ vector index (`IvfPqIndex`, `PostingList`) with 32× compression
- **spector-index:** `ProductQuantizer` with K-Means++ initialization and ADC distance
- **spector-index:** `VectorIndex.isReadOnly()` default method for read-only index detection
- **spector-query:** `ReciprocalRankFusion` for zero-config score merging
- **spector-query:** `HybridSearchOrchestrator` with virtual-thread parallel fan-out and optional LLM re-ranking
- **spector-query:** `Reranker` SPI and `LlmReranker` implementation via Ollama
- **spector-query:** `QueryParser` with directive syntax (mode:, k:) and auto-detect
- **spector-embed-api:** `EmbeddingProvider` SPI with `EmbeddingResult`, `EmbeddingConfig`, `EmbeddingException`
- **spector-embed-ollama:** `OllamaEmbeddingProvider` with HTTP client, retry logic, and fallback behavior
- **spector-gpu:** `GpuCapability` — runtime CUDA detection via Panama FFM
- **spector-gpu:** `GpuBatchSimilarity` — SIMD-accelerated batch cosine and dot product computation
- **spector-gpu:** `CudaKernelLauncher` — PTX kernel loader and executor via Panama FFM
- **spector-engine:** `SpectorEngine` unified facade with lifecycle management
- **spector-engine:** `SpectorConfig` immutable configuration with builder-style API
- **spector-engine:** GPU acceleration integration with graceful CPU SIMD fallback
- **spector-engine:** LLM re-ranker integration via config (`withReranker()`)
- **spector-engine:** Document deletion support (`delete()` method)
- **spector-engine:** Auto-embed ingestion, chunked ingestion, and streaming file ingestion
- **spector-engine:** IVF-PQ auto-training with buffered vector accumulation
- **spector-server:** Javalin REST API with virtual threads
- **spector-server:** CORS support via bundled plugin
- **spector-server:** Optional API key authentication (`X-API-Key` header)
- **spector-server:** Auto-embed ingest endpoint (`/api/v1/ingest/auto`)
- **spector-server:** Bulk ingest endpoint (`/api/v1/ingest/bulk`)
- **spector-server:** Document deletion endpoint (`DELETE /api/v1/documents/{id}`)
- **spector-server:** Metrics endpoint (`/api/v1/metrics`)
- **spector-server:** Vector dimension validation on ingest
- **spector-cluster:** gRPC-based distributed search with coordinator/shard fan-out
- **spector-cluster:** `ClusterCoordinator` with parallel shard queries and result merging
- **spector-cluster:** `RemoteShardClient` with TLS support (mutual TLS optional)
- **spector-cluster:** `ShardNode` gRPC server wrapping a local SpectorEngine
- **spector-cluster:** `ClusterConfig` with consistent hash and range partitioning
- **spector-bench:** JMH benchmarks for SIMD kernels, HNSW, BM25, ingestion, IVF-PQ, concurrency
- **spector-bench:** `PerformanceTestRunner` for comprehensive latency/throughput reporting
- 316+ tests across all modules, all passing

### Technical Decisions
- Java 25 with `jdk.incubator.vector` for SIMD
- `FloatVector.SPECIES_PREFERRED` for ISA-agnostic code
- `ReentrantLock` everywhere (no `synchronized`) to avoid virtual thread pinning
- Panama `MemorySegment` for zero-GC vector storage
- `Executors.newVirtualThreadPerTaskExecutor()` for hybrid search fan-out
- GPU module as optional dependency — graceful fallback to CPU SIMD
- LLM re-ranker wired through engine config, not global state
