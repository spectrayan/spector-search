#Requires -Version 5.1
<#
.SYNOPSIS
    Automates Spector Search roadmap updates across README.md and docs/docs/roadmap.md.
.DESCRIPTION
    This script provides an automated workflow to manage planned, active, completed, and
    deprioritized features. It automatically updates the checklist in README.md, reorganizes
    categories and appends archives in docs/docs/roadmap.md, and maintains the summary tables.
.PARAMETER Action
    The roadmap operation: Add, Complete, Deprioritize, or Remove.
.PARAMETER Name
    The name of the feature (e.g., "gRPC Replication Transport").
.PARAMETER Description
    A concise one-line description of the feature.
.PARAMETER Category
    The category for the feature: Compression, Agentic, Compute, Runtime, or Distributed.
.PARAMETER Status
    The feature status: Planned, Done, Exploratory, or Research.
.PARAMETER DetailText
    Optional multi-line detailed markdown description for docs/docs/roadmap.md.
.PARAMETER Compression
    The expected compression impact for the Summary Table (e.g. "+25%", "8x", "N/A"). Default: "N/A".
.PARAMETER Recall
    The expected recall impact for the Summary Table (e.g. "None", "-2%", "N/A"). Default: "None".
.PARAMETER Effort
    The expected implementation effort for the Summary Table (e.g. "Low", "Medium", "High"). Default: "Medium".
.EXAMPLE
    .agents\skills\update-roadmap\scripts\update-roadmap.ps1 -Action Add -Name "Hardware Cosine SIMD" -Description "Optimized cosine bounds" -Category Compute -Status Planned -Effort Low
.EXAMPLE
    .agents\skills\update-roadmap\scripts\update-roadmap.ps1 -Action Complete -Name "Hardware Cosine SIMD"
#>

[CmdletBinding()]
param (
    [Parameter(Mandatory = $true)]
    [ValidateSet('Add', 'Complete', 'Deprioritize', 'Remove')]
    [string]$Action,

    [Parameter(Mandatory = $true)]
    [string]$Name,

    [Parameter(Mandatory = $false)]
    [string]$Description = "",

    [Parameter(Mandatory = $false)]
    [ValidateSet('Compression', 'Agentic', 'Compute', 'Runtime', 'Distributed')]
    [string]$Category = "Runtime",

    [Parameter(Mandatory = $false)]
    [ValidateSet('Planned', 'Done', 'Exploratory', 'Research')]
    [string]$Status = "Planned",

    [Parameter(Mandatory = $false)]
    [string]$DetailText = "",

    [Parameter(Mandatory = $false)]
    [string]$Compression = "N/A",

    [Parameter(Mandatory = $false)]
    [string]$Recall = "None",

    [Parameter(Mandatory = $false)]
    [string]$Effort = "Medium"
)

# == Paths ==
$workspaceRoot = (Get-Item "$PSScriptRoot\..\..\..\..").FullName
$readmePath = Join-Path $workspaceRoot "README.md"
$roadmapPath = Join-Path $workspaceRoot "docs\docs\roadmap.md"

if (-not (Test-Path $readmePath)) {
    Write-Error "README.md not found at $readmePath"
    return
}
if (-not (Test-Path $roadmapPath)) {
    Write-Error "docs/docs/roadmap.md not found at $roadmapPath"
    return
}

# Resolve category headers
$categoryHeaderMap = @{
    'Compression' = '## Compression & Quantization'
    'Agentic'     = '## Agentic AI'
    'Compute'     = '## Compute & Hardware'
    'Runtime'     = '## Runtime & Deployment'
    'Distributed' = '## Distributed Clustering & Replication'
}

$emojiPlanned     = [char]::ConvertFromUtf32(0x1F51C)
$emojiDone        = [char]::ConvertFromUtf32(0x2705)
$emojiResearch    = [char]::ConvertFromUtf32(0x1F52C)
$emojiNotPlanned   = [char]::ConvertFromUtf32(0x1F534)

