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
  ```powershell
  mvn -B license:check --no-transfer-progress
  ```
  *If this fails, run `mvn license:format` to apply headers, then stage the changes.*

- **Build with Reproducible Output**:
  ```powershell
  mvn -B clean install --no-transfer-progress -Dproject.build.outputTimestamp=2024-01-01T00:00:00Z
  ```

- **Verify Reproducible JARs**:
  ```powershell
  mvn -B package -DskipTests --no-transfer-progress -Dproject.build.outputTimestamp=2024-01-01T00:00:00Z -pl '!spector-bench'
  ```

- **Verify No Dynamic Version Ranges**:
  ```powershell
  mvn -B dependency:tree --no-transfer-progress | Select-String -Pattern '\[(.*,.*)\]|\[.*,\)|\(.*,.*\]|LATEST|RELEASE|SNAPSHOT' | Where-Object { $_.Line -notmatch 'com.spectrayan' -and $_.Line -notmatch 'Building ' -and $_.Line -notmatch 'Reactor Summary' }
  ```
  *The output must be empty. If dynamic versions are detected, pin them in the respective POM files.*

- **Run Cognitive Benchmark Property Tests**:
  ```powershell
  mvn -B test -pl spector-bench --no-transfer-progress -DskipBenchTests=false -Dtest="*PropertyTest"
  ```

- **Run Cognitive Benchmark Unit Tests**:
  ```powershell
  mvn -B test -pl spector-bench --no-transfer-progress -DskipBenchTests=false -Dtest="*Test,!*PropertyTest,!*IntegrationTest"
  ```

- **Build Documentation**:
  ```powershell
  cd docs
  python -m mkdocs build --clean
  cd ..
  ```

### 2. Verify Local Test Coverage

- **Generate Coverage Report**:
  ```powershell
  mvn -B jacoco:report-aggregate --no-transfer-progress
  ```
- **Verify Coverage Baselines**:
  Check coverage metrics using python against `.github/coverage-baseline.json` to ensure there are no unintended regressions.

### 3. Create Incremental Commits

Follow the [Incremental Commits Skill](file:///d:/git/spector-search/.agents/skills/incremental-commits/SKILL.md):
- Group changes logically by component in strict priority order.
- Commit each group separately using Conventional Commits format.

### 4. Push to Origin

Push the commits to the remote branch:
```powershell
git push origin main
```
*(or the current active branch)*

### 5. Monitor GitHub Actions CI

- Open the browser to: `https://github.com/spectrayan/spector/actions`
- Locate the workflow run triggered by your latest push/commit.
- Click into the run and monitor the build steps.
- Wait until the run finishes (either Success or Failure).

### 6. Handle Build Failures (Loop)

- **If the GitHub Actions run fails**:
  1. Inspect the detailed logs for the failing step to locate the cause of the failure.
  2. Implement a fix for the failure locally.
  3. Re-run the relevant local validation checks from **Step 1**.
  4. Create a new commit containing the fix.
  5. Push the changes to GitHub.
  6. Return to **Step 5** to monitor the new run.
- **If the GitHub Actions run succeeds**:
  - The workflow is complete. Provide a final summary of the successful run.
