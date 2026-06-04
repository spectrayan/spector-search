# Spector Memory for Odysseus

> **Replace Odysseus's ChromaDB memory with Spector's cognitive memory engine — 4-tier architecture, sub-millisecond recall, emotional valence, and associative graphs.**

## Quick Start (Docker)

```bash
# From your Odysseus directory:
docker compose -f docker-compose.yml -f plugins/odysseus/docker/docker-compose.spector.yml up -d
```

Then go to **Odysseus Settings → MCP Servers** — Spector will auto-register.

## Quick Start (Manual)

1. **Add Spector as an MCP Server** in Odysseus:

   Go to **Settings → MCP Servers → Add Server**:

   | Field | Value |
   |---|---|
   | Name | `Spector Cognitive Memory` |
   | Transport | `stdio` |
   | Command | `java` |
   | Args | `["--add-modules", "jdk.incubator.vector", "--enable-native-access=ALL-UNNAMED", "--enable-preview", "-jar", "/path/to/spector.jar", "--mode", "odysseus"]` |

2. **Copy the skill file** to teach the agent to prefer Spector:

   ```bash
   cp plugins/odysseus/skill/SPECTOR_MEMORY.md /path/to/odysseus/data/skills/
   ```

3. **Restart Odysseus** — the agent now uses Spector for all memory operations.

## What You Get

| Feature | ChromaDB (default) | **Spector** |
|---|---|---|
| Storage | Single flat collection | **4-tier cognitive** (Working → Episodic → Semantic → Procedural) |
| Search | Basic cosine similarity | **6-phase fused scoring** (similarity × importance × decay × valence × habituation × graph) |
| Recall | ~50-200ms | **< 1ms** (SIMD-accelerated) |
| Emotional context | ❌ | ✅ Valence tracking |
| Anti-repetition | ❌ | ✅ Habituation |
| Associative recall | ❌ | ✅ Hebbian + Entity + Temporal graphs |
| Temporal decay | ❌ | ✅ Biologically-inspired decay |
| Self-reflection | ❌ | ✅ Metamemory introspection |

## Category Mapping

Odysseus memory categories map to Spector tiers automatically with `--mode odysseus`:

| Odysseus | Spector Tier | Why |
|---|---|---|
| `fact` | `SEMANTIC` | Stable knowledge |
| `event` | `EPISODIC` | Time-bound experiences |
| `contact` | `SEMANTIC` | People data |
| `preference` | `SEMANTIC` | User preferences |
| (agent skills) | `PROCEDURAL` | Reusable patterns |

## Architecture

```
Odysseus (Python/FastAPI)
  └── Agent (opencode)
       ├── Built-in: manage_memory → ChromaDB (can disable)
       └── MCP stdio → Spector MCP Server (Java 25)
                         ├── 12 memory tools
                         ├── 6 engine tools
                         └── --mode odysseus
                              └── Category → Tier auto-mapping
```

## Privacy

Both Odysseus and Spector run entirely locally. No data leaves your machine.
