# ⚡ Spector MCP Server

**Agent-native search and cognitive memory integration for the Spector AI Memory Backbone.**

Give any AI agent (Claude Desktop, Cursor, autonomous agents) instant access to Spector's SIMD-accelerated vector search engine and cognitive memory — with zero network overhead. The MCP server runs in-process via `SpectorRuntime`, calling the engine and memory directly on virtual threads for **88µs p50** query latency.

## Architecture

```
AI Agent ──JSON-RPC (stdio)──► SpectorMcpServer (thin orchestrator)
                                ├── SpectorRuntime
                                │   ├── SpectorEngine (search, ingest, RAG)
                                │   └── SpectorMemory (cognitive — optional)
                                ├── SpectorToolRegistry
                                │   ├── SemanticSearchTool  ──► engine.search()
                                │   ├── HybridSearchTool    ──► engine.keywordSearch()
                                │   ├── RagQueryTool        ──► engine.search() + formatting
                                │   ├── IngestDocumentTool  ──► engine.ingest()
                                │   ├── DeleteDocumentTool  ──► engine.delete()
                                │   ├── EngineStatusTool    ──► engine metadata
                                │   ├── CoreMemoryAppendTool    ──► memory.remember()
                                │   ├── RecallContextTool       ──► memory.recall()
                                │   ├── MemoryStatusTool        ──► memory.introspect()
                                │   ├── MemoryReinforceTool     ──► memory.reinforce()
                                │   ├── MemoryForgetTool        ──► memory.forget()
                                │   ├── MemoryIntrospectTool    ──► memory.introspect()
                                │   └── WorkingMemoryScratchpadTool ──► memory.remember()
                                ├── SpectorResourceProvider
                                └── SpectorPromptProvider

Total overhead: 88µs p50 per query (23–113× faster than Python MCP servers)
```

### Module Structure

```
spector-mcp/src/main/java/com/spectrayan/spector/mcp/
├── SpectorMcpServer.java          ← Thin orchestrator (accepts SpectorRuntime)
├── SpectorMcpMain.java            ← CLI entry point
├── schema/
│   └── ToolSchemaBuilder.java     ← Type-safe fluent builder for JSON schemas
├── tools/
│   ├── McpToolHandler.java        ← Abstract base with timing, error handling
│   ├── SpectorToolRegistry.java   ← Tool discovery & registration
│   ├── SemanticSearchTool.java
│   ├── HybridSearchTool.java
│   ├── RagQueryTool.java
│   ├── IngestDocumentTool.java
│   ├── DeleteDocumentTool.java
│   ├── EngineStatusTool.java
│   ├── CoreMemoryAppendTool.java
│   ├── RecallContextTool.java
│   ├── MemoryStatusTool.java
│   ├── MemoryReinforceTool.java
│   ├── MemoryForgetTool.java
│   ├── MemoryIntrospectTool.java
│   └── WorkingMemoryScratchpadTool.java
├── resources/
│   └── SpectorResourceProvider.java
├── prompts/
│   └── SpectorPromptProvider.java
└── util/
    └── ResultFormatter.java
```

## MCP Tools

### Search Tools (always available)

| Tool | Description |
|:---|:---|
| `semantic_search` | Semantic similarity search with auto-embedding |
| `hybrid_search` | Combined keyword (BM25) + vector search with RRF |
| `rag_query` | Retrieval-Augmented Generation with source citations |
| `ingest_document` | Document ingestion with auto-embedding + chunking |
| `delete_document` | Document deletion by ID |
| `engine_status` | Engine metadata, SIMD capabilities, GPU status |

### Cognitive Memory Tools (enabled via `spector.memory.enabled: true`)

| Tool | Description |
|:---|:---|
| `core_memory_append` | Store a semantic memory with tags and source |
| `recall_context` | Cognitive recall with fused scoring across tiers |
| `memory_status` | Memory tier counts and persistence info |
| `memory_reinforce` | Report positive/negative outcome for a memory |
| `memory_forget` | Tombstone a memory by ID |
| `memory_introspect` | Metamemory self-analysis on a topic |
| `working_memory_scratchpad` | Quick-write to working memory |

