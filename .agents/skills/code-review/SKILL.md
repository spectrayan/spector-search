# Skill: Code Review

This skill defines the code review process for the Spector Search project. Use this when reviewing PRs, inspecting diffs, or performing pre-commit quality checks.

## Trigger

This skill is triggered when:
- The user requests a code review of changes or a PR
- The user asks to "review", "check", or "audit" code
- Before merging or pushing significant changes
- As part of the PR Review workflow

## Instructions

### Step 1: Scope the Review

Identify what changed:
```bash
git diff --stat                          # unstaged changes
git diff --cached --stat                 # staged changes
git diff main...HEAD --stat              # full PR diff vs main
```

Categorize changes by module and risk level:
- **High risk:** core, index, storage (SIMD, Panama, hot paths)
- **Medium risk:** engine, memory, query (business logic)
- **Low risk:** docs, bench, scripts (non-production)

### Step 2: Architecture Check

For each changed module, verify module boundary rules:

- [ ] No Foundation module depending on Intelligence/Runtime
- [ ] `spector-memory` does NOT import from `spector-engine` (or vice versa)
- [ ] No new circular dependencies between modules
- [ ] New dependencies added to correct POM section
- [ ] If a new module was added, it follows the layer hierarchy

**Quick check command:**
```bash
# Find cross-module imports that violate boundaries
grep -rn "import com.spectrayan.spector.engine" spector-memory/src/
grep -rn "import com.spectrayan.spector.memory" spector-engine/src/
```

### Step 3: Java Standards Compliance

For each changed `.java` file, check:

**Hard blockers (must fix before merge):**
- [ ] No `synchronized` keyword anywhere — must use `ReentrantLock`
- [ ] No `System.out.println` — must use SLF4J logger
- [ ] No hardcoded SIMD lane widths — must use `SPECIES_PREFERRED`
- [ ] No `Thread.sleep()` in production — use `LockSupport.parkNanos()`
- [ ] No swallowed exceptions (`catch (Exception e) { }`)
- [ ] No hardcoded file paths — use `SpectorConfig` / `PersistenceFiles`
- [ ] `AutoCloseable` implemented for classes holding native resources

**Quality checks (should fix):**
- [ ] Javadoc on all new public classes and methods
- [ ] Records used for immutable data holders (not mutable POJOs)
- [ ] Pattern matching used instead of cast-after-instanceof
- [ ] Section separators (`// ───── Section ─────`) for class organization
- [ ] Meaningful variable names (not single letters except loop vars)

### Step 4: Performance Review (core, index, storage only)

For changes in hot-path modules:

- [ ] No `new float[]` or boxing in search/similarity paths
- [ ] `MemorySegment` slices used instead of `.toArray()` copies
- [ ] SIMD loops use `SPECIES.loopBound()` with scalar tail
- [ ] `VectorMask` used for tail handling (not branching)
- [ ] Arena lifecycle correct: `ofShared()` for concurrent, `ofConfined()` for single-thread
- [ ] JMH benchmark included for performance-sensitive changes

### Step 5: Test Coverage

- [ ] New public API methods have at least 1 test
- [ ] Tests use `@TempDir` for file operations (never hardcoded paths)
- [ ] Tests use AssertJ assertions (`assertThat`), not JUnit `assertEquals`
- [ ] Integration tests suffixed `*IntegrationTest`
- [ ] No test relies on external services without `@DisabledIfEnvironmentVariable`

**Quick gap check:**
```bash
# List production files without corresponding test files
for file in $(git diff --name-only --diff-filter=A | grep "src/main.*\.java$"); do
  test_file=$(echo $file | sed 's|src/main|src/test|' | sed 's|\.java$|Test.java|')
  [ ! -f "$test_file" ] && echo "MISSING TEST: $test_file"
done
```

### Step 6: Documentation

- [ ] README.md updated if public API changed
- [ ] `docs/docs/configuration/parameters.md` updated if config defaults changed
- [ ] Design docs updated if binary layouts or WAL format changed
- [ ] `mkdocs build --clean` produces no new warnings
- [ ] Mermaid diagrams have valid syntax

### Step 7: Git Hygiene

- [ ] Commit messages follow Conventional Commits: `<type>(<scope>): <desc>`
- [ ] No secrets, API keys, or credentials in diff
- [ ] No `.spector/` data files committed
- [ ] No generated files (`.class`, `target/`, `site/`) committed
- [ ] Commits are logically grouped (not one mega-commit)

### Step 8: Generate Review Summary

After completing all checks, produce a structured summary:

```markdown
## Code Review Summary

**Scope:** {N} files across {M} modules
**Risk:** High / Medium / Low

### ✅ Passed
- Architecture boundaries respected
- No synchronized/System.out violations
- Tests added for new APIs

### ⚠️ Warnings
- Missing Javadoc on `NewClass.process()` (line 42)
- No JMH benchmark for SIMD optimization

### ❌ Blockers
- `synchronized` used in MemoryWal.java:156 — must use ReentrantLock
- Missing test for `ShardedDiskHnswWriter.write()`

### Verdict: APPROVE / REQUEST_CHANGES / NEEDS_DISCUSSION
```

## Verification

After the review is complete:
1. All blockers must be resolved before merge
2. Warnings should be addressed or documented as tech debt
3. Review summary should be attached to the PR or provided to the user
