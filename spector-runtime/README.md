# ⚡ Spector Runtime

**Unified application context for a running Spector instance.**

`SpectorRuntime` composes the two core subsystems into a single, config-driven entry point that all consumers (MCP server, REST API, CLI) depend on:

- **`SpectorEngine`** — vector search, ingestion, RAG (always created)
- **`SpectorMemory`** — cognitive memory with biologically-inspired mechanisms (opt-in via `spector.memory.enabled: true`)

## Why a Separate Module?

`spector-memory` already depends on `spector-engine`. Placing `SpectorRuntime` in either would create a circular dependency. This thin module resolves that by depending on both.

```
spector-runtime
├── spector-engine   (search)
├── spector-memory   (cognitive memory)
├── spector-commons  (config)
└── spector-embed-api (embedding)
```

## Usage

### With Configuration File

```java
SpectorProperties props = SpectorProperties.builder()
    .configFile(Path.of("spector.yml"))
    .build();

EmbeddingProvider embedder = createEmbedder(props);

try (SpectorRuntime runtime = SpectorRuntime.from(props, embedder)) {
    // Search
    runtime.engine().ingest("doc1", "title", "content");
    var results = runtime.engine().search("query", 10);

    // Cognitive memory (if enabled)
    if (runtime.hasMemory()) {
        runtime.memory().remember("pref", "User likes dark mode",
            MemoryType.SEMANTIC, "preferences").join();
    }
}
```

### Engine-Only (No Memory)

```java
SpectorEngine engine = new SpectorEngine(config, embedder);
SpectorRuntime runtime = SpectorRuntime.engineOnly(engine, props);
```

## Configuration

Memory is opt-in via `spector.yml`:

```yaml
spector:
  engine:
    dimensions: 768
    persistence-mode: DISK
    data-directory: .spector-data
  embedding:
    model: nomic-embed-text
    base-url: http://localhost:11434
  memory:
    enabled: true                      # ← Enable cognitive memory
    persistence-mode: DISK
    persistence-path: .spector-memory
    capacity: 100000
    decay-enabled: true
    consolidation-interval: 60s
```

When `memory.enabled` is `false` (default), `SpectorRuntime` only creates the engine. When `true`, it creates both — sharing the same `EmbeddingProvider` for consistent vector dimensions.

## API

| Method | Description |
|--------|-------------|
| `SpectorRuntime.from(props, embedder)` | Factory — creates engine + optional memory from config |
| `SpectorRuntime.engineOnly(engine, props)` | Wrap an existing engine (no memory) |
| `runtime.engine()` | Returns the `SpectorEngine` (never null) |
| `runtime.memory()` | Returns `SpectorMemory` or `null` if not enabled |
| `runtime.hasMemory()` | `true` if cognitive memory is available |
| `runtime.properties()` | The configuration properties used |
| `runtime.close()` | Closes engine and memory (implements `AutoCloseable`) |

## Consumers

| Module | How it uses SpectorRuntime |
|--------|---------------------------|
| `spector-mcp` | `SpectorMcpServer(runtime)` — registers 6 search + 7 memory tools |
| `spector-server` | `SpectorServer(runtime, port, apiKey)` — REST API |
| `spector-dist` | Fat JAR bundles runtime + all modules |

## Dependencies

```xml
<dependency>
    <groupId>com.spectrayan</groupId>
    <artifactId>spector-runtime</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```
