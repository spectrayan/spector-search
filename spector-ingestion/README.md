# spector-ingestion 📥

> **Pure ingestion utilities — file discovery, text chunking, and title extraction.**

`spector-ingestion` is a utility module with **no dependency on engine, runtime, or memory**. It provides the building blocks that `IngestionHandler` (in `spector-runtime`) uses for file-based ingestion.

---

## 🏗️ Architecture

```
spector-ingestion (pure utility)
├── FileIngestionService    — file discovery + title extraction
├── IngestionPipeline       — chunking pipeline with configurable strategies
├── IngestionResult         — result record for ingestion operations
└── EmbeddingProviderFactory — embedding provider creation

Dependencies:
├── spector-config     (configuration)
└── spector-embed-api  (embedding SPI)
```

> [!IMPORTANT]
> This module does **NOT** depend on `spector-engine` or `spector-runtime`. Mode-aware ingestion routing is handled by `IngestionHandler` in `spector-runtime`.

---

## 🚀 Key APIs

### File Discovery

```java
// Build from config
var service = FileIngestionService.fromProperties(props, rootDir);

// Or use the builder
var service = FileIngestionService.builder()
    .rootDirectory(Path.of("/docs"))
    .filePattern("**/*.md")
    .chunkSize(800)
    .chunkOverlap(100)
    .skipDirs(".git", ".idea")
    .build();

// Discover matching files
List<Path> files = service.discover();
```

### Title Extraction

```java
// Extract title from markdown heading or use fallback
String title = FileIngestionService.extractTitle(content, "fallback.md");
```

### Chunking Pipeline

```java
var pipeline = new IngestionPipeline(target, embeddingProvider);

// Single document
IngestionResult result = pipeline.ingest("doc-1", "Hello world");

// Chunked (auto-splits large docs)
IngestionResult result = pipeline.ingestChunked("doc-1", longText);

// Streaming file (bounded memory for large files)
IngestionResult result = pipeline.ingestFile(Path.of("corpus.txt"), "corpus", 512, 64);
```

---

## 📊 Result Tracking

```java
public record IngestionResult(
    String documentId,
    int chunksStored,
    List<String> failures,
    long durationMs
) {
    boolean isFullSuccess();  // true if no failures
}
```

---

## 🔗 How It Fits

All entry points (CLI, MCP, Server) route through `SpectorRuntime`:

```
CLI/MCP/Server → SpectorRuntime.ingestion() → IngestionHandler
                                                    │
                                          ┌─────────┼─────────┐
                                          ▼                    ▼
                                     engine/memory    FileIngestionService
                                    (mode-aware)       (discovery + chunking)
```

`IngestionHandler` composes this module's utilities with mode-aware routing — SEARCH mode routes to engine, MEMORY mode routes to cognitive memory.
