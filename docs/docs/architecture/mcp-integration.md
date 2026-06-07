---
title: "MCP Integration — Model Context Protocol Server"
description: "Connect AI agents to Spector via the built-in MCP server. 13 tools for search, memory, and cognitive operations. Works with Claude Desktop, Cursor, and custom agents."
---

# 🤖 MCP Integration Architecture

> **Spector's built-in Model Context Protocol (MCP) server gives any AI agent instant, in-process access to SIMD-accelerated vector search — with zero network overhead.**

---

## Overview

The [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) is Anthropic's open standard for connecting AI agents to external data sources. Instead of writing custom Python glue-code with orchestration frameworks, agents connect directly to an MCP server via JSON-RPC and autonomously invoke tools.

**Spector's MCP server runs in-process.** When Claude Desktop or Cursor calls `engine_search`, the request goes from JSON-RPC → Java method call → SIMD kernel — never touching a network socket. This makes Spector **23–113× faster than Python-based MCP servers** that route through HTTP/gRPC.

---

## Architecture

```mermaid
graph LR
    subgraph "AI Agent (Claude, Cursor, etc.)"
        Agent["🤖 AI Agent"]
    end

    subgraph "spector-mcp (in-process)"
        Transport["📡 StdioTransport<br/><i>JSON-RPC 2.0</i>"]
        Server["⚡ SpectorMcpServer<br/><i>Thin orchestrator</i>"]
        
        subgraph Providers
            TR["🔧 SpectorToolRegistry"]
            RP["📄 SpectorResourceProvider"]
            PP["💬 SpectorPromptProvider"]
        end

        subgraph "Engine Tools"
            T1["EngineSearchTool"]
            T2["EngineHybridSearchTool"]
            T3["EngineRagTool"]
            T4["EngineIngestTool"]
            T5["EngineDeleteTool"]
            T6["EngineStatusTool"]
        end

        subgraph "Memory Tools"
            M1["MemoryRememberTool"]
            M2["MemoryRecallTool"]
            M3["MemoryForgetTool"]
            M4["MemoryIntrospectTool"]
            M5["... 7 more"]
        end

        subgraph Foundation
            SB["ToolSchemaBuilder"]
            RF["ResultFormatter"]
            TH["McpToolHandler<br/><i>Abstract base</i>"]
        end
    end

    subgraph "spector-runtime"
        Runtime["⚡ SpectorRuntime<br/><i>Composition Root</i>"]
    end

    subgraph "spector-engine"
        Engine["🔧 SpectorEngine"]
    end

    subgraph "spector-core"
        SIMD["🔬 SIMD Kernels<br/><i>AVX2/AVX-512/NEON</i>"]
    end

    Agent -- "stdin/stdout" --> Transport
    Transport --> Server
    Server --> TR & RP & PP
    TR --> T1 & T2 & T3 & T4 & T5 & T6
    T1 & T2 & T3 & T4 & T5 & T6 --> TH
    T1 & T2 & T3 & T4 & T5 & T6 --> SB
    T1 & T2 & T3 --> RF
    T6 --> RF
    T1 & T2 & T3 & T4 & T5 & T6 --> Runtime
    Runtime --> Engine
    Engine --> SIMD
```

### Data Flow

```mermaid
sequenceDiagram
    participant Agent as 🤖 AI Agent
    participant MCP as 📡 MCP Transport (stdio)
    participant Handler as 🔧 McpToolHandler
    participant Runtime as ⚡ SpectorRuntime
    participant Engine as 🔧 SpectorEngine
    participant SIMD as 🔬 SIMD Kernel

    Agent->>MCP: tools/call {"name": "engine_search", "arguments": {"query": "..."}}
    MCP->>Handler: EngineSearchTool.execute(runtime, args)
    
    Note over Handler: requireString(args, "query")<br/>optionalInt(args, "top_k", 5)
    
    Handler->>Runtime: runtime.search().query(query, topK)
    Runtime->>Engine: engine.search(query, topK)
    Engine->>SIMD: HNSW traversal (off-heap MemorySegment)
    SIMD-->>Engine: ScoredResult[] (~100µs)
    Engine-->>Runtime: SearchResponse
    Runtime-->>Handler: SpectorResult[]
    
    Note over Handler: ResultFormatter.formatSearchResults()<br/>McpToolHandler.textResult()
    
    Handler-->>MCP: CallToolResult (text content)
    MCP-->>Agent: {"content": [{"type": "text", "text": "Found 5 results..."}]}
```

