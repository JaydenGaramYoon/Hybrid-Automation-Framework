# Build reports/artifacts_hub/<runId>/index.html with root-absolute links (/reports/..., /logs/..., /screenshots/...).
# Run with -Root pointing at Jenkins WORKSPACE (after reports/logs/screenshots are synced).
param(
    [Parameter(Mandatory = $false)]
    [string]$Root = ""
)

$ErrorActionPreference = "Stop"

if (-not $Root -or -not (Test-Path -LiteralPath $Root)) {
    Write-Error "cf-pages-artifacts-hub: -Root must be an existing directory"
    exit 1
}

function Get-CanonicalRunId {
    param([string]$Base)
    $maxN = [long]0
    foreach ($sub in @('reports\failure', 'reports\skip', 'reports\extent_report', 'logs')) {
        $d = Join-Path $Base $sub
        if (-not (Test-Path -LiteralPath $d)) { continue }
        Get-ChildItem -LiteralPath $d -File -ErrorAction SilentlyContinue | ForEach-Object {
            $n = $null
            if ($_.Name -match '^run-(\d+)_(failure|skip)_bundle\.json$') { $n = [long]$Matches[1] }
            elseif ($_.Name -match '^run-(\d+)_extent_report\.html$') { $n = [long]$Matches[1] }
            elseif ($_.Name -match '^run-(\d+)_test_log\.log$') { $n = [long]$Matches[1] }
            if ($null -ne $n -and $n -gt $maxN) { $maxN = $n }
        }
    }
    if ($maxN -le 0) { return $null }
    return "run-$maxN"
}

function Escape-Html([string]$s) {
    if ($null -eq $s) { return "" }
    return $s.Replace("&", "&amp;").Replace("<", "&lt;").Replace(">", "&gt;").Replace('"', "&quot;")
}

$runId = Get-CanonicalRunId -Base $Root
if (-not $runId) {
    Write-Host 'cf-pages-artifacts-hub: no canonical run id - skipping'
    exit 0
}

$ridSeg = $runId -replace '^run-', ''
$outDir = Join-Path $Root "reports\artifacts_hub\$runId"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$blocks = New-Object System.Collections.Generic.List[string]

function Add-Block {
    param([string]$Title, [array]$Pairs)
    if (-not $Pairs -or $Pairs.Count -eq 0) { return }
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine("<h2>$Title</h2>")
    [void]$sb.AppendLine("<ul>")
    foreach ($pair in $Pairs) {
        $href = $pair.href
        $lab = Escape-Html $pair.label
        [void]$sb.AppendLine(('<li><a href="' + $href + '">' + $lab + '</a></li>'))
    }
    [void]$sb.AppendLine('</ul>')
    [void]$blocks.Add($sb.ToString())
}

$pairs = @()
$p = Join-Path $Root "reports\extent_report\${runId}_extent_report.html"
if (Test-Path -LiteralPath $p) {
    $pairs += @{ href = "/reports/extent_report/${runId}_extent_report.html"; label = "${runId}_extent_report.html" }
}
Add-Block "Extent report" $pairs

$pairs = @()
$p = Join-Path $Root "reports\failure\${runId}_failure_bundle.json"
if (Test-Path -LiteralPath $p) {
    $pairs += @{ href = "/reports/failure/${runId}_failure_bundle.json"; label = "${runId}_failure_bundle.json" }
}
Add-Block "Failure bundle" $pairs

$pairs = @()
$p = Join-Path $Root "reports\triage\${runId}_triage_report.json"
if (Test-Path -LiteralPath $p) {
    $pairs += @{ href = "/reports/triage/${runId}_triage_report.json"; label = "${runId}_triage_report.json" }
}
Add-Block "Triage" $pairs

$pairs = @()
$p = Join-Path $Root "reports\skip\${runId}_skip_bundle.json"
if (Test-Path -LiteralPath $p) {
    $pairs += @{ href = "/reports/skip/${runId}_skip_bundle.json"; label = "${runId}_skip_bundle.json" }
}
Add-Block "Skip bundle" $pairs

$pairs = @()
$p = Join-Path $Root "logs\${runId}_test_log.log"
if (Test-Path -LiteralPath $p) {
    $pairs += @{ href = "/logs/${runId}_test_log.log"; label = "${runId}_test_log.log" }
}
Add-Block "Test log" $pairs

foreach ($bucket in @("bug", "flaky", "needs_review")) {
    $pairs = @()
    foreach ($sub in @("report", "summary", "analysis")) {
        $dir = Join-Path $Root "reports\AI\$bucket\$sub"
        if (-not (Test-Path -LiteralPath $dir)) { continue }
        Get-ChildItem -LiteralPath $dir -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -like "*${ridSeg}*" } | Sort-Object Name | ForEach-Object {
            $nm = $_.Name
            $pairs += @{ href = "/reports/AI/$bucket/$sub/$nm"; label = "$bucket/$sub/$nm" }
        }
    }
    if ($pairs.Count -gt 0) {
        $bt = "AI ($bucket)"
        Add-Block $bt $pairs
    }
}

$pairs = @()
$shotDir = Join-Path $Root "screenshots"
if (Test-Path -LiteralPath $shotDir) {
    Get-ChildItem -LiteralPath $shotDir -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "*${ridSeg}*" } |
        Sort-Object Name |
        ForEach-Object {
            $nm = $_.Name
            $pairs += @{ href = "/screenshots/$nm"; label = $nm }
        }
}
Add-Block "Screenshots" $pairs

$bodyInner = if ($blocks.Count -gt 0) { $blocks -join "`n" } else { '<p><em>No matching files for this run.</em></p>' }

# Build HTML: avoid double-quoted strings that contain `$name` patterns PowerShell treats as variables (e.g. `<head>`, `<body>`).
$css = 'body{font-family:system-ui,Segoe UI,Helvetica,Arial,sans-serif;margin:1.25rem;line-height:1.45}' +
    'h1{font-size:1.25rem}h2{font-size:1.05rem;margin-top:1.25rem}ul{padding-left:1.25rem}a{color:#2563eb}'
$parts = @(
    '<!DOCTYPE html>'
    '<html lang="en">'
    '<head>'
    '<meta charset="utf-8">'
    '<meta name="viewport" content="width=device-width,initial-scale=1">'
    "<title>Artifacts $runId</title>"
    ('<style type="text/css">' + $css + '</style>')
    '</head>'
    '<body>'
    "<h1>Run $runId</h1>"
    $bodyInner
    '</body>'
    '</html>'
)
$html = ($parts -join "`n") + "`n"

$outFile = Join-Path $outDir "index.html"
Set-Content -LiteralPath $outFile -Value $html -Encoding UTF8
Write-Host ('cf-pages-artifacts-hub: wrote ' + $outFile)
exit 0
