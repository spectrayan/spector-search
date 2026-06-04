#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════
# Spector Cognitive Memory — OpenClaw One-Line Installer
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/spectrayan/spector/main/plugins/openclaw/install.sh | bash
#
# What it does:
#   1. Clones the Spector repo (shallow) or pulls latest if already cloned
#   2. Builds the OpenClaw plugin (npm install + tsc)
#   3. Installs the plugin into OpenClaw via `openclaw plugins install`
#   4. Runs the setup wizard
#
# Requirements:
#   - OpenClaw CLI installed and on PATH
#   - Node.js 20+ (comes with OpenClaw)
#   - Git
#
# ═══════════════════════════════════════════════════════════════════════

set -euo pipefail

# ─────────────────────────────────────────────────────────────────────
# Configuration
# ─────────────────────────────────────────────────────────────────────

REPO_URL="https://github.com/spectrayan/spector.git"
INSTALL_DIR="${HOME}/.openclaw/spector/source"
PLUGIN_DIR="plugins/openclaw"
BRANCH="${SPECTOR_BRANCH:-main}"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color
BOLD='\033[1m'

# ─────────────────────────────────────────────────────────────────────
# Helpers
# ─────────────────────────────────────────────────────────────────────

info()  { echo -e "${CYAN}[Spector]${NC} $1"; }
ok()    { echo -e "${GREEN}[Spector]${NC} ✅ $1"; }
warn()  { echo -e "${YELLOW}[Spector]${NC} ⚠️  $1"; }
fail()  { echo -e "${RED}[Spector]${NC} ❌ $1"; exit 1; }

# ─────────────────────────────────────────────────────────────────────
# Pre-flight checks
# ─────────────────────────────────────────────────────────────────────

echo ""
echo -e "${BOLD}╔══════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║  ⚡ Spector Cognitive Memory — OpenClaw Installer       ║${NC}"
echo -e "${BOLD}╚══════════════════════════════════════════════════════════╝${NC}"
echo ""

# Check for git
if ! command -v git &> /dev/null; then
    fail "Git is required but not found. Install it first."
fi

# Check for node
if ! command -v node &> /dev/null; then
    fail "Node.js is required but not found. Install Node.js 20+ first."
fi

# Check for openclaw CLI
if ! command -v openclaw &> /dev/null; then
    warn "OpenClaw CLI not found on PATH."
    warn "After installing Spector, you'll need to register it manually."
    OPENCLAW_AVAILABLE=false
else
    OPENCLAW_AVAILABLE=true
fi

# ─────────────────────────────────────────────────────────────────────
# Step 1: Clone or update the repository
# ─────────────────────────────────────────────────────────────────────

info "Step 1/4: Getting Spector source..."

if [ -d "${INSTALL_DIR}/.git" ]; then
    info "Existing clone found at ${INSTALL_DIR}, pulling latest..."
    cd "${INSTALL_DIR}"
    git fetch origin "${BRANCH}" --depth=1 2>/dev/null || true
    git checkout "${BRANCH}" 2>/dev/null || true
    git pull origin "${BRANCH}" --ff-only 2>/dev/null || git reset --hard "origin/${BRANCH}"
    ok "Updated to latest ${BRANCH}"
else
    info "Cloning Spector (shallow, ${BRANCH} branch)..."
    mkdir -p "$(dirname "${INSTALL_DIR}")"
    git clone --depth=1 --branch="${BRANCH}" --single-branch "${REPO_URL}" "${INSTALL_DIR}" 2>/dev/null
    ok "Cloned to ${INSTALL_DIR}"
fi

cd "${INSTALL_DIR}/${PLUGIN_DIR}"

# ─────────────────────────────────────────────────────────────────────
# Step 2: Build the TypeScript plugin
# ─────────────────────────────────────────────────────────────────────

info "Step 2/4: Building OpenClaw plugin..."

npm install --silent 2>/dev/null
npm run build --silent 2>/dev/null

ok "Plugin built successfully"

# ─────────────────────────────────────────────────────────────────────
# Step 3: Install into OpenClaw
# ─────────────────────────────────────────────────────────────────────

info "Step 3/4: Installing plugin into OpenClaw..."

if [ "${OPENCLAW_AVAILABLE}" = true ]; then
    # Use --link for development-friendly install (picks up updates)
    openclaw plugins install --link "${INSTALL_DIR}/${PLUGIN_DIR}" 2>/dev/null || {
        # Fallback to standard install
        openclaw plugins install "${INSTALL_DIR}/${PLUGIN_DIR}" 2>/dev/null || {
            warn "Auto-install failed. You can install manually:"
            warn "  openclaw plugins install ${INSTALL_DIR}/${PLUGIN_DIR}"
        }
    }
    ok "Plugin installed into OpenClaw"
else
    info "OpenClaw CLI not available — skipping auto-install."
    info "Install manually later:"
    echo ""
    echo "  openclaw plugins install ${INSTALL_DIR}/${PLUGIN_DIR}"
    echo ""
fi

# ─────────────────────────────────────────────────────────────────────
# Step 4: Run setup wizard (optional)
# ─────────────────────────────────────────────────────────────────────

info "Step 4/4: Running setup wizard..."

if [ "${OPENCLAW_AVAILABLE}" = true ]; then
    echo ""
    openclaw spector setup || {
        warn "Setup wizard failed or was skipped."
        info "You can run it later: openclaw spector setup"
    }
else
    info "Run the setup wizard after installing OpenClaw:"
    echo "  openclaw spector setup"
fi

# ─────────────────────────────────────────────────────────────────────
# Done
# ─────────────────────────────────────────────────────────────────────

echo ""
echo -e "${BOLD}╔══════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║  ✅ Spector installed!                                  ║${NC}"
echo -e "${BOLD}║                                                         ║${NC}"
echo -e "${BOLD}║  Next steps:                                            ║${NC}"
echo -e "${BOLD}║    1. openclaw spector setup    (if not done above)     ║${NC}"
echo -e "${BOLD}║    2. openclaw gateway restart                         ║${NC}"
echo -e "${BOLD}║                                                         ║${NC}"
echo -e "${BOLD}║  To update later:                                       ║${NC}"
echo -e "${BOLD}║    curl -fsSL https://raw.githubusercontent.com/        ║${NC}"
echo -e "${BOLD}║    spectrayan/spector/main/plugins/openclaw/install.sh  ║${NC}"
echo -e "${BOLD}║    | bash                                               ║${NC}"
echo -e "${BOLD}║                                                         ║${NC}"
echo -e "${BOLD}║  Source: ${INSTALL_DIR}  ║${NC}"
echo -e "${BOLD}╚══════════════════════════════════════════════════════════╝${NC}"
echo ""
