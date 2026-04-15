# After xcopy into cf-pages-deploy: add hub index.html under reports/AI/{bug,flaky,needs_review}
# and file-listing index.html under each report|summary|analysis folder (static hosts have no auto directory listing).
param(
    [string]$Root = "cf-pages-deploy"
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path -LiteralPath $Root)) {
    exit 0
}

function Escape-Html([string]$s) {
    if ($null -eq $s) { return "" }
    return $s.Replace("&", "&amp;").Replace("<", "&lt;").Replace(">", "&gt;").Replace('"', "&quot;")
}

function Write-FileIndex {
    param(
        [string]$DirPath,
        [string]$UrlBase
    )
    if (-not (Test-Path -LiteralPath $DirPath)) {
        return
    }
    $files = Get-ChildItem -LiteralPath $DirPath -File -ErrorAction SilentlyContinue | Sort-Object Name
    if ($files.Count -eq 0) {
        return
    }
    $UrlBase = $UrlBase.TrimEnd("/")
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine("<!DOCTYPE html>")
    [void]$sb.AppendLine('<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>files</title></head><body>')
    [void]$sb.AppendLine('<p><a href="../">Parent</a></p>')
    [void]$sb.AppendLine("<h1>${UrlBase}/</h1>")
    [void]$sb.AppendLine("<ul>")
    foreach ($f in $files) {
        $name = $f.Name
        $enc = Escape-Html $name
        [void]$sb.AppendLine("<li><a href=`"${UrlBase}/${enc}`">${enc}</a></li>")
    }
    [void]$sb.AppendLine("</ul></body></html>")
    $out = Join-Path $DirPath "index.html"
    Set-Content -LiteralPath $out -Value $sb.ToString() -Encoding UTF8
}

$reportsAi = Join-Path $Root "reports\AI"
if (-not (Test-Path -LiteralPath $reportsAi)) {
    exit 0
}

foreach ($bucket in @("bug", "flaky", "needs_review")) {
    $base = Join-Path $reportsAi $bucket
    if (-not (Test-Path -LiteralPath $base)) {
        continue
    }
    $hubHtml = @"
<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>AI/$bucket</title></head>
<body>
<p><a href="/reports/AI/">Up to AI</a></p>
<h1>reports/AI/$bucket/</h1>
<ul>
<li><a href="/reports/AI/$bucket/report/">report/</a></li>
<li><a href="/reports/AI/$bucket/summary/">summary/</a></li>
<li><a href="/reports/AI/$bucket/analysis/">analysis/</a></li>
</ul>
</body></html>
"@
    Set-Content -LiteralPath (Join-Path $base "index.html") -Value $hubHtml -Encoding UTF8

    foreach ($sub in @("report", "summary", "analysis")) {
        $d = Join-Path $base $sub
        $web = "/reports/AI/$bucket/$sub"
        Write-FileIndex -DirPath $d -UrlBase $web
    }
}

exit 0
