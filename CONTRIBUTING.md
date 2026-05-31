# Contributing to Spector

Thank you for your interest in contributing to Spector! This document provides guidelines and instructions for contributing.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Contributor License Agreement](#contributor-license-agreement)
- [Getting Started](#getting-started)
- [Development Setup](#development-setup)
- [Making Changes](#making-changes)
- [Coding Standards](#coding-standards)
- [Pull Request Process](#pull-request-process)
- [Reporting Issues](#reporting-issues)

## Code of Conduct

This project adheres to the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are expected to uphold this code. Please report unacceptable behavior to [support@spectrayan.com](mailto:support@spectrayan.com).

## Contributor License Agreement

By contributing to Spector, you agree that:

1. **You have the right** to submit the contribution. The code is your original work, or you have permission to submit it under the project's license terms.

2. **You grant Spectrayan** a perpetual, worldwide, non-exclusive, royalty-free, irrevocable license to use, reproduce, modify, distribute, and sublicense your contribution under:
   - The **Apache License 2.0** for all modules except `spector-memory`.
   - The **Business Source License 1.1** for the `spector-memory` module (which transitions to Apache 2.0 on the Change Date specified in its LICENSE file).

3. **You understand** that your contribution becomes part of the project and may be distributed under the project's current or future license terms as described above.

### How to Sign Off

All commits must include a `Signed-off-by` line certifying this agreement. Use the `-s` flag when committing:

```bash
git commit -s -m "feat(core): add new SIMD kernel"
```

This adds a line like:

```
Signed-off-by: Your Name <your.email@example.com>
```

> **Note:** Pull requests without signed-off commits will not be merged.

## Getting Started

1. **Fork** the repository on GitHub
2. **Clone** your fork locally
3. **Create a branch** for your change
4. **Make your changes** with appropriate tests
5. **Submit a pull request**

## Development Setup

### Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK  | 25+     | OpenJDK with Vector API incubator support |
| Maven | 3.9+   | For multi-module reactor build |
| Git  | 2.40+   | Version control |

### First-Time Setup

```bash
# Clone your fork
git clone https://github.com/<your-username>/spector.git
cd spector

# Verify JDK 25+ is installed
java -version

# Build the project (full reactor)
mvn clean compile

# Run the test suite (212 tests)
mvn test

# Run the server (optional)
mvn exec:java -pl spector-node -Dexec.mainClass="com.spectrayan.spector.server.SpectorNode"
```

### SIMD Verification

Spector uses the Java Vector API for SIMD acceleration. Verify your system supports it:

```bash
# Check SIMD capability
java --add-modules jdk.incubator.vector -cp spector-core/target/classes \
  com.spectrayan.spector.core.SimdCapability
```

Expected output includes your hardware's SIMD width (e.g., `S_256_BIT` for AVX2).

### Running Tests

```bash
# Full test suite
mvn test

# Single module
mvn test -pl spector-core

# Single test class
mvn test -pl spector-core -Dtest=DotProductTest
```

## Making Changes

### Branch Naming

Use descriptive branch names with a type prefix:

```
feat/add-quantization-support
fix/hnsw-concurrent-insert-race
perf/simd-avx512-unroll-loop
refactor/storage-arena-lifecycle
docs/api-usage-examples
```

### Commit Messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
feat(core): add AVX-512 double-pump dot product kernel
fix(index): prevent HNSW neighbor list corruption under concurrent insert
perf(storage): use bulk MemorySegment.copy for vector reads
refactor(query): extract RRF into standalone utility class
docs: add benchmark results to README
```

**Format:** `<type>(<scope>): <description>`

| Type | Purpose |
|------|---------|
| `feat` | New feature |
| `fix` | Bug fix |
| `perf` | Performance improvement |
| `refactor` | Code restructuring (no behavior change) |
| `docs` | Documentation only |
| `test` | Adding or updating tests |
| `chore` | Build, CI, tooling changes |

## Coding Standards

### Java

- **Java 25** — use records, sealed classes, pattern matching, switch expressions
- **Vector API** — always use `FloatVector.SPECIES_PREFERRED`, never hardcode lane widths
- **Panama FFM** — use `Arena.ofShared()` for concurrent access, `Arena.ofConfined()` for single-thread
- **Virtual Threads** — use `ReentrantLock` instead of `synchronized` to avoid pinning
- **Testing** — all new features require unit tests; use JUnit 5 + AssertJ
- **Javadoc** — all public classes and methods must have Javadoc comments

### Performance

- **No allocations in hot paths** — reuse buffers, use slice-based APIs with offset+length
- **Branchless SIMD** — use `VectorMask` for tail handling, never scalar fallback
- **Benchmark before/after** — performance PRs must include JMH results

### Architecture

- **Module boundaries** — respect the dependency graph; no circular dependencies
- **Interface-first** — add interfaces before implementations for pluggability
- **Zero-copy** — prefer `MemorySegment` slices over array copies

## Pull Request Process

1. **Ensure your branch is up to date** with `main`
2. **All tests pass** — CI will verify this automatically
3. **Fill out the PR template** — describe what changed and why
4. **Link related issues** — use `Closes #123` or `Fixes #456`
5. **One approval required** — a maintainer will review your PR
6. **Squash merge** — PRs are squash-merged to keep history clean

### PR Checklist

- [ ] Code follows the project's coding standards
- [ ] Tests added/updated for the change
- [ ] Javadoc updated for public API changes
- [ ] No hardcoded secrets or credentials
- [ ] Commit messages follow Conventional Commits
- [ ] JMH benchmarks included (if performance-related)

## Reporting Issues

### Bug Reports

Use the [Bug Report template](https://github.com/spectrayan/spector/issues/new?template=bug_report.md) and include:

- Steps to reproduce
- Expected vs actual behavior
- JDK version and SIMD capability output
- Relevant logs or stack traces

### Feature Requests

Use the [Feature Request template](https://github.com/spectrayan/spector/issues/new?template=feature_request.md) and describe:

- The problem you're trying to solve
- Your proposed solution
- Any alternatives you've considered

## Questions?

- **General questions:** Open a [Discussion](https://github.com/spectrayan/spector/discussions)
- **Bug reports:** Open an [Issue](https://github.com/spectrayan/spector/issues)
- **Security vulnerabilities:** See [SECURITY.md](SECURITY.md)
- **Email:** [developer@spectrayan.com](mailto:developer@spectrayan.com)

---

Thank you for contributing to Spector! ⚡
