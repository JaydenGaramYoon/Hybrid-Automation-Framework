// Split from Jenkinsfile — multiple top-level methods so CPS does not emit one huge ___cps___ (Method too large on load)
import com.cloudbees.groovy.cps.NonCPS

/**
 * Deep-copy JSON parse result so CPS can persist it (LazyMap from JsonSlurper is not).
 * Use Groovy [:] / [] literals — Jenkins sandbox often blocks `new LinkedHashMap()` / JsonSlurperClassic.
 * Do NOT use JsonSlurperClassic here — Script Security often blocks it.
 */
@NonCPS
def pnDeepCopyJsonForCps(Object o) {
    if (o == null) {
        return null
    }
    if (o instanceof Map) {
        def m = [:]
        o.each { k, v -> m[k] = pnDeepCopyJsonForCps(v) }
        return m
    }
    if (o instanceof List) {
        def l = []
        o.each { l << pnDeepCopyJsonForCps(it) }
        return l
    }
    return o
}

/**
 * Parse JSON off the CPS thread; return plain Maps/Lists safe for Jenkins CPS + sandbox-approved classes only.
 */
@NonCPS
def pnParseJsonText(String text) {
    if (text == null || text.isEmpty()) {
        return null
    }
    try {
        def raw = new groovy.json.JsonSlurper().parseText(text)
        return pnDeepCopyJsonForCps(raw)
    } catch (Throwable ignore) {
        return null
    }
}

/**
 * bug-run-{id}-02 → failure bundle index 1 (0-based, legacy).
 * bug-run-{id}-TC010 → index where failures[i].error.tc_id matches the suffix (tc_id-based stems).
 */
@NonCPS
def pnFailureIndexFromBugStem(String stem, String runId, Object failuresList) {
    if (!stem || !stem.startsWith('bug-run-')) {
        return -1
    }
    def i = stem.lastIndexOf('-')
    if (i < 0 || i >= stem.length() - 1) {
        return -1
    }
    def sfx = stem.substring(i + 1)
    if (sfx ==~ /^\d{2}$/) {
        try {
            return (sfx as int) - 1
        } catch (Throwable ignore) {
            return -1
        }
    }
    if (!runId?.trim() || !(failuresList instanceof List) || failuresList.isEmpty()) {
        return -1
    }
    def ridSeg = runId.replaceFirst(/^run-/, '')
    def pref = "bug-run-${ridSeg}-"
    if (!stem.startsWith(pref)) {
        return -1
    }
    def label = stem.substring(pref.length())
    if (!label) {
        return -1
    }
    def sanitizeTcStem = { String t ->
        if (!t?.trim()) {
            return ''
        }
        def s = t.replaceAll(/[^A-Za-z0-9_-]+/, '_')
        s = s.replaceAll(/^_+/, '').replaceAll(/_+$/, '')
        if (s.length() > 80) {
            s = s.substring(0, 80)
        }
        return s ?: ''
    }
    for (int idx = 0; idx < failuresList.size(); idx++) {
        def ent = failuresList[idx]
        def tcRaw = ent?.error?.tc_id?.toString()?.trim()
        if (tcRaw && sanitizeTcStem(tcRaw) == label) {
            return idx
        }
    }
    return -1
}

def runPostNotifications() {
    def ctx = [:]
    pnGatherInputs(ctx)
    if (ctx.notifyDevJira) {
        pnJiraNotifications(ctx)
    }
    if (ctx.notifyQaSlack) {
        pnSlackNotifications(ctx)
    } else {
 echo " QA Slack skipped (no signals: not a green all-pass run and no BUG/FLAKY/NEEDS_REVIEW/skip bundle)."
    }
    pnPostNotifyCleanup(ctx)
}

/**
 * Parse TestNG-style assertion: expected [A] but found [B].
 * Handles lines prefixed with java.lang.AssertionError: and picks the line containing both markers.
 */
def parseTestNgExpectedActualFromMessage(String msg) {
    if (!msg?.trim()) {
        return ['', '']
    }
    def line = msg.split(/\r?\n/).find { it.contains('expected [') && it.contains('] but found [') }
    if (!line) {
        line = msg.split(/\r?\n/)[0]
    }
    def ei = line.indexOf('expected [')
    if (ei >= 0) {
        line = line.substring(ei)
    }
    def sep = '] but found ['
    def idx = line.indexOf(sep)
    if (idx < 0) {
        return ['', '']
    }
    def head = line.substring(0, idx)
    def pref = 'expected ['
    if (!head.startsWith(pref)) {
        return ['', '']
    }
    def exp = head.substring(pref.length())
    def tail = line.substring(idx + sep.length())
    if (!tail.endsWith(']')) {
        return ['', '']
    }
    def act = tail.substring(0, tail.length() - 1)
    return [exp, act]
}

def pnGatherInputs(Map ctx) {
    pnGatherInputsPhase1(ctx)
    pnGatherInputsPhase2(ctx)
}

