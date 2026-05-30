---
description: End-to-end process for implementing a new feature in Spector Search, from understanding requirements to committing clean code.
---

## Trigger

When implementing a new feature, capability, or significant enhancement in any `spector-*` module.

## Steps

### 1. Understand Requirements

- Read RnD specs if available (`spector-memory/RnD/` for memory subsystem)
- Check the roadmap (`docs/docs/roadmap.md`) for planned features
- Identify which module(s) the feature belongs in

### 2. Verify Module Boundaries

Before writing code, confirm the target module is correct:

- Foundation (core, commons, config, storage) — shared abstractions
- Search (index, query, gpu) — search algorithms
- Intelligence (engine, memory, rag, ingestion) — orchestration
- Runtime (runtime, node, mcp, cli) — entry points

**Rule:** `spector-memory` and `spector-engine` never depend on each other.

### 3. Implement

- Follow coding standards (`.agents/skills/coding-standards/SKILL.md`)
- Use `ReentrantLock` not `synchronized`
- Use records for immutable data
- Use section separators for class organization
- Add SLF4J logging at appropriate levels

### 4. Write Tests

- JUnit 5 + AssertJ, `@TempDir` for file tests
- At least 1 test per new public API method
- Integration tests suffixed `*IntegrationTest`
- Run: `mvn test -pl spector-{module}`

### 5. Update Documentation

Run the doc-sync skill (`.agents/skills/doc-sync/SKILL.md`):
- Update module README if public API changed
- Update config docs if defaults changed
- Update design docs if binary layouts changed

### 6. Build & Verify

```bash
mvn test -pl spector-{module}     # module tests
mvn clean install                  # full reactor
cd docs && python -m mkdocs build --clean  # docs build
```

### 7. Commit

Run the incremental-commits skill (`.agents/skills/incremental-commits/SKILL.md`):
- Group by component in dependency order
- Conventional Commits format

### 8. Update Roadmap (if applicable)

Run the update-roadmap skill (`.agents/skills/update-roadmap/SKILL.md`):
- Mark feature as completed if it was on the roadmap
