<#
.SYNOPSIS
    Ingest a full cognitive benchmark dataset into Spector Memory via the REST API.

.DESCRIPTION
    Reads the dataset directory and ingests:
      1. corpus.jsonl         → POST /api/v1/memory/remember  (memories)
      2. hebbian_edges.jsonl  → POST /api/v1/memory/admin/import/hebbian-edges
      3. temporal_chains.jsonl → POST /api/v1/memory/admin/import/temporal-chains
      4. entities.jsonl       → POST /api/v1/memory/admin/import/entity-relations

    Mirrors the BenchmarkSetup.createMemoryInstance() loading flow:
    memories first, then graph structures (edges, chains, entities).

.PARAMETER DatasetDir
    Path to the dataset directory (default: D:\git\spector-datasets\balanced-baseline\data)

.PARAMETER BaseUrl
    Spector Node base URL (default: http://localhost:7070)

.PARAMETER BatchSize
    Records per progress update for corpus (default: 100)

.PARAMETER GraphBatchSize
    Records per bulk POST for edges/chains/entities (default: 500)

.PARAMETER MaxRecords
    Stop corpus ingestion after N records (0 = all, default: 0)

.PARAMETER SkipCorpus
    Skip corpus ingestion (useful when re-importing graph data only)

.EXAMPLE
    .\scripts\ingest-dataset.ps1
    .\scripts\ingest-dataset.ps1 -MaxRecords 200
    .\scripts\ingest-dataset.ps1 -SkipCorpus  # graph data only
#>
param(
    [string]$DatasetDir    = "D:\git\spector-datasets\balanced-baseline\data",
    [string]$BaseUrl       = "http://localhost:7070",
    [int]$BatchSize        = 100,
    [int]$GraphBatchSize   = 500,
    [int]$MaxRecords       = 0,
    [switch]$SkipCorpus
)

$ErrorActionPreference = "Continue"

# ── Banner ──
Write-Host ""
Write-Host "  ══════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "     Spector Full Dataset Ingestion" -ForegroundColor Cyan
Write-Host "  ══════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host ""

# ── Validate ──
$corpusFile   = Join-Path $DatasetDir "corpus.jsonl"
$edgesFile    = Join-Path $DatasetDir "hebbian_edges.jsonl"
$chainsFile   = Join-Path $DatasetDir "temporal_chains.jsonl"
$entitiesFile = Join-Path $DatasetDir "entities.jsonl"

if (-not (Test-Path $corpusFile)) {
    Write-Host "  [ERR] Dataset not found: $corpusFile" -ForegroundColor Red
    exit 1
}

$API = "$BaseUrl/api/v1/memory"

# ── Health check ──
try {
    $null = Invoke-RestMethod -Uri "$BaseUrl/health" -TimeoutSec 3
    Write-Host "  [OK] Backend is healthy at $BaseUrl" -ForegroundColor Green
} catch {
    Write-Host "  [ERR] Backend not reachable at $BaseUrl" -ForegroundColor Red
    Write-Host "        Start it first: .\scripts\start-cortex-dev.ps1" -ForegroundColor Yellow
    exit 1
}

# ── Count files ──
$corpusCount  = if (Test-Path $corpusFile)   { (Get-Content $corpusFile   | Measure-Object).Count } else { 0 }
$edgesCount   = if (Test-Path $edgesFile)    { (Get-Content $edgesFile    | Measure-Object).Count } else { 0 }
$chainsCount  = if (Test-Path $chainsFile)   { (Get-Content $chainsFile   | Measure-Object).Count } else { 0 }
$entitiesCount = if (Test-Path $entitiesFile) { (Get-Content $entitiesFile | Measure-Object).Count } else { 0 }

Write-Host "  Dataset:  $DatasetDir" -ForegroundColor Gray
Write-Host "  Corpus:   $corpusCount memories" -ForegroundColor Gray
Write-Host "  Edges:    $edgesCount hebbian edges" -ForegroundColor Gray
Write-Host "  Chains:   $chainsCount temporal chains" -ForegroundColor Gray
Write-Host "  Entities: $entitiesCount entity relations" -ForegroundColor Gray
Write-Host ""

$totalStart = Get-Date

# ══════════════════════════════════════════════════════════════
# PHASE 1: Corpus Ingestion
# ══════════════════════════════════════════════════════════════

if (-not $SkipCorpus) {
    Write-Host "  ── Phase 1/4: Ingesting Corpus ──" -ForegroundColor Yellow
    $limit = if ($MaxRecords -gt 0) { [Math]::Min($MaxRecords, $corpusCount) } else { $corpusCount }
    $success = 0; $failed = 0; $lineNum = 0
    $phaseStart = Get-Date

    Get-Content $corpusFile | ForEach-Object {
        $lineNum++
        if ($MaxRecords -gt 0 -and $lineNum -gt $MaxRecords) { return }

        $line = $_.Trim()
        if ([string]::IsNullOrWhiteSpace($line)) { return }

        try {
            $record = $line | ConvertFrom-Json

            $body = @{
                id       = $record.id
                text     = $record.text
                tier     = if ($record.memoryType) { $record.memoryType } else { "SEMANTIC" }
                source   = if ($record.source)     { $record.source }     else { "OBSERVED" }
                tags     = if ($record.synapticTags) { ($record.synapticTags -join ",") } else { "" }
            }

            if ($record.interest)  { $body.interest  = [double]$record.interest }
            if ($record.challenge) { $body.challenge = [double]$record.challenge }
            if ($record.urgency)   { $body.urgency   = [double]$record.urgency }
            if ($null -ne $record.valence) { $body.valence = [int]$record.valence }
            if ($null -ne $record.arousal) { $body.arousal = [int]$record.arousal }

            $json = $body | ConvertTo-Json -Compress
            $null = Invoke-RestMethod -Uri "$API/remember" -Method Post -Body $json `
                -ContentType "application/json" -TimeoutSec 15
            $success++
        } catch {
            $failed++
            if ($failed -le 3) {
                Write-Host "    [ERR] Line $lineNum : $($_.Exception.Message)" -ForegroundColor Red
            }
        }

        if ($lineNum % $BatchSize -eq 0) {
            $elapsed = (Get-Date) - $phaseStart
            $rate = if ($elapsed.TotalSeconds -gt 0) { [Math]::Round($success / $elapsed.TotalSeconds, 1) } else { 0 }
            $pct = [Math]::Round(($lineNum / $limit) * 100, 1)
            Write-Host ("    [{0,6:N0}/{1}] {2}%  |  OK: {3}  ERR: {4}  |  {5} rec/s" -f `
                $lineNum, $limit, $pct, $success, $failed, $rate) -ForegroundColor Gray
        }
    }

    $elapsed = (Get-Date) - $phaseStart
    $rate = if ($elapsed.TotalSeconds -gt 0) { [Math]::Round($success / $elapsed.TotalSeconds, 1) } else { 0 }
    Write-Host "    Corpus: $success OK, $failed failed, $([Math]::Round($elapsed.TotalSeconds, 1))s ($rate rec/s)" -ForegroundColor $(if ($failed -gt 0) { "Yellow" } else { "Green" })
    Write-Host ""
} else {
    Write-Host "  ── Phase 1/4: Skipped (corpus) ──" -ForegroundColor DarkGray
    Write-Host ""
}

