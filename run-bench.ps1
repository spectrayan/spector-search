# Run the cognitive benchmark harness with the full classpath
param(
    [string]$DatasetDir = "datasets\cognitive-benchmark",
    [string]$OutputDir = "target\benchmark-results",
    [string]$Profile = ""
)

# Build classpath from Maven dependency:build-classpath output
$depCp = Get-Content "spector-bench\target\bench-cp.txt" -ErrorAction Stop

# Collect all target/classes directories from known modules
$modules = @(
    "spector-bench", "spector-memory", "spector-core", "spector-commons",
    "spector-storage", "spector-index", "spector-embed-api", "spector-embed-ollama",
    "spector-config", "spector-ingestion", "spector-events", "spector-engine",
    "spector-rag", "spector-gpu", "spector-query", "spector-test-support"
)

$modCp = ($modules | ForEach-Object { "$PSScriptRoot\$_\target\classes" } | Where-Object { Test-Path $_ }) -join ";"

$fullCp = "$modCp;$depCp"

$javaArgs = @(
    "--enable-preview",
    "--add-modules", "jdk.incubator.vector",
    "-Xmx28g",
    "-cp", $fullCp,
    "com.spectrayan.spector.bench.cognitive.CognitiveBenchmarkHarness",
    $DatasetDir,
    $OutputDir
)

if ($Profile) {
    $javaArgs += @("", "", $Profile)
}

Write-Host "Running benchmark: dataset=$DatasetDir output=$OutputDir"
& java @javaArgs
Write-Host "Benchmark exited with code: $LASTEXITCODE"
