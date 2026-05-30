# Workflow: Documentation Update

Process for creating or updating documentation in the MkDocs Material site.

## Trigger

When creating new design docs, architecture pages, deep-dives, or the user requests documentation changes.

## Steps

### 1. Identify Scope

Determine the documentation type:

| Type | Location | Style |
|---|---|---|
| Architecture overview | `docs/docs/architecture/` | Mermaid diagrams, component descriptions |
| Design deep-dive | `docs/docs/deep-dives/` | Technical analysis, benchmarks, trade-offs |
| Memory subsystem | `docs/docs/memory/` | RFC wire format diagrams, neuroscience analogies |
| API reference | `docs/docs/api-reference/` | Request/response examples, endpoint tables |
| Module docs | `docs/docs/modules/` | Auto-included from `spector-*/README.md` via `--8<--` |
| Configuration | `docs/docs/configuration/` | Parameter tables, YAML examples |
| Getting started | `docs/docs/getting-started/` | Step-by-step tutorials |

### 2. Check Source of Truth

- Memory subsystem: `spector-memory/RnD/` specs are the design source of truth
- Engine/Index: code + test behavior is source of truth
- Configuration: `SpectorConfigFactory.java` defaults are source of truth

### 3. Write Content

Follow documentation standards:
- **Binary layouts:** RFC-style wire format diagrams (see `wal-design.md`)
- **Architecture:** Mermaid diagrams for component relationships
- **Code examples:** Real, working snippets from the codebase
- **Tables:** For configuration parameters, comparison matrices
- **Admonitions:** Use MkDocs Material admonitions (`!!! note`, `!!! warning`)

### 4. Update Navigation

If this is a new page, add to `docs/mkdocs.yml` nav section:
```yaml
nav:
  - Section:
    - Page Title: path/to/page.md
```

### 5. Verify Build

```bash
cd docs
python -m mkdocs build --clean
```

Fix any warnings about:
- Pages not in nav
- Broken cross-references
- Invalid Mermaid syntax

### 6. Preview (Optional)

```bash
cd docs
python -m mkdocs serve --dev-addr 127.0.0.1:8085
```

### 7. Commit

```
docs: <description of what was documented>
```
