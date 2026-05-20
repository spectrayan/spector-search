# spectorctl CLI Reference

`spectorctl` is the command-line tool for managing Spector Search instances. It connects to a running server via the REST API.

## Installation

Build from source:

```bash
cd spector-search
mvn clean package -pl spector-cli -am -DskipTests
```

The CLI is available at `spector-cli/target/spector-cli.jar`.

## Global Options

| Option | Default | Description |
|--------|---------|-------------|
| `--host` | localhost | Spector server hostname |
| `--port` | 7070 | Spector server port |
| `--json` | false | Output in JSON format |
| `--help` | — | Show help for any command |

## Commands

### index — Index Management

```bash
# Create an index
spectorctl index create --name my-index --dimensions 384

# List all indexes
spectorctl index list

# Delete an index
spectorctl index delete --name my-index
```

### ingest — Document Ingestion

```bash
# Ingest a single document
spectorctl ingest --id doc-1 \
  --content "SIMD-accelerated vector search" \
  --vector "0.1,0.2,0.3,0.4,0.5"
```

### search — Search Documents

```bash
# Text search
spectorctl search --text "vector search engine" --topK 10

# Vector search
spectorctl search --vector "0.1,0.2,0.3,0.4,0.5" --topK 5

# JSON output
spectorctl search --text "search" --json
```

### status — Server Status

```bash
# Check server status
spectorctl status
```

## Runnable CLI Example

This complete example demonstrates the full workflow using `spectorctl`:

```bash
# 1. Check that the server is running
spectorctl --host localhost --port 7070 status

# 2. Ingest documents
spectorctl ingest --id cli-doc-1 \
  --content "Spector Search uses HNSW for approximate nearest neighbors" \
  --vector "0.9,0.1,0.3,0.7,0.5"

spectorctl ingest --id cli-doc-2 \
  --content "IVF-PQ provides memory-efficient billion-scale search" \
  --vector "0.2,0.8,0.4,0.1,0.6"

# 3. Search for documents
spectorctl search --text "nearest neighbor search" --topK 5

# 4. Get results in JSON format for scripting
spectorctl search --text "billion scale" --topK 3 --json

# 5. Check engine status and metrics
spectorctl status
```

### Expected Output

```
$ spectorctl status
╔══════════════════════════════════════╗
║ Spector Search Status                ║
╠══════════════════════════════════════╣
║ Status:    RUNNING                   ║
║ Port:      7070                      ║
║ SIMD:      AVX-512 (512-bit)         ║
║ GPU:       Available (CUDA 12.x)     ║
║ Documents: 2                         ║
╚══════════════════════════════════════╝

$ spectorctl search --text "nearest neighbor" --topK 5
┌─────────────┬────────┬────────────────────────────────────────────┐
│ ID          │ Score  │ Content                                    │
├─────────────┼────────┼────────────────────────────────────────────┤
│ cli-doc-1   │ 0.9412 │ Spector Search uses HNSW for approximate.. │
│ cli-doc-2   │ 0.7231 │ IVF-PQ provides memory-efficient billion..  │
└─────────────┴────────┴────────────────────────────────────────────┘
```

## Error Handling

| Scenario | Behavior |
|----------|----------|
| Server unreachable | Displays connection error with host:port |
| Invalid arguments | Shows error message and command usage |
| No results | Displays empty result table |

## Using with Scripts

The `--json` flag makes output machine-parseable:

```bash
# Pipe search results to jq
spectorctl search --text "query" --json | jq '.results[].id'

# Check status in CI
if spectorctl status --json | jq -e '.status == "RUNNING"' > /dev/null; then
  echo "Server is healthy"
fi
```