## Quick Start

### 1. Build

```bash
mvn package -pl spector-dist -am -DskipTests
```

### 2. Configuration

Create a `spector.yml` with your settings:

```yaml
spector:
  engine:
    dimensions: 768
    persistence-mode: DISK
    data-directory: .spector/index
  embedding:
    model: nomic-embed-text
    base-url: http://localhost:11434
  memory:
    enabled: true                # Enable cognitive memory tools
    persistence-path: .spector-memory
```

### 3. Claude Desktop Configuration

Add to your `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "spector-search": {
      "command": "java",
      "args": [
        "--add-modules", "jdk.incubator.vector",
        "--enable-native-access=ALL-UNNAMED",
        "--enable-preview",
        "-jar", "/path/to/spector-dist/target/spector.jar",
        "--config", "/path/to/spector.yml"
      ]
    }
  }
}
```

### 4. CLI Options

```
--config <FILE>        Explicit config file (YAML or .properties)
--profile <NAME>       Configuration profile (loads spector-{profile}.yml)
--dims <N>             Vector dimensionality (default: 384)
--capacity <N>         Max document capacity (default: 100000)
--data-dir <DIR>       Persistence directory (auto-enables DISK mode)
--ollama-url <URL>     Ollama embedding server URL
--ollama-model <NAME>  Ollama embedding model name
--help, -h             Show help
```

> **Recommended:** Use a `spector.yml` config file. CLI flags override config file values.

## Why Spector MCP is Different

| Feature | Python Vector DB MCP | **Spector MCP** |
|:---|:---|:---|
| Search latency | 2–10ms (network + Python GIL) | **88µs p50** (in-process SIMD) |
| Network overhead | HTTP/gRPC round-trip | **Zero** (direct method call) |
| GC pauses | Python/JVM heap pressure | **≤0.01%** (100% off-heap Panama) |
| Concurrent queries | Limited by Python GIL | **61,000 QPS** (Virtual Threads) |
| Dependencies | Python framework stack | **Single JAR** (zero Python) |
| Cognitive memory | External service (Mem0, Zep) | **Built-in** (opt-in via config) |

## Design Patterns

### Adding a New Tool

To add a new MCP tool, create a class extending `McpToolHandler` and register it:

```java
// 1. Create the tool (one focused class)
public final class MyTool extends McpToolHandler {
    @Override public String name() { return "my_tool"; }
    @Override public String description() { return "Does something useful."; }
    @Override public Map<String, Object> inputSchema() {
        return ToolSchemaBuilder.object()
                .requiredString("input", "The input.")
                .optionalInt("count", "How many.", 5)
                .build();
    }
    @Override public CallToolResult execute(SpectorEngine engine, Map<String, Object> args) {
        String input = requireString(args, "input");
        int count = optionalInt(args, "count", 5);
        return textResult("Result: " + input);
    }
}

// 2. Register in SpectorToolRegistry.handlers() — one line:
List.of(
    new SemanticSearchTool(),
    // ... existing tools ...
    new MyTool()  // ← add here
);
```

### Key Design Decisions

- **Template Method** (`McpToolHandler`) — timing, error handling, and arg parsing in the base class
- **Builder Pattern** (`ToolSchemaBuilder`) — type-safe JSON schema, no nested `Map.of()`
- **Open/Closed Principle** (`SpectorToolRegistry`) — add a tool = 1 class + 1 line
- **Zero runtime overhead** — schemas built once, reused forever

## Protocol Support

- **Transport:** Stdio (JSON-RPC 2.0 over stdin/stdout)
- **MCP SDK:** Official Anthropic Java SDK (`io.modelcontextprotocol.sdk:mcp`)
- **Capabilities:** Tools, Resources, Prompts
- **Java Version:** 25+ (Virtual Threads, Vector API, Panama FFM)

## Test Suite

```
Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Covers: tool registry, all tool handlers, schema builder, argument validation.