def pnGatherInputsPhase1(Map ctx) {
                    def psEsc = { String s -> s.replace("'", "''") }
                    def stripBom = { String s -> s ? s.replace('\uFEFF', '') : '' }
                    def proj = (env.USE_PROJECT ?: env.PROJECT_PATH ?: env.WORKSPACE)?.toString()?.trim()
                    def ws = env.WORKSPACE?.trim()
                    // Prefer Cloudflare Pages base after successful deploy; else Jenkins artifact URLs
                    def artBase = "${env.BUILD_URL}artifact/"
                    if (env.CLOUDFLARE_DEPLOY_SUCCEEDED == 'true' && env.CLOUDFLARE_PAGES_PROJECT_NAME?.trim()) {
                        def b = "https://${env.CLOUDFLARE_PAGES_PROJECT_NAME.trim()}.pages.dev"
                        artBase = b.replaceAll(/\/+$/, '') + '/'
                    }
                    def runId = ''
                    try {
                        def wsEsc = ws ? ws.replace('\\', '\\\\').replace("'", "''") : ''
                        def projEsc = proj ? proj.replace('\\', '\\\\').replace("'", "''") : ''
                        def canonPs = """\$ErrorActionPreference = 'SilentlyContinue'
\$roots = @('${wsEsc}', '${projEsc}') | Where-Object { \$_ -and (Test-Path -LiteralPath \$_) } | Select-Object -Unique
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
                        def d = ''
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
                                    d = ds.toString()
                                }
                            }
                        } catch (Exception ignore) { }
                        if (d) {
                            runId = "run-${d}"
                        }
                    } catch (Exception ex) {
 echo " WARN: could not resolve run_id for notifications: ${ex.message}"
                    }

                    def triageRel = runId ? "reports/triage/${runId}_triage_report.json" : ''
                    def skipRel = runId ? "reports/skip/${runId}_skip_bundle.json" : ''
                    def aiBucket = 'bug'
                    def bugCur = 0
                    def flakyCur = 0
                    def needsReviewCur = 0
                    def triageSummary = [:]
                    def bugLines = []
                    def envLines = []
                    def failEntries = []
                    def methodStats = [:]
                    try {
                        if (triageRel && proj) {
                            def triagePs = """\$ErrorActionPreference = 'SilentlyContinue'
\$repo = '${psEsc(proj)}'
\$p = Join-Path \$repo '${psEsc(triageRel)}'
if (-not (Test-Path -LiteralPath \$p)) { \$p = Join-Path \$env:WORKSPACE '${psEsc(triageRel)}' }
if (-not (Test-Path -LiteralPath \$p)) { exit 0 }
try {
  \$j = Get-Content -Raw -LiteralPath \$p -Encoding UTF8 | ConvertFrom-Json
  \$cs = \$j.classification_summary
  Write-Output ('SUMMARY_BUG=' + \$cs.BUG)
  Write-Output ('SUMMARY_FLAKY=' + \$cs.FLAKY)
  Write-Output ('SUMMARY_NEEDS=' + \$cs.NEEDS_REVIEW)
  \$m = \$j.methods_with_failures
  if (\$null -ne \$m) {
    foreach (\$prop in \$m.PSObject.Properties) {
      \$v = \$prop.Value
      if (\$v.is_failed_in_current_run) {
        \$tid = \$prop.Name
        \$err = \$v.current_run_failure.error_message
        \$shot = \$v.current_run_failure.screenshot
        \$c = \$v.classification
        Write-Output ('FAIL_ENTRY=' + \$tid + '|' + \$c + '|' + \$err + '|' + \$shot)
        \$an = \$v.analysis
        if (\$null -ne \$an) {
          Write-Output ('METHOD_ANALYSIS=' + \$tid + '|' + \$c + '|' + \$an.failure_rate + '|' + \$an.fail_count + '|' + \$an.total_runs)
        }
        if (\$c -eq 'BUG') {
          Write-Output ('BUG_DETAIL=' + \$tid + '|' + \$err + '|' + \$shot)
        }
        if (\$c -eq 'FLAKY' -or \$c -eq 'NEEDS_REVIEW') {
          Write-Output ('FLAKY_DETAIL=' + \$tid + '|' + \$err + '|' + \$shot)
        }
      }
    }
  }
} catch { }
"""
                            def tout = powershell(returnStdout: true, script: triagePs)?.trim() ?: ''
                            tout.readLines().each { line ->
                                if (line.startsWith('METHOD_ANALYSIS=')) {
                                    def rest = line.length() > 16 ? line.substring(16) : ''
                                    def parts = rest.split('\\|', 5)
                                    if (parts.length >= 5) {
                                        methodStats[parts[0]] = [klass: parts[1], rate: parts[2], fc: parts[3], tr: parts[4]]
                                    }
                                }
                                if (line.startsWith('FAIL_ENTRY=')) {
                                    def rest = line.length() > 11 ? line.substring(11) : ''
                                    def parts = rest.split('\\|', 4)
                                    if (parts.length >= 4) {
                                        failEntries << [tid: parts[0], klass: parts[1], err: parts[2], shot: parts[3]]
                                    }
                                }
                                if (line.startsWith('SUMMARY_BUG=')) {
                                    def n = (line.length() > 12) ? line.substring(12) : '0'
                                    bugCur = (n ?: '0') as int
                                    triageSummary['BUG'] = bugCur
                                }
                                if (line.startsWith('SUMMARY_FLAKY=')) {
                                    def n = (line.length() > 14) ? line.substring(14) : '0'
                                    flakyCur = (n ?: '0') as int
                                    triageSummary['FLAKY'] = flakyCur
                                }
                                if (line.startsWith('SUMMARY_NEEDS=')) {
                                    def n = (line.length() > 14) ? line.substring(14) : '0'
                                    needsReviewCur = (n ?: '0') as int
                                    triageSummary['NEEDS_REVIEW'] = needsReviewCur
                                }
                                if (line.startsWith('BUG_DETAIL=')) {
                                    def rest = line.length() > 11 ? line.substring(11) : ''
                                    def parts = rest.split('\\|', 3)
                                    if (parts.length >= 3) {
                                        def tid = parts[0]
                                        def err = parts[1]
                                        bugLines << "* ${tid} — ${err}".toString()
                                        def shot = parts[2]
                                        def shotName = shot ? shot.replaceAll(/.*[\/\\]/, '') : ''
                                        if (shotName) {
                                            def shotUrl = "${artBase}screenshots/${shotName}"
                                            envLines << "* Screenshot ${shotName}: ${shotUrl}".toString()
                                        }
                                    }
                                }
                                if (line.startsWith('FLAKY_DETAIL=')) {
                                    def rest = line.length() > 14 ? line.substring(14) : ''
                                    def parts = rest.split('\\|', 3)
                                    if (parts.length >= 3) {
                                        def tid = parts[0]
                                        def err = parts[1]
                                        bugLines << "* ${tid} — ${err}".toString()
                                        def shot = parts[2]
                                        def shotName = shot ? shot.replaceAll(/.*[\/\\]/, '') : ''
                                        if (shotName) {
                                            def shotUrl = "${artBase}screenshots/${shotName}"
                                            envLines << "* Screenshot ${shotName}: ${shotUrl}".toString()
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception ex) {
 echo " WARN: triage parse: ${ex.message}"
                    }

                    try {
                        def triCandidates = []
                        if (triageRel) {
                            triCandidates << triageRel
                            if (proj) {
                                triCandidates << "${proj}/${triageRel}".replace('\\', '/')
                            }
                            if (ws) {
                                triCandidates << "${ws}/${triageRel}".replace('\\', '/')
                            }
                        }
                        def triPath = triCandidates.find { it && fileExists(it) }
                        if (triPath && failEntries) {
                            def tj = pnParseJsonText(readFile(encoding: 'UTF-8', file: triPath))
                            def mwf = tj?.methods_with_failures
                            if (mwf instanceof Map) {
                                def triageTc = [:]
                                mwf.each { tidKey, info ->
                                    def cr = info?.current_run_failure
                                    def tc = cr?.tc_id?.toString()?.trim()
                                    if (tc) {
                                        triageTc[tidKey] = tc
                                    }
                                }
                                failEntries.each { fe ->
                                    def tc = triageTc[fe.tid]
                                    if (tc) {
                                        fe.tcId = tc
                                    }
                                }
                            }
                        }
                    } catch (Exception ignore) { }

                    def manifestLines = []
                    try {
                        if (fileExists('mcp_manifest.txt')) {
                            manifestLines = readFile(encoding: 'UTF-8', file: 'mcp_manifest.txt').readLines().findAll { it?.trim() }
                        }
                    } catch (Exception ignore) { }
                    def firstStem = ''
                    if (manifestLines && !manifestLines.isEmpty()) {
                        def p0 = manifestLines[0].split('\\|', 4)
                        if (p0.length >= 2) {
                            def b0 = p0[1]?.trim()
                            aiBucket = (b0 == 'flaky' || b0 == 'needs_review') ? b0 : 'bug'
                        }
                        if (p0.length >= 3 && p0[2]?.trim()) {
                            firstStem = p0[2].trim()
                        }
                    }
                    def bugStems = []
                    manifestLines.each { line ->
                        def p = line.split('\\|', 4)
                        if (p.length >= 3 && p[1] == 'bug' && p[2]?.trim()) {
                            bugStems << p[2].trim()
                        }
                    }
                    if (bugStems.isEmpty() && bugCur > 0 && firstStem) {
                        bugStems << firstStem
                    }
                    // Manifest must list only this run's failures; cap by current failure bundle size if stale rows slipped in.
                    try {
                        if (runId) {
                            def fbRelCap = "reports/failure/${runId}_failure_bundle.json"
                            def fbPathCap = [fbRelCap, proj ? "${proj}/${fbRelCap}".replace('\\', '/') : '', ws ? "${ws}/${fbRelCap}".replace('\\', '/') : ''].find { it && fileExists(it) }
                            if (fbPathCap) {
                                def fbJcap = pnParseJsonText(readFile(encoding: 'UTF-8', file: fbPathCap))
                                def nFail = 0
                                if (fbJcap?.failures instanceof List) {
                                    nFail = fbJcap.failures.size()
                                }
                                if (nFail > 0 && bugStems.size() > nFail) {
                                    bugStems = bugStems.take(nFail)
                                }
                            }
                        }
                    } catch (Exception ignore) { }
                    def stemOrRun = firstStem ?: runId
                    def pathBucket = aiBucket
                    if (bugCur > 0 && bugStems && !bugStems.isEmpty()) {
                        stemOrRun = bugStems[0]
                        pathBucket = 'bug'
                    }
                    def aiSummaryRel = runId ? "reports/AI/${pathBucket}/summary/${stemOrRun}_ai_summary.json" : ''
                    def aiAnalysisRel = runId ? "reports/AI/${pathBucket}/analysis/${stemOrRun}_ai_rca.json" : ''
                    def aiReportUrl = runId ? "${artBase}reports/AI/${pathBucket}/report/${stemOrRun}_ai_report.html" : ''
                    def aiSummaryUrl = runId ? "${artBase}reports/AI/${pathBucket}/summary/${stemOrRun}_ai_summary.json" : ''
                    def aiAnalysisUrl = runId ? "${artBase}reports/AI/${pathBucket}/analysis/${stemOrRun}_ai_rca.json" : ''
                    def failureBundleArt = runId ? "${artBase}reports/failure/${runId}_failure_bundle.json" : ''
                    def triageReportArt = runId ? "${artBase}reports/triage/${runId}_triage_report.json" : ''
                    def skipBundleArt = runId ? "${artBase}reports/skip/${runId}_skip_bundle.json" : ''
                    def extentReportUrl = runId ? "${artBase}reports/extent_report/${runId}_extent_report.html" : ''
                    def testLogUrl = runId ? "${artBase}logs/${runId}_test_log.log" : ''

                    def artifactsHubRel = runId ? "reports/artifacts_hub/${runId}/index.html" : ''
                    def artifactsHubExists = false
                    if (artifactsHubRel) {
                        artifactsHubExists = fileExists(artifactsHubRel)
                        if (!artifactsHubExists && ws) {
                            artifactsHubExists = fileExists("${ws}/${artifactsHubRel}".replace('\\', '/'))
                        }
                        if (!artifactsHubExists && proj) {
                            artifactsHubExists = fileExists("${proj}/${artifactsHubRel}".replace('\\', '/'))
                        }
                    }
                    def artifactsHubUrl = (artifactsHubExists && runId) ? "${artBase}reports/artifacts_hub/${runId}/index.html" : ''

                    def ridSeg = runId ? runId.replaceFirst(/^run-/, '') : ''
                    def canonicalAiId = stemOrRun
                    if (!canonicalAiId || canonicalAiId == runId || !(canonicalAiId ==~ /^(bug|flaky|needs-review)-run-.+/)) {
                        def idx = (env.JIRA_BUG_INDEX ?: '1').trim()
                        def n = (idx.length() < 2) ? "0${idx}" : idx
                        if (pathBucket == 'flaky') {
                            canonicalAiId = "flaky-run-${ridSeg}-${n}"
                        } else if (pathBucket == 'needs_review') {
                            canonicalAiId = "needs-review-run-${ridSeg}-${n}"
                        } else {
                            canonicalAiId = "bug-run-${ridSeg}-${n}"
                        }
                    }

                    def skipCount = 0
                    def skipFirstTid = ''
                    def skipFirstTc = ''
                    def skipFirstReason = ''
                    def skipFirstMsg = ''
                    try {
                        if (skipRel && proj) {
                            def skipPs = """\$ErrorActionPreference = 'SilentlyContinue'
\$repo = '${psEsc(proj)}'
\$p = Join-Path \$repo '${psEsc(skipRel)}'
if (-not (Test-Path -LiteralPath \$p)) { \$p = Join-Path \$env:WORKSPACE '${psEsc(skipRel)}' }
if (-not (Test-Path -LiteralPath \$p)) { Write-Output 'SKIP_COUNT=0'; exit 0 }
try {
  \$j = Get-Content -Raw -LiteralPath \$p -Encoding UTF8 | ConvertFrom-Json
  Write-Output ('SKIP_COUNT=' + \$j.skips.Count)
  if (\$j.skips.Count -gt 0) {
    \$s0 = \$j.skips[0]
    \$stid = \$s0.meta.test_id
    \$sr = \$s0.cause.skip_reason
    \$sem = \$s0.cause.exception_message
    Write-Output ('SKIP_FIRST_TID=' + \$stid)
    Write-Output ('SKIP_FIRST_REASON=' + \$sr)
    if (\$sem) { Write-Output ('SKIP_FIRST_MSG=' + \$sem) }
  }
} catch { Write-Output 'SKIP_COUNT=0' }
"""
                            def sout = powershell(returnStdout: true, script: skipPs)?.trim() ?: ''
                            def sm = (sout =~ /SKIP_COUNT=(\d+)/)
                            if (sm) {
                                skipCount = sm[0][1] as int
                            }
                            sout.readLines().each { line ->
                                if (line.startsWith('SKIP_FIRST_TID=')) {
                                    skipFirstTid = line.length() > 15 ? line.substring(15).trim() : ''
                                }
                                if (line.startsWith('SKIP_FIRST_REASON=')) {
                                    skipFirstReason = line.length() > 18 ? line.substring(18).trim() : ''
                                }
                                if (line.startsWith('SKIP_FIRST_MSG=')) {
                                    skipFirstMsg = line.length() > 15 ? line.substring(15).trim() : ''
                                }
                            }
                        }
                    } catch (Exception ex) {
 echo " WARN: skip bundle parse: ${ex.message}"
                    }

                    def skipEntries = []
                    try {
                        if (skipRel && runId) {
                            def sbCandidates = [skipRel]
                            if (proj) {
                                sbCandidates << "${proj}/${skipRel}".replace('\\', '/')
                            }
                            if (ws) {
                                sbCandidates << "${ws}/${skipRel}".replace('\\', '/')
                            }
                            def sbPath = sbCandidates.find { it && fileExists(it) }
                            if (sbPath) {
                                def sj = pnParseJsonText(readFile(encoding: 'UTF-8', file: sbPath))
                                def skList = sj?.skips
                                if (skList instanceof List && !skList.isEmpty()) {
                                    def s0 = skList[0]
                                    skipFirstTc = s0?.meta?.tc_id?.toString()?.trim() ?: ''
                                }
                                if (skList instanceof List) {
                                    skList.each { s ->
                                        def tidS = s?.meta?.test_id?.toString() ?: '—'
                                        def tcS = s?.meta?.tc_id?.toString()?.trim() ?: ''
                                        def srS = s?.cause?.skip_reason?.toString() ?: ''
                                        def emS = s?.cause?.exception_message?.toString() ?: ''
                                        skipEntries << [tid: tidS, tcId: tcS, reason: srS, msg: emS]
                                    }
                                }
                            }
                        }
                    } catch (Exception ignore) { }

                    def failureBundlePathExists = false
                    try {
                        if (runId) {
                            def r = "reports/failure/${runId}_failure_bundle.json"
                            failureBundlePathExists = fileExists(r)
                            if (!failureBundlePathExists && ws) {
                                failureBundlePathExists = fileExists("${ws}/${r}".replace('\\', '/'))
                            }
                            if (!failureBundlePathExists && proj && proj != ws) {
                                failureBundlePathExists = fileExists("${proj}/${r}".replace('\\', '/'))
                            }
                        }
                    } catch (Exception ignore) { }

                    def skipOnlySignal = (bugCur == 0 && flakyCur == 0 && needsReviewCur == 0 && skipCount > 0)
                    if (skipOnlySignal && !failureBundlePathExists) {
                        canonicalAiId = '— (no failure / no MCP for this run)'
                    }

                    ctx.psEsc = psEsc
                    ctx.stripBom = stripBom
                    ctx.proj = proj
                    ctx.ws = ws
                    ctx.artBase = artBase
                    ctx.runId = runId
                    ctx.triageRel = triageRel
                    ctx.skipRel = skipRel
                    ctx.aiBucket = aiBucket
                    ctx.bugCur = bugCur
                    ctx.flakyCur = flakyCur
                    ctx.needsReviewCur = needsReviewCur
                    ctx.triageSummary = triageSummary
                    ctx.bugLines = bugLines
                    ctx.envLines = envLines
                    ctx.failEntries = failEntries
                    ctx.methodStats = methodStats
                    ctx.manifestLines = manifestLines
                    ctx.firstStem = firstStem
                    ctx.bugStems = bugStems
                    ctx.stemOrRun = stemOrRun
                    ctx.pathBucket = pathBucket
                    ctx.aiSummaryRel = aiSummaryRel
                    ctx.aiAnalysisRel = aiAnalysisRel
                    ctx.aiReportUrl = aiReportUrl
                    ctx.aiSummaryUrl = aiSummaryUrl
                    ctx.aiAnalysisUrl = aiAnalysisUrl
                    ctx.failureBundleArt = failureBundleArt
                    ctx.triageReportArt = triageReportArt
                    ctx.skipBundleArt = skipBundleArt
                    ctx.extentReportUrl = extentReportUrl
                    ctx.testLogUrl = testLogUrl
                    ctx.artifactsHubUrl = artifactsHubUrl
                    ctx.ridSeg = ridSeg
                    ctx.canonicalAiId = canonicalAiId
                    ctx.skipCount = skipCount
                    ctx.skipFirstTid = skipFirstTid
                    ctx.skipFirstTc = skipFirstTc
                    ctx.skipFirstReason = skipFirstReason
                    ctx.skipFirstMsg = skipFirstMsg
                    ctx.skipEntries = skipEntries
                    ctx.skipOnlySignal = skipOnlySignal
                    ctx.failureBundlePathExists = failureBundlePathExists
}

def pnGatherInputsPhase2(Map ctx) {
                    def psEsc = ctx.psEsc
                    def stripBom = ctx.stripBom
                    def proj = ctx.proj
                    def ws = ctx.ws
                    def runId = ctx.runId
                    def aiSummaryRel = ctx.aiSummaryRel
                    def testTotal = 0
                    def testPass = 0
                    def testFail = 0
                    def testSkip = 0
                    // Per-run counts: never use Surefire's first TEST-*.xml when runId is known — it often
                    // reflects another class or stale suite totals. Order: (1) history jsonl for run_id,
                    // (2) skip + failure bundle entry counts, (3) Surefire only if runId is empty (legacy).
                    if (runId) {
                        def histLineTotal = 0
                        try {
                            def histCandidates = ['reports/history/test_run_history.jsonl']
                            if (proj) {
                                histCandidates << "${proj}/reports/history/test_run_history.jsonl".replace('\\', '/')
                            }
                            if (ws) {
                                histCandidates << "${ws}/reports/history/test_run_history.jsonl".replace('\\', '/')
                            }
                            def hp = histCandidates.find { it && fileExists(it) }
                            if (hp) {
                                def rid = runId.trim()
                                int pc = 0
                                int fc = 0
                                int sc = 0
                                readFile(encoding: 'UTF-8', file: hp).readLines().each { line ->
                                    if (!line?.trim()) {
                                        return
                                    }
                                    def j = pnParseJsonText(line)
                                    if (j && rid == j.run_id?.toString()?.trim()) {
                                        def st = j.status?.toString()
                                        if (st == 'PASS') {
                                            pc++
                                        } else if (st == 'FAIL') {
                                            fc++
                                        } else if (st == 'SKIP') {
                                            sc++
                                        }
                                    }
                                }
                                histLineTotal = pc + fc + sc
                                if (histLineTotal > 0) {
                                    testTotal = histLineTotal
                                    testPass = pc
                                    testFail = fc
                                    testSkip = sc
                                }
                            }
                        } catch (Exception ignore) { }

                        if (histLineTotal == 0) {
                            try {
                                def skRel = "reports/skip/${runId}_skip_bundle.json"
                                def fbRel = "reports/failure/${runId}_failure_bundle.json"
                                def skPath = [skRel, proj ? "${proj}/${skRel}".replace('\\', '/') : '', ws ? "${ws}/${skRel}".replace('\\', '/') : ''].find { it && fileExists(it) }
                                def fbPath = [fbRel, proj ? "${proj}/${fbRel}".replace('\\', '/') : '', ws ? "${ws}/${fbRel}".replace('\\', '/') : ''].find { it && fileExists(it) }
                                int skN = 0
                                int fbN = 0
                                if (skPath) {
                                    def sj = pnParseJsonText(readFile(encoding: 'UTF-8', file: skPath))
                                    def sl = sj?.skips
                                    if (sl instanceof List) {
                                        skN = sl.size()
                                    }
                                }
                                if (fbPath) {
                                    def fj = pnParseJsonText(readFile(encoding: 'UTF-8', file: fbPath))
                                    def fl = fj?.failures
                                    if (fl instanceof List) {
                                        fbN = fl.size()
                                    }
                                }
                                if (skN > 0 || fbN > 0) {
                                    testSkip = skN
                                    testFail = fbN
                                    testPass = 0
                                    testTotal = skN + fbN
                                }
                            } catch (Exception ignore) { }
                        }
                    } else {
                        try {
                            if (proj) {
                                def sfPs = """\$ErrorActionPreference = 'SilentlyContinue'
\$repo = '${psEsc(proj)}'
\$dir = Join-Path \$repo 'target\\surefire-reports'
if (-not (Test-Path -LiteralPath \$dir)) { exit 0 }
\$f = Get-ChildItem -LiteralPath \$dir -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not \$f) { exit 0 }
[xml]\$x = Get-Content -LiteralPath \$f.FullName -Encoding UTF8
\$ts = \$x.testsuite
if (\$null -eq \$ts) { exit 0 }
\$t = [int]\$ts.tests
\$fails = [int]\$ts.failures + [int]\$ts.errors
\$sk = [int]\$ts.skipped
\$pass = \$t - \$fails - \$sk
if (\$pass -lt 0) { \$pass = 0 }
Write-Output ('TS_TOTAL=' + \$t)
Write-Output ('TS_FAIL=' + \$fails)
Write-Output ('TS_SKIP=' + \$sk)
Write-Output ('TS_PASS=' + \$pass)
"""
                                def sfOut = powershell(returnStdout: true, script: sfPs)?.trim() ?: ''
                                sfOut.readLines().each { line ->
                                    if (line.startsWith('TS_TOTAL=')) testTotal = (line.length() > 9 ? line.substring(9) : '0') as int
                                    if (line.startsWith('TS_FAIL=')) testFail = (line.length() > 8 ? line.substring(8) : '0') as int
                                    if (line.startsWith('TS_SKIP=')) testSkip = (line.length() > 8 ? line.substring(8) : '0') as int
                                    if (line.startsWith('TS_PASS=')) testPass = (line.length() > 8 ? line.substring(8) : '0') as int
                                }
                            }
                        } catch (Exception ignore) { }
                    }

                    // History jsonl can miss SKIP rows (listener timing, parallel suites) while skip bundle + Surefire match the run.
                    try {
                        int bc = 0
                        if (ctx.skipCount != null) {
                            try {
                                bc = ctx.skipCount as int
                            } catch (Exception ignoreBc) {
                                bc = 0
                            }
                        }
                        if (bc > testSkip) {
                            testSkip = bc
                        }
                        if (runId && testSkip == 0 && proj) {
                            def sfAggPs = """\$ErrorActionPreference = 'SilentlyContinue'
\$repo = '${psEsc(proj)}'
\$dir = Join-Path \$repo 'target\\surefire-reports'
if (-not (Test-Path -LiteralPath \$dir)) { exit 0 }
\$tsk = 0
Get-ChildItem -LiteralPath \$dir -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue | ForEach-Object {
  try {
    [xml]\$x = Get-Content -LiteralPath \$_.FullName -Encoding UTF8
    \$ts = \$x.testsuite
    if (\$null -ne \$ts) { \$tsk += [int]\$ts.skipped }
  } catch { }
}
Write-Output ('SF_SKIP=' + \$tsk)
"""
                            def sfAggOut = powershell(returnStdout: true, script: sfAggPs)?.trim() ?: ''
                            sfAggOut.readLines().each { line ->
                                if (line.startsWith('SF_SKIP=')) {
                                    try {
                                        int sk = (line.length() > 8 ? line.substring(8) : '0') as int
                                        if (sk > testSkip) {
                                            testSkip = sk
                                        }
                                    } catch (Exception ignoreSk) { }
                                }
                            }
                        }
                        int rsum = testPass + testFail + testSkip
                        if (rsum > testTotal) {
                            testTotal = rsum
                        }
                    } catch (Exception ignore) { }

                    def aiSummaryText = ''
                    def aiRootCause = ''
                    def aiConfidence = ''
                    def aiRcaPrimary = ''
                    try {
                        if (aiSummaryRel && proj) {
                            def aiPs = """\$ErrorActionPreference = 'Stop'
\$repo = '${psEsc(proj)}'
\$p = Join-Path \$repo '${psEsc(aiSummaryRel)}'
if (-not (Test-Path -LiteralPath \$p)) { \$p = Join-Path \$env:WORKSPACE '${psEsc(aiSummaryRel)}' }
\$fs = Join-Path \$env:WORKSPACE 'jenkins_ai_summary.txt'
\$fr = Join-Path \$env:WORKSPACE 'jenkins_ai_root.txt'
\$fb = Join-Path \$env:WORKSPACE 'jenkins_ai_bug_description.txt'
\$fi = Join-Path \$env:WORKSPACE 'jenkins_ai_impact.txt'
\$fj = Join-Path \$env:WORKSPACE 'jenkins_ai_root_jira.txt'
\$fc = Join-Path \$env:WORKSPACE 'jenkins_ai_confidence.txt'
\$fp = Join-Path \$env:WORKSPACE 'jenkins_ai_rca_primary.txt'
\$ff = Join-Path \$env:WORKSPACE 'jenkins_ai_flaky_description.txt'
\$fl = Join-Path \$env:WORKSPACE 'jenkins_ai_log_refs.txt'
if (-not (Test-Path -LiteralPath \$p)) { exit 0 }
\$j = Get-Content -Raw -LiteralPath \$p -Encoding UTF8 | ConvertFrom-Json
\$sum = [string]\$j.summary
# Jira title / Slack reason: use short summary only — do not substitute root_cause_excerpt
if ([string]::IsNullOrWhiteSpace(\$sum)) { \$sum = '' }
\$root = \$j.root_cause
if ([string]::IsNullOrWhiteSpace(\$root)) { \$root = [string]\$j.root_cause_excerpt }
\$rca = ''
\$bestPct = -1
if (\$null -ne \$j.root_cause_candidates) {
  foreach (\$c in @(\$j.root_cause_candidates)) {
    if (\$null -eq \$c) { continue }
    \$p = 0
    if (\$null -ne \$c.probability_percent) {
      try { \$p = [int]\$c.probability_percent } catch { \$p = 0 }
    }
    \$a = [string]\$c.analysis
    if ([string]::IsNullOrWhiteSpace(\$a)) { continue }
    if (\$p -gt \$bestPct) { \$bestPct = \$p; \$rca = \$a }
  }
}
if ([string]::IsNullOrWhiteSpace(\$rca)) { \$rca = [string]\$j.root_cause_analysis_jira }
\$enc = New-Object System.Text.UTF8Encoding \$false
[System.IO.File]::WriteAllText(\$fs, [string]\$sum, \$enc)
[System.IO.File]::WriteAllText(\$fr, [string]\$root, \$enc)
[System.IO.File]::WriteAllText(\$fb, [string]\$j.bug_description, \$enc)
[System.IO.File]::WriteAllText(\$fi, [string]\$j.impact, \$enc)
[System.IO.File]::WriteAllText(\$fj, [string]\$j.root_cause_analysis_jira, \$enc)
[System.IO.File]::WriteAllText(\$fc, [string]\$j.confidence, \$enc)
[System.IO.File]::WriteAllText(\$fp, \$rca, \$enc)
[System.IO.File]::WriteAllText(\$ff, [string]\$j.flaky_description, \$enc)
if (\$null -ne \$j.log_line_references) {
  \$rj = \$j.log_line_references | ConvertTo-Json -Depth 8 -Compress
  [System.IO.File]::WriteAllText(\$fl, \$rj, \$enc)
}
"""
                            powershell(returnStdout: true, script: aiPs)
                            if (fileExists('jenkins_ai_summary.txt')) {
                                aiSummaryText = stripBom(readFile(encoding: 'UTF-8', file: 'jenkins_ai_summary.txt').trim())
                            }
                            if (fileExists('jenkins_ai_root.txt')) {
                                aiRootCause = stripBom(readFile(encoding: 'UTF-8', file: 'jenkins_ai_root.txt').trim())
                            }
                            if (fileExists('jenkins_ai_confidence.txt')) {
                                aiConfidence = stripBom(readFile(encoding: 'UTF-8', file: 'jenkins_ai_confidence.txt').trim())
                            }
                            if (fileExists('jenkins_ai_rca_primary.txt')) {
                                aiRcaPrimary = stripBom(readFile(encoding: 'UTF-8', file: 'jenkins_ai_rca_primary.txt').trim())
                            }
                        }
                    } catch (Exception ignore) { }

                    def aiBugDescription = ''
                    def aiFlakyDescription = ''
                    def aiLogRefsJson = ''
                    def aiImpact = ''
                    def aiRootJira = ''
                    try {
                        if (fileExists('jenkins_ai_bug_description.txt')) {
                            aiBugDescription = stripBom(readFile(encoding: 'UTF-8', file: 'jenkins_ai_bug_description.txt').trim())
                        }
                        if (fileExists('jenkins_ai_flaky_description.txt')) {
                            aiFlakyDescription = stripBom(readFile(encoding: 'UTF-8', file: 'jenkins_ai_flaky_description.txt').trim())
                        }
                        if (fileExists('jenkins_ai_log_refs.txt')) {
                            aiLogRefsJson = stripBom(readFile(encoding: 'UTF-8', file: 'jenkins_ai_log_refs.txt').trim())
                        }
                        if (fileExists('jenkins_ai_impact.txt')) {
                            aiImpact = stripBom(readFile(encoding: 'UTF-8', file: 'jenkins_ai_impact.txt').trim())
                        }
                        if (fileExists('jenkins_ai_root_jira.txt')) {
                            aiRootJira = stripBom(readFile(encoding: 'UTF-8', file: 'jenkins_ai_root_jira.txt').trim())
                        }
                    } catch (Exception ignore) { }

                    try {
                        if ((!aiBugDescription?.trim() || !aiImpact?.trim() || !aiRootJira?.trim()) && aiSummaryRel) {
                            def sumCand = [aiSummaryRel]
                            if (proj) {
                                sumCand << "${proj}/${aiSummaryRel}".replace('\\', '/')
                            }
                            if (ws) {
                                sumCand << "${ws}/${aiSummaryRel}".replace('\\', '/')
                            }
                            def sp = sumCand.find { it && fileExists(it) }
                            if (sp) {
                                def jo = new groovy.json.JsonSlurper().parseText(readFile(encoding: 'UTF-8', file: sp))
                                if (!aiBugDescription?.trim()) {
                                    aiBugDescription = (jo.bug_description ?: jo.summary ?: '').toString().trim()
                                }
                                if (!aiImpact?.trim()) {
                                    aiImpact = (jo.impact ?: '').toString().trim()
                                }
                                if (!aiRootJira?.trim()) {
                                    aiRootJira = (jo.root_cause_analysis_jira ?: jo.root_cause ?: '').toString().trim()
                                }
                                if (!aiRcaPrimary?.trim()) {
                                    aiRcaPrimary = (jo.root_cause ?: '').toString().trim()
                                }
                            }
                        }
                    } catch (Exception ignore) { }

                    def fbTestId = ''
                    def fbException = ''
                    def fbTestData = ''
                    def fbExpected = ''
                    def fbActual = ''
                    def triageRel = ctx.triageRel ?: ''
                    try {
                        def fbRelMeta = runId ? "reports/failure/${runId}_failure_bundle.json" : ''
                        def repoRoot = (proj ?: ws ?: '').toString().trim()
                        if (fbRelMeta && repoRoot) {
                            def fbPs = """\$ErrorActionPreference = 'Stop'
\$repo = '${psEsc(repoRoot)}'
\$p = Join-Path \$repo '${psEsc(fbRelMeta)}'
if (-not (Test-Path -LiteralPath \$p)) { \$p = Join-Path \$env:WORKSPACE '${psEsc(fbRelMeta)}' }
\$root = \$env:WORKSPACE
if (-not (Test-Path -LiteralPath \$p)) { exit 0 }
\$j = Get-Content -Raw -LiteralPath \$p -Encoding UTF8 | ConvertFrom-Json
\$foundIdx = 0
\$triPath = Join-Path \$repo '${psEsc(triageRel)}'
if (-not (Test-Path -LiteralPath \$triPath)) { \$triPath = Join-Path \$env:WORKSPACE '${psEsc(triageRel)}' }
if ((Test-Path -LiteralPath \$triPath) -and \$j.failures.Count -gt 0) {
  try {
    \$tri = Get-Content -Raw -LiteralPath \$triPath -Encoding UTF8 | ConvertFrom-Json
    \$methods = \$tri.methods_with_failures
    \$foundIdx = -1
    for (\$i = 0; \$i -lt \$j.failures.Count; \$i++) {
      \$tid = [string]\$j.failures[\$i].meta.test_id
      \$ent = \$null
      if (\$null -ne \$methods) {
        foreach (\$prop in \$methods.PSObject.Properties) {
          if (\$prop.Name -eq \$tid) { \$ent = \$prop.Value; break }
        }
      }
      if (\$null -ne \$ent -and \$ent.classification -eq 'BUG') { \$foundIdx = \$i; break }
    }
    if (\$foundIdx -lt 0) { \$foundIdx = 0 }
  } catch { \$foundIdx = 0 }
}
\$f0 = \$j.failures[\$foundIdx]
\$exm = [string]\$f0.error.exception_message
\$exp = ''
\$act = ''
if (\$null -ne \$f0.error) {
  if (\$null -ne \$f0.error.expected) { \$exp = [string]\$f0.error.expected }
  if (\$null -ne \$f0.error.actual) { \$act = [string]\$f0.error.actual }
}
if ([string]::IsNullOrWhiteSpace(\$exp) -or \$exp -eq 'null') {
  \$line = (\$exm -split "`n") | Where-Object { \$_.Contains('expected [') } | Select-Object -First 1
  if ([string]::IsNullOrWhiteSpace(\$line)) { \$line = (\$exm -split "`n")[0] }
  \$ei = \$line.IndexOf('expected [')
  if (\$ei -ge 0) { \$line = \$line.Substring(\$ei) }
  \$sep = '] but found ['
  \$ix = \$line.IndexOf(\$sep)
  if (\$ix -ge 0) {
    \$head = \$line.Substring(0, \$ix)
    \$pref = 'expected ['
    if (\$head.StartsWith(\$pref)) {
      \$exp = \$head.Substring(\$pref.Length)
      \$tail = \$line.Substring(\$ix + \$sep.Length)
      if (\$tail.EndsWith(']')) { \$act = \$tail.Substring(0, \$tail.Length - 1) }
    }
  }
}
\$enc = New-Object System.Text.UTF8Encoding \$false
[System.IO.File]::WriteAllText((Join-Path \$root 'jenkins_fb_test_id.txt'), ([string]\$f0.meta.test_id), \$enc)
[System.IO.File]::WriteAllText((Join-Path \$root 'jenkins_fb_exception.txt'), \$exm, \$enc)
[System.IO.File]::WriteAllText((Join-Path \$root 'jenkins_fb_test_data.txt'), ([string]\$f0.error.test_data), \$enc)
[System.IO.File]::WriteAllText((Join-Path \$root 'jenkins_fb_expected.txt'), \$exp, \$enc)
[System.IO.File]::WriteAllText((Join-Path \$root 'jenkins_fb_actual.txt'), \$act, \$enc)
"""
                            powershell(returnStdout: true, script: fbPs)
                            if (fileExists('jenkins_fb_test_id.txt')) {
                                fbTestId = stripBom(readFile(encoding: 'UTF-8', file: 'jenkins_fb_test_id.txt').trim())
                            }
                            if (fileExists('jenkins_fb_exception.txt')) {
                                fbException = stripBom(readFile(encoding: 'UTF-8', file: 'jenkins_fb_exception.txt').trim())
                            }
                            if (fileExists('jenkins_fb_test_data.txt')) {
                                fbTestData = stripBom(readFile(encoding: 'UTF-8', file: 'jenkins_fb_test_data.txt').trim())
                            }
                            if (fileExists('jenkins_fb_expected.txt')) {
                                fbExpected = stripBom(readFile(encoding: 'UTF-8', file: 'jenkins_fb_expected.txt').trim())
                            }
                            if (fileExists('jenkins_fb_actual.txt')) {
                                fbActual = stripBom(readFile(encoding: 'UTF-8', file: 'jenkins_fb_actual.txt').trim())
                            }
                            if (fbException?.trim()) {
                                def pe = parseTestNgExpectedActualFromMessage(fbException)
                                if (!fbExpected?.trim() && pe[0]) {
                                    fbExpected = pe[0]
                                }
                                if (!fbActual?.trim() && pe[1] != null) {
                                    fbActual = pe[1]
                                }
                            }
                        }
                        if (!fbTestId?.trim() && runId) {
                            def r = "reports/failure/${runId}_failure_bundle.json"
                            def candidates = [r]
                            if (ws) {
                                candidates << "${ws}/${r}".replace('\\', '/')
                            }
                            if (proj && proj != ws) {
                                candidates << "${proj}/${r}".replace('\\', '/')
                            }
                            def fbPath = candidates.find { it && fileExists(it) }
                            if (fbPath) {
                                try {
                                    def j = new groovy.json.JsonSlurper().parseText(readFile(encoding: 'UTF-8', file: fbPath))
                                    def failures = j.failures
                                    if (failures && failures.size() > 0) {
                                        def foundIdx = 0
                                        if (triageRel) {
                                            def triCandidates = [triageRel]
                                            if (ws) {
                                                triCandidates << "${ws}/${triageRel}".replace('\\', '/')
                                            }
                                            if (proj && proj != ws) {
                                                triCandidates << "${proj}/${triageRel}".replace('\\', '/')
                                            }
                                            def triPath = triCandidates.find { it && fileExists(it) }
                                            if (triPath) {
                                                try {
                                                    def tri = new groovy.json.JsonSlurper().parseText(readFile(encoding: 'UTF-8', file: triPath))
                                                    def methods = tri.methods_with_failures
                                                    if (methods instanceof Map) {
                                                        foundIdx = -1
                                                        for (int i = 0; i < failures.size(); i++) {
                                                            def tid = failures[i]?.meta?.test_id?.toString()
                                                            def ent = methods[tid]
                                                            if (ent && ent.classification == 'BUG') {
                                                                foundIdx = i
                                                                break
                                                            }
                                                        }
                                                        if (foundIdx < 0) {
                                                            foundIdx = 0
                                                        }
                                                    }
                                                } catch (Exception ignore2) { }
                                            }
                                        }
                                        fbTestId = (failures[foundIdx]?.meta?.test_id ?: '').toString().trim()
                                    }
                                } catch (Exception ignore3) { }
                            }
                        }
                    } catch (Exception ignore) { }

                    def hasFailureBundleForRun = false
                    try {
                        if (runId) {
                            def r = "reports/failure/${runId}_failure_bundle.json"
                            hasFailureBundleForRun = fileExists(r)
                            if (!hasFailureBundleForRun && ws) {
                                hasFailureBundleForRun = fileExists("${ws}/${r}".replace('\\', '/'))
                            }
                            if (!hasFailureBundleForRun && proj && proj != ws) {
                                hasFailureBundleForRun = fileExists("${proj}/${r}".replace('\\', '/'))
                            }
                        }
                    } catch (Exception ignore) { }

                    def greenRun = (testTotal > 0 && testFail == 0 && testSkip == 0
                            && ctx.bugCur == 0 && ctx.flakyCur == 0 && ctx.needsReviewCur == 0 && ctx.skipCount == 0)
                    ctx.greenRun = greenRun
                    ctx.notifyQaSlack = greenRun || (ctx.bugCur > 0 || ctx.flakyCur > 0 || ctx.needsReviewCur > 0 || ctx.skipCount > 0)
                    ctx.notifyDevJira = (ctx.bugCur > 0) && hasFailureBundleForRun && (testFail > 0)
                    ctx.jiraBrowseUrl = ''
                    ctx.jiraExtraUrls = ''
                    ctx.testTotal = testTotal
                    ctx.testPass = testPass
                    ctx.testFail = testFail
                    ctx.testSkip = testSkip
                    ctx.aiSummaryText = aiSummaryText
                    ctx.aiRootCause = aiRootCause
                    ctx.aiConfidence = aiConfidence
                    ctx.aiRcaPrimary = aiRcaPrimary
                    ctx.aiBugDescription = aiBugDescription
                    ctx.aiFlakyDescription = aiFlakyDescription
                    ctx.aiLogRefsJson = aiLogRefsJson
                    ctx.aiImpact = aiImpact
                    ctx.aiRootJira = aiRootJira
                    ctx.fbTestId = fbTestId
                    ctx.fbException = fbException
                    ctx.fbTestData = fbTestData
                    ctx.fbExpected = fbExpected
                    ctx.fbActual = fbActual
}

def pnJiraNotifications(Map ctx) {
                        // Must be method-scoped: ctx sync runs after try/catch (CPS cannot see vars declared only inside try)
                        def jiraBrowseUrl = ctx.jiraBrowseUrl ?: ''
                        def jiraExtraUrls = ctx.jiraExtraUrls ?: ''
                        try {
                            def psEsc = ctx.psEsc
                            def proj = ctx.proj
                            def ws = ctx.ws
                            def stripBom = { String s -> s ? s.replace('\uFEFF', '') : '' }
                            def runId = ctx.runId
                            def bugStems = ctx.bugStems
                            def canonicalAiId = ctx.canonicalAiId
                            def artBase = ctx.artBase
                            def failureBundleArt = ctx.failureBundleArt
                            def envLines = ctx.envLines
                            def aiBugDescription = ctx.aiBugDescription
                            def aiImpact = ctx.aiImpact
                            def aiConfidence = ctx.aiConfidence
                            def aiRcaPrimary = ctx.aiRcaPrimary
                            def aiRootJira = ctx.aiRootJira
                            def aiSummaryText = ctx.aiSummaryText
                            def fbTestId = (ctx.fbTestId ?: '').trim()
                            def fbExpected = ctx.fbExpected
                            def fbActual = ctx.fbActual
                            def fbException = ctx.fbException ?: ''
                            def fbTestData = ctx.fbTestData
                            def testLogUrl = ctx.testLogUrl
                            def aiReportUrl = ctx.aiReportUrl
                            def aiSummaryRel = ctx.aiSummaryRel ?: ''
                            def jHost = env.JIRA_HOST?.trim()?.replaceAll(/\/+$/, '')
                            def jiraBrowseRe = /https?:\/\/[^\s]+\/browse\/[A-Z][A-Z0-9]+-\d+/
                            def jProject = env.JIRA_PROJECT_KEY?.trim()
                            def jEmail = env.JIRA_EMAIL?.trim()
                            def jToken = env.JIRA_API_TOKEN?.trim()
                            def jIssueType = env.JIRA_ISSUE_TYPE_ID?.trim()
                            def triageRel = ctx.triageRel ?: ''
                            if (jHost && jProject && jEmail && jToken && jIssueType && jHost.startsWith('http')) {
                                def fbRel = runId ? "reports/failure/${runId}_failure_bundle.json" : ''
                                def browser = ''
                                def osname = ''
                                def baseUrl = ''
                                def fbTcId = ''
                                try {
                                    if (fbRel && proj) {
                                        def envPs = """\$ErrorActionPreference = 'SilentlyContinue'
\$repo = '${psEsc(proj)}'
\$p = Join-Path \$repo '${psEsc(fbRel)}'
if (-not (Test-Path -LiteralPath \$p)) { \$p = Join-Path \$env:WORKSPACE '${psEsc(fbRel)}' }
if (-not (Test-Path -LiteralPath \$p)) { exit 0 }
try {
  \$j = Get-Content -Raw -LiteralPath \$p -Encoding UTF8 | ConvertFrom-Json
  \$foundIdx = 0
  \$triPath = Join-Path \$repo '${psEsc(triageRel)}'
  if (-not (Test-Path -LiteralPath \$triPath)) { \$triPath = Join-Path \$env:WORKSPACE '${psEsc(triageRel)}' }
  if ((Test-Path -LiteralPath \$triPath) -and \$j.failures.Count -gt 0) {
    try {
      \$tri = Get-Content -Raw -LiteralPath \$triPath -Encoding UTF8 | ConvertFrom-Json
      \$methods = \$tri.methods_with_failures
      \$foundIdx = -1
      for (\$i = 0; \$i -lt \$j.failures.Count; \$i++) {
        \$tid = [string]\$j.failures[\$i].meta.test_id
        \$ent = \$null
        if (\$null -ne \$methods) {
          foreach (\$prop in \$methods.PSObject.Properties) {
            if (\$prop.Name -eq \$tid) { \$ent = \$prop.Value; break }
          }
        }
        if (\$null -ne \$ent -and \$ent.classification -eq 'BUG') { \$foundIdx = \$i; break }
      }
      if (\$foundIdx -lt 0) { \$foundIdx = 0 }
    } catch { \$foundIdx = 0 }
  }
  \$e = \$j.failures[\$foundIdx].environment
  Write-Output ('ENV_BROWSER=' + \$e.browser)
  Write-Output ('ENV_OS=' + \$e.os)
  Write-Output ('ENV_BASE=' + \$e.base_url)
  \$tci = \$j.failures[\$foundIdx].error.tc_id
  if (\$null -eq \$tci) { \$tci = '' }
  Write-Output ('TC_ID=' + [string]\$tci)
} catch { }
"""
                                        def eout = powershell(returnStdout: true, script: envPs)?.trim() ?: ''
                                        eout.readLines().each { line ->
                                            // Use prefix length (was off-by-one: ENV_OS= is 7 chars, ENV_BASE= is 9 — not 8/10).
                                            if (line.startsWith('ENV_BROWSER=')) {
                                                browser = line.length() > 'ENV_BROWSER='.length() ? line.substring('ENV_BROWSER='.length()) : ''
                                            }
                                            if (line.startsWith('ENV_OS=')) {
                                                osname = line.length() > 'ENV_OS='.length() ? line.substring('ENV_OS='.length()) : ''
                                            }
                                            if (line.startsWith('ENV_BASE=')) {
                                                baseUrl = line.length() > 'ENV_BASE='.length() ? line.substring('ENV_BASE='.length()) : ''
                                            }
                                            if (line.startsWith('TC_ID=')) {
                                                fbTcId = line.length() > 'TC_ID='.length() ? line.substring('TC_ID='.length()) : ''
                                            }
                                        }
                                    }
                                } catch (Exception ignore) { }

                                def testTypeLabel = env.TEST_TYPE?.trim() ?: 'Regression'
                                def featureShort = ''
                                def methodShort = ''
                                if (fbTestId) {
                                    def mm = (fbTestId =~ /testCases\.(\w+)#([^\[]+)/)
                                    if (mm) {
                                        featureShort = mm[0][1]
                                        methodShort = mm[0][2]
                                    }
                                }
                                def testClassMethodLabel = (featureShort?.trim() && methodShort?.trim())
                                        ? "${featureShort}.${methodShort}"
                                        : (fbTestId ?: '—')

                                def bugTicketId = canonicalAiId

                                def screenshotUrl = ''
                                if (envLines && !envLines.isEmpty()) {
                                    def el = envLines.find { it && (it.contains('http://') || it.contains('https://')) }
                                    if (el) {
                                        def um = (el =~ /(https?:\/\/\S+)/)
                                        if (um) {
                                            screenshotUrl = um[0][1].replaceAll(/[)\]}>.,;]+$/, '')
                                        }
                                    }
                                }

                                def bugDescBlock = aiBugDescription?.trim() ? aiBugDescription.trim() : ''
                                if (!bugDescBlock || bugDescBlock.startsWith('(Run MCP')) {
                                    if (aiSummaryText?.trim()) {
                                        bugDescBlock = aiSummaryText.trim()
                                    }
                                }
                                if (!bugDescBlock) {
                                    bugDescBlock = '(Run MCP / analyze-failure to populate; see AI summary JSON.)'
                                }
                                def impactBlock = aiImpact?.trim() ? aiImpact.trim() : 'N/A'

                                def confPctStr = '95'
                                if (aiConfidence?.trim()) {
                                    def cm = (aiConfidence =~ /(\d+)/)
                                    if (cm) {
                                        confPctStr = cm[0][1]
                                    }
                                }
                                def rcaClean = (aiRcaPrimary ?: aiRootJira ?: '').trim()
                                if (!rcaClean && aiRootCause?.trim()) {
                                    rcaClean = aiRootCause.trim()
                                }
                                if (!rcaClean) {
                                    rcaClean = '(No root cause hypothesis available, or MCP did not run — see AI report.)'
                                } else {
                                    rcaClean = rcaClean.replaceAll(/^-\s*\(\d+%\)\s*/, '').trim()
                                }

                                def failHeadline = '[Validation Failure]'
                                // Jira: [bug-run-…-TC010 | …-01] + short AI noun-phrase summary; body starts with same line + "---".
                                def jiraSummaryShort = (aiSummaryText?.trim())
                                        ? aiSummaryText.trim().replaceAll(/\r?\n/, ' ').replaceAll(/\s+/, ' ').trim()
                                        : "${testClassMethodLabel == '—' ? 'Test.case' : testClassMethodLabel} — validation / assertion mismatch"
                                def bugBracket = "[${bugTicketId}]"
                                def jiraTitle = "${bugBracket} ${jiraSummaryShort}"
                                if (jiraTitle.length() > 255) {
                                    jiraTitle = jiraTitle.substring(0, 255)
                                }

                                def expDisp = fbExpected?.trim() ? "\"${fbExpected}\"" : '—'
                                def actDisp = (fbActual != null && fbActual.toString().trim() != '') ? "\"${fbActual}\"" : '"" (empty string)'
                                if (expDisp == '—' && fbException?.trim()) {
                                    def pe = parseTestNgExpectedActualFromMessage(fbException)
                                    if (pe[0]?.trim()) {
                                        expDisp = "\"${pe[0]}\""
                                    }
                                    if (pe[1] != null && (fbActual == null || fbActual.toString().trim() == '')) {
                                        def av = pe[1].toString()
                                        actDisp = av.trim() == '' ? '"" (empty string)' : "\"${av}\""
                                    }
                                }
                                if (expDisp == '—' && aiSummaryRel?.trim()) {
                                    try {
                                        if (fileExists(aiSummaryRel)) {
                                            def jsum = new groovy.json.JsonSlurper().parseText(readFile(encoding: 'UTF-8', file: aiSummaryRel))
                                            def exAi = jsum.expected
                                            if (exAi != null && exAi.toString().trim() && exAi.toString().trim() != 'N/A') {
                                                expDisp = "\"${exAi}\""
                                            }
                                            if (jsum.actual != null && (fbActual == null || fbActual.toString().trim() == '')) {
                                                def av = jsum.actual.toString()
                                                actDisp = av.trim() == '' ? '"" (empty string)' : "\"${av}\""
                                            }
                                        }
                                    } catch (Exception ignore) { }
                                }
                                if (expDisp == '—' && runId) {
                                    def logRel = "logs/${runId}_test_log.log"
                                    def logCandidates = []
                                    logCandidates << logRel
                                    if (ws) {
                                        logCandidates << "${ws}/${logRel}".replace('\\', '/')
                                    }
                                    if (proj) {
                                        logCandidates << "${proj}/${logRel}".replace('\\', '/')
                                    }
                                    def logText = ''
                                    logCandidates.unique().each { lp ->
                                        if (logText) {
                                            return
                                        }
                                        try {
                                            if (fileExists(lp)) {
                                                logText = readFile(encoding: 'UTF-8', file: lp)
                                            }
                                        } catch (Exception ignore) { }
                                    }
                                    if (logText) {
                                        def lines = logText.split(/\r?\n/)
                                        def candidate = ''
                                        if (fbTestId) {
                                            candidate = lines.find { it.contains(fbTestId) && it.contains('expected [') && it.contains('] but found [') }
                                        }
                                        if (!candidate) {
                                            candidate = lines.find { it.contains('[FAIL]') && it.contains('expected [') && it.contains('] but found [') }
                                        }
                                        if (!candidate) {
                                            candidate = lines.find { it.contains('AssertionError') && it.contains('expected [') && it.contains('] but found [') }
                                        }
                                        if (candidate) {
                                            def pe = parseTestNgExpectedActualFromMessage(candidate)
                                            if (pe[0]?.trim()) {
                                                expDisp = "\"${pe[0]}\""
                                            }
                                            if (pe[1] != null && (fbActual == null || fbActual.toString().trim() == '')) {
                                                def av = pe[1].toString()
                                                actDisp = av.trim() == '' ? '"" (empty string)' : "\"${av}\""
                                            }
                                        }
                                    }
                                }

                                def attParts = []
                                if (screenshotUrl) {
                                    attParts << "[Screenshot|${screenshotUrl}]"
                                }
                                if (testLogUrl) {
                                    attParts << "[Logs|${testLogUrl}]"
                                }
                                if (aiReportUrl) {
                                    attParts << "[AI Report|${aiReportUrl}]"
                                }
                                def attachmentLine = attParts ? attParts.join(' | ') : '_Screenshot | Logs | AI Report — links unavailable; see Jenkins artifacts._'

                                def aiModelFooter = env.JIRA_AI_MODEL?.trim() ?: 'Claude 3.5'
                                def baseUrlJira = baseUrl ? "[${baseUrl}|${baseUrl}]" : 'the application base URL'

                                def desc = """${bugBracket} ${jiraSummaryShort}
---

*Summary*:
${bugDescBlock}

*Expected*: ${expDisp}
*Actual*: ${actDisp}

*Root Cause (${confPctStr}% confidence)*:
${rcaClean}

*Impact*:
${impactBlock}

*Repro*:
1. Open ${baseUrlJira} → navigate to the flow under test: *${testClassMethodLabel}* (`${fbTestId ?: '—'}`) — see failure bundle for full test id.
2. Use the test data / parameters recorded for this BUG${fbTestData ? ": ${fbTestData.take(400)}" : ' (see failure bundle).'}
3. Repeat until the failure matches *Actual* vs *Expected* above (same assertion / validation as the automated test).

*Test Info*:
${testTypeLabel} | ${testClassMethodLabel} | ${fbTcId?.trim() ? fbTcId.trim() : '—'} | ${browser}/${osname} | Build #${env.BUILD_NUMBER}

*Bug ID (artifact)*: ${bugTicketId}

*Attachments*: ${attachmentLine}
----
*AI assisted (${aiModelFooter})*
""".toString()

                                def jiraFmtYmd = { java.util.Calendar c ->
                                    String.format(java.util.Locale.US, '%04d-%02d-%02d',
                                        c.get(java.util.Calendar.YEAR),
                                        c.get(java.util.Calendar.MONTH) + 1,
                                        c.get(java.util.Calendar.DAY_OF_MONTH))
                                }
                                def startD = env.JIRA_START_DATE?.trim()
                                if (!startD) {
                                    startD = jiraFmtYmd(java.util.Calendar.getInstance())
                                }
                                def dueD = env.JIRA_DUE_DATE?.trim()
                                if (!dueD) {
                                    try {
                                        def p = startD.split('-')
                                        if (p.length == 3) {
                                            def cal = java.util.Calendar.getInstance()
                                            cal.set(Integer.parseInt(p[0], 10), Integer.parseInt(p[1], 10) - 1, Integer.parseInt(p[2], 10), 0, 0, 0)
                                            cal.set(java.util.Calendar.MILLISECOND, 0)
                                            cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
                                            dueD = jiraFmtYmd(cal)
                                        } else {
                                            def cal2 = java.util.Calendar.getInstance()
                                            cal2.add(java.util.Calendar.DAY_OF_MONTH, 1)
                                            dueD = jiraFmtYmd(cal2)
                                        }
                                    } catch (Exception ignored) {
                                        def cal3 = java.util.Calendar.getInstance()
                                        cal3.add(java.util.Calendar.DAY_OF_MONTH, 1)
                                        dueD = jiraFmtYmd(cal3)
                                    }
                                }

                                def jiraLabels = ["jenkins-build-${env.BUILD_NUMBER}"]
                                def extraLabs = env.JIRA_EXTRA_LABELS?.trim()
                                if (extraLabs) {
                                    extraLabs.split(',').each { seg ->
                                        def t = seg.trim()
                                        if (t) {
                                            jiraLabels << t
                                        }
                                    }
                                }

                                def jiraFields = [
                                    project: [key: jProject],
                                    summary: jiraTitle,
                                    description: desc,
                                    issuetype: [id: jIssueType],
                                    labels: jiraLabels,
                                    duedate: dueD
                                ]
                                if (env.JIRA_ASSIGNEE_ACCOUNT_ID?.trim()) {
                                    jiraFields.assignee = [accountId: env.JIRA_ASSIGNEE_ACCOUNT_ID.trim()]
                                }
                                def startCf = env.JIRA_START_DATE_CUSTOM_FIELD?.trim()
                                if (startCf) {
                                    jiraFields[startCf] = startD
                                }
                                def teamCf = env.JIRA_TEAM_CUSTOM_FIELD?.trim()
                                def teamVal = env.JIRA_TEAM_VALUE?.trim()
                                if (teamCf && teamVal) {
                                    jiraFields[teamCf] = [value: teamVal]
                                }

                                def jiraBody = [fields: jiraFields]
 writeFile file: 'jira_issue_bug.json', text: groovy.json.JsonOutput.toJson(jiraBody)
 bat """
@echo off
curl -s -S -u "%JIRA_EMAIL%:%JIRA_API_TOKEN%" -H "Content-Type: application/json" --data-binary @jira_issue_bug.json -o jira_create_response.json "%JIRA_HOST%/rest/api/2/issue"
"""
                                if (fileExists('jira_create_response.json')) {
                                    try {
                                        def jiraPs = """\$ErrorActionPreference = 'SilentlyContinue'
\$p = Join-Path \$env:WORKSPACE 'jira_create_response.json'
if (-not (Test-Path -LiteralPath \$p)) { exit 0 }
try {
  \$j = Get-Content -Raw -LiteralPath \$p -Encoding UTF8 | ConvertFrom-Json
  if (\$j.key) { Write-Output ('JIRA_KEY=' + \$j.key) }
} catch { }
"""
                                        def jout = powershell(returnStdout: true, script: jiraPs)?.trim() ?: ''
                                        def jm = (jout =~ /JIRA_KEY=(.+)/)
                                        if (jm) {
                                            def key1 = jm[0][1]?.trim()
                                            def u1 = key1 ? "${jHost}/browse/${key1}".toString() : ''
                                            if (u1 && (u1 ==~ jiraBrowseRe)) {
                                                jiraBrowseUrl = u1
                                            }
                                        }
                                    } catch (Exception ignore) { }
                                }
                                if (bugStems.size() > 1) {
                                    bugStems.drop(1).eachWithIndex { stem, jix ->
                                        def sumRelStem = "reports/AI/bug/summary/${stem}_ai_summary.json"
                                        def anaRelStem = "reports/AI/bug/analysis/${stem}_ai_rca.json"
                                        def sumCandidates = []
                                        sumCandidates << sumRelStem
                                        if (ws) {
                                            sumCandidates << "${ws}/${sumRelStem}".replace('\\', '/')
                                        }
                                        if (proj) {
                                            sumCandidates << "${proj}/${sumRelStem}".replace('\\', '/')
                                        }
                                        sumCandidates = sumCandidates.unique().findAll { it }
                                        def sumPath = sumCandidates.find { it && fileExists(it) }
                                        def anaCandidates = []
                                        anaCandidates << anaRelStem
                                        if (ws) {
                                            anaCandidates << "${ws}/${anaRelStem}".replace('\\', '/')
                                        }
                                        if (proj) {
                                            anaCandidates << "${proj}/${anaRelStem}".replace('\\', '/')
                                        }
                                        anaCandidates = anaCandidates.unique().findAll { it }
                                        def anaPath = anaCandidates.find { it && fileExists(it) }
                                        def mcpIdx = bugStems.indexOf(stem)
                                        if (mcpIdx < 0) {
                                            mcpIdx = jix + 1
                                        }
                                        def mcpRespRel = "mcp_response_${mcpIdx}.json"
                                        def exSum = ''
                                        def exBug = ''
                                        def exExp = ''
                                        def exAct = ''
                                        def exImp = ''
                                        def exRca = ''
                                        def exConf = '95'
                                        def exTid = ''
                                        def exTcId = ''
                                        def fbDataEx = ''
                                        // Prefer *_ai_rca.json first (superset + same fields as summary); then overlay *_ai_summary.json.
                                        try {
                                            if (anaPath) {
                                                def anaRaw = stripBom(readFile(encoding: 'UTF-8', file: anaPath))
                                                def ja = pnParseJsonText(anaRaw)
                                                if (ja == null) {
 echo " WARN Jira extra stem=${stem} anaPath parse failed (use pnParseJsonText): ${anaPath}"
                                                } else {
                                                fbDataEx = (ja.test_data ?: '').toString()
                                                exSum = (ja.summary ?: '').toString()
                                                exBug = (ja.bug_description ?: ja.summary ?: '').toString()
                                                exExp = (ja.expected != null) ? ja.expected.toString() : ''
                                                exAct = (ja.actual != null) ? ja.actual.toString() : ''
                                                exImp = (ja.impact ?: '').toString()
                                                exRca = (ja.root_cause_analysis_jira ?: ja.root_cause ?: '').toString().trim()
                                                if (ja.confidence != null) {
                                                    exConf = ja.confidence.toString()
                                                }
                                                exTid = (ja.test_id ?: '').toString()
                                                }
                                            }
                                        } catch (Exception eAna) {
 echo " WARN Jira extra stem=${stem} anaPath=${anaPath ?: 'MISSING'}: ${eAna.message ?: eAna.toString()}"
                                        }
                                        try {
                                            if (sumPath) {
                                                def sumRaw = stripBom(readFile(encoding: 'UTF-8', file: sumPath))
                                                def jsum = pnParseJsonText(sumRaw)
                                                if (jsum == null) {
 echo " WARN Jira extra stem=${stem} sumPath parse failed (use pnParseJsonText): ${sumPath}"
                                                } else {
                                                if (!exSum?.trim() && jsum.summary != null) {
                                                    exSum = jsum.summary.toString()
                                                }
                                                if (!exBug?.trim() && jsum.bug_description != null) {
                                                    exBug = jsum.bug_description.toString()
                                                }
                                                if (!exExp?.trim() && jsum.expected != null) {
                                                    exExp = jsum.expected.toString()
                                                }
                                                if ((exAct == null || exAct.toString().trim() == '') && jsum.actual != null) {
                                                    exAct = jsum.actual.toString()
                                                }
                                                if (!exImp?.trim() && jsum.impact != null) {
                                                    exImp = jsum.impact.toString()
                                                }
                                                if (!exRca?.trim() && jsum.root_cause_analysis_jira != null) {
                                                    exRca = jsum.root_cause_analysis_jira.toString().trim()
                                                }
                                                if (jsum.confidence != null) {
                                                    exConf = jsum.confidence.toString()
                                                }
                                                if (!exTid?.trim() && jsum.test_id != null) {
                                                    exTid = jsum.test_id.toString()
                                                }
                                                }
                                            }
                                        } catch (Exception eSum) {
 echo " WARN Jira extra stem=${stem} sumPath=${sumPath ?: 'MISSING'}: ${eSum.message ?: eSum.toString()}"
                                        }
                                        try {
                                            def fbRelRun = runId ? "reports/failure/${runId}_failure_bundle.json" : ''
                                            def fbCandRun = []
                                            if (fbRelRun) {
                                                fbCandRun << fbRelRun
                                                if (ws) {
                                                    fbCandRun << "${ws}/${fbRelRun}".replace('\\', '/')
                                                }
                                                if (proj) {
                                                    fbCandRun << "${proj}/${fbRelRun}".replace('\\', '/')
                                                }
                                            }
                                            fbCandRun = fbCandRun.unique().findAll { it }
                                            def fbPathRun = fbCandRun.find { it && fileExists(it) }
                                            def fbJ = null
                                            if (fbPathRun) {
                                                try {
                                                    def fbRaw = stripBom(readFile(encoding: 'UTF-8', file: fbPathRun))
                                                    fbJ = pnParseJsonText(fbRaw)
                                                } catch (Exception ignoreFbRead) { }
                                                if (fbJ == null) {
 echo " WARN Jira extra stem=${stem} failure bundle parse failed: ${fbPathRun}"
                                                }
                                            }
                                            def failIdx = pnFailureIndexFromBugStem(stem, runId, fbJ?.failures)
                                            if (fbPathRun && failIdx >= 0 && fbJ != null) {
                                                def fl = fbJ.failures
                                                if (fl instanceof List && failIdx < fl.size()) {
                                                    def ent = fl[failIdx]
                                                    def meta = ent?.meta
                                                    def err = ent?.error
                                                    if (!exTid?.trim() && meta?.test_id != null) {
                                                        exTid = meta.test_id.toString()
                                                    }
                                                    if (!exTcId?.trim() && err?.tc_id != null) {
                                                        def tx = err.tc_id.toString().trim()
                                                        if (tx) {
                                                            exTcId = tx
                                                        }
                                                    }
                                                    if (!fbDataEx?.trim() && err?.test_data != null) {
                                                        fbDataEx = err.test_data.toString()
                                                    }
                                                    if (!exExp?.trim() && err?.expected != null) {
                                                        exExp = err.expected.toString()
                                                    }
                                                    if ((exAct == null || exAct.toString().trim() == '') && err?.actual != null) {
                                                        exAct = err.actual.toString()
                                                    }
                                                    if (!exSum?.trim() && err?.exception_message != null) {
                                                        def em = err.exception_message.toString().trim()
                                                        def one = em.split(/\r?\n/).find { it?.trim() }
                                                        exSum = (one ?: em).take(200)
                                                    }
                                                    if (!exBug?.trim() && err?.exception_message != null) {
                                                        exBug = err.exception_message.toString().take(1200)
                                                    }
                                                }
                                            }
                                        } catch (Exception eFb) {
 echo " WARN Jira extra stem=${stem} failure bundle fallback: ${eFb.message}"
                                        }
                                        try {
                                            def mcpCand = [mcpRespRel]
                                            if (ws) {
                                                mcpCand << "${ws}/${mcpRespRel}".replace('\\', '/')
                                            }
                                            if (proj) {
                                                mcpCand << "${proj}/${mcpRespRel}".replace('\\', '/')
                                            }
                                            mcpCand = mcpCand.unique().findAll { it }
                                            def mcpPath = mcpCand.find { it && fileExists(it) }
                                            if (mcpPath) {
                                                def mcpRaw = stripBom(readFile(encoding: 'UTF-8', file: mcpPath))
                                                def mr = pnParseJsonText(mcpRaw)
                                                if (mr == null) {
 echo " Jira extra stem=${stem}: mcp_response_${mcpIdx}.json parse failed (pnParseJsonText)"
                                                } else if (mr.error) {
 echo " Jira extra stem=${stem}: mcp_response_${mcpIdx} API error: ${mr.error}"
                                                } else {
                                                    if (!exSum?.trim() && mr.summary != null) {
                                                        exSum = mr.summary.toString().trim()
                                                    }
                                                    if (!exBug?.trim() && mr.bug_description != null) {
                                                        exBug = mr.bug_description.toString().trim()
                                                    }
                                                    if (!exExp?.trim() && mr.expected != null) {
                                                        exExp = mr.expected.toString()
                                                    }
                                                    if ((exAct == null || exAct.toString().trim() == '') && mr.actual != null) {
                                                        exAct = mr.actual.toString()
                                                    }
                                                    if (!exImp?.trim() && mr.impact != null) {
                                                        exImp = mr.impact.toString().trim()
                                                    }
                                                    if (!exRca?.trim()) {
                                                        def rx = (mr.root_cause_analysis_jira ?: mr.root_cause ?: '').toString().trim()
                                                        if (rx) {
                                                            exRca = rx
                                                        }
                                                    }
                                                    if (mr.confidence != null) {
                                                        exConf = mr.confidence.toString()
                                                    }
                                                    if (!exTid?.trim() && mr.test_id != null) {
                                                        exTid = mr.test_id.toString()
                                                    }
                                                }
                                            } else {
 echo " Jira extra stem=${stem}: mcp_response_${mcpIdx}.json not found (paths: ${mcpCand.join(' | ')})"
                                            }
                                        } catch (Exception emcp) {
 echo " Jira extra stem=${stem}: mcp_response_${mcpIdx} parse: ${emcp.message}"
                                        }
 echo " Jira extra stem=${stem} mcpIdx=${mcpIdx} sumPath=${sumPath ?: 'MISSING'} anaPath=${anaPath ?: 'MISSING'} exSumLen=${exSum?.length() ?: 0}"
                                        if (!exBug?.trim() && exSum?.trim()) {
                                            exBug = exSum.trim()
                                        }
                                        if (!exBug?.trim()) {
                                            exBug = '(Run MCP / analyze-failure to populate; see AI summary JSON.)'
                                        }
                                        if (!exRca) {
                                            exRca = '(No root cause hypothesis available, or MCP did not run — see AI report.)'
                                        } else {
                                            exRca = exRca.replaceAll(/^-\s*\(\d+%\)\s*/, '').trim()
                                        }
                                        def featEx = ''
                                        def methEx = ''
                                        if (exTid) {
                                            def mmx = (exTid =~ /testCases\.(\w+)#([^\[]+)/)
                                            if (mmx.find()) {
                                                featEx = mmx.group(1)
                                                methEx = mmx.group(2)
                                            }
                                        }
                                        def tcmEx = (featEx?.trim() && methEx?.trim())
                                                ? "${featEx}.${methEx}"
                                                : (exTid ?: '—')
                                        def expDispEx = exExp?.trim() ? "\"${exExp}\"" : '—'
                                        def actDispEx = (exAct != null && exAct.toString().trim() != '') ? "\"${exAct}\"" : '"" (empty string)'
                                        def aiReportStemUrl = "${artBase}reports/AI/bug/report/${stem}_ai_report.html"
                                        def attPartsEx = []
                                        if (screenshotUrl) {
                                            attPartsEx << "[Screenshot|${screenshotUrl}]"
                                        }
                                        if (testLogUrl) {
                                            attPartsEx << "[Logs|${testLogUrl}]"
                                        }
                                        attPartsEx << "[AI Report|${aiReportStemUrl}]"
                                        def attachmentLineEx = attPartsEx ? attPartsEx.join(' | ') : '_Screenshot | Logs | AI Report — links unavailable; see Jenkins artifacts._'
                                        def hasAiNarrative = exSum?.trim() || (exBug?.trim() && !exBug.startsWith('(Run MCP')) || exRca?.trim() || exImp?.trim()
                                        def hasExpAct = exExp?.trim() ? true : ((exAct != null) && exAct.toString().trim() != '')
                                        // Do not use sumPath/anaPath alone — files can exist while parse yields empty fields (then prefer link-only template).
                                        def useRichExtraDesc = hasAiNarrative || hasExpAct
                                        def exSumOne = (exSum?.trim())
                                                ? exSum.trim().replaceAll(/\r?\n/, ' ').replaceAll(/\s+/, ' ').trim()
                                                : "${tcmEx == '—' ? 'Test.case' : tcmEx} — validation / assertion mismatch"
                                        def stemBracket = "[${stem}]"
                                        def jd = useRichExtraDesc ? """${stemBracket} ${exSumOne}
---

*Summary*:
${exBug}

*Expected*: ${expDispEx}
*Actual*: ${actDispEx}

*Root Cause (${exConf}% confidence)*:
${exRca}

*Impact*:
${exImp?.trim() ? exImp : 'N/A'}

*Repro*:
1. Open ${baseUrlJira} → navigate to the flow under test: *${tcmEx}* (`${exTid ?: '—'}`) — see failure bundle for full test id.
2. Use the test data / parameters recorded for this BUG${fbDataEx ? ": ${fbDataEx.take(400)}" : ' (see failure bundle).'}
3. Repeat until the failure matches *Actual* vs *Expected* above (same assertion / validation as the automated test).

*Test Info*:
${testTypeLabel} | ${tcmEx} | ${exTcId?.trim() ? exTcId.trim() : '—'} | ${browser}/${osname} | Build #${env.BUILD_NUMBER}

*Bug ID (artifact)*: ${stem}

*Attachments*: ${attachmentLineEx}
----
*AI assisted (${aiModelFooter})*
""".toString() : """${stemBracket} ${stem} (${runId})
---

*Bug ID (artifact)*: ${stem}

*AI summary JSON:* [${artBase}reports/AI/bug/summary/${stem}_ai_summary.json|link]
*AI analysis JSON:* [${artBase}reports/AI/bug/analysis/${stem}_ai_rca.json|link]
*AI report HTML:* [${artBase}reports/AI/bug/report/${stem}_ai_report.html|link]
*Failure bundle:* [${failureBundleArt}|link]
----
*AI assisted (${aiModelFooter})*
"""
                                        def jt = (useRichExtraDesc ? "${stemBracket} ${exSumOne}" : "${stemBracket} ${stem} (${runId})")
                                        if (jt.length() > 255) {
                                            jt = jt.substring(0, 255)
                                        }
                                        def jf2 = [
                                            project: [key: jProject],
                                            summary: jt,
                                            description: jd,
                                            issuetype: [id: jIssueType],
                                            labels: jiraLabels,
                                            duedate: dueD
                                        ]
                                        if (env.JIRA_ASSIGNEE_ACCOUNT_ID?.trim()) {
                                            jf2.assignee = [accountId: env.JIRA_ASSIGNEE_ACCOUNT_ID.trim()]
                                        }
                                        if (startCf) {
                                            jf2[startCf] = startD
                                        }
                                        if (teamCf && teamVal) {
                                            jf2[teamCf] = [value: teamVal]
                                        }
                                        writeFile file: "jira_issue_bug_extra_${jix}.json", text: groovy.json.JsonOutput.toJson([fields: jf2])
 bat """
@echo off
curl -s -S -u "%JIRA_EMAIL%:%JIRA_API_TOKEN%" -H "Content-Type: application/json" --data-binary @jira_issue_bug_extra_${jix}.json -o jira_create_extra_${jix}.json "%JIRA_HOST%/rest/api/2/issue"
"""
                                        if (fileExists("jira_create_extra_${jix}.json")) {
                                            try {
                                                def jiraPs2 = """\$ErrorActionPreference = 'SilentlyContinue'
\$p = Join-Path \$env:WORKSPACE 'jira_create_extra_${jix}.json'
if (-not (Test-Path -LiteralPath \$p)) { exit 0 }
try {
  \$j = Get-Content -Raw -LiteralPath \$p -Encoding UTF8 | ConvertFrom-Json
  if (\$j.key) { Write-Output ('JIRA_KEY=' + \$j.key) }
} catch { }
"""
                                                def jout2 = powershell(returnStdout: true, script: jiraPs2)?.trim() ?: ''
                                                def jm2 = (jout2 =~ /JIRA_KEY=(.+)/)
                                                if (jm2) {
                                                    def key2 = jm2[0][1]?.trim()
                                                    def u2 = key2 ? "${jHost}/browse/${key2}".toString() : ''
                                                    if (u2 && (u2 ==~ jiraBrowseRe)) {
                                                        jiraExtraUrls = jiraExtraUrls ? (jiraExtraUrls + '\n' + u2) : u2
                                                    }
                                                }
                                            } catch (Exception ignore) { }
                                        }
                                    }
                                }
                                // Normalize/validate to prevent blank lines or non-URLs propagating into Slack mapping.
                                def extraList = []
                                if (jiraExtraUrls?.trim()) {
                                    extraList = jiraExtraUrls
                                        .split('\n')
                                        .collect { it?.trim() }
                                        .findAll { it && (it ==~ jiraBrowseRe) }
                                        .unique()
                                }
                                jiraExtraUrls = extraList ? extraList.join('\n') : ''
                                if (!(jiraBrowseUrl?.trim() && (jiraBrowseUrl.trim() ==~ jiraBrowseRe))) {
                                    jiraBrowseUrl = ''
                                }
 echo " Jira BUG issue attempted: ${jiraBrowseUrl ?: '(parse response for key)'} ${jiraExtraUrls ? '(+' + (extraList.size()) + ' more)' : ''}"
                            } else {
 echo " Jira env incomplete or JIRA_HOST invalid — skipping BUG Jira."
                            }
                        } catch (Exception e) {
 echo " Jira BUG step failed: ${e.message}"
                        }
                        ctx.jiraBrowseUrl = jiraBrowseUrl
                        ctx.jiraExtraUrls = jiraExtraUrls
}

def pnSlackNotifications(Map ctx) {
                        def proj = ctx.proj
                        def runId = ctx.runId
                        def bugCur = ctx.bugCur
                        def flakyCur = ctx.flakyCur
                        def needsReviewCur = ctx.needsReviewCur
                        def skipCount = ctx.skipCount
                        def failEntries = ctx.failEntries
                        def methodStats = ctx.methodStats
                        def envLines = ctx.envLines
                        def artBase = ctx.artBase
                        def manifestLines = ctx.manifestLines
                        def aiReportUrl = ctx.aiReportUrl
                        def aiSummaryUrl = ctx.aiSummaryUrl
                        def aiAnalysisUrl = ctx.aiAnalysisUrl
                        def failureBundleArt = ctx.failureBundleArt
                        def triageReportArt = ctx.triageReportArt
                        def skipBundleArt = ctx.skipBundleArt
                        def extentReportUrl = ctx.extentReportUrl
                        def testLogUrl = ctx.testLogUrl
                        def artifactsHubUrl = ctx.artifactsHubUrl ?: ''
                        def canonicalAiId = ctx.canonicalAiId
                        def skipFirstTid = ctx.skipFirstTid
                        def skipFirstTc = ctx.skipFirstTc ?: ''
                        def skipFirstReason = ctx.skipFirstReason
                        def skipFirstMsg = ctx.skipFirstMsg
                        def skipEntries = ctx.skipEntries ?: []
                        def skipOnlySignal = ctx.skipOnlySignal ?: false
                        def greenRun = ctx.greenRun ?: false
                        def testTotal = ctx.testTotal
                        def testPass = ctx.testPass
                        def testFail = ctx.testFail
                        def testSkip = ctx.testSkip
                        def aiSummaryText = ctx.aiSummaryText
                        def aiRootCause = ctx.aiRootCause
                        def aiConfidence = ctx.aiConfidence
                        def aiRcaPrimary = ctx.aiRcaPrimary
                        def aiRootJira = ctx.aiRootJira
                        def aiBugDescription = ctx.aiBugDescription
                        def aiFlakyDescription = ctx.aiFlakyDescription
                        def aiLogRefsJson = ctx.aiLogRefsJson
                        def fbTestId = ctx.fbTestId
                        def fbExpected = ctx.fbExpected
                        def fbActual = ctx.fbActual
                        def fbException = ctx.fbException
                        def jiraBrowseUrl = ctx.jiraBrowseUrl
                        def jiraExtraUrls = ctx.jiraExtraUrls
                        def bugStems = ctx.bugStems ?: []
                        try {
                            def hook = env.SLACK_WEBHOOK_URL?.trim()
                            if (hook) {
                                def rcSlack = (aiRcaPrimary ?: aiRootJira ?: aiRootCause ?: '').trim()
                                def confSuffix = aiConfidence ? " (${aiConfidence}%)" : ''
                                def logRefShort = ''
                                if (aiLogRefsJson) {
                                    logRefShort = aiLogRefsJson.length() > 900 ? (aiLogRefsJson.take(897) + '…') : aiLogRefsJson
                                }
                                def testSummaryLine = (testTotal > 0) ? "Total ${testTotal} | Pass ${testPass} | Fail ${testFail} | Skip ${testSkip}" : (runId ? '_No per-run totals (history empty and no skip/failure bundles for this run) — see Extent / console._' : '_Surefire `TEST-*.xml` not found — see Jenkins test report / console._')
                                def testSumCompact = (testTotal > 0) ? "Total ${testTotal} | Pass ${testPass} | Fail ${testFail} | Skip ${testSkip}" : testSummaryLine
                                def manifestBlock = ''
                                if (manifestLines) {
                                    manifestBlock = manifestLines.collect { line ->
                                        def p = line.split('\\|', 4)
                                        if (p.length < 3) {
                                            return ''
                                        }
                                        def idx = p[0]
                                        def bkt = p[1]
                                        def stem = p[2]?.trim()
                                        def tid = p.length > 3 ? p[3] : ''
                                        if (!stem) {
                                            return ''
                                        }
                                        def su = "${artBase}reports/AI/${bkt}/summary/${stem}_ai_summary.json"
                                        def ru = "${artBase}reports/AI/${bkt}/report/${stem}_ai_report.html"
                                        def au = "${artBase}reports/AI/${bkt}/analysis/${stem}_ai_rca.json"
                                        "[${idx}] *${bkt}* `${tid}`  <${su}|summary> · <${au}|analysis> · <${ru}|report>"
                                    }.findAll { it }.join('\n')
                                }
                                def aiTrip = (aiReportUrl && aiSummaryUrl && aiAnalysisUrl) ? "<${aiReportUrl}|AI Report> | <${aiSummaryUrl}|AI Summary> | <${aiAnalysisUrl}|AI Analysis>" : (manifestBlock ?: '_No AI links for this run._')
                                def artParts = []
                                if (failureBundleArt) {
                                    artParts << "<${failureBundleArt}|Failure Bundle>"
                                }
                                if (triageReportArt) {
                                    artParts << "<${triageReportArt}|Triage>"
                                }
                                if (testLogUrl) {
                                    artParts << "<${testLogUrl}|Test Log>"
                                }
                                def artTrip = artParts ? artParts.join(' | ') : '—'
                                def shotUrlForFe = { fe ->
                                    if (!fe || !fe.shot) {
                                        return ''
                                    }
                                    def shotName = fe.shot.replaceAll(/.*[\/\\]/, '')
                                    return shotName ? "${artBase}screenshots/${shotName}" : ''
                                }
                                def formatTidWithTc = { fe ->
                                    if (!fe) {
                                        return '—'
                                    }
                                    def base = fe.tid ?: '—'
                                    def tc = fe.tcId?.toString()?.trim()
                                    return tc ? "${base} · `${tc}`" : base
                                }
                                def formatSkipTidWithTc = { se ->
                                    if (!se) {
                                        return '—'
                                    }
                                    def base = se.tid ?: '—'
                                    def tc = se.tcId?.toString()?.trim()
                                    return tc ? "${base} · `${tc}`" : base
                                }
                                def formatSkipFirstWithTc = {
                                    def base = skipFirstTid ?: '—'
                                    def tc = skipFirstTc?.toString()?.trim()
                                    return tc ? "${base} · `${tc}`" : base
                                }
                                // Slack mrkdwn: <browseUrl|KAN-010> so the key is clickable (same visible text as plain key).
                                def jiraTicketsSlackLine = {
                                    def jiraKeyRe = /https?:\/\/[^\s]+\/browse\/([A-Z][A-Z0-9]+-\d+)/
                                    def items = []
                                    if (jiraBrowseUrl?.trim()) {
                                        def m = (jiraBrowseUrl =~ jiraKeyRe)
                                        if (m) {
                                            items << [jiraBrowseUrl.trim(), m[0][1]]
                                        }
                                    }
                                    if (jiraExtraUrls?.trim()) {
                                        jiraExtraUrls.split('\n').each { raw ->
                                            def u = raw?.trim()
                                            if (!u) {
                                                return
                                            }
                                            def mm = (u =~ jiraKeyRe)
                                            if (mm) {
                                                items << [u, mm[0][1]]
                                            }
                                        }
                                    }
                                    def seen = []
                                    def parts = []
                                    items.each { pair ->
                                        def ky = pair[1]?.toString()
                                        if (ky && !seen.contains(ky)) {
                                            seen << ky
                                            parts << "<${pair[0]}|${ky}>"
                                        }
                                    }
                                    return parts ? parts.join(' | ') : '_none_'
                                }
                                def appendQaDigest = { StringBuilder sb ->
                                    sb.append('Test Summary\n')
                                    sb.append("${testSumCompact}\n\n")
                                    sb.append('Triage Summary\n')
                                    sb.append("BUG ${bugCur} | FLAKY ${flakyCur} | NEEDS_REVIEW ${needsReviewCur} | SKIP ${testSkip}\n\n")
                                    sb.append('Jira Tickets\n')
                                    sb.append("${jiraTicketsSlackLine()}\n\n")
                                    sb.append('Artifacts\n')
                                    if (extentReportUrl) {
                                        sb.append("<${extentReportUrl}|Test Report>\n")
                                    } else {
                                        sb.append('Test Report\n')
                                    }
                                    if (artifactsHubUrl) {
                                        sb.append("<${artifactsHubUrl}|Run artifacts hub>\n")
                                    } else {
                                        sb.append('Run artifacts hub\n')
                                    }
                                }
                                def expDisp = fbExpected ? "\"${fbExpected}\"" : '—'
                                def actDisp = (fbActual != null && fbActual.toString().trim() != '') ? "\"${fbActual}\"" : '"" (empty)'
                                def failRateLine = { tid ->
                                    def ms = methodStats[tid]
                                    if (!ms) {
                                        return 'Fail rate: — (no triage history)'
                                    }
                                    try {
                                        def r = Double.parseDouble(ms.rate.toString())
                                        def pct = Math.round(r * 100)
                                        return "Fail rate: ${pct}% (${ms.fc}/${ms.tr} runs)"
                                    } catch (Exception ignored) {
                                        return 'Fail rate: —'
                                    }
                                }
                                def failRateShortPct = { tid ->
                                    def ms = methodStats[tid]
                                    if (!ms) {
                                        return 'n/a'
                                    }
                                    try {
                                        def r = Double.parseDouble(ms.rate.toString())
                                        def pct = Math.round(r * 100)
                                        return "${pct}% fail rate"
                                    } catch (Exception ignored) {
                                        return 'n/a'
                                    }
                                }
                                def firstEnvScreenshotUrl = {
                                    if (envLines && !envLines.isEmpty()) {
                                        def el = envLines.find { it && (it.contains('http://') || it.contains('https://')) }
                                        if (el) {
                                            def um = (el =~ /(https?:\/\/\S+)/)
                                            if (um) {
                                                return um[0][1].replaceAll(/[)\]}>.,;]+$/, '')
                                            }
                                        }
                                    }
                                    return ''
                                }
                                def manifestRows = []
                                if (manifestLines) {
                                    manifestLines.each { line ->
                                        def p = line.split('\\|', 4)
                                        if (p.length >= 4 && p[2]?.trim()) {
                                            manifestRows << [bucket: p[1], stem: p[2].trim(), tid: (p.length > 3 ? p[3] : '')]
                                        }
                                    }
                                }
                                def findManifestForTid = { String tid ->
                                    if (!tid) {
                                        return null
                                    }
                                    def r = manifestRows.find { it.tid == tid }
                                    if (r) {
                                        return r
                                    }
                                    return manifestRows.find { tid.startsWith(it.tid) || it.tid.startsWith(tid) }
                                }
                                def readAiSnip = { String bucket, String stem ->
                                    if (!stem) {
                                        return ['', '']
                                    }
                                    def b = bucket
                                    if (b == 'needs_review') {
                                        b = 'needs_review'
                                    }
                                    def rels = ["reports/AI/${b}/summary/${stem}_ai_summary.json"]
                                    if (b == 'needs_review') {
                                        rels << "reports/AI/flaky/summary/${stem}_ai_summary.json"
                                    }
                                    try {
                                        for (rel in rels) {
                                            if (fileExists(rel)) {
                                                def txt = readFile(encoding: 'UTF-8', file: rel)
                                                def j = new groovy.json.JsonSlurper().parseText(txt)
                                                def stripPlaceholder = { String x ->
                                                    if (!x?.trim()) {
                                                        return ''
                                                    }
                                                    def t = x.trim()
                                                    if (t.equalsIgnoreCase('BUG_DESCRIPTION') || t.startsWith('## BUG_DESCRIPTION')) {
                                                        return ''
                                                    }
                                                    return x
                                                }
                                                // reason line: use summary from MCP *_ai_summary.json (short title) as-is
                                                def usedAiSummary = false
                                                def s = ''
                                                if (j.summary != null && j.summary.toString().trim()) {
                                                    def raw = stripPlaceholder(j.summary.toString())
                                                    if (raw?.trim()) {
                                                        s = raw
                                                        usedAiSummary = true
                                                    }
                                                }
                                                if (!s?.trim()) {
                                                    s = (
                                                        j.needs_review_description
                                                        ?: j.flaky_description
                                                        ?: j.bug_description
                                                        ?: j.root_cause_excerpt
                                                        ?: j.root_cause
                                                        ?: ''
                                                    ).toString()
                                                    s = stripPlaceholder(s)
                                                }
                                                s = s.replaceAll(/\r?\n/, ' ').trim()
                                                if (!usedAiSummary && s.length() > 220) {
                                                    s = s.take(217) + '...'
                                                }
                                                def c = j.confidence != null ? j.confidence.toString() : ''
                                                return [s, c]
                                            }
                                        }
                                    } catch (Exception ignore) { }
                                    return ['', '']
                                }
                                def aiLinksLine = { String bucket, String stem ->
                                    if (!stem) {
                                        return ''
                                    }
                                    def b = bucket
                                    if (b == 'needs_review') {
                                        b = 'needs_review'
                                    }
                                    def ru = "${artBase}reports/AI/${b}/report/${stem}_ai_report.html"
                                    def su = "${artBase}reports/AI/${b}/summary/${stem}_ai_summary.json"
                                    def au = "${artBase}reports/AI/${b}/analysis/${stem}_ai_rca.json"
                                    return "<${ru}|AI Report> | <${su}|AI Summary> | <${au}|AI Analysis>"
                                }
                                def confLow = ''
                                if (aiConfidence?.trim()) {
                                    try {
                                        def cm = (aiConfidence =~ /(\d+)/)
                                        if (cm && (cm[0][1] as int) < 80) {
                                            confLow = ' (low)'
                                        }
                                    } catch (Exception ignored) { }
                                }
                                def jiraKeyLine = ''
                                if (jiraBrowseUrl) {
                                    def km = (jiraBrowseUrl =~ /\/browse\/([A-Z][A-Z0-9]+-\d+)/)
                                    if (km) {
                                        jiraKeyLine = "<${jiraBrowseUrl}|${km[0][1]}> auto-created"
                                    } else {
                                        jiraKeyLine = "<${jiraBrowseUrl}|ticket> auto-created"
                                    }
                                } else {
                                    jiraKeyLine = '_Jira not created_'
                                }
                                def headlineParts = []
                                if (bugCur > 0) {
                                    headlineParts << "${bugCur} BUG"
                                }
                                if (flakyCur > 0) {
                                    headlineParts << "${flakyCur} FLAKY"
                                }
                                if (needsReviewCur > 0) {
                                    headlineParts << "${needsReviewCur} REVIEW"
                                }
                                if (skipCount > 0) {
                                    headlineParts << "${skipCount} SKIP"
                                }
                                def activeCats = 0
                                if (bugCur > 0) {
                                    activeCats++
                                }
                                if (flakyCur > 0) {
                                    activeCats++
                                }
                                if (needsReviewCur > 0) {
                                    activeCats++
                                }
                                if (skipCount > 0) {
                                    activeCats++
                                }
                                // Mix layout: multiple categories OR 2+ items in one category (e.g. two BUGs only — same as "Critical Issues" minus unused sections).
                                def mixMultiSameCat = (bugCur >= 2) || (flakyCur >= 2) || (needsReviewCur >= 2) || (skipCount >= 2)
                                // One failed test or one triage BUG should still use the Critical Issues headline (not only when 2+ categories).
                                def hasFailureSignal = (testFail > 0) || (bugCur > 0)
                                def hybridSlack = (activeCats >= 2) || mixMultiSameCat || hasFailureSignal
                                def slackHeadline = hybridSlack ? "${env.JOB_NAME} #${env.BUILD_NUMBER} - Critical Issues \uD83D\uDFE5" : (headlineParts ? "${env.JOB_NAME} #${env.BUILD_NUMBER} (${headlineParts.join(', ')})" : "${env.JOB_NAME} #${env.BUILD_NUMBER}")
                                def slackText = ''
                                if (greenRun) {
                                    def sbOk = new StringBuilder()
                                    sbOk.append("*${env.JOB_NAME}* #${env.BUILD_NUMBER} — *All tests passed* \u2705\n\n")
                                    appendQaDigest(sbOk)
                                    sbOk.append('---\n')
                                    slackText = sbOk.toString()
                                } else if (skipOnlySignal) {
                                    def sbSkip = new StringBuilder()
                                    sbSkip.append("*${env.JOB_NAME}* #${env.BUILD_NUMBER} — *Skipped tests* \u26A0\uFE0F\n\n")
                                    appendQaDigest(sbSkip)
                                    if (skipEntries && !skipEntries.isEmpty()) {
                                        sbSkip.append('*Skip detail:* ')
                                        sbSkip.append(skipEntries.collect { formatSkipTidWithTc(it) }.join(', '))
                                        sbSkip.append('\n')
                                    } else {
                                        sbSkip.append("*Skip detail:* ${formatSkipFirstWithTc()}\n")
                                    }
                                    sbSkip.append('_Build UNSTABLE when Surefire reports skipped tests._\n')
                                    sbSkip.append('---\n')
                                    slackText = sbSkip.toString()
                                } else if (hybridSlack) {
                                    def hb = new StringBuilder()
                                    hb.append("*${env.JOB_NAME}* #${env.BUILD_NUMBER} · *Critical Issues* \uD83D\uDFE5\n\n")
                                    appendQaDigest(hb)
                                    hb.append('---\n')
                                    slackText = hb.toString()
                                } else {
                                    def sb = new StringBuilder()
                                    sb.append("*${env.JOB_NAME}* #${env.BUILD_NUMBER}\n\n")
                                    appendQaDigest(sb)
                                    sb.append('---\n')
                                    slackText = sb.toString()
                                }
                                def buildResultSlack = 'UNKNOWN'
                                try {
                                    buildResultSlack = (currentBuild?.result ?: currentBuild?.currentResult ?: 'UNKNOWN')?.toString()?.trim() ?: 'UNKNOWN'
                                } catch (Exception ignoredBr) { }
                                def consoleUrlSlack = ''
                                try {
                                    def bu = env.BUILD_URL?.trim()
                                    if (bu) {
                                        consoleUrlSlack = bu.replaceAll(/\/+$/, '') + '/console'
                                    }
                                } catch (Exception ignoredCu) { }
                                if (slackText.length() > 2900) {
                                    slackText = slackText.take(2890) + '…(truncated)'
                                }
                                def slackFooterQa = new StringBuilder()
                                slackFooterQa.append("Run ${runId ?: '—'} · ${buildResultSlack}\n")
                                if (consoleUrlSlack) {
                                    slackFooterQa.append("<${consoleUrlSlack}|Console log>\n")
                                } else {
                                    slackFooterQa.append('Console log\n')
                                }
                                slackText = slackText + slackFooterQa.toString()
                                def slackPayload = groovy.json.JsonOutput.toJson([
                                    text: slackHeadline ?: "QA triage: ${env.JOB_NAME} #${env.BUILD_NUMBER} (${runId})",
                                    blocks: [
                                        [type: 'section', text: [type: 'mrkdwn', text: slackText]]
                                    ]
                                ])
 writeFile file: 'slack_qa_triage.json', text: slackPayload
 bat """
@echo off
curl -s -S -X POST -H "Content-Type: application/json" --data-binary @slack_qa_triage.json "%SLACK_WEBHOOK_URL%"
"""
 echo " Slack QA triage POST attempted (BUG/FLAKY/NEEDS_REVIEW/SKIP signal)."
                            } else {
 echo " SLACK_WEBHOOK_URL not set — skipping QA Slack."
                            }
                        } catch (Exception e) {
 echo " Slack QA step failed: ${e.message}"
                        }
}

def pnPostNotifyCleanup(Map ctx) {
                // Stop MCP after notifications (files already written during pipeline)
 try {
                    powershell """
                        Write-Host 'Stopping MCP Orchestrator...'
                        Get-CimInstance Win32_Process -Filter 'Name = ''python.exe''' -ErrorAction SilentlyContinue | ForEach-Object {
                            if (\$_.CommandLine -like '*mcp_orchestrator.py*') {
                                Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue
                            }
                        }
                        Get-Process pythonw -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
                        Write-Host 'Cleanup complete'
                        exit 0
                    """
                } catch (Exception e) {
 echo " Cleanup warning: ${e.message}"
                }

 try {
 archiveArtifacts artifacts: 'reports/**/*,logs/**/*,screenshots/**/*,mcp_*.json,mcp_manifest.txt,jira_*.json,slack_*.json,jenkins_notify_ai.txt,jenkins_ai_*.txt,jenkins_fb_*.txt', allowEmptyArchive: true
                } catch (Exception e) {
 echo " Artifact archiving failed: ${e.message}"
                }

}
return this
