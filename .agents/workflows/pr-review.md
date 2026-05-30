---
description: Structured pull request review process ensuring code quality, architecture compliance, and test coverage before merge.
---

# Workflow: PR Review

Structured pull request review process ensuring code quality, architecture compliance, and test coverage before merge.

## Trigger

When reviewing a pull request, inspecting a diff before push, or the user requests a code review.

## Steps

### 1. Scope the Diff

```bash
git diff main...HEAD --stat
```

Count changed files, identify affected modules, classify risk:
- **High risk:** core, index, storage (SIMD, Panama, hot paths)
- **Medium risk:** engine, memory, query (business logic)
- **Low risk:** docs, bench, scripts

### 2. Run Code Review Skill

Execute the full 8-step code review (`.agents/skills/code-review/SKILL.md`):
1. Scope → 2. Architecture → 3. Java Standards → 4. Performance → 5. Tests → 6. Docs → 7. Git Hygiene → 8. Summary

### 3. Run Tests

```bash
mvn test                            # full suite
mvn test -pl spector-{module}       # changed modules only
```

### 4. Verify Docs

If documentation changed:
```bash
cd docs && python -m mkdocs build --clean 2>&1 | grep WARNING
```

### 5. Generate Verdict

Produce a structured review summary with:
- ✅ Passed checks
- ⚠️ Warnings (non-blocking)
- ❌ Blockers (must fix)
- Verdict: `APPROVE` / `REQUEST_CHANGES` / `NEEDS_DISCUSSION`

### 6. Follow Up

- All blockers must be resolved before merge
- PRs are squash-merged to keep history clean
- Commit message for squash should follow Conventional Commits
