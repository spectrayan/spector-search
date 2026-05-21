# 🖥️ CLI Reference

> **Manage Spector Search from the command line.** `spectorctl` connects to a running server via REST and provides commands for indexing, ingestion, search, and status monitoring — with both human-friendly tables and machine-parseable JSON output.

---

## 📦 Installation

Build from source:

```bash
cd spector-search
mvn clean package -pl spector-cli -am -DskipTests
```

The CLI JAR is at `spector-cli/target/spector-cli.jar`. Run it with:

```bash
java -jar spector-cli/target/spector-cli.jar [command] [options]
```

!!! tip
    Create an alias for convenience:
> ```bash
> alias spectorctl='java -jar /path/to/spector-cli.jar'
> ```

---

## 🌐 Global Options

| Option | Default | Description |
|--------|---------|-------------|
| `--host` | localhost | Spector server hostname |
| `--port` | 7070 | Spector server port |
| `--json` | false | Output in JSON format (machine-parseable) |
| `--api-key` | — | API key for authentication |
| `--help` | — | Show help for any command |

---

## 📋 Commands

### 📊 `index` — Index Management

Create, list, and delete indexes.

```bash
# Create an index with specific dimensions
spectorctl index create --name my-index --dimensions 384

# List all indexes
spectorctl index list

# Delete an index
spectorctl index delete --name my-index
```

| Option | Required | Description |
|--------|----------|-------------|
| `--name` | ✅ | Index name |
| `--dimensions` | ✅ (create) | Vector dimensionality |

---

### 📥 `ingest` — Document Ingestion

```bash
# Ingest a single document with vector
spectorctl ingest --id doc-1 \
  --content "SIMD-accelerated vector search on Java 25" \
  --vector "0.1,0.2,0.3,0.4,0.5"

# Ingest with title
spectorctl ingest --id doc-2 \
  --title "Panama FFM" \
  --content "Foreign Function and Memory API for zero-copy storage" \
  --vector "0.4,0.5,0.6,0.7,0.8"
```

| Option | Required | Description |
|--------|----------|-------------|
| `--id` | ✅ | Document identifier |
| `--content` | ✅ | Document text content |
| `--vector` | ✅ | Comma-separated float values |
| `--title` | ❌ | Document title |

---

### 🔍 `search` — Search Documents

```bash
# Text/keyword search
spectorctl search --text "vector search engine" --topK 10

# Vector search
spectorctl search --vector "0.1,0.2,0.3,0.4,0.5" --topK 5

# Hybrid search
spectorctl search --text "search" --vector "0.1,0.2,0.3,0.4,0.5" --topK 10

# JSON output for scripting
spectorctl search --text "search" --json
```

| Option | Required | Description |
|--------|----------|-------------|
| `--text` | ❌* | Query text for keyword search |
| `--vector` | ❌* | Comma-separated query vector |
| `--topK` | ❌ | Number of results (default: 10) |

!!! important
    *At least one of `--text` or `--vector` is required.

---

### 💚 `status` — Server Status

```bash
# Human-readable status
spectorctl status

# JSON output
spectorctl status --json
```

---

## 🎨 Output Formats

### 📋 Table Format (Default)

Human-readable tables for interactive use:

```
$ spectorctl status
╔══════════════════════════════════════╗
║ Spector Search Status                ║
╠══════════════════════════════════════╣
║ Status:    RUNNING                   ║
║ Port:      7070                      ║
║ SIMD:      AVX-512 (512-bit)         ║
║ GPU:       Available (CUDA 12.x)     ║
║ Documents: 1250                      ║
╚══════════════════════════════════════╝
```

```
$ spectorctl search --text "nearest neighbor" --topK 5
┌─────────────┬────────┬────────────────────────────────────────────┐
│ ID          │ Score  │ Content                                    │
├─────────────┼────────┼────────────────────────────────────────────┤
│ doc-1       │ 0.9412 │ Spector Search uses HNSW for approximate.. │
│ doc-2       │ 0.7231 │ IVF-PQ provides memory-efficient billion.. │
└─────────────┴────────┴────────────────────────────────────────────┘
```

### 🔧 JSON Format (`--json`)

Machine-parseable output for scripting and automation:

```json
{"status": "RUNNING", "port": 7070, "simd": "AVX-512 (512-bit)", "gpuAvailable": true, "documentCount": 1250}
```

---

## 🔧 Scripting Examples

### Pipe to jq

```bash
# Extract document IDs from search results
spectorctl search --text "query" --json | jq '.results[].id'

# Check server health in CI
if spectorctl status --json | jq -e '.status == "RUNNING"' > /dev/null; then
  echo "Server is healthy"
fi
```

### Batch Ingestion from File

```bash
# Ingest from a JSONL file
while IFS= read -r line; do
  id=$(echo "$line" | jq -r '.id')
  content=$(echo "$line" | jq -r '.content')
  vector=$(echo "$line" | jq -r '.vector | join(",")')
  spectorctl ingest --id "$id" --content "$content" --vector "$vector"
done < documents.jsonl
```

### Health Check Script

```bash
#!/bin/bash
MAX_RETRIES=30
for i in $(seq 1 $MAX_RETRIES); do
  if spectorctl --host $SPECTOR_HOST --port $SPECTOR_PORT status --json 2>/dev/null | \
     jq -e '.status == "RUNNING"' > /dev/null 2>&1; then
    echo "✅ Spector Search is ready"
    exit 0
  fi
  echo "⏳ Waiting for server... ($i/$MAX_RETRIES)"
  sleep 1
done
echo "❌ Server did not start in time"
exit 1
```

---

## ⚠️ Error Handling

| Scenario | Behavior |
|----------|----------|
| Server unreachable | Displays connection error with host:port |
| Invalid arguments | Shows error message and command usage |
| Missing required options | Shows which options are missing |
| No results found | Displays empty result table |

```
$ spectorctl --host badhost --port 9999 status
Error: Cannot connect to badhost:9999 — Connection refused
```

---

## 🔗 See Also

- [REST API Reference](../api-reference/rest-endpoints.md) — The API that spectorctl uses
- [Getting Started](../getting-started/quickstart.md) — Server setup before using CLI
- [Configuration Guide](../configuration/parameters.md) — Server configuration