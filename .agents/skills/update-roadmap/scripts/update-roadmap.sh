#!/usr/bin/env bash

# Automates Spector Search roadmap updates across README.md and docs/docs/roadmap.md on Unix/Linux/macOS.

set -e

# --- Default Arguments ---
ACTION=""
NAME=""
DESCRIPTION=""
CATEGORY="Runtime"
STATUS="Planned"
DETAIL_TEXT=""
COMPRESSION="N/A"
RECALL="None"
EFFORT="Medium"

# --- Parse Arguments ---
while [[ $# -gt 0 ]]; do
  case $1 in
    -Action|-action|--action)
      ACTION="$2"
      shift 2
      ;;
    -Name|-name|--name)
      NAME="$2"
      shift 2
      ;;
    -Description|-description|--description)
      DESCRIPTION="$2"
      shift 2
      ;;
    -Category|-category|--category)
      CATEGORY="$2"
      shift 2
      ;;
    -Status|-status|--status)
      STATUS="$2"
      shift 2
      ;;
    -DetailText|-detailtext|--detailtext)
      DETAIL_TEXT="$2"
      shift 2
      ;;
    -Compression|-compression|--compression)
      COMPRESSION="$2"
      shift 2
      ;;
    -Recall|-recall|--recall)
      RECALL="$2"
      shift 2
      ;;
    -Effort|-effort|--effort)
      EFFORT="$2"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1"
      exit 1
      ;;
  esac
done

# --- Validate Required arguments ---
if [ -z "$ACTION" ] || [ -z "$NAME" ]; then
  echo "Error: -Action and -Name are required arguments."
  echo "Usage: ./update-roadmap.sh -Action [Add|Complete|Deprioritize|Remove] -Name \"Feature Name\" [options]"
  exit 1
fi

if [[ ! "$ACTION" =~ ^(Add|Complete|Deprioritize|Remove)$ ]]; then
  echo "Error: Invalid action '$ACTION'. Must be Add, Complete, Deprioritize, or Remove."
  exit 1
fi

# --- Resolve Paths ---
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
README_PATH="$WORKSPACE_ROOT/README.md"
ROADMAP_PATH="$WORKSPACE_ROOT/docs/docs/roadmap.md"

if [ ! -f "$README_PATH" ]; then
  echo "Error: README.md not found at $README_PATH"
  exit 1
fi
if [ ! -f "$ROADMAP_PATH" ]; then
  echo "Error: docs/docs/roadmap.md not found at $ROADMAP_PATH"
  exit 1
fi

# --- Resolve Icons & Anchors ---
EMOJI_PLANNED="🔜"
EMOJI_DONE="✅"
EMOJI_RESEARCH="🔬"
EMOJI_NOT_PLANNED="🔴"

case "$STATUS" in
  Planned)
    STATUS_ICON="$EMOJI_PLANNED Planned"
    STATUS_DETAILS_ICON="$EMOJI_PLANNED"
    ;;
  Done)
    STATUS_ICON="$EMOJI_DONE Done"
    STATUS_DETAILS_ICON="$EMOJI_DONE"
    ;;
  Exploratory|Research)
    STATUS_ICON="$EMOJI_RESEARCH $STATUS"
    STATUS_DETAILS_ICON="$EMOJI_RESEARCH"
    ;;
  *)
    STATUS_ICON="$STATUS"
    STATUS_DETAILS_ICON=""
    ;;
esac

# Clean anchor (e.g. "Hardware Cosine SIMD" -> "hardware-cosine-simd")
CLEAN_ANCHOR=$(echo "$NAME" | tr '[:upper:]' '[:lower:]' | sed 's/ /-/g; s/\&/and/g; s/(//g; s/)//g; s/\///g')

# =============================================================================
# ACTION: ADD
# =============================================================================
if [ "$ACTION" = "Add" ]; then
  echo "Adding feature '$NAME' to roadmap..."

  # 1. Update README.md
  export TARGET_LINE="> See the [detailed Roadmap]"
  export NEW_README_LINE="- [ ] $NAME ($DESCRIPTION)"
  
  if grep -F -q "$TARGET_LINE" "$README_PATH"; then
    # Use Perl for clean multi-line injection without BSD/GNU sed incompatibilities
    perl -i -pe 's/\Q$ENV{TARGET_LINE}\E/$ENV{NEW_README_LINE}\n\n$ENV{TARGET_LINE}/g' "$README_PATH"
    echo "  [OK] README.md updated."
  else
    echo "Warning: Could not locate roadmap section in README.md."
  fi

  # 2. Update docs/docs/roadmap.md Detailed Section
  CATEGORY_HEADER=""
  case "$CATEGORY" in
    Compression) CATEGORY_HEADER="## Compression & Quantization" ;;
    Agentic)     CATEGORY_HEADER="## Agentic AI" ;;
    Compute)     CATEGORY_HEADER="## Compute & Hardware" ;;
    Runtime)     CATEGORY_HEADER="## Runtime & Deployment" ;;
    Distributed) CATEGORY_HEADER="## Distributed Clustering & Replication" ;;
  esac

  DETAILS_BLOCK="### $STATUS_DETAILS_ICON $NAME {#$CLEAN_ANCHOR}