---

## Module Structure

```
spector-mcp/src/main/java/com/spectrayan/spector/mcp/
├── SpectorMcpServer.java          ← Thin orchestrator (assembly only)
├── SpectorMcpMain.java            ← CLI entry point
├── schema/
│   └── ToolSchemaBuilder.java     ← Type-safe fluent builder for JSON schemas
├── tools/
│   ├── McpToolHandler.java        ← Abstract base with timing, error handling
│   ├── SpectorToolRegistry.java   ← Mode-aware tool discovery & registration
│   ├── engine/                    ← Engine tools (available in SEARCH/HYBRID mode)
│   │   ├── EngineSearchTool.java
│   │   ├── EngineHybridSearchTool.java
│   │   ├── EngineRagTool.java
│   │   ├── EngineIngestTool.java
│   │   ├── EngineDeleteTool.java
│   │   └── EngineStatusTool.java
│   └── memory/                    ← Memory tools (available in MEMORY/HYBRID mode)
│       ├── MemoryRememberTool.java
│       ├── MemoryRecallTool.java
│       ├── MemoryForgetTool.java
│       ├── MemoryReinforceTool.java
│       ├── MemorySuppressTool.java
│       ├── MemoryResolveTool.java
│       ├── MemoryIntrospectTool.java
│       ├── MemoryScratchpadTool.java
│       ├── MemoryReminderTool.java
│       ├── MemoryWhyNotTool.java
│       └── MemoryStatusTool.java
├── resources/
│   └── SpectorResourceProvider.java   ← Resource definitions & handlers
├── prompts/
│   └── SpectorPromptProvider.java     ← Prompt templates & handlers
└── util/
    └── ResultFormatter.java           ← Search result formatting utilities
```

---

## Tool Reference

### `engine_search`

Performs semantic similarity search using vector embeddings. Requires an embedding provider (e.g., Ollama) to be configured.

| Parameter | Type | Required | Default | Description |
|:---|:---|:---|:---|:---|
| `query` | string | ✅ | — | Natural language search query |
| `top_k` | integer | ❌ | 5 | Number of results to return (1–100) |

### `engine_hybrid_search`

Combined keyword (BM25) + semantic (vector) search with reciprocal rank fusion. Falls back to keyword-only if no embedding provider is configured.

| Parameter | Type | Required | Default | Description |
|:---|:---|:---|:---|:---|
| `query` | string | ✅ | — | Search query for both keyword and semantic matching |
| `top_k` | integer | ❌ | 5 | Number of results to return |
| `mode` | enum | ❌ | `hybrid` | Search mode: `hybrid`, `keyword`, or `vector` |

### `engine_rag`

Retrieval-Augmented Generation — retrieves relevant context with source citations formatted for LLM consumption.

| Parameter | Type | Required | Default | Description |
|:---|:---|:---|:---|:---|
| `query` | string | ✅ | — | The question or topic to retrieve context for |
| `top_k` | integer | ❌ | 5 | Number of context passages to retrieve |

### `engine_ingest`

Ingests a document into the search index with automatic embedding and optional chunking.

| Parameter | Type | Required | Default | Description |
|:---|:---|:---|:---|:---|
| `id` | string | ✅ | — | Unique document identifier |
| `content` | string | ✅ | — | Document text content |
| `title` | string | ❌ | — | Optional document title |

### `engine_delete`

Removes a document from the search index by ID.

| Parameter | Type | Required | Default | Description |
|:---|:---|:---|:---|:---|
| `id` | string | ✅ | — | Document ID to delete |

### `engine_status`

Returns engine metadata including document count, dimensions, SIMD capabilities, embedding provider status, and GPU availability.

| Parameter | Type | Required | Default | Description |
|:---|:---|:---|:---|:---|
| *(none)* | — | — | — | No input parameters required |

### Memory Tools

