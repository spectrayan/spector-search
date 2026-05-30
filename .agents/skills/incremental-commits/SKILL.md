# Skill: Incremental Commits

This skill defines the process for creating clean, logical, component-grouped git commits following the project's Conventional Commits standard.

## Trigger

This skill is triggered when:
- The user requests "commit", "incremental commit", or "commit all changes"
- After completing a feature or fix with multiple file changes
- As the final step in the Feature Development workflow

## Instructions

### Step 1: Inventory Changes

```bash
git status --short
git diff --cached --name-only   # check for already-staged files
```

If there are staged files, decide: unstage with `git reset HEAD -- .` to start clean, or commit staged files first.

### Step 2: Group by Logical Unit

Group changes in this strict dependency order. Each group becomes one commit:

| Priority | Category | Commit Type | Example |
|---|---|---|---|
| 1 | **Module deletions** | `refactor:` | `refactor: remove spector-server module` |
| 2 | **Build/POM changes** | `build:` | `build: remove server/cluster from reactor` |
| 3 | **Foundation** (core, commons, config, storage) | `feat/refactor({mod}):` | `refactor(config): add PersistenceFiles record` |
| 4 | **Embedding** (embed-api, embed-ollama) | `feat/refactor({mod}):` | |
| 5 | **Search** (index, query, gpu) | `feat/refactor({mod}):` | `feat(index): sharded disk HNSW persistence` |
| 6 | **Intelligence** (engine, memory, rag, ingestion) | `feat/refactor({mod}):` | `feat(memory): WAL corruption recovery` |
| 7 | **Runtime** (runtime, node, mcp, cli, client) | `feat/refactor({mod}):` | |
| 8 | **Infrastructure** (metrics, bench, dist, spring) | `feat({mod}):` | `feat(metrics): Prometheus instrumentation` |
| 9 | **New modules** (whole new `spector-*`) | `feat({mod}):` | `feat(node): spector-node unified server` |
| 10 | **Documentation** | `docs:` | `docs: update WAL design deep-dive` |
| 11 | **Scripts/CI/deploy** | `chore:` or `build:` | `chore: update MCP config and scripts` |
| 12 | **Test files** | `test:` | `test: update engine and memory test suites` |
| 13 | **Project meta** | `docs:` or `chore:` | `docs: update README and CHANGELOG` |

### Step 3: Commit Each Group

For each group:

```bash
git add <files-in-group>
git commit -m "<type>(<scope>): <short description>

- Bullet explaining what changed
- Bullet explaining why (if non-obvious)"
```

**Rules:**
- Production source (`src/main/`) and its tests (`src/test/`) in the same module CAN go in the same commit if they are part of the same logical change
- However, if changes span many modules, keep tests in a separate final commit
- Never mix unrelated modules in one commit
- POM changes go with the module they affect, OR in a separate `build:` commit if they affect multiple modules

### Step 4: Verify

```bash
git log --oneline -N      # verify clean history
mvn test -pl <module>     # verify changed modules still build
```

### Commit Message Format

```
<type>(<scope>): <imperative short description>

<optional body — explain WHY, not WHAT>

- Bullet point 1
- Bullet point 2
```

| Type | When |
|---|---|
| `feat` | New functionality, new class, new API |
| `fix` | Bug fix, test fix, correction |
| `perf` | Performance improvement (must include numbers) |
| `refactor` | Code restructuring with no behavior change |
| `docs` | Documentation only |
| `test` | Adding or updating tests only |
| `build` | POM, Maven, CI, dependency changes |
| `chore` | Scripts, tooling, config, non-code |

**Scope:** Module name without `spector-` prefix. Omit scope for cross-cutting changes.

## Examples

```
feat(memory): WAL corruption recovery with torn-write detection

- Torn writes at EOF detected via magic/CRC failure, resolved by truncate()
- Mid-log corruption quarantined to .quarantine/ to prevent data divergence
- ReentrantLock replaces synchronized for virtual thread safety
```

```
refactor: remove spector-cluster module (deferred to V3 roadmap)

Cluster coordination, shard management, and replication are
planned for V3. Removing premature scaffolding to reduce build
surface and test noise.
```

```
build: remove server/cluster from reactor, update POM dependencies

- Remove spector-server and spector-cluster from root POM modules
- Update dependency versions and module cross-references
```
