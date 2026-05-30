#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════
# collect-labs.sh — Auto-discover labs/* branches and generate docs
# ═══════════════════════════════════════════════════════════════════════
#
# This script is run by CI before `mkdocs build`. It:
#   1. Discovers all remote branches matching `origin/labs/*`
#   2. Extracts LABS.md from each branch via `git show`
#   3. Copies each LABS.md into docs/docs/labs/<branch-name>.md
#   4. Auto-generates docs/docs/labs/index.md with overview cards
#
# Convention: Each labs/* branch must have a LABS.md at the repo root.
#   - Line 1: `# <Title>` (becomes the nav entry and card title)
#   - Lines 3-5: First paragraph (becomes the overview blurb)
#
# Usage:
#   ./scripts/collect-labs.sh          # Run from repo root
#   ./scripts/collect-labs.sh --dry-run  # Preview without writing files
#
set -eu

DOCS_LABS_DIR="docs/docs/labs"
DRY_RUN=false

if [[ "${1:-}" == "--dry-run" ]]; then
    DRY_RUN=true
    echo "🔍 Dry run mode — no files will be written"
fi

# ─── Ensure we have remote branch info ───────────────────────────────
git fetch --prune origin 'refs/heads/labs/*:refs/remotes/origin/labs/*' 2>/dev/null || true

# ─── Discover all labs branches ──────────────────────────────────────
LABS_BRANCHES=$(git branch -r --list 'origin/labs/*' 2>/dev/null | sed 's/^ *//' | sort)

if [[ -z "$LABS_BRANCHES" ]]; then
    echo "ℹ️  No labs/* branches found. Skipping labs docs generation."
    # Create minimal index if the nav references it
    mkdir -p "$DOCS_LABS_DIR"
    cat > "$DOCS_LABS_DIR/index.md" << 'EOF'
# 🔬 Labs

> **Experimental branches exploring cutting-edge JVM features and research ideas.**

No active lab branches found. When a `labs/*` branch is pushed with a `LABS.md` file,
it will automatically appear here.

Check the [Roadmap](../roadmap.md) for planned experiments.
EOF
    exit 0
fi

echo "🔬 Discovered lab branches:"
echo "$LABS_BRANCHES" | sed 's/^/   /'

# ─── Create output directory ────────────────────────────────────────
if [[ "$DRY_RUN" == "false" ]]; then
    mkdir -p "$DOCS_LABS_DIR"
fi

# ─── Collect LABS.md from each branch ────────────────────────────────
declare -a LAB_ENTRIES=()