# ══════════════════════════════════════════════════════════════
# Helper: Bulk POST JSONL file in batches
# ══════════════════════════════════════════════════════════════

function Send-BulkJsonl {
    param(
        [string]$FilePath,
        [string]$Endpoint,
        [string]$Label,
        [int]$BatchSize
    )

    if (-not (Test-Path $FilePath)) {
        Write-Host "    [SKIP] $Label file not found: $FilePath" -ForegroundColor DarkGray
        return
    }

    $lines = Get-Content $FilePath | Where-Object { $_.Trim() -ne "" }
    $total = $lines.Count
    if ($total -eq 0) {
        Write-Host "    [SKIP] $Label file is empty" -ForegroundColor DarkGray
        return
    }

    $phaseStart = Get-Date
    $totalLoaded = 0; $totalSkipped = 0; $batchNum = 0

    for ($i = 0; $i -lt $total; $i += $BatchSize) {
        $batchNum++
        $end = [Math]::Min($i + $BatchSize, $total)
        $batchLines = $lines[$i..($end - 1)]

        # Parse each line and build a JSON array
        $records = @()
        foreach ($line in $batchLines) {
            try {
                $records += ($line | ConvertFrom-Json)
            } catch {
                $totalSkipped++
            }
        }

        if ($records.Count -eq 0) { continue }

        $json = $records | ConvertTo-Json -Depth 5 -Compress
        # Ensure it's a JSON array even for single records
        if ($records.Count -eq 1) { $json = "[$json]" }

        try {
            $result = Invoke-RestMethod -Uri "$Endpoint" -Method Post -Body $json `
                -ContentType "application/json" -TimeoutSec 30

            if ($result.loaded)  { $totalLoaded  += $result.loaded }
            if ($result.linked)  { $totalLoaded  += $result.linked }
            if ($result.skipped) { $totalSkipped += $result.skipped }
        } catch {
            Write-Host "    [ERR] Batch $batchNum : $($_.Exception.Message)" -ForegroundColor Red
            $totalSkipped += $records.Count
        }

        if ($batchNum % 5 -eq 0 -or $end -ge $total) {
            $pct = [Math]::Round(($end / $total) * 100, 1)
            Write-Host "    [$end/$total] $pct% | loaded: $totalLoaded  skipped: $totalSkipped" -ForegroundColor Gray
        }
    }

    $elapsed = (Get-Date) - $phaseStart
    Write-Host "    $Label : $totalLoaded loaded, $totalSkipped skipped, $([Math]::Round($elapsed.TotalSeconds, 1))s" `
        -ForegroundColor $(if ($totalSkipped -gt $totalLoaded) { "Yellow" } else { "Green" })
}

# ══════════════════════════════════════════════════════════════
# PHASE 2: Hebbian Edges
# ══════════════════════════════════════════════════════════════

Write-Host "  ── Phase 2/4: Importing Hebbian Edges ──" -ForegroundColor Yellow
Send-BulkJsonl -FilePath $edgesFile -Endpoint "$API/admin/import/hebbian-edges" `
    -Label "Hebbian edges" -BatchSize $GraphBatchSize
Write-Host ""

# ══════════════════════════════════════════════════════════════
# PHASE 3: Temporal Chains
# ══════════════════════════════════════════════════════════════

Write-Host "  ── Phase 3/4: Importing Temporal Chains ──" -ForegroundColor Yellow
Send-BulkJsonl -FilePath $chainsFile -Endpoint "$API/admin/import/temporal-chains" `
    -Label "Temporal chains" -BatchSize $GraphBatchSize
Write-Host ""

# ══════════════════════════════════════════════════════════════
# PHASE 4: Entity Relations
# ══════════════════════════════════════════════════════════════

Write-Host "  ── Phase 4/4: Importing Entity Relations ──" -ForegroundColor Yellow
Send-BulkJsonl -FilePath $entitiesFile -Endpoint "$API/admin/import/entity-relations" `
    -Label "Entity relations" -BatchSize $GraphBatchSize
Write-Host ""

# ══════════════════════════════════════════════════════════════
# SUMMARY
# ══════════════════════════════════════════════════════════════

$totalElapsed = (Get-Date) - $totalStart

Write-Host "  ══════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "  Ingestion Complete" -ForegroundColor Cyan
Write-Host "  ══════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "    Duration: $([Math]::Round($totalElapsed.TotalSeconds, 1))s" -ForegroundColor Gray
Write-Host ""

# Verify via status endpoint
try {
    $status = Invoke-RestMethod -Uri "$API/status" -TimeoutSec 5
    Write-Host "  Memory Status:" -ForegroundColor Cyan
    Write-Host "    Total memories:  $($status.totalMemories)" -ForegroundColor White
    $status.tierCounts.PSObject.Properties | ForEach-Object {
        Write-Host "      $($_.Name): $($_.Value)" -ForegroundColor Gray
    }
    Write-Host "    Hebbian edges:   $($status.hebbianEdges)" -ForegroundColor White
    Write-Host "    Temporal links:  $($status.temporalLinks)" -ForegroundColor White
    Write-Host "    Entity nodes:    $($status.entityNodes)" -ForegroundColor White
    Write-Host "    Entity edges:    $($status.entityEdges)" -ForegroundColor White
} catch {
    Write-Host "  [WARN] Could not verify status: $_" -ForegroundColor Yellow
}
Write-Host ""
