# Skill: Update Roadmap

This skill enables the agent to dynamically and consistently manage the project roadmap for Spector Search, ensuring perfect synchronization between the root `README.md` and the detailed documentation in `docs/docs/roadmap.md`.

## Trigger

This skill is automatically triggered when the user requests roadmap modifications, such as:
- Adding a new feature or research goal.
- Completing a planned feature or task.
- Deprioritizing or marking a feature as not planned.
- Removing a feature completely from the roadmap.
- Automatically whenever a task inside a plan is completed, to keep the roadmap in sync.

## Workspace Requirements

- The repository root must contain this skill package under `.agents/skills/update-roadmap/`
- The helper scripts must be located at:
  - Windows: `.agents/skills/update-roadmap/scripts/update-roadmap.ps1`
  - Unix/Linux/macOS: `.agents/skills/update-roadmap/scripts/update-roadmap.sh`
- The root must contain `README.md` with the checklist under `## 📈 Roadmap`
- The docs folder must contain `docs/docs/roadmap.md` with the Summary Table and categories.

## Instructions for the Agent

When this skill is triggered, you must **never** manually edit the Markdown files (`README.md` or `docs/docs/roadmap.md`). Instead, you must run the PowerShell script `update-roadmap.ps1` or Bash script `update-roadmap.sh` located inside this skill package depending on your operating system environment.

### Action Mapping

Determine the appropriate action verb based on the user's request:

#### 1. Add Action (`-Action Add`)
Use when introducing a new feature, index, optimization, or research target.
- **Syntax (Windows)**:
  ```powershell
  powershell -ExecutionPolicy Bypass -File .agents/skills/update-roadmap/scripts/update-roadmap.ps1 -Action Add -Name "<Feature Name>" -Description "<One-line description>" -Category <Compression|Agentic|Compute|Runtime|Distributed> -Status <Planned|Exploratory|Research> -Compression "<savings>" -Recall "<impact>" -Effort <Low|Medium|High> -DetailText "<Detailed markdown specifications>"
  ```
- **Syntax (Unix/Linux/macOS)**:
  ```bash
  .agents/skills/update-roadmap/scripts/update-roadmap.sh -Action Add -Name "<Feature Name>" -Description "<One-line description>" -Category <Compression|Agentic|Compute|Runtime|Distributed> -Status <Planned|Exploratory|Research> -Compression "<savings>" -Recall "<impact>" -Effort <Low|Medium|High> -DetailText "<Detailed markdown specifications>"
  ```
- **Arguments**:
  - `-Name`: The exact name of the feature.
  - `-Description`: One-line summary (goes to README checklist).
  - `-Category`: Workspace category (`Compression`, `Agentic`, `Compute`, `Runtime`, `Distributed`).
  - `-Status`: One of `Planned`, `Exploratory`, `Research` (defaults to `Planned`).
  - `-Compression`: Projected space savings (e.g. "+25%", "8x", "N/A").
  - `-Recall`: Projected recall impact (e.g. "None", "-2%").
  - `-Effort`: Implementation effort (`Low`, `Medium`, `High`).
  - `-DetailText`: Detailed multi-line markdown block to append under the category details.

#### 2. Complete Action (`-Action Complete`)
Use when a feature is successfully implemented, verified, and merged.
- **Syntax (Windows)**:
  ```powershell
  powershell -ExecutionPolicy Bypass -File .agents/skills/update-roadmap/scripts/update-roadmap.ps1 -Action Complete -Name "<Feature Name>"
  ```
- **Syntax (Unix/Linux/macOS)**:
  ```bash
  .agents/skills/update-roadmap/scripts/update-roadmap.sh -Action Complete -Name "<Feature Name>"
  ```
- **Behavior**:
  - Marks the checkbox completed in `README.md` (`- [x] **Feature Name**`).
  - Moves the detailed block to `## Recently Completed (Archive)` in `docs/docs/roadmap.md`.
  - Updates the Summary Table row to `✅ Done`.

#### 3. Deprioritize Action (`-Action Deprioritize`)
Use when a feature is put on hold, marked not planned, or deferred.
- **Syntax (Windows)**:
  ```powershell
  powershell -ExecutionPolicy Bypass -File .agents/skills/update-roadmap/scripts/update-roadmap.ps1 -Action Deprioritize -Name "<Feature Name>"
  ```
- **Syntax (Unix/Linux/macOS)**:
  ```bash
  .agents/skills/update-roadmap/scripts/update-roadmap.sh -Action Deprioritize -Name "<Feature Name>"
  ```
- **Behavior**:
  - Updates Summary Table status to `🔴 Not planned`.
  - Updates detailed block status header to `🔴 Not Planned`.

#### 4. Remove Action (`-Action Remove`)
Use when a feature is entirely excised from the project scope.
- **Syntax (Windows)**:
  ```powershell
  powershell -ExecutionPolicy Bypass -File .agents/skills/update-roadmap/scripts/update-roadmap.ps1 -Action Remove -Name "<Feature Name>"
  ```
- **Syntax (Unix/Linux/macOS)**:
  ```bash
  .agents/skills/update-roadmap/scripts/update-roadmap.sh -Action Remove -Name "<Feature Name>"
  ```
- **Behavior**:
  - Deletes checkbox from `README.md`.
  - Deletes detailed description and Summary Table row from `docs/docs/roadmap.md`.

### Verification Steps

After executing the script, the agent must:
1. Verify the exit code is 0 and output confirms successful modification.
2. Run `git diff` to review all modified lines across `README.md` and `docs/docs/roadmap.md`.
3. Confirm that the checkbox states, table rows, and detailed sections align perfectly.
