# âš¡ Spector MCP Server

**Agent-native search and cognitive memory integration for the Spector AI Memory Backbone.**

Give any AI agent (Claude Desktop, Cursor, autonomous agents) instant access to Spector's SIMD-accelerated vector search engine and cognitive memory â€” with zero network overhead. The MCP server runs in-process via `SpectorRuntime`, calling the engine and memory directly on virtual threads for **88Âµs p50** query latency.

## Architecture

```
AI Agent â”€â”€JSON-RPC (stdio)â”€â”€â–º SpectorMcpServer (thin orchestrator)
                                â”œâ”€â”€ SpectorRuntime
                                â”‚   â”œâ”€â”€ SpectorEngine (search, ingest, RAG)
                                â”‚   â””â”€â”€ SpectorMemory (cognitive â€” optional)
                                â”œâ”€â”€ SpectorToolRegistry
                                â”‚   â”œâ”€â”€ EngineSearchTool  â”€â”€â–º engine.search()
                                â”‚   â”œâ”€â”€ EngineHybridSearchTool    â”€â”€â–º engine.keywordSearch()
                                â”‚   â”œâ”€â”€ EngineRagTool        â”€â”€â–º engine.search() + formatting
                                â”‚   â”œâ”€â”€ EngineIngestTool  â”€â”€â–º engine.ingest()
                                â”‚   â”œâ”€â”€ EngineDeleteTool  â”€â”€â–º engine.delete()
                                â”‚   â”œâ”€â”€ EngineStatusTool    â”€â”€â–º engine metadata
                                â”‚   â”œâ”€â”€ MemoryRememberTool    â”€â”€â–º memory.remember()
                                â”‚   â”œâ”€â”€ MemoryRecallTool       â”€â”€â–º memory.recall()
                                â”‚   â”œâ”€â”€ MemoryStatusTool        â”€â”€â–º memory.introspect()
                                â”‚   â”œâ”€â”€ MemoryReinforceTool     â”€â”€â–º memory.reinforce()
                                â”‚   â”œâ”€â”€ MemoryForgetTool        â”€â”€â–º memory.forget()
                                â”‚   â”œâ”€â”€ MemoryIntrospectTool    â”€â”€â–º memory.introspect()
                                â”‚   â””â”€â”€ MemoryScratchpadTool â”€â”€â–º memory.remember()
                                â”œâ”€â”€ SpectorResourceProvider
                                â””â”€â”€ SpectorPromptProvider

Total overhead: 88Âµs p50 per query (23â€“113Ã— faster than Python MCP servers)
```

### Module Structure

```
spector-mcp/src/main/java/com/spectrayan/spector/mcp/
â”œâ”€â”€ SpectorMcpServer.java          â† Thin orchestrator (accepts SpectorRuntime)
â”œâ”€â”€ SpectorMcpMain.java            â† CLI entry point
â”œâ”€â”€ schema/
â”‚   â””â”€â”€ ToolSchemaBuilder.java     â† Type-safe fluent builder for JSON schemas
â”œâ”€â”€ tools/
â”‚   â”œâ”€â”€ McpToolHandler.java        â† Abstract base with timing, error handling
â”‚   â”œâ”€â”€ SpectorToolRegistry.java   â† Tool discovery & registration
â”‚   â”œâ”€â”€ EngineSearchTool.java
â”‚   â”œâ”€â”€ EngineHybridSearchTool.java
â”‚   â”œâ”€â”€ EngineRagTool.java
â”‚   â”œâ”€â”€ EngineIngestTool.java
â”‚   â”œâ”€â”€ EngineDeleteTool.java
â”‚   â”œâ”€â”€ EngineStatusTool.java
â”‚   â”œâ”€â”€ MemoryRememberTool.java
â”‚   â”œâ”€â”€ MemoryRecallTool.java
â”‚   â”œâ”€â”€ MemoryStatusTool.java
â”‚   â”œâ”€â”€ MemoryReinforceTool.java
â”‚   â”œâ”€â”€ MemoryForgetTool.java
â”‚   â”œâ”€â”€ MemoryIntrospectTool.java
â”‚   â””â”€â”€ MemoryScratchpadTool.java
â”œâ”€â”€ resources/
â”‚   â””â”€â”€ SpectorResourceProvider.java
â”œâ”€â”€ prompts/
â”‚   â””â”€â”€ SpectorPromptProvider.java
â””â”€â”€ util/
    â””â”€â”€ ResultFormatter.java
```

## MCP Tools

### Engine Tools (available in SEARCH/HYBRID mode)

| Tool | Description |
|:---|:---|
| `engine_search` | Semantic similarity search with auto-embedding |
| `engine_hybrid_search` | Combined keyword (BM25) + vector search with RRF |
| `engine_rag` | Retrieval-Augmented Generation with source citations |
| `engine_ingest` | Document ingestion with auto-embedding + chunking |
| `engine_delete` | Document deletion by ID |
| `engine_status` | Engine metadata, SIMD capabilities, GPU status |

### Memory Tools (available in MEMORY/HYBRID mode)

| Tool | Description |
|:---|:---|
| `memory_remember` | Store a semantic memory with tags and source |
| `memory_recall` | Cognitive recall with fused scoring across tiers |
| `memory_status` | Memory tier counts and persistence info |
| `memory_reinforce` | Report positive/negative outcome for a memory |
| `memory_forget` | Tombstone a memory by ID |
| `memory_introspect` | Metamemory self-analysis on a topic |
| `memory_scratchpad` | Quick-write to working memory |

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
    "spector": {
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
| Search latency | 2â€“10ms (network + Python GIL) | **88Âµs p50** (in-process SIMD) |
| Network overhead | HTTP/gRPC round-trip | **Zero** (direct method call) |
| GC pauses | Python/JVM heap pressure | **â‰¤0.01%** (100% off-heap Panama) |
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

// 2. Register in SpectorToolRegistry.handlers() â€” one line:
List.of(
    new EngineSearchTool(),
    // ... existing tools ...
    new MyTool()  // â† add here
);
```

### Key Design Decisions

- **Template Method** (`McpToolHandler`) â€” timing, error handling, and arg parsing in the base class
- **Builder Pattern** (`ToolSchemaBuilder`) â€” type-safe JSON schema, no nested `Map.of()`
- **Open/Closed Principle** (`SpectorToolRegistry`) â€” add a tool = 1 class + 1 line
- **Zero runtime overhead** â€” schemas built once, reused forever

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

