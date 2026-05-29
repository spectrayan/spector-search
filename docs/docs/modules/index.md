# Modules

Spector Search is organized as a multi-module Maven project. Each module has a focused responsibility, clear API boundaries, and minimal cross-module coupling.

---

## Architecture

```mermaid
graph LR
    subgraph "🔬 Foundation"
        core["spector-core<br/><i>SIMD kernels</i>"]
        commons["spector-commons<br/><i>Chunkers, tokenizer</i>"]
        config["spector-config<br/><i>SpectorConfig + YAML</i>"]
        storage["spector-storage<br/><i>Panama MemorySegment</i>"]
    end

    subgraph "🧠 Intelligence"
        embedApi["spector-embed-api<br/><i>Embedding SPI</i>"]
        embedOllama["spector-embed-ollama<br/><i>Ollama provider</i>"]
        index["spector-index<br/><i>HNSW + IVF-PQ + BM25</i>"]
        query["spector-query<br/><i>Hybrid + RRF + rerank</i>"]
        gpu["spector-gpu<br/><i>CUDA via Panama FFM</i>"]
    end

    subgraph "⚡ Engine"
        rag["spector-rag<br/><i>RAG pipeline</i>"]
        engine["spector-engine<br/><i>Search facade</i>"]
        ingestion["spector-ingestion<br/><i>File ingest pipeline</i>"]
        memory["spector-memory<br/><i>Cognitive memory 🧠</i>"]
    end

    subgraph "🌐 Runtime & Interfaces"
        runtime["spector-runtime<br/><i>Composition root</i>"]
        node["spector-node<br/><i>Armeria: REST + gRPC + SSE</i>"]
        mcp["spector-mcp<br/><i>MCP Server (stdio)</i>"]
        cli["spector-cli<br/><i>spectorctl</i>"]
        client["spector-client<br/><i>Java SDK</i>"]
        spring["spector-spring<br/><i>Spring AI</i>"]
    end

    subgraph "📦 Distribution"
        metrics["spector-metrics<br/><i>Prometheus + JVM</i>"]
        bench["spector-bench<br/><i>JMH benchmarks</i>"]
        dist["spector-dist<br/><i>Fat JAR</i>"]
    end
```

---

## Module Dependency Graph

```mermaid
graph TD
    node["🌐 node"] --> runtime["⚡ runtime"]
    node --> mcp["🤖 mcp"]
    node --> metrics["📈 metrics"]
    mcp --> runtime
    mcp --> ingestion["📥 ingestion"]
    cli["🖥️ cli"] --> runtime
    cli --> client["📦 client"]

    runtime --> engine["⚡ engine"]
    runtime --> memory["🧠 memory"]
    runtime --> ingestion

    engine --> query["🔍 query"]
    engine --> rag["🤖 rag"]
    engine --> ingestion
    engine --> index["📊 index"]
    engine --> storage["💾 storage"]
    engine --> embedapi["🧬 embed-api"]
    engine -.-> gpu["🎮 gpu"]

    memory --> index
    memory --> storage
    memory --> ingestion
    memory --> embedapi
    memory --> core["🔬 core"]

    metrics --> engine
    metrics --> memory

    ingestion --> config["⚙️ config"]
    ingestion --> embedapi

    rag --> query
    rag --> index
    rag --> storage
    rag --> embedapi

    query --> index
    index --> storage
    index --> config
    storage --> config
    storage --> core
    config --> core

    embedapi --> commons["📄 commons"]
    gpu --> core
    gpu --> storage

    dist["📦 dist"] --> mcp
    dist --> cli
    dist --> runtime

    spring["🌱 spring"] --> engine
    spring --> memory
    spring --> metrics
    bench["🧪 bench"] --> engine
    bench --> memory
```

> **Legend:** Solid arrows = compile dependency. Dotted arrow (`gpu`) = optional dependency.

!!! important "Architecture"
    `spector-ingestion` defines the `IngestionPipeline` and `IngestionTarget` interface. Both `spector-engine` and `spector-memory` depend on it to implement their `IngestionTarget`. `spector-memory` is fully independent of `spector-engine` — they are peers, wired together only at the `SpectorRuntime` composition root.

---

## Architecture: Entry Points → Runtime → Subsystems

All entry points (MCP, CLI, Server) route through `SpectorRuntime`:

