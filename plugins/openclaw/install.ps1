# ═══════════════════════════════════════════════════════════════════════
# Spector Cognitive Memory — OpenClaw One-Line Installer (Windows)
#
# Usage:
#   irm https://raw.githubusercontent.com/spectrayan/spector/main/plugins/openclaw/install.ps1 | iex
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

$ErrorActionPreference = "Stop"

# ─────────────────────────────────────────────────────────────────────
# Configuration
# ─────────────────────────────────────────────────────────────────────

$RepoUrl    = "https://github.com/spectrayan/spector.git"
$InstallDir = Join-Path $env:USERPROFILE ".openclaw\spector\source"
$PluginDir  = "plugins\openclaw"
$Branch     = if ($env:SPECTOR_BRANCH) { $env:SPECTOR_BRANCH } else { "main" }

# ─────────────────────────────────────────────────────────────────────
# Helpers
# ─────────────────────────────────────────────────────────────────────

function Info($msg)  { Write-Host "[Spector] $msg" -ForegroundColor Cyan }
function Ok($msg)    { Write-Host "[Spector] ✅ $msg" -ForegroundColor Green }
function Warn($msg)  { Write-Host "[Spector] ⚠️  $msg" -ForegroundColor Yellow }
function Fail($msg)  { Write-Host "[Spector] ❌ $msg" -ForegroundColor Red; exit 1 }

# ─────────────────────────────────────────────────────────────────────
# Pre-flight checks
# ─────────────────────────────────────────────────────────────────────

Write-Host ""
Write-Host "╔══════════════════════════════════════════════════════════╗" -ForegroundColor White
Write-Host "║  ⚡ Spector Cognitive Memory — OpenClaw Installer       ║" -ForegroundColor White
Write-Host "╚══════════════════════════════════════════════════════════╝" -ForegroundColor White
Write-Host ""

# Check for git
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Fail "Git is required but not found. Install it first: winget install Git.Git"
}

# Check for node
if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    Fail "Node.js is required but not found. Install Node.js 20+: winget install OpenJS.NodeJS.LTS"
}

# Check for openclaw CLI
$OpenClawAvailable = $true
if (-not (Get-Command openclaw -ErrorAction SilentlyContinue)) {
    Warn "OpenClaw CLI not found on PATH."
    Warn "After installing Spector, you'll need to register it manually."
    $OpenClawAvailable = $false
}

# ─────────────────────────────────────────────────────────────────────
# Step 1: Clone or update the repository
# ─────────────────────────────────────────────────────────────────────

Info "Step 1/4: Getting Spector source..."

$GitDir = Join-Path $InstallDir ".git"
if (Test-Path $GitDir) {
    Info "Existing clone found at $InstallDir, pulling latest..."
    Push-Location $InstallDir
    try {
        git fetch origin $Branch --depth=1 2>$null
        git checkout $Branch 2>$null
        git pull origin $Branch --ff-only 2>$null
        if ($LASTEXITCODE -ne 0) {
            git reset --hard "origin/$Branch" 2>$null
        }
        Ok "Updated to latest $Branch"
    } finally {
        Pop-Location
    }
} else {
    Info "Cloning Spector (shallow, $Branch branch)..."
    $ParentDir = Split-Path $InstallDir -Parent
    if (-not (Test-Path $ParentDir)) {
        New-Item -ItemType Directory -Path $ParentDir -Force | Out-Null
    }
    git clone --depth=1 --branch=$Branch --single-branch $RepoUrl $InstallDir 2>$null
    Ok "Cloned to $InstallDir"
}

$PluginPath = Join-Path $InstallDir $PluginDir
Push-Location $PluginPath

# ─────────────────────────────────────────────────────────────────────
# Step 2: Build the TypeScript plugin
# ─────────────────────────────────────────────────────────────────────

Info "Step 2/4: Building OpenClaw plugin..."

try {
    npm install --silent 2>$null
    npm run build --silent 2>$null
    Ok "Plugin built successfully"
} catch {
    Fail "Failed to build plugin: $_"
} finally {
    Pop-Location
}

# ─────────────────────────────────────────────────────────────────────
# Step 3: Install into OpenClaw
# ─────────────────────────────────────────────────────────────────────

Info "Step 3/4: Installing plugin into OpenClaw..."

if ($OpenClawAvailable) {
    try {
        openclaw plugins install --link $PluginPath 2>$null
        Ok "Plugin installed into OpenClaw (linked)"
    } catch {
        try {
            openclaw plugins install $PluginPath 2>$null
            Ok "Plugin installed into OpenClaw"
        } catch {
            Warn "Auto-install failed. You can install manually:"
            Warn "  openclaw plugins install $PluginPath"
        }
    }
} else {
    Info "OpenClaw CLI not available — skipping auto-install."
    Info "Install manually later:"
    Write-Host ""
    Write-Host "  openclaw plugins install $PluginPath"
    Write-Host ""
}

# ─────────────────────────────────────────────────────────────────────
# Step 4: Run setup wizard (optional)
# ─────────────────────────────────────────────────────────────────────

Info "Step 4/4: Running setup wizard..."

if ($OpenClawAvailable) {
    Write-Host ""
    try {
        openclaw spector setup
    } catch {
        Warn "Setup wizard failed or was skipped."
        Info "You can run it later: openclaw spector setup"
    }
} else {
    Info "Run the setup wizard after installing OpenClaw:"
    Write-Host "  openclaw spector setup"
}

# ─────────────────────────────────────────────────────────────────────
# Done
# ─────────────────────────────────────────────────────────────────────

Write-Host ""
Write-Host "╔══════════════════════════════════════════════════════════╗" -ForegroundColor White
Write-Host "║  ✅ Spector installed!                                  ║" -ForegroundColor White
Write-Host "║                                                         ║" -ForegroundColor White
Write-Host "║  Next steps:                                            ║" -ForegroundColor White
Write-Host "║    1. openclaw spector setup    (if not done above)     ║" -ForegroundColor White
Write-Host "║    2. openclaw gateway restart                         ║" -ForegroundColor White
Write-Host "║                                                         ║" -ForegroundColor White
Write-Host "║  To update later: re-run this script                    ║" -ForegroundColor White
Write-Host "║                                                         ║" -ForegroundColor White
Write-Host "║  Source: $InstallDir" -ForegroundColor White
Write-Host "╚══════════════════════════════════════════════════════════╝" -ForegroundColor White
Write-Host ""
