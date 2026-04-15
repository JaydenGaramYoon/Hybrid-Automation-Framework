// First statement must not be a bare `{` — Groovy 3+ / CPS can report "Ambiguous expression" at line 1, column 1.
// Build with Parameters (Pipeline from SCM): values are chosen each run; defaults match pom.xml testSuite + typical Grid run.
properties([
    parameters([
        choice(name: 'TEST_SUITE', choices: ['Regression', 'LogIn', 'SignUp', 'Cart', 'CheckOut'], description: 'testSuites/<name>.xml — full Regression or one-class suites. Ignored if SINGLE_TEST_CLASS is set.'),
        string(name: 'SINGLE_TEST_CLASS', defaultValue: '', trim: true, description: 'Optional: FQCN or Class#method (e.g. testCases.LogIn). When set, runs only that; TEST_SUITE ignored. Prefer empty + TEST_SUITE=LogIn etc. for class-only runs.'),
        choice(name: 'BROWSER', choices: ['chrome', 'firefox', 'edge'], description: 'Maven -Dbrowser'),
        choice(name: 'OS', choices: ['linux', 'windows', 'mac'], description: 'Maven -Dos')
    ])
])

    // Jenkinsfile — MCP Orchestrator integration
    // Scripted Pipeline (no top-level `pipeline { }`) so `load('jenkins/postNotifications.groovy')` does not
    // trigger Declarative ModelInterpreter "Only one pipeline { ... } block" (Replay / finalize).
    //
    // Jenkins Credentials (kind: Secret text). Credential ID must match credentialsId below exactly:
    //   SLACK_WEBHOOK_URL, JIRA_PROJECT_KEY, JIRA_ISSUE_TYPE_ID, JIRA_HOST, JIRA_EMAIL,
    //   JIRA_API_TOKEN, CLOUDFLARE_API_TOKEN, CLOUDFLARE_ACCOUNT_ID, CLOUDFLARE_PAGES_PROJECT_NAME
    // Non-secret: set PROJECT_PATH as a global or job Environment variable (repo root with pom.xml), or omit it and use WORKSPACE.
    // Avoid Groovy "${env.SECRET}" in bat/powershell — use %VAR% / $env:VAR so Jenkins does not warn on credential interpolation.
    // Agent needs Node.js (npx) for wrangler. CLOUDFLARE_PAGES_PROJECT_NAME = Pages project slug (https://<slug>.pages.dev/)

    timestamps {
        timeout(time: 1, unit: 'HOURS') {
            node {
                withCredentials([
                        string(credentialsId: 'SLACK_WEBHOOK_URL', variable: 'SLACK_WEBHOOK_URL'),
                        string(credentialsId: 'JIRA_PROJECT_KEY', variable: 'JIRA_PROJECT_KEY'),
                        string(credentialsId: 'JIRA_ISSUE_TYPE_ID', variable: 'JIRA_ISSUE_TYPE_ID'),
                        string(credentialsId: 'JIRA_HOST', variable: 'JIRA_HOST'),
                        string(credentialsId: 'JIRA_EMAIL', variable: 'JIRA_EMAIL'),
                        string(credentialsId: 'JIRA_API_TOKEN', variable: 'JIRA_API_TOKEN'),
                        string(credentialsId: 'CLOUDFLARE_API_TOKEN', variable: 'CLOUDFLARE_API_TOKEN'),
                        string(credentialsId: 'CLOUDFLARE_ACCOUNT_ID', variable: 'CLOUDFLARE_ACCOUNT_ID'),
                        string(credentialsId: 'CLOUDFLARE_PAGES_PROJECT_NAME', variable: 'CLOUDFLARE_PAGES_PROJECT_NAME')
                ]) {
                env.MCP_PORT = '5000'
                env.CLOUDFLARE_DEPLOY_SUCCEEDED = 'false'

                try {

                    stage('Prepare Environment') {
                        echo ' Preparing Python environment...'
                        def root = env.PROJECT_PATH?.trim() ?: env.WORKSPACE?.trim()
                        if (!root) {
                            error('Set Jenkins env PROJECT_PATH to the repo root (folder with pom.xml), or use a job with SCM so WORKSPACE is set.')
                        }
                        if (!fileExists("${root}/pom.xml")) {
                            error("pom.xml not found under: ${root}. Check PROJECT_PATH or SCM checkout.")
                        }
                        env.USE_PROJECT = root
                        echo ' USE_PROJECT is set (path omitted from console to avoid env masking noise)'
                        try {
                            bat '''
    @echo off
    setlocal
    echo Setting up Python venv in project directory...
    cd /d "%USE_PROJECT%"
    if not exist ".venv" (
    echo Creating virtual environment...
    python -m venv .venv
    )
    call .venv/Scripts/activate.bat
    echo Installing dependencies...
    if exist "mcp/requirements.txt" (
    pip install --quiet -r mcp/requirements.txt
    echo MCP requirements installed
    ) else (
    echo mcp/requirements.txt not found, skipping MCP dependencies
    )
    echo Python environment ready
    '''
                        } catch (Exception e) {
                            echo " Environment setup warning: ${e.message}"
                        }
                    }

                    stage('Start MCP Server') {
                        echo ' Starting MCP Orchestrator Server...'
                        // mcp_orchestrator.py binds Flask to port 5000; align env and wait long enough for cold start.
                        powershell '''
    $ErrorActionPreference = 'Continue'
    $root = $env:USE_PROJECT
    if (-not $root) { Write-Error 'USE_PROJECT is not set'; exit 1 }
    $mcpPy = Join-Path $root 'mcp/mcp_orchestrator.py'
    if (-not (Test-Path -LiteralPath $mcpPy)) {
    Write-Host 'mcp/mcp_orchestrator.py not found under USE_PROJECT; skipping MCP start.'
    exit 0
    }
    $py = Join-Path $root '.venv/Scripts/python.exe'
    if (-not (Test-Path -LiteralPath $py)) {
    Write-Host '.venv python not found; run Prepare Environment first.'
    exit 0
    }
    Get-CimInstance Win32_Process -Filter "Name = 'python.exe'" -ErrorAction SilentlyContinue | ForEach-Object {
    if ($_.CommandLine -like '*mcp_orchestrator.py*') {
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }
    }
    Get-Process pythonw -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    $env:PYTHONIOENCODING = 'utf-8'
    $env:PYTHONUTF8 = '1'
    Set-Location -LiteralPath $root
    $dotenv = Join-Path $root '.env'
    if (Test-Path -LiteralPath $dotenv) {
    Write-Host 'Loading .env into process environment for MCP...'
    Get-Content -LiteralPath $dotenv | ForEach-Object {
        $line = $_.Trim()
        if ($line.StartsWith('#') -or $line -eq '') { return }
        $eq = $line.IndexOf('=')
        if ($eq -gt 0) {
        $n = $line.Substring(0, $eq).Trim()
        $v = $line.Substring($eq + 1).Trim().Trim([char]34).Trim([char]39)
        Set-Item -Path ('Env:' + $n) -Value $v
        }
    }
    }
    # Jenkins pipeline + curl use port 5000; mcp_orchestrator also listens on 5000.
    $env:MCP_PORT = '5000'
    Write-Host 'Launching mcp/mcp_orchestrator.py (detached)...'
    Start-Process -FilePath $py -ArgumentList @('mcp/mcp_orchestrator.py') -WorkingDirectory $root -WindowStyle Hidden
    Start-Sleep -Seconds 5
    Write-Host 'Checking MCP server health on http://127.0.0.1:5000/health ...'
    $healthUrl = 'http://127.0.0.1:5000/health'
    $up = $false
    for ($i = 1; $i -le 60; $i++) {
    try {
        $r = Invoke-WebRequest -Uri $healthUrl -UseBasicParsing -TimeoutSec 5
        if ($r.StatusCode -eq 200) {
        Write-Host ('MCP server is UP (attempt ' + $i + ')')
        $up = $true
        break
        }
    } catch {
        Start-Sleep -Seconds 2
    }
    }
    if (-not $up) {
    Write-Error 'MCP server health check failed after 60 attempts (~2 min). Fix MCP startup or increase retries.'
    exit 1
    }
    exit 0
    '''
                    }

                    stage('Build Project') {
                        echo ' Building project...'
                        try {
                            bat '''
    @echo off
    cd /d "%USE_PROJECT%"
    if not exist "pom.xml" (
    echo pom.xml not found at USE_PROJECT
    exit /b 1
    )
    mvn clean test-compile -DskipTests=true
    '''
                        } catch (Exception e) {
                            echo " Build warning: ${e.message}"
                        }
                    }

                    stage('Run Tests') {
                        echo ' Running tests (see Build Parameters for suite, optional single class, browser, OS)...'
                        catchError(buildResult: 'FAILURE', stageResult: 'FAILURE', catchInterruptions: true) {
                            script {
                                env.TEST_SUITE = ((params?.TEST_SUITE ?: 'Regression') as String).trim()
                                env.BROWSER = params?.BROWSER ?: 'chrome'
                                env.OS = params?.OS ?: 'linux'
                                env.SINGLE_TEST_CLASS = ((params?.SINGLE_TEST_CLASS ?: '') as String).trim()
                                if (env.SINGLE_TEST_CLASS) {
                                    echo " mvn test -DsingleTest=true -Dtest=${env.SINGLE_TEST_CLASS} -Dbrowser=${env.BROWSER} -Dos=${env.OS}"
                                } else {
                                    echo " mvn test -DtestSuite=${env.TEST_SUITE} -Dbrowser=${env.BROWSER} -Dos=${env.OS}"
                                }
                            }
                            bat '''
    @echo off
    setlocal
    cd /d "%USE_PROJECT%"
    if not exist "pom.xml" (
    echo pom.xml not found, skipping tests
    exit /b 1
    )
    if not "%SINGLE_TEST_CLASS%"=="" goto runSingle
    call mvn test -DtestSuite=%TEST_SUITE% -Dbrowser=%BROWSER% -Dos=%OS%
    if errorlevel 1 exit /b 1
    goto endRun
    :runSingle
    call mvn test -DsingleTest=true -Dtest=%SINGLE_TEST_CLASS% -Dbrowser=%BROWSER% -Dos=%OS%
    if errorlevel 1 exit /b 1
    :endRun
    exit /b 0
    '''
                        }
                    }

                    stage('Mark UNSTABLE if tests skipped') {
                        script {
                            def proj = env.USE_PROJECT?.trim()
                            if (!proj) {
                                echo ' USE_PROJECT not set — skipping UNSTABLE check'
                                return
                            }
                            def repoEsc = proj.replace('\\', '\\\\').replace("'", "''")
                            def ps = """\$ErrorActionPreference = 'SilentlyContinue'
    \$dir = Join-Path '${repoEsc}' 'target\\surefire-reports'
    if (-not (Test-Path -LiteralPath \$dir)) { Write-Output 'SKIP_COUNT=0'; exit 0 }
    \$sum = 0
    Get-ChildItem -LiteralPath \$dir -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue | ForEach-Object {
    try {
        [xml]\$x = Get-Content -LiteralPath \$_.FullName -Encoding UTF8
        \$ts = \$x.testsuite
        if (\$null -ne \$ts) { \$sum += [int]\$ts.skipped }
    } catch { }
    }
    Write-Output ('SKIP_COUNT=' + \$sum)
    """
                            def out = powershell(returnStdout: true, script: ps)?.trim() ?: ''
                            def m = (out =~ /SKIP_COUNT=(\d+)/)
                            def skipped = m ? (m[0][1] as int) : 0
                            if (skipped > 0) {
                                def cur = currentBuild.result ?: currentBuild.currentResult ?: 'SUCCESS'
                                if (cur == 'FAILURE') {
                                    echo " Surefire skipped=${skipped}; build is already FAILURE — leaving FAILURE"
                                } else {
                                    currentBuild.result = 'UNSTABLE'
                                    echo " Surefire skipped=${skipped} — build marked UNSTABLE"
                                }
                            }
                        }
                    }

                    stage('Collect Artifacts') {
                        echo ' Collecting test artifacts...'
                        try {
                            bat '''
    @echo off
    setlocal
    cd /d "%USE_PROJECT%"
    echo Current dir: %cd%
    if exist "reports" (
    echo Copying reports to workspace...
    xcopy /E /I /Y /Q reports "%WORKSPACE%/reports" 2>nul || echo reports copy failed
    )
    if exist "logs" (
    echo Copying logs to workspace...
    xcopy /E /I /Y /Q logs "%WORKSPACE%/logs" 2>nul || echo logs copy failed
    )
    if exist "screenshots" (
    echo Copying screenshots to workspace...
    xcopy /E /I /Y /Q screenshots "%WORKSPACE%/screenshots" 2>nul || echo screenshots copy failed
    )
    echo Artifacts collected
    '''
                        } catch (Exception e) {
                            echo " Artifact collection warning: ${e.message}"
                        }
                    }

                    stage('Process MCP Notifications') {
                        def upr = env.USE_PROJECT?.trim()
                        def ws = env.WORKSPACE?.trim()
                        def hasFailureDir = (upr && fileExists(upr + '/reports/failure')) || (ws && fileExists(ws + '/reports/failure'))
                        def hasSkipDir = (upr && fileExists(upr + '/reports/skip')) || (ws && fileExists(ws + '/reports/skip'))
                        def hasExtentDir = (upr && fileExists(upr + '/reports/extent_report')) || (ws && fileExists(ws + '/reports/extent_report'))
                        def hasLogsDir = (upr && fileExists(upr + '/logs')) || (ws && fileExists(ws + '/logs'))
                        if (!hasFailureDir && !hasSkipDir && !hasExtentDir && !hasLogsDir) {
                            echo ' Skipping MCP — no reports/failure, reports/skip, reports/extent_report, or logs'
                        } else {
                            echo ' Processing test failures through MCP...'
                            try {
                                def repoEsc = env.USE_PROJECT?.replace('\\', '\\\\')?.replace("'", "''")
                                def canonPs = """\$ErrorActionPreference = 'SilentlyContinue'
    \$roots = @('${repoEsc}', \$env:WORKSPACE) | Where-Object { \$_ -and (Test-Path -LiteralPath \$_) } | Select-Object -Unique
    \$maxN = [long]0
    foreach (\$root in \$roots) {
    foreach (\$sub in @('reports\\failure','reports\\skip','reports\\extent_report','logs')) {
        \$d = Join-Path \$root \$sub
        if (-not (Test-Path -LiteralPath \$d)) { continue }
        Get-ChildItem -LiteralPath \$d -ErrorAction SilentlyContinue | ForEach-Object {
        \$n = \$null
        if (\$_.Name -match '^run-(\\d+)_(failure|skip)_bundle\\.json\$') { \$n = [long]\$Matches[1] }
        elseif (\$_.Name -match '^run-(\\d+)_extent_report\\.html\$') { \$n = [long]\$Matches[1] }
        elseif (\$_.Name -match '^run-(\\d+)_test_log\\.log\$') { \$n = [long]\$Matches[1] }
        if (\$null -ne \$n -and \$n -gt \$maxN) { \$maxN = \$n }
        }
    }
    }
    if (\$maxN -gt 0) { Write-Output ('CANONICAL=' + \$maxN) }
    """
                                def canonOut = powershell(returnStdout: true, script: canonPs)?.trim() ?: ''
                                def canonicalDigits = ''
                                try {
                                    def canLine = canonOut?.split('\n')?.find { it != null && it.contains('CANONICAL=') }
                                    if (canLine) {
                                        def idx = canLine.indexOf('CANONICAL=')
                                        if (idx >= 0) {
                                            def rest = canLine.substring(idx + 'CANONICAL='.length())
                                            def ds = new StringBuilder()
                                            for (def ch : rest.toCharArray()) {
                                                if (ch >= '0' && ch <= '9') {
                                                    ds.append(ch)
                                                } else if (ds.length() > 0) {
                                                    break
                                                }
                                            }
                                            canonicalDigits = ds.toString()
                                        }
                                    }
                                } catch (Exception ignore) { }
                                def latestBundle = canonicalDigits ? "run-${canonicalDigits}_failure_bundle.json" : ''
                                def bundleOnDisk = ''
                                if (latestBundle) {
                                    def checkPs = """\$ErrorActionPreference = 'SilentlyContinue'
    \$repo = '${repoEsc}'
    \$name = '${latestBundle.replace("'", "''")}'
    \$bp = Join-Path \$repo ('reports/failure/' + \$name)
    if (-not (Test-Path -LiteralPath \$bp)) { \$bp = Join-Path \$env:WORKSPACE ('reports/failure/' + \$name) }
    if (Test-Path -LiteralPath \$bp) { Write-Output \$bp; exit 0 }
    """
                                    bundleOnDisk = powershell(returnStdout: true, script: checkPs)?.trim() ?: ''
                                }

                                def mcpCleanupPs = """\$ErrorActionPreference = 'SilentlyContinue'
    \$w = \$env:WORKSPACE
    if (-not \$w) { exit 0 }
    Remove-Item -LiteralPath (Join-Path \$w 'mcp_manifest.txt') -Force -ErrorAction SilentlyContinue
    Get-ChildItem -LiteralPath \$w -Filter 'mcp_response*.json' -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue
    """
                                if (!canonicalDigits) {
                                    echo ' Skipping MCP — could not resolve canonical run id from bundle filenames'
                                    powershell(script: mcpCleanupPs)
                                } else if (!bundleOnDisk) {
                                    echo " Skipping MCP — no failure bundle for current run (canonical run-${canonicalDigits}); stale failure bundles from older runs are ignored"
                                    powershell(script: mcpCleanupPs)
                                } else {
                                    def bundlePath = bundleOnDisk
                                    echo ' Found failure bundle for current run (path in workspace log / artifacts)'
                                    def runIdFromBundle = latestBundle.replace('_failure_bundle.json', '').trim()
                                    def failCount = 1
                                    try {
                                        def cntPs2 = """\$ErrorActionPreference = 'Stop'
    \$repo = '${repoEsc}'
    \$bp = Join-Path \$repo 'reports/failure/${latestBundle}'
    if (-not (Test-Path -LiteralPath \$bp)) { \$bp = Join-Path \$env:WORKSPACE 'reports/failure/${latestBundle}' }
    if (-not (Test-Path -LiteralPath \$bp)) { Write-Output '0'; exit 0 }
    \$j = Get-Content -Raw -LiteralPath \$bp -Encoding UTF8 | ConvertFrom-Json
    Write-Output \$j.failures.Count
    """
                                        def cn = powershell(returnStdout: true, script: cntPs2)?.trim()
                                        if (cn?.isInteger() && (cn as int) >= 0) {
                                            failCount = cn as int
                                        }
                                    } catch (Exception ec) {
                                        echo " WARN: failure count default 1: ${ec.message}"
                                    }
                                    if (failCount <= 0) {
                                        echo ' Skipping MCP — failure bundle has no failure entries'
                                        powershell(script: mcpCleanupPs)
                                    } else {
                                    echo ' Clearing stale mcp_response_*.json / mcp_manifest.txt before this run...'
                                    powershell(script: mcpCleanupPs)
                                    echo " Failure entries in bundle: ${failCount} — calling MCP for each failure_index"
                                    for (int fi = 0; fi < failCount; fi++) {
                                        def mcpReq = [
                                            bundle_path: bundlePath.replace('\\', '/'),
                                            failure_index: fi,
                                            screenshot_url: '',
                                            test_metadata: [
                                                job: env.JOB_NAME,
                                                build: env.BUILD_NUMBER,
                                                run_id: runIdFromBundle
                                            ]
                                        ]
                                        writeFile file: "mcp_request_${fi}.json", text: groovy.json.JsonOutput.toJson(mcpReq)
                                        bat """
    @echo off
    cd /d "%WORKSPACE%"
    echo MCP analyze failure_index=${fi}...
    curl -s -S -X POST http://127.0.0.1:5000/api/analyze-failure -H "Content-Type: application/json" --data-binary @mcp_request_${fi}.json -o mcp_response_${fi}.json
    if exist mcp_response_${fi}.json (echo MCP response saved: mcp_response_${fi}.json) else (echo MCP failed for index ${fi})
    """
                                    }
                                    bat '''
    @echo off
    cd /d "%WORKSPACE%"
    if exist mcp_response_0.json copy /Y mcp_response_0.json mcp_response.json >nul
    '''
                                    powershell """
    \$ErrorActionPreference = "Stop"
    \$lines = @()
    \$fc = ${failCount}
    for (\$i = 0; \$i -lt \$fc; \$i++) {
        \$f = Join-Path \$env:WORKSPACE ("mcp_response_{0}.json" -f \$i)
        if (-not (Test-Path -LiteralPath \$f)) { continue }
        \$j = Get-Content -Raw -LiteralPath \$f -Encoding UTF8 | ConvertFrom-Json
        \$stem = \$j.artifact_file_stem
        if (-not \$stem) { \$stem = "" }
        \$bucket = \$j.ai_bucket
        if (-not \$bucket) { \$bucket = "" }
        \$tid = \$j.test_id
        if (-not \$tid) { \$tid = "" }
        \$lines += "{0}|{1}|{2}|{3}" -f \$i, \$bucket, \$stem, \$tid
    }
    if (\$lines.Count -gt 0) {
        \$lines | Set-Content -Encoding UTF8 (Join-Path \$env:WORKSPACE "mcp_manifest.txt")
    }
    """
                                    }
                                }
                            } catch (Exception e) {
                                echo " MCP processing failed: ${e.message}"
                            }
                        }
                    }

                    stage('Sync MCP artifacts to workspace') {
                        echo ' Syncing reports (including AI) to workspace for Jenkins artifacts...'
                        try {
                            bat '''
    @echo off
    setlocal
    cd /d "%USE_PROJECT%"
    if exist "reports" (
    echo Copying reports to workspace...
    xcopy /E /I /Y /Q reports "%WORKSPACE%/reports"
    )
    if exist "logs" (
    xcopy /E /I /Y /Q logs "%WORKSPACE%/logs" 2>nul || echo logs copy skipped
    )
    if exist "screenshots" (
    echo Copying screenshots to workspace...
    xcopy /E /I /Y /Q screenshots "%WORKSPACE%/screenshots" 2>nul || echo screenshots copy skipped
    )
    echo Sync complete
    '''
                        } catch (Exception e) {
                            echo " Sync MCP artifacts warning: ${e.message}"
                        }
                    }

                    stage('Generate artifacts hub HTML') {
                        echo ' Writing reports/artifacts_hub/<runId>/index.html (Slack + Cloudflare)...'
                        try {
                            bat '''
    @echo off
    setlocal
    cd /d "%WORKSPACE%"
    powershell -NoProfile -ExecutionPolicy Bypass -File "%USE_PROJECT%\\jenkins\\cf-pages-artifacts-hub.ps1" -Root "%WORKSPACE%"
    if errorlevel 1 exit /b 1
    '''
                        } catch (Exception e) {
                            echo " Artifacts hub warning: ${e.message}"
                        }
                    }

                    stage('Deploy to Cloudflare Pages') {
                        echo ' Deploying reports/logs/screenshots to Cloudflare Pages (public links for Slack/Jira)...'
                        script {
                            try {
                                if (!env.CLOUDFLARE_PAGES_PROJECT_NAME?.trim()) {
                                    echo ' CLOUDFLARE_PAGES_PROJECT_NAME empty — skipping Cloudflare deploy'
                                } else {
                                    // Windows xcopy treats "cf-pages-deploy/logs" as "/logs" switch — use backslashes only.
                                    def ws = env.WORKSPACE?.trim()
                                    bat '''
    @echo off
    setlocal
    cd /d "%WORKSPACE%"
    if exist cf-pages-deploy rd /s /q cf-pages-deploy
    mkdir cf-pages-deploy
    '''
                                    // Root-relative URLs (/reports/...) so links work from any path. Static hosts do not
                                    // auto-list directories; hub pages avoid broken relative links (e.g. .../reports/AI + href="reports/").
                                    writeFile file: "${ws}/cf-pages-deploy/index.html", text: '''<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Automation artifacts</title></head>
<body>
<h1>Automation artifacts</h1>
<ul>
<li><a href="/reports/">reports/</a></li>
<li><a href="/logs/">logs/</a></li>
<li><a href="/screenshots/">screenshots/</a></li>
</ul>
<p><a href="/reports/">Browse reports</a> (AI, triage, failure bundles, …)</p>
</body></html>
'''
                                    writeFile file: "${ws}/cf-pages-deploy/reports/index.html", text: '''<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>reports — Automation artifacts</title></head>
<body>
<p><a href="/">↑ Root</a></p>
<h1>reports/</h1>
<ul>
<li><a href="/reports/AI/">AI/</a> (bug · flaky · needs_review)</li>
<li><a href="/reports/triage/">triage/</a></li>
<li><a href="/reports/failure/">failure/</a></li>
<li><a href="/reports/skip/">skip/</a></li>
<li><a href="/reports/extent_report/">extent_report/</a></li>
<li><a href="/reports/history/">history/</a></li>
<li><a href="/reports/artifacts_hub/">artifacts_hub/</a> (per-run index)</li>
</ul>
</body></html>
'''
                                    writeFile file: "${ws}/cf-pages-deploy/reports/AI/index.html", text: '''<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>reports/AI — Automation artifacts</title></head>
<body>
<p><a href="/reports/">↑ reports</a> · <a href="/">root</a></p>
<h1>reports/AI/</h1>
<ul>
<li><a href="/reports/AI/bug/">bug/</a></li>
<li><a href="/reports/AI/flaky/">flaky/</a></li>
<li><a href="/reports/AI/needs_review/">needs_review/</a></li>
</ul>
<p>Files live under <code>report/</code>, <code>summary/</code>, <code>analysis/</code> in each bucket.</p>
</body></html>
'''
                                    bat '''
    @echo off
    setlocal
    cd /d "%WORKSPACE%"
    if exist reports xcopy /E /I /Y /Q reports cf-pages-deploy\\reports\\
    if exist screenshots xcopy /E /I /Y /Q screenshots cf-pages-deploy\\screenshots\\
    if exist logs xcopy /E /I /Y /Q logs cf-pages-deploy\\logs\\
    powershell -NoProfile -ExecutionPolicy Bypass -File "%USE_PROJECT%\\jenkins\\cf-pages-dir-index.ps1" -Root "%WORKSPACE%\\cf-pages-deploy"
    if errorlevel 1 exit /b 1
    call npx --yes wrangler pages deploy cf-pages-deploy --project-name="%CLOUDFLARE_PAGES_PROJECT_NAME%"
    if errorlevel 1 exit /b 1
    '''
                                    env.CLOUDFLARE_DEPLOY_SUCCEEDED = 'true'
                                    bat '''
    @echo off
    echo Cloudflare Pages deploy finished. Public URL pattern: https://%CLOUDFLARE_PAGES_PROJECT_NAME%.pages.dev/
    '''
                                }
                            } catch (Exception e) {
                                env.CLOUDFLARE_DEPLOY_SUCCEEDED = 'false'
                                echo " Cloudflare deploy failed — Slack/Jira will use Jenkins artifact URLs: ${e.message}"
                            }
                        }
                    }

                    // Keep finalize on the same executor as the rest of the build: do not add another `node { }` here.
                    // If you see "Waiting for next available executor" at this stage, raise the agent's # of executors
                    // (or stop concurrent builds competing for one slot). Replay + edited scripts can also confuse CPS.
                    stage('Finalize notifications') {
                        timeout(time: 45, unit: 'MINUTES') {
                            def ws = env.WORKSPACE?.trim()
                            def proj = (env.USE_PROJECT ?: ws)?.trim()
                            def postScript = "${ws}/jenkins/postNotifications.groovy".replace('\\', '/')
                            if (!fileExists(postScript)) {
                                postScript = "${proj}/jenkins/postNotifications.groovy".replace('\\', '/')
                            }
                            if (!fileExists(postScript)) {
                                error("postNotifications.groovy not found. Tried WORKSPACE and USE_PROJECT: ${postScript}")
                            }
                            def pn = load postScript
                            pn.runPostNotifications()
                        }
                    }
                } finally {
                    def br = currentBuild.result ?: currentBuild.currentResult ?: 'UNKNOWN'
                    echo " Cleanup (Slack/Jira/archive ran in Finalize notifications stage); build result: ${br}"
                    if (br == 'SUCCESS') {
                        echo ' Pipeline completed successfully (all stages passed)'
                    } else if (br == 'UNSTABLE') {
                        echo ' Pipeline completed with UNSTABLE (e.g. skipped tests per Surefire — see Mark UNSTABLE stage)'
                    } else {
                        echo ' Pipeline failed — triage Slack/Jira are handled in Finalize notifications when BUG/FLAKY/NEEDS_REVIEW/SKIP signals exist.'
                    }
                }
                }
            }
        }
    }
