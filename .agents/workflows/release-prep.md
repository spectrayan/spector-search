---
description: End-to-end process for preparing a Spector Search release — test verification, changelog, version bump, docs, and tagging.
---

# Workflow: Release Preparation

End-to-end process for preparing a Spector Search release — test verification, changelog, version bump, docs, and tagging.

## Trigger

When preparing for a tagged release or the user requests release preparation.

## Steps

### 1. Test Gap Analysis

Identify modules with missing test coverage:

```bash
# Count production vs test files per module
for dir in spector-*/; do
  main=$(find "$dir/src/main" -name "*.java" 2>/dev/null | wc -l)
  test=$(find "$dir/src/test" -name "*.java" 2>/dev/null | wc -l)
  echo "$dir main=$main test=$test"
done
```

Flag critical gaps (0 tests in production modules).

### 2. Full Build

```bash
mvn clean install
```

All tests must pass. Zero tolerance for failures.

### 3. Dependency Audit

```bash
# Check for circular dependencies
grep -rn "import com.spectrayan.spector.engine" spector-memory/src/
grep -rn "import com.spectrayan.spector.memory" spector-engine/src/

# Verify no SNAPSHOT dependencies in release
grep -rn "SNAPSHOT" spector-*/pom.xml
```

### 4. Generate Changelog

From commit history since last tag:

```bash
git log --oneline $(git describe --tags --abbrev=0)..HEAD
```

Group entries by type:
- **Added** — `feat:` commits
- **Changed** — `refactor:` commits
- **Fixed** — `fix:` commits
- **Performance** — `perf:` commits
- **Removed** — deletion commits

Prepend to `CHANGELOG.md` with version header and date.

### 5. Version Bump

Update version in root `pom.xml` (child POMs inherit via parent).

### 6. Update Roadmap

Use update-roadmap skill to mark completed features as done.

### 7. Docs Verification

```bash
cd docs && python -m mkdocs build --clean
```

Zero warnings for controlled files.

### 8. Tag & Commit

```bash
git add -A
git commit -m "chore: prepare release v{version}"
git tag -a v{version} -m "Release v{version}"
```
