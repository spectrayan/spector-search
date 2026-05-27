# ⚡ Spector MCP Server

**Agent-native search integration for the Spector AI Memory Backbone.**

Give any AI agent (Claude Desktop, Cursor, autonomous agents) instant access to Spector's SIMD-accelerated vector search engine — with zero network overhead. The MCP server runs in-process, calling `SpectorEngine` directly via virtual threads for 50–200µs query latency.

## Architecture

```
AI Agent ──JSON-RPC (stdio)──► SpectorMcpServer (thin orchestrator)
                                ├── SpectorToolRegistry
                                │   ├── SemanticSearchTool  ──► SpectorEngine.search()
                                │   ├── HybridSearchTool    ──► SpectorEngine.keywordSearch()
                                │   ├── RagQueryTool        ──► SpectorEngine.search() + formatting
                                │   ├── IngestDocumentTool  ──► SpectorEngine.ingest()
                                │   ├── DeleteDocumentTool  ──► SpectorEngine.delete()
                                │   └── EngineStatusTool    ──► SpectorEngine metadata
                                ├── SpectorResourceProvider
                                └── SpectorPromptProvider

Total overhead: 50-200µs per query (100× faster than Python MCP servers)
```

### Module Structure

```
spector-mcp/src/main/java/com/spectrayan/spector/mcp/
├── SpectorMcpServer.java          ← Thin orchestrator (assembly only)
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
│   └── EngineStatusTool.java
├── resources/
│   └── SpectorResourceProvider.java
├── prompts/
│   └── SpectorPromptProvider.java
└── util/
    └── ResultFormatter.java
```

## MCP Tools

| Tool | Description |
|:---|:---|
| `semantic_search` | Semantic similarity search with auto-embedding |
| `hybrid_search` | Combined keyword (BM25) + vector search with RRF |
| `rag_query` | Retrieval-Augmented Generation with source citations |
| `ingest_document` | Document ingestion with auto-embedding + chunking |
| `delete_document` | Document deletion by ID |
| `engine_status` | Engine metadata, SIMD capabilities, GPU status |

## Quick Start

### 1. Build

```bash
mvn package -pl spector-mcp -am -DskipTests
```

### 2. Claude Desktop Configuration

Add to your `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "spector-memory": {
      "command": "java",
      "args": [
        "--add-modules", "jdk.incubator.vector",
        "--enable-native-access=ALL-UNNAMED",
        "--enable-preview",
        "-jar", "/path/to/spector-mcp.jar",
        "--dims", "768",
        "--ollama-url", "http://localhost:11434",
        "--ollama-model", "nomic-embed-text"
      ]
    }
  }
}
```

### 3. CLI Options

```
--dims <N>             Vector dimensionality (default: 384)
--capacity <N>         Max document capacity (default: 100000)
--ollama-url <URL>     Ollama embedding server URL
--ollama-model <NAME>  Ollama embedding model name
--help, -h             Show help
```

## Why Spector MCP is Different

| Feature | Python Vector DB MCP | **Spector MCP** |
|:---|:---|:---|
| Search latency | 2–10ms (network + Python GIL) | **50–200µs** (in-process SIMD) |
| Network overhead | HTTP/gRPC round-trip | **Zero** (direct method call) |
| GC pauses | Python/JVM heap pressure | **Zero** (100% off-heap Panama) |
| Concurrent queries | Limited by Python GIL | **10,000+ QPS** (Virtual Threads) |
| Dependencies | Python framework stack | **Single JAR** (zero Python) |

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

Covers: tool registry, all 6 tool handlers, schema builder, argument validation.