!!! info \"Status: $STATUS\"
    $DESCRIPTION

$DETAIL_TEXT

---"

  if grep -F -q "$CATEGORY_HEADER" "$ROADMAP_PATH"; then
    export CATEGORY_HEADER
    export DETAILS_BLOCK
    perl -i -pe 's/\Q$ENV{CATEGORY_HEADER}\E/$ENV{CATEGORY_HEADER}\n\n$ENV{DETAILS_BLOCK}/g' "$ROADMAP_PATH"
    echo "  [OK] Detailed section in roadmap.md updated."
  else
    echo "Warning: Could not locate category header '$CATEGORY_HEADER' in docs/docs/roadmap.md."
  fi

  # 3. Update Summary Table in docs/docs/roadmap.md
  export NEW_ROW="| {IDX} | **$NAME** | $COMPRESSION | $RECALL | $EFFORT | $STATUS_ICON |"
  perl -i -0777 -pe '
    my ($doc, $table) = split(/## Summary Table/, $_, 2);
    if ($table) {
      my $highest = 0;
      my $last_row = "";
      while ($table =~ /^\|\s*(\d+)\s*\|.*$/mg) {
        my $val = $1;
        if ($val > $highest) {
          $highest = $val;
        }
        $last_row = $&;
      }
      my $new_idx = $highest + 1;
      my $row_template = $ENV{NEW_ROW};
      $row_template =~ s/\{IDX\}/$new_idx/g;
      my $eol = $table =~ /\r\n/ ? "\r\n" : "\n";
      if ($last_row) {
        $last_row =~ s/\r$//;
        $table =~ s/\Q$last_row\E\r?\n/$last_row$eol$row_template$eol/m;
      }
      $_ = $doc . "## Summary Table" . $table;
    }
  ' "$ROADMAP_PATH"
  echo "  [OK] Summary Table in roadmap.md updated."

# =============================================================================
# ACTION: COMPLETE
# =============================================================================
elif [ "$ACTION" = "Complete" ]; then
  echo "Completing feature '$NAME'..."

  # 1. Update README.md (check checkbox)
  export NAME
  perl -i -pe 's/-\s*\[\s*\]\s*\Q$ENV{NAME}\E/- [x] **$ENV{NAME}**/g' "$README_PATH"
  echo "  [OK] README.md checklist updated."

  # 2. Update docs/docs/roadmap.md Detailed Section & Reorganize Archive
  export NAME
  export CLEAN_ANCHOR
  export EMOJI_DONE
  perl -i -0777 -pe '
    my $name = $ENV{NAME};
    my $anchor = $ENV{CLEAN_ANCHOR};
    my $emoji_done = $ENV{EMOJI_DONE};
    my $escaped_name = quotemeta($name);
    my $escaped_anchor = quotemeta($anchor);
    
    # Regex to find the detailed block
    my $section_regex = qr/(?s)###\s+\S+\s+$escaped_name\s+\{#$escaped_anchor\}.*?---(?:\r?\n|$)/;
    
    if ($_ =~ /$section_regex/) {
      my $captured_block = $&;
      
      # Remove from active category
      $_ =~ s/\Q$captured_block\E//;
      
      # Format detailed block for Recently Completed archive
      $captured_block =~ s/^###\s+\S+/### $emoji_done/;
      $captured_block =~ s/Status:\s*(Planned|Exploratory|Research)/Status: Done/;
      $captured_block =~ s/!!! info/!!! success/;
      $captured_block =~ s/Planned|Exploratory|Research/Completed/g;
      
      # Check line endings of the file to preserve them
      my $eol = $_ =~ /\r\n/ ? "\r\n" : "\n";
      
      # Append to Recently Completed section
      my $archive_header = "## Recently Completed (Archive)";
      if ($_ =~ /\Q$archive_header\E/) {
        $_ =~ s/(\Q$archive_header\E)/$1$eol$eol$captured_block/;
      } else {
        $_ = $_ . $eol . $eol . "---" . $eol . $eol . "## Recently Completed (Archive)" . $eol . $eol . $captured_block;
      }
    }
  ' "$ROADMAP_PATH"
  echo "  [OK] Detailed section moved to Recently Completed (Archive)."

  # 3. Update Summary Table Status to Done
  export NAME
  export EMOJI_DONE
  perl -i -0777 -pe '
    my ($doc, $table) = split(/## Summary Table/, $_, 2);
    if ($table) {
      my $name = $ENV{NAME};
      my $escaped_name = quotemeta($name);
      my @lines = split(/\r?\n/, $table);
      for my $line (@lines) {
        if ($line =~ /^\|\s*(\d+)\s*\|\s*\*\*$escaped_name\*\*/) {
          my @parts = split(/\|/, $line, -1);
          $parts[$#parts - 1] = " $ENV{EMOJI_DONE} Done ";
          $line = join("|", @parts);
        }
      }
      $table = join("\n", @lines);
      if ($_ =~ /\r\n/) {
        $table =~ s/\n/\r\n/g;
      }
      $_ = $doc . "## Summary Table" . $table;
    }
  ' "$ROADMAP_PATH"
  echo "  [OK] Summary Table row updated to $EMOJI_DONE Done."

# =============================================================================
# ACTION: DEPRIORITIZE
# =============================================================================
elif [ "$ACTION" = "Deprioritize" ]; then
  echo "Deprioritizing feature '$NAME'..."

  # 1. Update Summary Table Status in docs/docs/roadmap.md to Not Planned
  export NAME
  export EMOJI_NOT_PLANNED
  perl -i -0777 -pe '
    my ($doc, $table) = split(/## Summary Table/, $_, 2);
    if ($table) {
      my $name = $ENV{NAME};
      my $escaped_name = quotemeta($name);
      my @lines = split(/\r?\n/, $table);
      for my $line (@lines) {
        if ($line =~ /^\|\s*(\d+)\s*\|\s*\*\*$escaped_name\*\*/) {
          my @parts = split(/\|/, $line, -1);
          $parts[$#parts - 1] = " $ENV{EMOJI_NOT_PLANNED} Not planned ";
          $line = join("|", @parts);
        }
      }
      $table = join("\n", @lines);
      if ($_ =~ /\r\n/) {
        $table =~ s/\n/\r\n/g;
      }
      $_ = $doc . "## Summary Table" . $table;
    }
  ' "$ROADMAP_PATH"
  echo "  [OK] Summary Table row updated to $EMOJI_NOT_PLANNED Not planned."

  # 2. Update status in detailed description block
  export NAME
  export CLEAN_ANCHOR
  export EMOJI_NOT_PLANNED
  perl -i -0777 -pe '
    my $name = $ENV{NAME};
    my $anchor = $ENV{CLEAN_ANCHOR};
    my $emoji_not_planned = $ENV{EMOJI_NOT_PLANNED};
    my $escaped_name = quotemeta($name);
    my $escaped_anchor = quotemeta($anchor);
    
    # Regex to find the detailed block
    my $section_regex = qr/(?s)###\s+\S+\s+$escaped_name\s+\{#$escaped_anchor\}.*?---(?:\r?\n|$)/;
    
    if ($_ =~ /$section_regex/) {
      my $target_block = $&;
      my $replaced_block = $target_block;
      $replaced_block =~ s/^###\s+\S+/### $emoji_not_planned/m;
      $replaced_block =~ s/Status:\s*[^"\n\r]+/Status: Not Planned/;
      
      $_ =~ s/\Q$target_block\E/$replaced_block/;
    }
  ' "$ROADMAP_PATH"
  echo "  [OK] Detailed section status updated to $EMOJI_NOT_PLANNED Not Planned."

# =============================================================================
# ACTION: REMOVE
# =============================================================================
elif [ "$ACTION" = "Remove" ]; then
  echo "Removing feature '$NAME' completely from roadmap..."

  # 1. Remove from README.md
  export NAME
  perl -i -ne 'print unless /-\s*\[[\s*x]?\]\s*(?:\*\*)?\Q$ENV{NAME}\E/' "$README_PATH"
  echo "  [OK] Removed from README.md checklist."

  # 2. Remove detailed description from docs/docs/roadmap.md
  export NAME
  export CLEAN_ANCHOR
  perl -i -0777 -pe '
    my $name = $ENV{NAME};
    my $anchor = $ENV{CLEAN_ANCHOR};
    my $escaped_name = quotemeta($name);
    my $escaped_anchor = quotemeta($anchor);
    
    my $section_regex = qr/(?s)###\s+\S+\s+$escaped_name\s+\{#$escaped_anchor\}.*?---(?:\r?\n|$)/;
    $_ =~ s/$section_regex//;
  ' "$ROADMAP_PATH"
  echo "  [OK] Removed detailed description block."

  # 3. Remove row from Summary Table
  export NAME
  perl -i -0777 -pe '
    my ($doc, $table) = split(/## Summary Table/, $_, 2);
    if ($table) {
      my $name = $ENV{NAME};
      my $escaped_name = quotemeta($name);
      my @lines = split(/\r?\n/, $table);
      @lines = grep { !/^\|\s*\d+\s*\|\s*(?:\*\*)?\Q$name\E/ } @lines;
      $table = join("\n", @lines);
      if ($_ =~ /\r\n/) {
        $table =~ s/\n/\r\n/g;
      }
      $_ = $doc . "## Summary Table" . $table;
    }
  ' "$ROADMAP_PATH"
  echo "  [OK] Removed row from Summary Table."

fi

echo "Roadmap update completed successfully!"
