# Spector Search — Agent Rules

## Project Identity

Spector Search is a **Java 25** vector search engine with biologically-inspired cognitive memory, built on Panama FFM, SIMD Vector API, and virtual threads. 22-module Maven reactor.

## Critical Constraints

- **JDK 25** with `jdk.incubator.vector`
- **NEVER** use `synchronized` — always `ReentrantLock` (virtual thread pinning)
- **NEVER** `System.out.println` — use SLF4J `LoggerFactory.getLogger()`
- **NEVER** hardcode SIMD lane widths — use `FloatVector.SPECIES_PREFERRED`
- **NEVER** commit secrets/tokens to repo
- `.spector/` is in `.gitignore` — never remove

## Architecture Boundaries

| Layer | Modules | Depends On |
|---|---|---|
| Foundation | core, commons, config, storage | Each other only |
| Embedding | embed-api, embed-ollama | commons |
| Search | index, query, gpu | Foundation + embed-api |
| Intelligence | rag, engine, ingestion, memory | Search + Foundation |
| Runtime | runtime, node, mcp, cli, client | Intelligence |
| Infrastructure | metrics, bench, dist, spring | Any |

**`spector-memory` and `spector-engine` are independent peers — never depend on each other.** Wired only at `SpectorRuntime`.

## Directory Paths

- Engine: `.spector/index/` — Memory: `.spector/memory/` — WAL: `.spector/memory/wal/`
- Source of truth: `SpectorConfigFactory.java`

## Git Conventions

- Format: `<type>(<scope>): <description>` (Conventional Commits)
- Types: `feat`, `fix`, `perf`, `refactor`, `docs`, `test`, `build`, `chore`
- Scope: module name without `spector-` (e.g., `engine`, `memory`)
- Commit order: foundation → search → intelligence → runtime → docs → tests
- Branch: `feat/desc`, `fix/desc`, `perf/desc`, `docs/desc`

## Documentation

- MkDocs Material site in `docs/`, build: `python -m mkdocs build --clean`
- Module READMEs included via `--8<--` snippets in `docs/docs/modules/`
- Binary layouts: RFC-style wire format diagrams
- Design source of truth: `spector-memory/RnD/` for memory subsystem
- Config docs: `docs/docs/configuration/parameters.md`

## Key Patterns

- Records for immutable data (`PersistenceFiles`, `NodeInfo`, `SearchResult`)
- Builder pattern for configs (`SpectorConfig.builder()`, `SpectorEngine.builder()`)
- Abstract Factory for component assembly (`EngineComponentFactory`)
- `IngestionTarget` interface — both engine and memory implement their own
- `AutoCloseable` for anything holding native resources

## Skills Reference

Detailed coding standards, code review process, and other skills are defined in `.agents/skills/`. Agents should read the relevant SKILL.md before performing specialized tasks.
