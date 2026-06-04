# ⚡ @spectrayan/spector — Cognitive Memory for OpenClaw

> **Biologically-inspired AI memory that gives your OpenClaw agent the ability to remember, forget, consolidate, and associate — with sub-millisecond recall.**

[![npm](https://img.shields.io/npm/v/@spectrayan/spector.svg)](https://www.npmjs.com/package/@spectrayan/spector)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/)

---

## What You Get

| Feature | Default Memory (memory-core) | **Spector** |
|---|---|---|
| Storage | Flat Markdown files | **4-tier cognitive architecture** (Working → Episodic → Semantic → Procedural) |
| Search | Basic embedding similarity | **6-phase fused scoring** — similarity × importance × decay × valence × habituation × graph boost |
| Recall latency | ~50-200ms | **< 1ms** (SIMD-accelerated, off-heap) |
| Cross-session | File-based persistence | **Off-heap Panama persistence** with WAL |
| Emotional context | ❌ | **✅ Valence tracking** (-128 to +127) |
| Anti-repetition | ❌ | **✅ Habituation** (anti-filter bubble) |
| Associative recall | ❌ | **✅ Hebbian + Entity + Temporal graphs** |
| Temporal decay | ❌ | **✅ Biologically-inspired decay** with reconsolidation |
| Self-reflection | ❌ | **✅ Metamemory introspection** |

---

## Quick Start

### Option 1: One-Line Installer (Recommended)

The installer clones the repo, builds the plugin, installs it into OpenClaw, and runs the setup wizard — all in one command.

**macOS / Linux:**
```bash
curl -fsSL https://raw.githubusercontent.com/spectrayan/spector/main/plugins/openclaw/install.sh | bash
```

**Windows (PowerShell):**
```powershell
irm https://raw.githubusercontent.com/spectrayan/spector/main/plugins/openclaw/install.ps1 | iex
```

### Option 2: Local Checkout (for development)

```bash
# Clone the repo
git clone https://github.com/spectrayan/spector.git
cd spector/plugins/openclaw

# Build the TypeScript plugin
npm install && npm run build

# Install into OpenClaw (--link keeps it connected to your checkout)
openclaw plugins install --link .
openclaw spector setup
openclaw gateway restart
```

### 🔜 Coming Soon

> **npm** — `openclaw plugins install @spectrayan/spector`
> Once published to the npm registry, this will be the simplest one-step install.

> **OpenClaw CLI git install** — `openclaw plugins install git:github.com/spectrayan/spector-openclaw@main`
> Requires a dedicated plugin repo (planned).

That's it. Your agent now uses Spector for all memory operations.

---

## Setup Wizard

The setup wizard handles everything:

1. **Java 25+** — Auto-downloads Eclipse Temurin JDK if not found
2. **Embedding Provider** — Choose from:
   - 🦙 **Ollama** (local, free, recommended)
   - 🔑 **OpenAI API**
   - 🌐 **Any OpenAI-compatible endpoint** (Together, Groq, vLLM, etc.)
3. **spector.jar** — Auto-downloads from GitHub Releases
4. **Configuration** — Generates optimized `spector.yml`
5. **OpenClaw config** — Updates `openclaw.json` automatically
6. **Health check** — Verifies everything works

---

## Available Tools

### OpenClaw Compatibility (works with built-in agent prompts)

| Tool | Description |
|---|---|
| `memory_search` | Semantic search across all memory tiers |
| `memory_get` | Retrieve a specific memory by ID |

### Spector Native (full cognitive memory API)

| Tool | Description |
|---|---|
| `memory_remember` | Store a memory with tier, tags, importance, and valence |
| `memory_recall` | Cognitive recall with fused scoring and profiles |
| `memory_reinforce` | Report positive/negative feedback on a memory |
| `memory_forget` | Tombstone a memory by ID |
| `memory_status` | Memory tier counts and persistence info |
| `memory_introspect` | Metamemory self-analysis on a topic |
| `memory_scratchpad` | Quick-write to working memory |
| `memory_suppress` | Temporarily hide a memory from recall |
| `memory_why_not` | Explain why a memory wasn't recalled |
| `memory_reminder` | Set a prospective memory reminder |
| `memory_resolve` | Resolve conflicting memories |
| `engine_search` | Raw semantic similarity search |
| `engine_hybrid_search` | BM25 + vector hybrid search |
| `engine_rag` | Retrieval-Augmented Generation with citations |
| `engine_ingest` | Document ingestion with chunking |
| `engine_delete` | Document deletion |
| `engine_status` | Engine metadata and SIMD capabilities |

---

## Cognitive Profiles

Use profiles to tune recall scoring for different agent modes:

| Profile | Best For | How It Scores |
|---|---|---|
| `BALANCED` | General chat | Equal weight to similarity and importance |
| `EXPLORING` | Creative brainstorming | High associative scoring, cross-domain retrieval |
| `DEBUGGING` | Error investigation | Focuses on failures, errors, and fixes |
| `RECALLING` | Retrieving proven solutions | Prioritizes high-confidence, reinforced memories |
| `HYPERFOCUS` | Deep research | Zero decay, strict tag matching |
| `DIVERGENT` | Lateral thinking | Cross-domain connections, low similarity threshold |
| `THE_EXECUTOR` | Task execution | Strict matching, no tangents |
| `PARANOID_SENTINEL` | Security audit | Threat-only recall |

```json
{
  "query": "why did the payment service crash?",
  "profile": "DEBUGGING",
  "synaptic_filter": "payments,errors"
}
```

---

## Configuration

All settings are managed via OpenClaw's plugin config in `~/.openclaw/openclaw.json`:

```json
{
  "plugins": {
    "slots": {
      "memory": "memory-spector"
    },
    "entries": {
      "memory-spector": {
        "enabled": true,
        "config": {
          "dimensions": 768,
          "embeddingProvider": "ollama",
          "embeddingBaseUrl": "http://localhost:11434",
          "embeddingModel": "nomic-embed-text",
          "capacity": 100000
        }
      }
    }
  }
}
```

### Configuration Reference

| Key | Type | Default | Description |
|---|---|---|---|
| `spectorJarPath` | string | (auto-download) | Path to spector.jar |
| `javaHome` | string | (auto-download Temurin) | Path to Java 25+ |
| `dimensions` | number | 768 | Embedding dimensions |
| `embeddingProvider` | string | "ollama" | "ollama" or "openai-compatible" |
| `embeddingBaseUrl` | string | "http://localhost:11434" | Provider URL |
| `embeddingModel` | string | "nomic-embed-text" | Model name |
| `embeddingApiKey` | string | | API key (for non-Ollama providers) |
| `dataDirectory` | string | ~/.openclaw/spector/data/ | Persistent storage |
| `capacity` | number | 100000 | Max memories |
| `jvmArgs` | string | "-Xms256m -Xmx1g" | JVM memory settings |
| `tagExtractor` | string | "content" | "content", "llm", or "none" |
| `logLevel` | string | "INFO" | DEBUG/INFO/WARN/ERROR |

---

## Architecture

```
OpenClaw Gateway (Node.js)
    ↕  Plugin Slot: memory = "memory-spector"
@spectrayan/spector (TypeScript)
    ├── SpectorBridge (subprocess manager)
    ├── Tool aliases (memory_search → memory_recall)
    ├── Passthrough (12 native Spector tools)
    └── Skills (agent instructions for cognitive features)
    ↕  stdio (JSON-RPC 2.0)
Spector MCP Server (Java 25, single JAR)
    ├── SpectorEngine (SIMD vector search)
    └── SpectorMemory (cognitive memory engine)
        ├── 4-tier storage (off-heap Panama)
        ├── 6-phase fused scoring pipeline
        ├── Hebbian + Entity + Temporal graphs
        └── Decay, habituation, valence, surprise
```

**Privacy:** Both OpenClaw and Spector run entirely locally. No data leaves your machine.

---

## Troubleshooting

### "Java not found"

The setup wizard auto-downloads Eclipse Temurin JDK 25. If this fails:

```bash
# Install manually (macOS)
brew install --cask temurin@25

# Install manually (Ubuntu)
sudo apt install temurin-25-jdk

# Install manually (Windows)
winget install EclipseAdoptium.Temurin.25.JDK
```

### "Ollama not running"

```bash
# Start Ollama
ollama serve

# Pull an embedding model
ollama pull nomic-embed-text
```

### "Spector process won't start"

Check the logs:
```bash
# View Spector stderr output
openclaw logs --filter spector
```

Common issues:
- **Port 11434 in use**: Another process is using Ollama's port
- **Out of memory**: Increase `jvmArgs` to `-Xms512m -Xmx2g`
- **Dimension mismatch**: Ensure `dimensions` matches your embedding model

---

## License

Apache License 2.0 — See [LICENSE](../LICENSE) for details.

**Built with ⚡ by [Spectrayan](https://www.spectrayan.com/)**
