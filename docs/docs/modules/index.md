# Modules

Spector Search is organized as a multi-module Maven project. Each module has a focused responsibility, clear API boundaries, and minimal cross-module coupling.

---

## Module Dependency Graph

```mermaid
graph TD
    commons["spector-commons"]
    core["spector-core"]
    config["spector-config"]
    storage["spector-storage"]
    embedApi["spector-embed-api"]
    embedOllama["spector-embed-ollama"]
    idx["spector-index"]
    query["spector-query"]
    gpu["spector-gpu"]
    rag["spector-rag"]
    engine["spector-engine"]
    ingestion["spector-ingestion"]
    memory["spector-memory"]
    runtime["spector-runtime"]
    mcp["spector-mcp"]
    server["spector-server"]
    cli["spector-cli"]
    client["spector-client"]
    spring["spector-spring"]
    cluster["spector-cluster"]
    bench["spector-bench"]
    dist["spector-dist"]

    core --> commons
    config --> commons
    storage --> core
    embedOllama --> embedApi
    idx --> core
    query --> idx
    gpu --> core
    rag --> query
    rag --> embedApi
    engine --> rag
    engine --> storage
    engine --> embedOllama
    engine --> config

    ingestion --> config
    ingestion --> embedApi

    engine --> ingestion
    memory --> ingestion
    memory --> core
    memory --> embedApi
    memory --> idx

    runtime --> engine
    runtime --> memory
    runtime --> ingestion
    runtime --> config

    mcp --> runtime
    server --> runtime
    cli --> runtime
    cli --> client

    client --> commons
    spring --> engine
    cluster --> runtime
    bench --> engine

    dist --> mcp
    dist --> cli
    dist --> runtime
```

> [!IMPORTANT]
> **Architecture:** `spector-ingestion` defines the `IngestionPipeline` and `IngestionTarget` interface. Both `spector-engine` and `spector-memory` depend on it to implement their `IngestionTarget`. `SpectorRuntime` is the composition root that wires everything together.

---

## Architecture: Entry Points → Runtime → Subsystems

All entry points (MCP, CLI, Server) route through `SpectorRuntime`:

```mermaid
graph TD
    cli["🖥️ spector-cli<br/><i>SpectorCtl</i>"]
    mcp["🤖 spector-mcp<br/><i>SpectorMcpMain</i>"]
    server["🌐 spector-server<br/><i>SpectorServer</i>"]

    cli --> runtime
    mcp --> runtime
    server --> runtime

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
| [spector-server](spector-server.md) | HTTP server — REST API endpoints + SSE streaming |

### Client Layer

| Module | Description |
|:---|:---|
| [spector-cli](spector-cli.md) | CLI tool — `spectorctl` with remote (HTTP) and local batch (runtime) modes |
| [spector-client](spector-client.md) | Java client — programmatic HTTP API access |
| [spector-spring](spector-spring.md) | Spring AI integration — auto-configuration |

### Infrastructure

| Module | Description |
|:---|:---|
| [spector-cluster](spector-cluster.md) | Distributed mode — cluster coordination |
| [spector-bench](spector-bench.md) | Benchmarks — JMH performance testing |
| [spector-dist](spector-dist.md) | Distribution — single fat JAR packaging |
