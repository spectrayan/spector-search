<#
.SYNOPSIS
  Starts both the Spector Node backend and Cortex UI dev server.

.EXAMPLE
  .\scripts\start-cortex-dev.ps1
  .\scripts\start-cortex-dev.ps1 -SkipBuild
  .\scripts\start-cortex-dev.ps1 -FrontendOnly
#>
param(
    [switch]$SkipBuild,
    [switch]$BackendOnly,
    [switch]$FrontendOnly,
    [int]$Port = 7070
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot | Split-Path -Parent

function Write-Step { param([string]$msg) Write-Host "  > $msg" -ForegroundColor Cyan }
function Write-OK   { param([string]$msg) Write-Host "  [OK] $msg" -ForegroundColor Green }
function Write-Err  { param([string]$msg) Write-Host "  [ERR] $msg" -ForegroundColor Red }

Write-Host ""
Write-Host "  ================================================" -ForegroundColor DarkMagenta
Write-Host "     Spector Cortex -- Dev Environment              " -ForegroundColor DarkMagenta
Write-Host "  ================================================" -ForegroundColor DarkMagenta
Write-Host ""

# -- Step 1: Maven build --
if (-not $SkipBuild -and -not $FrontendOnly) {
    Write-Step "Building Maven reactor (install, skip tests)..."
    Push-Location $root
    try {
        mvn install -DskipTests -T1C -q
        if ($LASTEXITCODE -ne 0) {
            Write-Err "Maven build failed (exit code $LASTEXITCODE)."
            exit 1
        }
        Write-OK "Maven build succeeded"
    } finally {
        Pop-Location
    }
}

# -- Step 2: Start backend using mvn exec:exec (Maven handles classpath) --
$backendJob = $null
if (-not $FrontendOnly) {
    Write-Step "Starting Spector Node on port $Port..."

    $backendJob = Start-Job -ScriptBlock {
        param([string]$rootDir, [int]$p)
        $env:SPECTOR_PORT = $p
        Set-Location $rootDir
        mvn -pl spector-node compile exec:exec -q 2>&1
    } -ArgumentList $root, $Port

    # Wait for backend to be ready
    $ready = $false
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 2
        try {
            $resp = Invoke-RestMethod -Uri "http://localhost:$Port/health" -TimeoutSec 2 -ErrorAction SilentlyContinue
            if ($resp) { $ready = $true; break }
        } catch { }
        if ($backendJob.State -eq "Failed" -or $backendJob.State -eq "Completed") {
            Write-Err "Backend process exited unexpectedly:"
            Receive-Job $backendJob | Select-Object -Last 20 | ForEach-Object { Write-Host "    $_" }
            exit 1
        }
        Write-Host "    waiting for backend... ($($i * 2)s)" -ForegroundColor DarkGray
    }

    if ($ready) {
        Write-OK "Backend ready at http://localhost:$Port"
    } else {
        Write-Err "Backend did not become healthy in 60s."
        Receive-Job $backendJob | Select-Object -Last 30 | ForEach-Object { Write-Host "    $_" }
        exit 1
    }
}

if ($BackendOnly) {
    Write-Host ""
    Write-OK "Backend running. Press Ctrl+C to stop."
    try {
        Wait-Job $backendJob | Out-Null
        Receive-Job $backendJob | ForEach-Object { Write-Host $_ }
    } finally {
        if ($backendJob) {
            Stop-Job $backendJob -ErrorAction SilentlyContinue
            Remove-Job $backendJob -Force -ErrorAction SilentlyContinue
        }
    }
    exit 0
}

# -- Step 3: Start Cortex UI --
Write-Step "Starting Cortex UI dev server..."
Write-Host ""
Write-Host "  ------------------------------------------------" -ForegroundColor DarkCyan
Write-Host "    Backend:  http://localhost:$Port" -ForegroundColor DarkCyan
Write-Host "    Cortex:   http://localhost:4200" -ForegroundColor DarkCyan
Write-Host "    Proxy:    /api/* -> localhost:$Port" -ForegroundColor DarkCyan
Write-Host "    Press Ctrl+C to stop both servers" -ForegroundColor DarkCyan
Write-Host "  ------------------------------------------------" -ForegroundColor DarkCyan
Write-Host ""

try {
    Push-Location (Join-Path $root "spector-cortex")
    npm run start
} finally {
    Pop-Location
    if ($backendJob) {
        Write-Step "Stopping backend..."
        Stop-Job $backendJob -ErrorAction SilentlyContinue
        Remove-Job $backendJob -Force -ErrorAction SilentlyContinue
        Write-OK "Backend stopped"
    }
    Write-Host ""
    Write-OK "All servers stopped."
}
