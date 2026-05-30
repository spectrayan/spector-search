# Skill: Documentation Sync

This skill ensures MkDocs site, module READMEs, configuration docs, and design documents stay in sync with production code changes.

## Trigger

This skill is triggered when:
- Production code changes public API, configuration defaults, or directory paths
- A new module is created or an existing one is renamed/deleted
- The user requests "update docs", "sync docs", or "fix doc warnings"
- As part of the Feature Development or Module Lifecycle workflows

## Instructions

### Step 1: Identify What Changed

```bash
git diff --name-only HEAD~1  # or appropriate range
```

Map changed files to documentation impact:

| Code Change | Docs to Update |
|---|---|
| `SpectorConfig` / `SpectorProperties` | `docs/docs/configuration/parameters.md`, `spector-defaults.yml` |
| `SpectorConfigFactory` (path defaults) | All docs referencing `.spector/` paths |
| Public API in any module | `spector-{mod}/README.md` |
| `MemoryWal`, `CognitiveRecordLayout` | `docs/docs/memory/wal-design.md` |
| New module created | `docs/mkdocs.yml`, `docs/docs/modules/index.md`, new module page |
| Module removed | `docs/mkdocs.yml`, `docs/docs/modules/index.md`, delete page |
| POM dependency changes | `docs/docs/modules/index.md` (dependency graph) |

### Step 2: Update Module Docs

For each module with changed public API:

1. Update `spector-{module}/README.md` (auto-included in docs site via `--8<--`)
2. Ensure `docs/docs/modules/spector-{module}.md` exists with snippet include:
   ```markdown
   --8<-- "spector-{module}/README.md"
   ```
3. Ensure nav entry exists in `docs/mkdocs.yml` under `Modules:`

### Step 3: Update Config Docs

If `SpectorConfig`, `SpectorProperties`, or `SpectorConfigFactory` changed:

1. Extract current defaults from `SpectorConfigFactory.java` (source of truth)
2. Update `spector-config/src/main/resources/spector-defaults.yml`
3. Update `docs/docs/configuration/parameters.md`
4. Grep for old path references: `grep -rn "old-path" docs/ scripts/ *.md`

### Step 4: Update Design Docs

If binary layouts, WAL format, or synapse headers changed:

1. Cross-reference with `spector-memory/RnD/wal_design_spec.md` (design source of truth)
2. Update RFC-style wire format diagrams in `docs/docs/memory/wal-design.md`
3. Update `docs/docs/memory/panama-design.md` if record layout changed

### Step 5: Fix Nav & Cross-References

1. Check `docs/mkdocs.yml` for:
   - No duplicate `extra_css` or `extra_javascript` keys (YAML last-key-wins)
   - All nav entries point to existing files
   - No stale module entries (deleted modules)
2. Check for pages not in nav:
   ```bash
   python -m mkdocs build --clean 2>&1 | grep "not included in the nav"
   ```

### Step 6: Verify

```bash
cd docs
python -m mkdocs build --clean
```

**Must have:**
- Zero "page not in nav" warnings for modules
- Zero "link target not found" warnings for files we control
- All `--8<--` snippet includes resolve to existing files

## MkDocs Quick Reference

| Task | Command |
|---|---|
| Build site | `cd docs && python -m mkdocs build --clean` |
| Serve locally | `cd docs && python -m mkdocs serve --dev-addr 127.0.0.1:8085` |
| Check warnings | `python -m mkdocs build --clean 2>&1 \| grep WARNING` |

**Stack:** MkDocs 1.6.1 + Material for MkDocs 9.7.6
