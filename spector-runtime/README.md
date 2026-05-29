# ⚡ Spector Runtime

**Composition root — the single entry point for all Spector consumers.**

`SpectorRuntime` creates, wires, and exposes all subsystem services. It is a thin composition root with no business logic — each handler owns its domain.

## Architecture

```
spector-runtime (Composition Root)
├── SpectorRuntime          — lifecycle, wiring, pipeline factory
├── SearchHandler           — mode-aware search routing
├── IngestionHandler        — thin layer over unified IngestionPipeline
├── spector-engine          (vector search, RAG, EngineIngestionTarget)
├── spector-memory          (cognitive memory, CognitiveIngestionTarget)
├── spector-ingestion       (IngestionPipeline, IngestionTarget, FileDiscoveryService)
├── spector-config          (configuration)
└── spector-embed-api       (embedding)
```

`SpectorRuntime.ingestion()` builds the `IngestionPipeline` with the correct `IngestionTarget` (engine or cognitive) and reads chunking configuration from `spector.yml`.

## Service Accessors

| Accessor | Returns | Description |
|----------|---------|-------------|
| `runtime.search()` | `SearchHandler` | Mode-aware search (engine or memory) |
| `runtime.ingestion()` | `IngestionHandler` | Mode-aware ingestion (text, file, directory) |
| `runtime.engine()` | `SpectorEngine` | Direct engine access (always available) |
| `runtime.memory()` | `SpectorMemory` | Direct memory access (null if disabled) |
| `runtime.mode()` | `SpectorMode` | Current mode (SEARCH or MEMORY) |

## Usage

### Search & Ingest via Handlers

```java
try (var runtime = SpectorRuntime.from(props, embedder, true)) {
    // Search — mode-aware (routes to engine or memory)
    var results = runtime.search().query("query text", 10);
    
    // Ingest text — pipeline auto-chunks if content > threshold
    runtime.ingestion().ingest("doc-1", "content text");
    
    // Ingest directory — discovers files, chunks, embeds, and stores
    runtime.ingestion().ingest(Path.of("/docs"), "**/*.md", 800, 100, ".git");
    
    // Chunked ingestion — returns IngestionResult with chunk count
    var result = runtime.ingestion().ingestChunked("doc-2", longContent);
}
```

### Factory Methods

```java
// Standard — creates engine + optional memory from config
SpectorRuntime.from(props, embedder);

// With writable index (for ingestion)
SpectorRuntime.from(props, embedder, true);

// Engine-only (no memory)
SpectorRuntime.engineOnly(engine, props);
```

## Configuration

Runtime behavior is driven by `spector.yml`:

```yaml
spector:
  mode: search              # search or memory
  engine:
    dimensions: 768
    persistence-mode: DISK
    data-directory: .spector-data
  embedding:
    model: nomic-embed-text
    base-url: http://localhost:11434
  memory:
    enabled: true
    persistence-mode: DISK
    persistence-path: .spector-memory
  ingestion:
    root-directory: /path/to/docs
    file-pattern: "**/*.md"
    chunk-size: 800
    chunk-overlap: 100
```

## Consumers

| Module | How it uses SpectorRuntime |
|--------|---------------------------|
| `spector-cli` | `SpectorCtl` — CLI commands call `runtime.search()` / `runtime.ingestion()` |
| `spector-mcp` | `SpectorMcpServer(runtime)` — MCP tools call runtime handlers |
| `spector-server` | `SpectorServer(runtime)` — REST API endpoints |
| `spector-dist` | Fat JAR bundles runtime + all modules |

## Dependencies

```xml
<dependency>
    <groupId>com.spectrayan</groupId>
    <artifactId>spector-runtime</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```