```mermaid
graph TD
    cli["🖥️ spector-cli<br/><i>SpectorCtl</i>"]
    mcp["🤖 spector-mcp<br/><i>SpectorMcpMain</i>"]
    node["🌐 spector-node<br/><i>SpectorNode (Armeria)</i>"]

    cli --> runtime
    mcp --> runtime
    node --> runtime

    runtime["⚡ SpectorRuntime<br/><i>Composition Root</i>"]

    runtime --> sh["SearchHandler<br/><i>mode-aware search</i>"]
    runtime --> ih["IngestionHandler<br/><i>delegates to IngestionPipeline</i>"]

    sh --> engine["SpectorEngine"]
    sh --> memory["SpectorMemory"]
    ih --> pipeline["IngestionPipeline<br/><i>chunk → embed → store</i>"]
    pipeline --> engineTarget["EngineIngestionTarget<br/><i>SEARCH mode</i>"]
    pipeline --> memTarget["CognitiveIngestionTarget<br/><i>MEMORY mode</i>"]
```

**SpectorRuntime** is a thin composition root — it creates and wires subsystems but contains no business logic. Each handler owns its domain:

| Handler | Responsibility | Routes to |
|---------|---------------|-----------|
| `SearchHandler` | Mode-aware search | Engine (SEARCH mode) or Memory (MEMORY mode) |
| `IngestionHandler` | Delegates to unified `IngestionPipeline` | Pipeline → `EngineIngestionTarget` or `CognitiveIngestionTarget` |

---

## Module Overview

### Foundation Layer

| Module | Description |
|:---|:---|
| [spector-commons](spector-commons.md) | Shared utilities — concurrent primitives, I/O helpers |
| [spector-core](spector-core.md) | Core abstractions — quantization, SIMD, similarity functions |
| [spector-config](spector-config.md) | Configuration — `SpectorProperties`, `SpectorConfigFactory`, YAML loading |
| [spector-storage](spector-storage.md) | Persistent storage — memory-mapped files, arena management |

### Embedding Layer

| Module | Description |
|:---|:---|
| [spector-embed-api](spector-embed-api.md) | Embedding provider SPI — model-agnostic interface |
| [spector-embed-ollama](spector-embed-ollama.md) | Ollama embedding implementation |

### Search Layer

| Module | Description |
|:---|:---|
| [spector-index](spector-index.md) | Vector indexing — HNSW, IVF, brute-force |
| [spector-query](spector-query.md) | Query processing — parsing, planning, execution |
| [spector-gpu](spector-gpu.md) | GPU acceleration — Panama FFM bindings |

### Intelligence Layer

| Module | Description |
|:---|:---|
| [spector-rag](spector-rag.md) | RAG pipeline — retrieval-augmented generation |
| [spector-engine](spector-engine.md) | Search engine — orchestrates index + RAG + storage |
| [spector-ingestion](spector-ingestion.md) | Unified ingestion pipeline — `IngestionPipeline` (builder), `IngestionTarget` interface, `FileDiscoveryService` |
| [spector-memory](spector-memory.md) | Cognitive memory — biologically-inspired agent memory |

### Runtime Layer

| Module | Description |
|:---|:---|
| [spector-runtime](spector-runtime.md) | Composition root — wires engine + memory + ingestion pipeline, exposes `SearchHandler` and `IngestionHandler` |
| [spector-mcp](spector-mcp.md) | MCP server — Model Context Protocol integration via stdio |
| [spector-node](spector-node.md) | Unified node — Armeria HTTP REST + gRPC + SSE events + cluster coordination |

### Client Layer

| Module | Description |
|:---|:---|
| [spector-cli](spector-cli.md) | CLI tool — `spectorctl` with remote (HTTP) and local batch (runtime) modes |
| [spector-client](spector-client.md) | Java client — programmatic HTTP API access |
| [spector-spring](spector-spring.md) | Spring AI integration — auto-configuration |

### Infrastructure

| Module | Description |
|:---|:---|
| [spector-metrics](spector-metrics.md) | Metrics — Prometheus + JVM instrumentation |
| [spector-bench](spector-bench.md) | Benchmarks — JMH performance testing |
| [spector-dist](spector-dist.md) | Distribution — single fat JAR packaging |