for BRANCH in $LABS_BRANCHES; do
    # Extract branch short name: origin/labs/valhalla → valhalla
    SHORT_NAME="${BRANCH#origin/labs/}"
    SAFE_NAME=$(echo "$SHORT_NAME" | tr '/' '-')

    echo "   📄 Processing: $BRANCH → labs/$SAFE_NAME.md"

    # Try to extract LABS.md from this branch
    CONTENT=$(git show "$BRANCH:LABS.md" 2>/dev/null) || {
        echo "   ⚠️  No LABS.md found in $BRANCH — skipping"
        continue
    }

    # Extract title (first H1 line)
    TITLE=$(echo "$CONTENT" | grep -m1 '^# ' | sed 's/^# //')
    if [[ -z "$TITLE" ]]; then
        TITLE="Labs: $SHORT_NAME"
    fi

    # Extract overview: first non-empty, non-heading paragraph after the title
    # Skip lines starting with #, >, ---, or empty lines, then grab until next blank line
    OVERVIEW=$(echo "$CONTENT" | awk '
        BEGIN { found_title=0; in_para=0 }
        /^# / { found_title=1; next }
        found_title && /^$/ && !in_para { next }
        found_title && /^[>#\-\[]/ && !in_para { next }
        found_title && /^.+$/ && !in_para { in_para=1; print; next }
        in_para && /^.+$/ { print; next }
        in_para && /^$/ { exit }
    ')

    if [[ -z "$OVERVIEW" ]]; then
        OVERVIEW="Experimental branch: \`labs/$SHORT_NAME\`"
    else
        # Collapse multi-line to single line (read <<< splits on newlines)
        OVERVIEW=$(echo "$OVERVIEW" | tr '\n' ' ' | sed 's/  */ /g')
    fi

    # Extract metadata (disable pipefail for grep pipelines)
    STATUS=$(set +o pipefail; echo "$CONTENT" | grep -m1 'Status:' | sed 's/.*Status:[[:space:]]*//' | sed 's/[*]//g' | sed 's/^[[:space:]]*//')
    [[ -z "$STATUS" ]] && STATUS="Experimental"

    LAST_UPDATED=$(set +o pipefail; git log -1 --format='%cd' --date=short "$BRANCH" 2>/dev/null)
    [[ -z "$LAST_UPDATED" ]] && LAST_UPDATED="unknown"

    COMMIT_COUNT=$(set +o pipefail; git rev-list --count "origin/main..$BRANCH" 2>/dev/null)
    [[ -z "$COMMIT_COUNT" ]] && COMMIT_COUNT="?"

    if [[ "$DRY_RUN" == "false" ]]; then
        # Write the full LABS.md as a doc page, with metadata header
        {
            echo "---"
            echo "title: \"$TITLE\""
            echo "---"
            echo ""
            echo "!!! warning \"Experimental Branch\""
            echo "    This page is auto-generated from the \`labs/$SHORT_NAME\` branch."
            echo "    It requires a specialized JDK or environment. See build instructions below."
            echo ""
            echo "**Branch:** [\`labs/$SHORT_NAME\`](https://github.com/spectrayan/spector/tree/labs/$SHORT_NAME)"
            echo "| **Last updated:** $LAST_UPDATED"
            echo "| **Commits ahead of main:** $COMMIT_COUNT"
            echo ""
            echo "---"
            echo ""
            echo "$CONTENT"
        } > "$DOCS_LABS_DIR/$SAFE_NAME.md"
    fi

    # Collect entry for index page (use SOH as delimiter — pipe conflicts with markdown tables)
    SEP=$'\x01'
    LAB_ENTRIES+=("${SAFE_NAME}${SEP}${TITLE}${SEP}${OVERVIEW}${SEP}${STATUS}${SEP}${LAST_UPDATED}${SEP}${COMMIT_COUNT}")

    echo "   ✅ Done: $TITLE"
done

# ─── Generate index page ────────────────────────────────────────────
if [[ "$DRY_RUN" == "false" ]]; then
    INDEX_FILE="$DOCS_LABS_DIR/index.md"

    cat > "$INDEX_FILE" << 'HEADER'
# 🔬 Labs

> **Experimental branches exploring cutting-edge JVM features and research ideas.**
>
> Each lab branch contains a self-contained experiment that may require specialized
> JDK builds or dependencies. Labs are automatically discovered from `labs/*` branches
> and documented here.

!!! info "How Labs Work"
    Any branch named `labs/<feature>` with a `LABS.md` file at the root is automatically
    picked up by CI and rendered here. No manual editing of `main` required.

---

HEADER

    for ENTRY in "${LAB_ENTRIES[@]}"; do
        IFS=$'\x01' read -r SAFE_NAME TITLE OVERVIEW STATUS LAST_UPDATED COMMIT_COUNT <<< "$ENTRY"

        cat >> "$INDEX_FILE" << EOF
## [$TITLE]($SAFE_NAME.md)

| | |
|---|---|
| **Branch** | [\`labs/$SAFE_NAME\`](https://github.com/spectrayan/spector/tree/labs/$SAFE_NAME) |
| **Status** | $STATUS |
| **Updated** | $LAST_UPDATED |
| **Commits** | $COMMIT_COUNT ahead of main |

$OVERVIEW

[:octicons-arrow-right-24: Full details]($SAFE_NAME.md){ .md-button }

---

EOF
    done

    echo ""
    echo "✅ Generated $INDEX_FILE with ${#LAB_ENTRIES[@]} lab(s)"
fi

echo "🔬 Labs collection complete: ${#LAB_ENTRIES[@]} lab(s) processed"
