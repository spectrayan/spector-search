# spector-cli 🖥️

> **Command-line interface (`spectorctl`) for Spector Search — with both remote and local batch modes.**

`spector-cli` implements **`spectorctl`**, a unified CLI that supports:
- **Remote mode** — manage a running Spector server via REST API (search, ingest single docs, status)
- **Local batch mode** — discover and ingest files directly via `SpectorRuntime` (no server needed)

---

## 🚀 Quick Start

```bash
# Build from source
mvn clean package -pl spector-dist -am -DskipTests

# Run via fat JAR
java --add-modules jdk.incubator.vector -cp spector-dist/target/spector.jar \
    com.spectrayan.spector.cli.SpectorCtl [command] [options]
```

---

## 📥 Ingestion

The `ingest` command auto-detects mode from the flags provided:

### Local Batch Mode (via Runtime)

Discovers and ingests files directly — no server needed. Honors `spector.yml` config.

```bash
# Ingest from config (root-directory from spector.yml)
spectorctl ingest --config spector.yml

# Ingest with explicit root directory
spectorctl ingest --root /path/to/docs --pattern "**/*.md"

# Override chunk size
spectorctl ingest --config spector.yml --root . --chunk-size 1200
```

### Remote Mode (via HTTP)

Sends a single document to a running Spector server.

```bash
# Ingest text content
spectorctl ingest --content "Hello world" --id doc-1

# Ingest from a file
spectorctl ingest --file README.md --title "Project README"
```

---

## 🔍 Search

```bash
# Search with default settings
spectorctl search --text "vector databases" --topK 5

# Output as JSON (machine-parseable)
spectorctl search --text "HNSW algorithm" --json
```

---

## 📊 Status

```bash
# Show engine status
spectorctl status

# JSON output
spectorctl status --json
```

---

## 🌐 Global Options

| Option | Default | Description |
|--------|---------|-------------|
| `--host` | localhost | Spector server hostname (remote mode) |
| `--port` | 7070 | Spector server port (remote mode) |
| `--json` | false | Output in JSON format |

---

## 🏗️ Architecture

```
spectorctl ingest --root /docs    → SpectorRuntime → IngestionHandler → engine/memory
spectorctl ingest --content "..."  → SpectorClient → HTTP → SpectorServer
spectorctl search --text "..."     → SpectorClient → HTTP → SpectorServer
```

The CLI depends on both `spector-runtime` (local operations) and `spector-client` (remote operations). Mode is auto-detected from the flags provided.
