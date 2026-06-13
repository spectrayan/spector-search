---
description: Validates build, tests, licenses, and docs locally, then incrementally commits, pushes, and monitors GitHub CI until a successful build is achieved.
---

## Trigger

Use this workflow when:
- Local changes are ready to be verified, committed, and pushed.
- The user asks to run the CI validation, commit, push, and monitor workflow.

## Steps

### 1. Run Pre-Commit Local Validation

Ensure all checks pass locally to mimic the GitHub Actions CI environment exactly:

- **Check License Headers**:
  ```bash
  mvn -B license:check --no-transfer-progress
  ```
  *If this fails, run `mvn license:format` to apply headers, then stage the changes.*

- **Build with Reproducible Output**:
  ```bash
  mvn -B clean install --no-transfer-progress -Dproject.build.outputTimestamp=2024-01-01T00:00:00Z
  ```

- **Verify Reproducible JARs**:
  ```bash
  mvn -B package -DskipTests --no-transfer-progress -Dproject.build.outputTimestamp=2024-01-01T00:00:00Z -pl '!spector-bench'
  ```

- **Verify No Dynamic Version Ranges**:
  ```bash
  mvn -B dependency:tree --no-transfer-progress | grep -E '\[(.*,.*)\]|\[.*,\)|\(.*,.*\]|LATEST|RELEASE|SNAPSHOT' | grep -v 'com.spectrayan' | grep -v 'Building ' | grep -v 'Reactor Summary'
  ```
  *The output must be empty. If dynamic versions are detected, pin them in the respective POM files.*

- **Run Cognitive Benchmark Property Tests**:
  ```bash
  mvn -B test -pl spector-bench --no-transfer-progress -DskipBenchTests=false -Dtest="*PropertyTest"
  ```

- **Run Cognitive Benchmark Unit Tests**:
  ```bash
  mvn -B test -pl spector-bench --no-transfer-progress -DskipBenchTests=false -Dtest="*Test,!*PropertyTest,!*IntegrationTest"
  ```

- **Build Documentation**:
  ```bash
  cd docs && python -m mkdocs build --clean && cd ..
  ```

### 2. Verify Local Test Coverage

- **Generate Coverage Report**:
  ```bash
  mvn -B jacoco:report-aggregate --no-transfer-progress
  ```
- **Verify Coverage Baselines**:
  Check coverage metrics using python against `.github/coverage-baseline.json` to ensure there are no unintended regressions.

### 3. Create Incremental Commits

Follow the [Incremental Commits Skill](.agents/skills/incremental-commits/SKILL.md):
- Group changes logically by component in strict priority order.
- Commit each group separately using Conventional Commits format.

### 4. Push to Origin

Push the commits to the remote branch:
```bash
git push origin main
```
*(or the current active branch)*

### 5. Monitor GitHub Actions CI

> **IMPORTANT**: Use the user's existing Chrome browser — do NOT open a new browser window.
> The user is already logged into GitHub in their active browser session. Opening a new
> window loses the session and you won't be able to view detailed logs.

- **Open a new tab** in the existing browser (use the active page ID from the browser state)
  and navigate to: `https://github.com/spectrayan/spector/actions`
- Locate the workflow run triggered by your latest push/commit.
- Click into the run to view the job summary page.
- Monitor every 30 seconds by reloading the page until the run completes.
- If the run **succeeds**: proceed to the summary.
- If the run **fails**: click into the failed job, expand the failing step,
  and read the full error logs directly in the browser (you have access since
  you're in the user's authenticated session).

### 6. Handle Build Failures (Loop)

- **If the GitHub Actions run fails**:
  1. Read the detailed logs from the failing step **directly in the browser tab**
     (scroll down, expand collapsed sections, read error output).
  2. Identify the root cause from the logs.
  3. Implement a fix for the failure locally.
  4. Re-run the relevant local validation checks from **Step 1**.
  5. Create a new commit containing the fix.
  6. Push the changes to GitHub.
  7. Return to **Step 5** to monitor the new run (reuse the same browser tab).
- **If the GitHub Actions run succeeds**:
  - The workflow is complete. Provide a final summary of the successful run.