$statusIconMap = @{
    'Planned'     = "$emojiPlanned Planned"
    'Done'        = "$emojiDone Done"
    'Exploratory' = "$emojiResearch Exploratory"
    'Research'    = "$emojiResearch Research"
}

$statusDetailsIconMap = @{
    'Planned'     = $emojiPlanned
    'Done'        = $emojiDone
    'Exploratory' = $emojiResearch
    'Research'    = $emojiResearch
}

$cleanAnchor = $Name.ToLower().Replace(' ', '-').Replace('&', 'and').Replace('(', '').Replace(')', '').Replace('/', '-')

# =============================================================================
# ACTION: ADD
# =============================================================================
if ($Action -eq 'Add') {
    Write-Host "Adding feature '$Name' to roadmap..."

    # 1. Update README.md
    $readmeContent = Get-Content $readmePath -Raw
    $newReadmeLine = "- [ ] $Name ($Description)"
    
    # Insert before the closing roadmap link
    $targetLine = "> See the [detailed Roadmap]"
    if ($readmeContent -match [regex]::Escape($targetLine)) {
        $readmeContent = $readmeContent -replace [regex]::Escape($targetLine), "$newReadmeLine`n`n$targetLine"
        Set-Content $readmePath $readmeContent -NoNewline
        Write-Host "  [OK] README.md updated."
    } else {
        Write-Warning "Could not locate roadmap section in README.md."
    }

    # 2. Update docs/docs/roadmap.md Detailed Section
    $roadmapContent = Get-Content $roadmapPath -Raw
    $targetHeader = $categoryHeaderMap[$Category]
    
    $statusText = $statusDetailsIconMap[$Status]
    
    # Construct detailed block natively in a multi-line single-quoted string template
    $template = '### {0} {1} {{#{2}}}

!!! info "Status: {3}"
    {4}

{5}

---'
    $detailsBlock = $template -f $statusText, $Name, $cleanAnchor, $Status, $Description, $DetailText

    if ($roadmapContent -match [regex]::Escape($targetHeader)) {
        $roadmapContent = $roadmapContent -replace [regex]::Escape($targetHeader), ($targetHeader + "`r`n`r`n" + $detailsBlock)
        Write-Host "  [OK] Detailed section in roadmap.md updated."
    } else {
        Write-Warning "Could not locate category header '$targetHeader' in docs/docs/roadmap.md."
    }

    # 3. Update Summary Table in docs/docs/roadmap.md
    $lines = $roadmapContent -split '\r?\n'
    $newLines = [System.Collections.Generic.List[string]]::new()
    $tableIndex = 0
    $highestIndex = 0
    $inSummaryTable = $false

    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        $newLines.Add($line)

        if ($line -match '## Summary Table') {
            $inSummaryTable = $true
        }

        # Detect index in table rows only inside the summary table section
        if ($inSummaryTable -and $line -match '^\|\s*(\d+)\s*\|') {
            $idx = [int]$Matches[1]
            if ($idx -gt $highestIndex) {
                $highestIndex = $idx
            }
            $tableIndex = $i
        }
    }

    # Construct new row
    $newIdx = $highestIndex + 1
    $statusIcon = $statusIconMap[$Status]
    $newRow = '| {0} | **{1}** | {2} | {3} | {4} | {5} |' -f $newIdx, $Name, $Compression, $Recall, $Effort, $statusIcon

    # Insert new row right after the last table line
    $newLines.Insert($tableIndex + 1, $newRow)
    Set-Content $roadmapPath ($newLines -join "`r`n")
    Write-Host "  [OK] Summary Table in roadmap.md updated with row $newIdx."
} elseif ($Action -eq 'Complete') {
    # =============================================================================
    # ACTION: COMPLETE
    # =============================================================================
    Write-Host "Completing feature '$Name'..."

    # 1. Update README.md (check checkbox)
    $readmeContent = Get-Content $readmePath -Raw
    
    # Escape target checkbox regex
    $regexTarget = "- \[\s*\]\s*" + [regex]::Escape($Name)
    if ($readmeContent -match $regexTarget) {
        $readmeContent = [regex]::Replace($readmeContent, $regexTarget, "- [x] **$Name**")
        Set-Content $readmePath $readmeContent -NoNewline
        Write-Host "  [OK] README.md checklist updated."
    } else {
        Write-Warning "Could not locate incomplete checkbox for '$Name' in README.md."
    }

    # 2. Update docs/docs/roadmap.md Detailed Section & Reorganize Archive
    $roadmapContent = Get-Content $roadmapPath -Raw
    
    # Locate detailed section block via generic status match
    $escapedName = [regex]::Escape($Name)
    $sectionRegex = '(?s)###\s+\S+\s+' + $escapedName + '\s+\{#' + $cleanAnchor + '\}.*?---(?:\r?\n|$)'

    if ($roadmapContent -match $sectionRegex) {
        $capturedBlock = $Matches[0]
        
        # Remove from active category
        $roadmapContent = $roadmapContent -replace [regex]::Escape($capturedBlock), ""
        
        # Format the block for Recently Completed archive
        $capturedBlock = [regex]::Replace($capturedBlock, "^###\s+\S+", "### $emojiDone")
        $capturedBlock = $capturedBlock -replace "Status:\s*(Planned|Exploratory|Research)", "Status: Done"
        $capturedBlock = $capturedBlock -replace "!!! info", "!!! success"
        $capturedBlock = $capturedBlock -replace "Planned|Exploratory|Research", "Completed"
        
        # Append to Recently Completed section
        $archiveHeader = "## Recently Completed (Archive)"
        if ($roadmapContent -match [regex]::Escape($archiveHeader)) {
            $roadmapContent = $roadmapContent -replace [regex]::Escape($archiveHeader), ($archiveHeader + "`r`n`r`n" + $capturedBlock)
            Write-Host "  [OK] Detailed section moved to Recently Completed (Archive)."
        } else {
            # Create Recently Completed section if not present
            $roadmapContent = $roadmapContent + "`r`n`r`n---\r`n\r`n## Recently Completed (Archive)`r`n`r`n" + $capturedBlock
            Write-Host "  [OK] Recently Completed (Archive) section initialized and updated."
        }
    } else {
        Write-Warning "Could not find detailed roadmap block for '$Name' in docs/docs/roadmap.md."
    }

    # 3. Update Summary Table Status to Done
    $lines = $roadmapContent -split '\r?\n'
    $newLines = [System.Collections.Generic.List[string]]::new()
    $tableUpdated = $false
    $inSummaryTable = $false

    foreach ($line in $lines) {
        if ($line -match '## Summary Table') {
            $inSummaryTable = $true
        }
        if ($inSummaryTable -and $line -match ('^\|\s*(\d+)\s*\|\s*\*\*' + $escapedName + '\*\*')) {
            # Replace the status column (last column) with completed check
            $parts = $line -split '\|'
            $parts[$parts.Length - 2] = " $emojiDone Done "
            $line = $parts -join '|'
            $tableUpdated = $true
        }
        $newLines.Add($line)
    }

    Set-Content $roadmapPath ($newLines -join "`r`n")
    if ($tableUpdated) {
        Write-Host "  [OK] Summary Table row updated to $emojiDone Done."
    } else {
        Write-Warning "Could not find Summary Table row for '$Name'."
    }
} elseif ($Action -eq 'Deprioritize') {
    # =============================================================================
    # ACTION: DEPRIORITIZE
    # =============================================================================
    Write-Host "Deprioritizing feature '$Name'..."

    # 1. Update Summary Table Status in docs/docs/roadmap.md to Not Planned
    $roadmapContent = Get-Content $roadmapPath -Raw
    $escapedName = [regex]::Escape($Name)
    $lines = $roadmapContent -split '\r?\n'
    $newLines = [System.Collections.Generic.List[string]]::new()
    $tableUpdated = $false
    $inSummaryTable = $false

    foreach ($line in $lines) {
        if ($line -match '## Summary Table') {
            $inSummaryTable = $true
        }
        if ($inSummaryTable -and $line -match ('^\|\s*(\d+)\s*\|\s*\*\*' + $escapedName + '\*\*')) {
            $parts = $line -split '\|'
            $parts[$parts.Length - 2] = " $emojiNotPlanned Not planned "
            $line = $parts -join '|'
            $tableUpdated = $true
        }
        $newLines.Add($line)
    }

    Set-Content $roadmapPath ($newLines -join "`r`n")
    if ($tableUpdated) {
        Write-Host "  [OK] Summary Table row updated to $emojiNotPlanned Not planned."
    } else {
        Write-Warning "Could not find Summary Table row for '$Name'."
    }

    # 2. Update status in detailed description block
    $roadmapContent = Get-Content $roadmapPath -Raw
    $sectionRegex = '(?s)###\s+\S+\s+' + $escapedName + '\s+\{#' + $cleanAnchor + '\}.*?---(?:\r?\n|$)'
    
    if ($roadmapContent -match $sectionRegex) {
        $targetBlock = $Matches[0]
        $replacedBlock = [regex]::Replace($targetBlock, "(?m)^###\s+\S+", "### $emojiNotPlanned")
        $replacedBlock = $replacedBlock -replace 'Status:\s*[^"\r\n]+', "Status: Not Planned"
        
        $roadmapContent = $roadmapContent -replace [regex]::Escape($targetBlock), $replacedBlock
        Set-Content $roadmapPath $roadmapContent -NoNewline
        Write-Host "  [OK] Detailed section status updated to $emojiNotPlanned Not Planned."
    }
} elseif ($Action -eq 'Remove') {
    # =============================================================================
    # ACTION: REMOVE
    # =============================================================================
    Write-Host "Removing feature '$Name' completely from roadmap..."

    # 1. Remove from README.md
    $readmeContent = Get-Content $readmePath -Raw
    $escapedName = [regex]::Escape($Name)
    $lineRegex = '(?m)^-\s*\[[\s*x]?\]\s*(?:\*\*)?' + $escapedName + '(?:\*\*)?.*?\r?\n'
    
    if ($readmeContent -match $lineRegex) {
        $readmeContent = [regex]::Replace($readmeContent, $lineRegex, "")
        Set-Content $readmePath $readmeContent -NoNewline
        Write-Host "  [OK] Removed from README.md checklist."
    }

    # 2. Remove detailed description from docs/docs/roadmap.md
    $roadmapContent = Get-Content $roadmapPath -Raw
    $sectionRegex = '(?s)###\s+\S+\s+' + $escapedName + '\s+\{#' + $cleanAnchor + '\}.*?---(?:\r?\n|$)'
    
    if ($roadmapContent -match $sectionRegex) {
        $roadmapContent = $roadmapContent -replace $sectionRegex, ""
        Write-Host "  [OK] Removed detailed description block."
    }

    # 3. Remove row from Summary Table
    $lines = $roadmapContent -split '\r?\n'
    $newLines = [System.Collections.Generic.List[string]]::new()
    $rowRemoved = $false
    $inSummaryTable = $false

    foreach ($line in $lines) {
        if ($line -match '## Summary Table') {
            $inSummaryTable = $true
        }
        if ($inSummaryTable -and $line -match ('^\|\s*(\d+)\s*\|\s*(?:\*\*)?' + $escapedName + '(?:\*\*)?\s*\|')) {
            $rowRemoved = $true
            continue; # Skip adding this line to delete the row
        }
        $newLines.Add($line)
    }

    Set-Content $roadmapPath ($newLines -join "`r`n")
    if ($rowRemoved) {
        Write-Host "  [OK] Removed row from Summary Table."
    }
}

Write-Host "Roadmap update completed successfully!" -ForegroundColor Green
