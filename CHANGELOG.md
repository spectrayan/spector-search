# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added — spector-events (Telemetry Event Bus)
- **spector-events:** New module — decoupled telemetry event bus for real-time observability
- **spector-events:** `TelemetryBus` — instance-based, thread-safe event router (not static, HA-safe)
- **spector-events:** `TelemetryScope` — per-query scope that accumulates telemetry and flushes on close
- **spector-events:** `TelemetryEvent` — sealed event hierarchy (SIMD, GPU, query trace, graph pulse, memory diagnostic, cluster topology, etc.)
- **spector-events:** 12 telemetry event types mapping 1:1 to Cortex dashboard cards

### Added — spector-cortex (Neural Dashboard)
- **spector-cortex:** SIMD Panel — 16-lane hardware visualization with intensity (speed) and utilization (fill level) color-coded bars
- **spector-cortex:** Cognitive Profile Radar — hexagonal radar chart with animated dot displacement toward dominant profile corner
- **spector-cortex:** Vector Space layer controls — Query dot, k-NN lines, Axes grid, and Labels toggles (matching Neural Graph pattern)
- **spector-cortex:** Vector Space 3D axes grid — RGB-tinted X/Y/Z axis lines with concentric ring markers at r=10/20/30
- **spector-cortex:** Vector Space dimension labels — billboard sprites (`dim₀`/`dim₁`/`dim₂`) that face the camera
- **spector-cortex:** Vector Space tier legend — Working/Episodic/Semantic/Procedural color legend overlay
- **spector-cortex:** `ThemeService.getCanvasColor()` — resolves Angular Material 21 M3 CSS variables (oklch) to canvas-compatible hex via off-screen div probe
- **spector-cortex:** `VectorLayerToggles` and `toggleVectorLayer()` in `CortexStateService`

### Changed — spector-metrics (Unified Decorator)
- **spector-metrics:** `MeteredSpectorEngine` now accepts optional `TelemetryBus` — single decorator for both Micrometer timers and event-bus telemetry
- **spector-metrics:** Eliminated need for separate telemetry decorator layer

### Fixed — spector-cortex (Angular 21 Compatibility)
- **spector-cortex:** Moved all `effect()` calls from `ngAfterViewInit` to constructors — Angular 21 requires injection context (NG0203)
- **spector-cortex:** Added initialization guards to constructor effects to prevent accessing uninitialized THREE.js scenes/canvas contexts
- **spector-cortex:** Replaced `THREE.Clock` (deprecated r183+) with `THREE.Timer` in NeuralGraphComponent and VectorSpaceComponent
- **spector-cortex:** Fixed canvas rendering across all 9 canvas components — oklch() color space silently ignored by Canvas 2D API

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
- **spector-node:** Armeria REST API with virtual threads
- **spector-node:** CORS support via bundled plugin
- **spector-node:** Optional API key authentication (`X-API-Key` header)
- **spector-node:** Auto-embed ingest endpoint (`/api/v1/ingest/auto`)
- **spector-node:** Bulk ingest endpoint (`/api/v1/ingest/bulk`)
- **spector-node:** Document deletion endpoint (`DELETE /api/v1/documents/{id}`)
- **spector-node:** Metrics endpoint (`/api/v1/metrics`)
- **spector-node:** Vector dimension validation on ingest
- **spector-node:** gRPC-based distributed search with coordinator/shard fan-out
- **spector-node:** `ClusterCoordinator` with parallel shard queries and result merging
- **spector-node:** `RemoteShardClient` with TLS support (mutual TLS optional)
- **spector-node:** `ShardNode` gRPC server wrapping a local SpectorEngine
- **spector-node:** `ClusterConfig` with consistent hash and range partitioning
- **spector-bench:** JMH benchmarks for SIMD kernels, HNSW, BM25, ingestion, IVF-PQ, concurrency
- **spector-bench:** `PerformanceTestRunner` for comprehensive latency/throughput reporting
- 316+ tests across all modules, all passing

### Added — spector-mcp (Agent-Native MCP Server)
- **spector-mcp:** Built-in Model Context Protocol (MCP) server for AI agent integration (Claude Desktop, Cursor, autonomous agents)
- **spector-mcp:** 6 MCP tools: `semantic_search`, `hybrid_search`, `rag_query`, `ingest_document`, `delete_document`, `engine_status`
- **spector-mcp:** `McpToolHandler` abstract base class with template method pattern (timing, error handling, arg parsing)
- **spector-mcp:** `ToolSchemaBuilder` — type-safe fluent builder for JSON schemas (replaces error-prone `Map.of()` literals)
- **spector-mcp:** `SpectorToolRegistry` — tool discovery and registration with Open/Closed Principle
- **spector-mcp:** `SpectorResourceProvider` and `SpectorPromptProvider` — MCP resource/prompt definitions
- **spector-mcp:** `ResultFormatter` — shared formatting utilities for search results, RAG context, engine status
- **spector-mcp:** `SpectorMcpMain` CLI entry point with Ollama embedding provider auto-detection
- **spector-mcp:** In-process MCP execution with zero network overhead (50–200µs per tool call)
- **spector-mcp:** 15 unit tests covering tool registry, all tool handlers, schema builder, and argument validation

### Technical Decisions
- Java 25 with `jdk.incubator.vector` for SIMD
- `FloatVector.SPECIES_PREFERRED` for ISA-agnostic code
- `ReentrantLock` everywhere (no `synchronized`) to avoid virtual thread pinning
- Panama `MemorySegment` for zero-GC vector storage
- `Executors.newVirtualThreadPerTaskExecutor()` for hybrid search fan-out
- GPU module as optional dependency — graceful fallback to CPU SIMD
- LLM re-ranker wired through engine config, not global state