| Tool | Parameters | Description |
|:---|:---|:---|
| `memory_remember` | `id`, `text`, `type`, `source`, `tags` | Store a cognitive memory |
| `memory_recall` | `query`, `top_k`, `tags`, `types` | Cognitive recall across all tiers |
| `memory_forget` | `id` | Tombstone a memory |
| `memory_reinforce` | `id`, `valence` | Positive/negative feedback |
| `memory_suppress` | `id`, `reason` | Suppress from recall |
| `memory_resolve` | `id` | Mark as resolved |
| `memory_introspect` | `topic` | Topic knowledge analysis |
| `memory_scratchpad` | `text` | Quick-write to working memory |
| `memory_reminder` | `text`, `delay_seconds`, `tags` | Schedule future reminder |
| `memory_why_not` | `memory_id`, `query`, `top_k` | Explain why not recalled |
| `memory_status` | *(none)* | Tier counts and partition info |

---

## Extending the MCP Server

### Adding a New Tool

Every tool extends `McpToolHandler`, which handles timing, error handling, and argument parsing. You implement four methods:

```java
public abstract class McpToolHandler {
    abstract String name();
    abstract String description();
    abstract Map<String, Object> inputSchema();
    abstract CallToolResult execute(SpectorEngine engine, Map<String, Object> args);

    // Base class automatically provides:
    // - Timing wrapper (nanoTime → milliseconds)
    // - Structured error handling with logging
    // - Argument parsing: requireString(), optionalInt(), optionalString()
    // - Result factories: textResult(), errorResult()
}
```

Define the tool schema with `ToolSchemaBuilder`:

```java
var schema = ToolSchemaBuilder.object()
    .requiredString("query", "Natural language search query.")
    .optionalInt("top_k", "Number of results to return.", 5)
    .optionalEnum("mode", "Search mode.", "hybrid", "hybrid", "keyword", "vector")
    .build();
```

Register the tool in `SpectorToolRegistry.handlers()`:

```java
List.of(
    new EngineSearchTool(),
    new EngineHybridSearchTool(),
    new EngineRagTool(),
    new EngineIngestTool(),
    new EngineDeleteTool(),
    new EngineStatusTool(serverVersion)
    // new YourNewTool()  ← just add here
);
```

---

## Performance: Why In-Process Wins

### The Python MCP Tax

Python MCP servers introduce multiple layers of overhead:

```mermaid
graph LR
    A1["🤖 Agent"] --> B1["JSON-RPC"]
    B1 --> C1["🐍 Python process"]
    C1 --> D1["Deserialize"]
    D1 --> E1["HTTP/gRPC round-trip"]
    E1 --> F1["Vector DB"]
    F1 --> G1["Serialize response"]
    G1 --> H1["JSON-RPC"]
    H1 --> I1["🤖 Agent"]

    style C1 fill:#e74c3c,color:white
    style E1 fill:#e74c3c,color:white
```

> **Total: 2–10ms per query** (network + GIL + serialization)

### Spector's Zero-Copy Path

```mermaid
graph LR
    A2["🤖 Agent"] --> B2["JSON-RPC"]
    B2 --> C2["☕ Virtual Thread"]
    C2 --> D2["SpectorEngine.search()"]
    D2 --> E2["Off-heap MemorySegment"]
    E2 --> F2["SIMD registers"]
    F2 --> G2["✅ Results"]

    style C2 fill:#00b894,color:white
    style E2 fill:#00b894,color:white
    style G2 fill:#00b894,color:white
```

> **Total: 88µs p50 per query** (23–113× faster)

| Bottleneck | Python MCP | Spector MCP |
|:---|:---|:---|
| Network round-trip | 500–2,000µs | **0µs** (in-process) |
| JSON serialization | 100–500µs | **0µs** (direct Java objects) |
| Python GIL contention | Blocks concurrent queries | **0µs** (Virtual Threads) |
| GC pressure | Heap allocation per query | **0µs** (off-heap Panama) |
| Search computation | ~100µs (native C++) | **~100µs** (Panama SIMD) |
| **Total** | **2,000–10,000µs** | **88µs p50** |

---

## Security Considerations

> [!WARNING]
> The `engine_ingest` and `engine_delete` tools allow agents to modify the search index. In production environments, consider:
> - Running the MCP server in read-only mode (expose only search tools)
> - Using `SEARCH` mode to disable memory write tools
> - Implementing document-level access control
> - Rate limiting ingestion operations
> - Auditing all write operations

---

## See Also

- [MCP Server Usage Guide](../sdk-usage/mcp-server.md) — Practical setup for Claude Desktop, Cursor, and custom agents
- [Architecture Overview](overview.md) — Full system architecture
- [Core Concepts](core-concepts.md) — HNSW, BM25, RRF deep-dives
