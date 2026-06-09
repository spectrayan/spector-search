# 🤖 MCP Server Usage Guide

> **Connect any AI agent to Spector's search engine in minutes.**

This guide covers practical setup for Claude Desktop, Cursor IDE, and custom MCP clients.

---

## Quick Start (3 Steps)

### 1. Build the Distribution JAR

```bash
cd spector
mvn package -pl spector-dist -am -DskipTests
```

The fat JAR is produced at `spector-dist/target/spector.jar`.

### 2. Configure Your AI Agent

Add the following to your agent's MCP configuration (see per-agent sections below):

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

### 3. Start Using

Your AI agent now has access to up to 21 tools. With cognitive memory enabled (`spector.memory.enabled: true`), all tools are registered. In `SEARCH` mode, only the 6 engine tools are available:

- *"Search for documents about SIMD acceleration"* → `engine_search`
- *"Find articles mentioning 'Panama' and related to memory management"* → `engine_hybrid_search`
- *"What does the codebase say about quantization?"* → `engine_rag`
- *"Add this document to the index: ..."* → `engine_ingest`
- *"Remember that the user prefers dark mode"* → `memory_remember`
- *"What do you remember about the user's preferences?"* → `memory_recall`

---

## CLI Options

| Flag | Default | Description |
|:---|:---|:---|
| `--config <FILE>` | *(none)* | Explicit config file (YAML or .properties) |
| `--profile <NAME>` | *(none)* | Configuration profile (loads `spector-{profile}.yml`) |
| `--dims <N>` | 384 | Vector dimensionality (must match your embedding model) |
| `--capacity <N>` | 100,000 | Maximum document capacity |
| `--data-dir <DIR>` | *(none)* | Persistence directory (auto-enables DISK mode) |
| `--ollama-url <URL>` | *(none)* | Ollama embedding server URL (e.g., `http://localhost:11434`) |
| `--ollama-model <NAME>` | *(none)* | Ollama embedding model name (e.g., `nomic-embed-text`) |
| `--help`, `-h` | — | Show help message |

> [!TIP]
> **Recommended approach:** Use a `spector.yml` config file rather than CLI flags. CLI flags override values from the config file.

### Configuration File

All settings can be specified in a `spector.yml` file:

```yaml
spector:
  mode: HYBRID                   # SEARCH | MEMORY | HYBRID (default)
  engine:
    dimensions: 768
    capacity: 100000
    persistence-mode: DISK
    data-directory: .spector/index
  embedding:
    model: nomic-embed-text
    base-url: http://localhost:11434
  memory:
    enabled: true                  # Enable cognitive memory tools
    persistence-path: .spector/memory
    nodes-per-partition: 10000     # Records per semantic partition file
```

See the [Configuration Guide](../configuration/parameters.md) for the complete list of settings.

### Choosing Dimensions

The `--dims` flag must match your embedding model's output dimensionality:

| Model | Dimensions | Flag |
|:---|:---|:---|
| `nomic-embed-text` | 768 | `--dims 768` |
| `all-minilm` | 384 | `--dims 384` |
| `mxbai-embed-large` | 1024 | `--dims 1024` |
| `qwen3-embedding` | 4096 | `--dims 4096` |

---

## Agent Configuration

### Claude Desktop

Edit your `claude_desktop_config.json`:

=== "macOS"

    ```
    ~/Library/Application Support/Claude/claude_desktop_config.json
    ```

=== "Windows"

    ```
    %APPDATA%\Claude\claude_desktop_config.json
    ```

=== "Linux"

    ```
    ~/.config/Claude/claude_desktop_config.json
    ```

**Configuration:**

```json
{
  "mcpServers": {
    "spector": {
      "command": "java",
      "args": [
        "--add-modules", "jdk.incubator.vector",
        "--enable-native-access=ALL-UNNAMED",
        "--enable-preview",
        "-jar", "/absolute/path/to/spector.jar",
        "--config", "/absolute/path/to/spector.yml"
      ]
    }
  }
}
```

> [!TIP]
> Use absolute paths for the JAR file. Relative paths may not resolve correctly from Claude Desktop's working directory.

### Cursor IDE

Add to your Cursor MCP settings (`.cursor/mcp.json` in your project, or global settings):

```json
{
  "mcpServers": {
    "spector": {
      "command": "java",
      "args": [
        "--add-modules", "jdk.incubator.vector",
        "--enable-native-access=ALL-UNNAMED",
        "--enable-preview",
        "-jar", "/absolute/path/to/spector.jar",
        "--config", "/absolute/path/to/spector.yml"
      ]
    }
  }
}
```

### Custom MCP Clients

