# Spector Memory Integration — Agent Rule

## Purpose

Spector MCP (`spector`) is the agent's **secondary brain** — a persistent cognitive memory system that survives across conversations. The agent MUST use it proactively for knowledge persistence and retrieval.

---

## Mandatory Behaviors

### 1. Always Remember (Store to Spector)

After every meaningful interaction, the agent MUST call `memory_remember` on the `spector` MCP server to persist:

- **Decisions made** — architecture choices, design trade-offs, user preferences (tier: `SEMANTIC`, tags: `decision`)
- **Problems solved** — bugs found, root causes, fixes applied (tier: `EPISODIC`, tags: `debugging,fix`)
- **User preferences & instructions** — coding style, workflow preferences, tool preferences (tier: `SEMANTIC`, tags: `preferences,user`, source: `USER_STATED`)
- **Procedural knowledge** — how-to patterns, build steps, deployment procedures (tier: `PROCEDURAL`, tags: `procedure`)
- **Working context** — current task state, in-progress items (tier: `WORKING`, tags: `context`)

**Tier selection guide:**
| Tier | Use For | Example |
|---|---|---|
| `WORKING` | Ephemeral task context, scratch notes | "Currently debugging OOM in indexer" |
| `EPISODIC` | Specific events with time context | "Fixed NPE in SynapticTagEncoder on 2026-06-12" |
| `SEMANTIC` | Facts, knowledge, decisions | "User prefers ReentrantLock over StampedLock for simplicity" |
| `PROCEDURAL` | Skills, patterns, how-to | "To run benchmarks: mvn -pl spector-bench test -Pbench" |

**ICNU scoring guide (0.0–1.0):**
- `interest`: How relevant to current/future tasks
- `challenge`: How complex or novel the information is
- `urgency`: How time-critical (deadlines, incidents)

### 2. Always Recall (Search Spector First)

Before starting any non-trivial task, the agent MUST call `memory_recall` to check for:

- Prior decisions on the same topic
- Past debugging sessions with similar symptoms
- User preferences that affect the approach
- Procedural knowledge for the task at hand

**Use appropriate recall profiles:**
| Profile | When to Use |
|---|---|
| `BALANCED` | General recall (default) |
| `DEBUGGING` | Investigating errors or failures |
| `EXPLORING` | Creative/associative discovery |
| `RECALLING` | Looking for proven solutions |
| `HYPERFOCUS` | Deep-dive on a narrow topic |

### 3. Reinforce After Use

When a recalled memory helps solve a problem, call `memory_reinforce` with `positive` or `strongly_positive`. If a memory was misleading, reinforce with `negative`.

### 4. Introspect Before Answering

For knowledge-heavy questions, call `memory_introspect` to check confidence and knowledge gaps before answering.

---

## What NOT to Store

- Trivial acknowledgments ("ok", "sure", "got it")
- Redundant information already stored
- Raw file contents (store summaries/insights instead)
- Temporary build output or log dumps

---

## Integration Pattern

```
User Request → memory_recall (check prior knowledge)
            → Do the work
            → memory_remember (persist outcomes)
            → memory_reinforce (if recall helped)
```

---

## Configuration

- **MCP Server**: `spector`
- **Endpoint**: `http://localhost:7070/mcp` (Streamable HTTP, stateless)
- **Config key**: `"serverUrl"` (not `"url"`)
- **Default namespace**: empty (default)
- **Transport**: Stateless — no session tracking. Server restarts are transparent to clients.