Any application implementing the [MCP client specification](https://modelcontextprotocol.io/docs/concepts/clients) can connect to Spector. The server communicates via **JSON-RPC 2.0 over stdio** (stdin/stdout).

**Key requirements:**

1. Spawn the Java process with the correct JVM flags
2. Write JSON-RPC messages to the process's stdin
3. Read JSON-RPC responses from the process's stdout
4. All logging goes to stderr (stdout is reserved for protocol messages)

**Example initialization sequence:**

```json
// Client → Server
{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-03-26", "capabilities": {}, "clientInfo": {"name": "my-app", "version": "1.0"}}}

// Server → Client
{"jsonrpc": "2.0", "id": 1, "result": {"protocolVersion": "2025-03-26", "capabilities": {"tools": {}}, "serverInfo": {"name": "spector-mcp", "version": "0.1.0"}}}

// Client → Server
{"jsonrpc": "2.0", "method": "notifications/initialized"}
```

---

## MCP Tools Overview

Once connected, your agent has access to these tools:

### Engine Tools (available in SEARCH and HYBRID mode)

| Tool | Description | Requires Embedding |
|:---|:---|:---|
| `engine_search` | Vector similarity search | ✅ |
| `engine_hybrid_search` | Keyword + vector with RRF fusion | Partial (keyword mode works without) |
| `engine_rag` | Retrieval-Augmented Generation context | ✅ |
| `engine_ingest` | Add documents to the index | ✅ (for auto-embedding) |
| `engine_delete` | Remove documents by ID | ❌ |
| `engine_status` | Engine capabilities and stats | ❌ |

### Memory Tools (available in MEMORY and HYBRID mode)

| Tool | Description |
|:---|:---|
| `memory_remember` | Store a cognitive memory with tags and source (ID auto-generated) |
| `memory_recall` | Cognitive recall with fused scoring across tiers |
| `memory_inspect` | Full cognitive X-ray of a memory (header + vector + metadata) |
| `memory_browse` | Browse memories by tag (AND semantics, no vector search) |
| `memory_export` | Bulk JSON export of all live memories |
| `memory_forget` | Tombstone a memory by ID |
| `memory_reinforce` | Report positive/negative outcome for a memory |
| `memory_suppress` | Suppress a memory from recall results |
| `memory_resolve` | Mark a memory as resolved |
| `memory_introspect` | Metamemory self-analysis on a topic |
| `memory_compute_importance` | Read-only importance estimation for text |
| `memory_scratchpad` | Quick-write to working memory |
| `memory_reminder` | Schedule a time-triggered reminder |
| `memory_why_not` | Explain why a memory was not recalled |
| `memory_status` | Memory tier counts and persistence info |

> [!NOTE]
> For full tool schemas and parameter details, see the [MCP Integration Architecture](../architecture/mcp-integration.md#tool-reference) page.

---

## Troubleshooting

### Agent can't find or start the server

- **Check the JAR path** — Use absolute paths, not relative
- **Check Java version** — Spector requires JDK 25+. Run `java -version` to verify
- **Check JVM flags** — `--add-modules jdk.incubator.vector` is required

### "Embedding provider not configured" errors

The `engine_search` and `engine_rag` tools require an embedding provider. Ensure:

1. Ollama is running: `ollama serve`
2. The model is pulled: `ollama pull nomic-embed-text`
3. Both `--ollama-url` and `--ollama-model` are specified in the args

### Stdout corruption / garbled output

Spector redirects all logging to **stderr**. If you see garbled output:

- Check that nothing else is writing to stdout
- Verify the logback configuration routes to stderr
- Check for print statements in any custom code

### Performance issues

- **High latency on first query** — The HNSW index is built lazily. First query triggers graph construction. Subsequent queries are fast.
- **Memory usage** — Vectors are stored off-heap. Monitor with `-XX:NativeMemoryTracking=summary` and `jcmd <pid> VM.native_memory summary`

---

## Adding a New Tool

To extend the MCP server with a custom tool:

1. **Create a new class** extending `McpToolHandler`:

```java
public final class MyCustomTool extends McpToolHandler {
    @Override public String name() { return "my_custom_tool"; }
    @Override public String description() { return "Does something useful."; }
    @Override public Map<String, Object> inputSchema() {
        return ToolSchemaBuilder.object()
                .requiredString("input", "The input parameter.")
                .build();
    }
    @Override public CallToolResult execute(SpectorEngine engine, Map<String, Object> args) {
        String input = requireString(args, "input");
        // Your logic here
        return textResult("Result: " + input);
    }
}
```

2. **Register it** in `SpectorToolRegistry.handlers()`:

```java
List.of(
    new EngineSearchTool(),
    // ... existing tools ...
    new MyCustomTool()  // ← add here
);
```

That's it — the tool is automatically available to all connected agents.

---

## See Also

- [MCP Integration Architecture](../architecture/mcp-integration.md) — Module structure, data flow, and performance analysis
- [Python SDK](python-sdk.md) — Python client wrapping the MCP server
- [Architecture Overview](../architecture/overview.md) — Full system architecture
- [REST API Reference](../api-reference/rest-endpoints.md) — Alternative HTTP interface
