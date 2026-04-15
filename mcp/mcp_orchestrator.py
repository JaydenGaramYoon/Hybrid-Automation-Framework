"""
MCP Orchestrator: Receives test notifications from Jenkins
- Routes:
  POST /api/test-notification - Receive test result from Jenkins
  GET /health - Health check
  
Flow:
  Jenkins (Triage) → MCP (Ollama LLM) → Jira Issue Creation
  
Note: Slack notifications are handled by Jenkins, not MCP.
MCP focuses on AI-powered Jira issue generation for classified bugs.
"""

from flask import Flask, request, jsonify
import requests
import json
import os
from datetime import datetime
from dotenv import load_dotenv
import base64
import anthropic
import sys
import re

# Repo root (parent of this package directory mcp/)
_PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
LOG_FILE = os.path.join(_PROJECT_ROOT, "logs", "mcp", "mcp_api_debug.log")


def setup_logging():
    """Open or append the MCP debug log file; returns a writable stream or None."""
    global LOG_FILE
    try:
        os.makedirs(os.path.dirname(LOG_FILE), exist_ok=True)
        return open(LOG_FILE, "a", encoding="utf-8", buffering=1)
    except OSError:
        return None


log_stream = setup_logging()


def log_both(msg):
    """Write the same message to stdout and the log file."""
    print(msg, flush=True)
    if log_stream:
        log_stream.write(msg + "\n")
        log_stream.flush()


# Load environment variables from project root
load_dotenv(os.path.join(_PROJECT_ROOT, ".env"))

app = Flask(__name__)

# Configuration
SLACK_WEBHOOK_URL = os.getenv('SLACK_WEBHOOK_URL')
JENKINS_URL = os.getenv('JENKINS_URL', 'http://localhost:8080')

# Jira Configuration
JIRA_HOST = os.getenv('JIRA_HOST')
JIRA_PROJECT_KEY = os.getenv('JIRA_PROJECT_KEY')
JIRA_EMAIL = os.getenv('JIRA_EMAIL')
JIRA_API_TOKEN = os.getenv('JIRA_API_TOKEN')
JIRA_ISSUE_TYPE = os.getenv('JIRA_ISSUE_TYPE', 'Bug')

# Ollama Configuration (Legacy)
OLLAMA_BASE_URL = os.getenv('OLLAMA_BASE_URL', 'http://localhost:11434')
OLLAMA_MODEL = os.getenv('OLLAMA_MODEL', 'mistral')

# Claude Configuration (NEW - RECOMMENDED)
def _normalize_claude_key(raw):
    if not raw:
        return None
    s = raw.strip()
    if s.startswith("\ufeff"):
        s = s.lstrip("\ufeff")
    return s or None


CLAUDE_API_KEY = _normalize_claude_key(os.getenv("CLAUDE_API_KEY"))
CLAUDE_MODEL = os.getenv("CLAUDE_MODEL", "claude-opus-4-1")
CLAUDE_INIT_ERROR = None

# Initialize Claude client
claude_client = None
if CLAUDE_API_KEY:
    try:
        claude_client = anthropic.Anthropic(api_key=CLAUDE_API_KEY)
        if not hasattr(claude_client, "messages"):
            CLAUDE_INIT_ERROR = (
                "anthropic SDK has no .messages API (need anthropic>=0.40). Run: pip install -r mcp/requirements.txt"
            )
            print(f"❌ {CLAUDE_INIT_ERROR}")
            try:
                with open(LOG_FILE, "a", encoding="utf-8") as dbg:
                    dbg.write(f"\n[CLAUDE_INIT_FAILURE] {CLAUDE_INIT_ERROR}\n")
            except Exception:
                pass
            claude_client = None
        else:
            print(f"✅ Claude client ready (model={CLAUDE_MODEL}, anthropic={anthropic.__version__})")
    except Exception as e:
        import traceback

        CLAUDE_INIT_ERROR = f"{type(e).__name__}: {e}"
        print(f"❌ Failed to initialize Claude client: {CLAUDE_INIT_ERROR}")
        traceback.print_exc()
        try:
            with open(LOG_FILE, "a", encoding="utf-8") as dbg:
                dbg.write(f"\n[CLAUDE_INIT_FAILURE] {CLAUDE_INIT_ERROR}\n")
                dbg.write(traceback.format_exc())
                if "proxies" in str(e).lower():
                    dbg.write(
                        "\nHint: old anthropic + httpx 0.28 — upgrade anthropic (mcp/requirements.txt) or pin httpx<0.28.\n"
                    )
        except Exception:
            pass
        claude_client = None
else:
    print("❌ CLAUDE_API_KEY is not set")


def get_jira_auth_header():
    """Generate Jira Basic Auth header"""
    credentials = f"{JIRA_EMAIL}:{JIRA_API_TOKEN}"
    encoded = base64.b64encode(credentials.encode()).decode()
    return {"Authorization": f"Basic {encoded}", "Content-Type": "application/json"}


@app.route('/api/test-notification', methods=['POST'])
def receive_test_notification():
    """
    Receive test notification from Jenkins with triage analysis
    Flow: Jenkins (with triage) → LLM → Jira Issue Creation
    
    Only creates Jira issues for classified BUG failures.
    Triage classification determines whether issue creation is needed.
    """
    try:
        data = request.get_json()
        
        build_status = data.get('buildStatus', 'UNKNOWN')
        build_number = data.get('buildNumber', 'N/A')
        job_name = data.get('jobName', 'Unknown Job')
        build_url = data.get('buildUrl', '')
        artifact_url = data.get('artifactUrl', '')
        tests_run = data.get('testsRun', '0')
        tests_skipped = data.get('testsSkipped', '0')
        tests_failed = data.get('testsFailed', '0')
        run_id = data.get('runId', '')
        triage_report_path = data.get('triageReportPath', '')
        skip_bundle_path = data.get('skipBundlePath', '')
        triage_classification = data.get('triageClassification', {})
        failed_tests = data.get('failedTests', [])
        error_message = data.get('errorMessage', '')
        
        jira_issue_key = None
        jira_issue_url = None
        
        # Create Jira issues only when triage reports BUG-class failures
        if build_status == 'FAILURE' and triage_classification.get('BUG', 0) > 0:
            print(f"🔄 Creating Jira issue from LLM response...")
            
            # Step 1: Generate Jira description using LLM
            jira_description = generate_jira_description_from_llm(
                job_name=job_name,
                build_number=build_number,
                run_id=run_id,
                build_url=build_url,
                failed_tests=failed_tests,
                error_message=error_message,
                triage_classification=triage_classification,
                test_stats={
                    'total': tests_run,
                    'failed': tests_failed,
                    'skipped': tests_skipped
                }
            )
            
            # Step 2: Create Jira issue
            jira_response = create_jira_issue(
                summary=f"Test Failure: {job_name} #{build_number}",
                description=jira_description,
                build_url=build_url
            )
            
            if jira_response:
                jira_issue_key = jira_response.get('key')
                jira_issue_url = f"{JIRA_HOST}/browse/{jira_issue_key}"
                print(f"✅ Jira issue created: {jira_issue_url}")
                return jsonify({
                    "status": "success",
                    "message": "Jira issue created",
                    "jira_issue_key": jira_issue_key,
                    "jira_issue_url": jira_issue_url
                }), 201
            else:
                return jsonify({"status": "error", "message": "Failed to create Jira issue"}), 500
        else:
            print(f"⚠️ No bugs classified in triage - skipping Jira issue creation")
            return jsonify({
                "status": "success",
                "message": "No Jira issue needed (no bugs classified)",
                "jira_issue_key": None
            }), 200
            
    except Exception as e:
        print(f"❌ Error processing notification: {str(e)}")
        import traceback
        traceback.print_exc()
        return jsonify({"status": "error", "message": str(e)}), 500


def generate_jira_description_from_llm(job_name, build_number, run_id, build_url, failed_tests, error_message, triage_classification, test_stats):
    """
    Use Ollama LLM to generate Jira issue description
    """
    try:
        # Prepare test information
        test_list = "\n".join([f"- {test}" for test in failed_tests[:5]])  # Top 5 tests
        
        # Build LLM prompt
        prompt = f"""You are a test failure analysis AI. Generate a Jira issue description in Markdown format.

## Input Data:
- Job: {job_name}
- Build: #{build_number}
- Run ID: {run_id}
- Build URL: {build_url}
- Failed Tests: {test_list}
- Error: {error_message}
- Triage: BUG={triage_classification.get('BUG', 0)}, FLAKY={triage_classification.get('FLAKY', 0)}
- Stats: {test_stats['failed']}/{test_stats['total']} failed

Generate ONLY the Jira description in this exact Markdown format:

## Summary
One-line summary of the issue.

## Environment
- Build: #{build_number}
- URL: {build_url}

## Steps to Reproduce
1. Run {job_name}
2. See test failures

## Expected Result
All tests should pass

## Actual Result
{test_stats['failed']} tests failed

## Error Details
{error_message}

## Failed Tests
{test_list}

## Root Cause Analysis
(Brief analysis based on triage)

Do NOT include any text outside this structure."""

        print(f"🔄 Calling Ollama for LLM response (model: {OLLAMA_MODEL})...")
        
        # Call Ollama locally via HTTP
        response = requests.post(
            f"{OLLAMA_BASE_URL}/api/generate",
            json={
                "model": OLLAMA_MODEL,
                "prompt": prompt,
                "stream": False
            },
            timeout=120
        )
        
        if response.status_code == 200:
            llm_response = response.json()
            description = llm_response.get('response', '').strip()
            print(f"✅ LLM response received ({len(description)} chars)")
            return description
        else:
            print(f"⚠️ LLM call failed: {response.text}")
            # Fallback to simple description
            return f"""## Summary
Test failure in {job_name} (Build #{build_number})

## Environment
- Build: #{build_number}
- URL: {build_url}

## Failed Tests
{chr(10).join(failed_tests[:5])}

## Error Details
{error_message}"""
            
    except Exception as e:
        print(f"❌ LLM generation error: {str(e)}")
        # Fallback description
        return f"Test failure in {job_name}\n\nError: {error_message}"


def create_jira_issue(summary, description, build_url):
    """
    Create a Jira issue via REST API
    """
    try:
        if not all([JIRA_HOST, JIRA_PROJECT_KEY, JIRA_EMAIL, JIRA_API_TOKEN]):
            print("⚠️ Jira credentials not configured, skipping issue creation")
            return None
        
        url = f"{JIRA_HOST}/rest/api/3/issues"
        
        payload = {
            "fields": {
                "project": {"key": JIRA_PROJECT_KEY},
                "issuetype": {"name": JIRA_ISSUE_TYPE},
                "summary": summary[:255],  # Jira has 255 char limit
                "description": {
                    "type": "doc",
                    "version": 1,
                    "content": [
                        {
                            "type": "paragraph",
                            "content": [
                                {
                                    "type": "text",
                                    "text": description
                                }
                            ]
                        }
                    ]
                },
                "customfield_10001": build_url  # Add build URL if custom field exists
            }
        }
        
        headers = get_jira_auth_header()
        response = requests.post(url, json=payload, headers=headers, timeout=30)
        
        if response.status_code in [200, 201]:
            issue_data = response.json()
            return issue_data
        else:
            print(f"❌ Jira issue creation failed: {response.status_code} - {response.text}")
            return None
            
    except Exception as e:
        print(f"❌ Jira API error: {str(e)}")
        return None


# Instructs Claude to return fields used by Jira BUG tickets (see Jenkinsfile).
# SUMMARY: one noun-phrase line (~sentence length), concise English title style — rules below + normalize_ai_summary_noun_phrase().
JIRA_AI_JSON_SUFFIX = """
---
Respond with ONLY one valid JSON object (no markdown code fences, no text before or after).
Use English for all string values.

SUMMARY field (strict — different from other keys):
- Exactly one short line; max ~90 characters; title-style noun phrase only.
- No full clauses like "The test failed because..." / "We see that..." — use a compact label
  (e.g. "Incorrect order confirmation copy", "Missing password validation message").
- No trailing period; no semicolons; not multiple sentences.

Never echo field names as values: do not use the literal text "BUG_DESCRIPTION", "## BUG_DESCRIPTION",
"SUMMARY", or markdown headings. For BUG_DESCRIPTION, IMPACT, ROOT_CAUSE, etc., write real prose sentences.
SUMMARY is the exception: noun-phrase title line only.

Required keys:
- "SUMMARY": One-line noun-phrase title, e.g. "Missing password validation message"
- "BUG_DESCRIPTION": 3-8 sentences for developers: what is wrong, where in the flow, and why the test failed
- "IMPACT": How this defect could affect end users or the business if released (registration, security perception, etc.)
- "CONFIDENCE": Integer 0-100 for your single best overall hypothesis
- "ROOT_CAUSE": Detailed narrative (optional but recommended)
- "ROOT_CAUSE_CANDIDATES": JSON array of objects. Each object MUST have:
    - "analysis": string (one distinct hypothesis)
    - "probability_percent": integer 0-100
  Provide at least 2 candidates when possible. Assign realistic probabilities; they should not all be 100.

Root cause candidates (all probability levels) are used for Jira "Root Cause Analysis" when present. Assign realistic probabilities; the strongest hypothesis is listed first in the Jira field.

Do not use markdown heading syntax (#, ##, ###) inside any JSON string — write plain titles and sentences only.
"""

# Flaky / intermittent failure — QA Slack; artifacts under reports/AI/flaky/
FLAKY_AI_JSON_SUFFIX = """
---
Respond with ONLY one valid JSON object (no markdown code fences, no text before or after).
Use English for all string values.

SUMMARY: one line, max ~90 characters, English noun phrase / title-style label only (no full sentences,
no trailing period). Same rules as BUG pipeline SUMMARY.

The failure is classified FLAKY (or NEEDS_REVIEW): the test is intermittently failing or needs human review.
Use the failure bundle, triage JSON, consolidated history, and the RUN LOG EXCERPT below to explain why this looks flaky
(unstable environment, timing, prior pass/fail pattern, etc.), not a deterministic product defect.

Required keys:
- "SUMMARY": Short noun phrase, e.g. "Intermittent validation message capture"
- "FLAKY_DESCRIPTION": 4-10 sentences: why this failure pattern suggests flakiness vs a stable bug; reference history/triage when relevant
- "IMPACT": How intermittent failures hurt CI signal, release confidence, and team velocity (system / process quality)
- "CONFIDENCE": Integer 0-100 for your assessment that this is primarily flakiness (not that the app is correct)
- "ROOT_CAUSE": Narrative: likely environmental, timing, or test-stability causes
- "ROOT_CAUSE_CANDIDATES": array of { "analysis": string, "probability_percent": int } with at least 2 items when possible
- "LOG_LINE_REFERENCES": array of objects (may be empty if log excerpt is insufficient). Each object MUST have:
    - "log_file": string (basename or path)
    - "line_number": integer (approximate line in the excerpt if exact unknown)
    - "excerpt": string (short quote from log)
    - "relevance": string (why this line matters for flaky diagnosis)

Do not put markdown code fences (```) inside JSON string values — use plain text only.
Do not use markdown heading syntax (#, ##, ###) inside any JSON string — write plain titles and sentences only.
"""

# Human review / ambiguous signal — artifacts under reports/AI/needs_review/
NEEDS_REVIEW_AI_JSON_SUFFIX = """
---
Respond with ONLY one valid JSON object (no markdown code fences, no text before or after).
Use English for all string values.

SUMMARY: one line, max ~90 characters, English noun phrase / title-style label only (no full sentences,
no trailing period). Same rules as BUG pipeline SUMMARY.

The failure is classified NEEDS_REVIEW: triage could not confidently label it BUG vs FLAKY; a human must decide.

Required keys:
- "SUMMARY": Short noun phrase, e.g. "Ambiguous assertion vs product behavior"
- "NEEDS_REVIEW_DESCRIPTION": 4-10 sentences: why this case needs human review (mixed signals, unclear expectation, environment vs product, etc.)
- "IMPACT": How unresolved classification hurts release decisions and CI trust
- "CONFIDENCE": Integer 0-100 for your assessment that human review is required (not product correctness)
- "ROOT_CAUSE": Narrative: what is unclear and what evidence would resolve it
- "ROOT_CAUSE_CANDIDATES": array of { "analysis": string, "probability_percent": int } with at least 2 items when possible
- "LOG_LINE_REFERENCES": array of objects (may be empty). Each object MUST have:
    - "log_file": string (basename or path)
    - "line_number": integer (approximate line in the excerpt if exact unknown)
    - "excerpt": string (short quote from log)
    - "relevance": string (why this line matters)

Do not put markdown code fences (```) inside JSON string values — use plain text only.
Do not use markdown heading syntax (#, ##, ###) inside any JSON string — write plain titles and sentences only.
"""


def _resolve_ai_bucket(triage_data_full, test_id, request_bucket):
    """Where to store AI artifacts: 'bug', 'flaky', or 'needs_review'."""
    rb = (request_bucket or "").strip().lower()
    if rb in ("bug", "flaky", "needs_review"):
        return rb
    if not triage_data_full or not test_id:
        return "bug"
    methods = triage_data_full.get("methods_with_failures") or {}
    ent = methods.get(test_id)
    if not isinstance(ent, dict):
        for _k, v in methods.items():
            if isinstance(v, dict) and v.get("is_failed_in_current_run"):
                ent = v
                break
    if not isinstance(ent, dict):
        return "bug"
    c = (ent.get("classification") or "").strip().upper()
    if c == "FLAKY":
        return "flaky"
    if c == "NEEDS_REVIEW":
        return "needs_review"
    return "bug"


def _sanitize_tc_id_stem(tc_id: str) -> str:
    """Safe single segment for filenames: letters, digits, underscore, hyphen only."""
    if not tc_id or not str(tc_id).strip():
        return ""
    s = str(tc_id).strip()
    s = re.sub(r"[^A-Za-z0-9_-]+", "_", s).strip("_") or ""
    if len(s) > 80:
        s = s[:80]
    return s


def _artifact_file_stem(
    run_id: str, failure_index: int, ai_bucket: str, tc_id: str = ""
) -> str:
    """
    Unified artifact stem — same string in filenames, HTML, Slack, Jira:
      bug:           bug-run-{run_id}-{TC_ID or NN}
      flaky:         flaky-run-{run_id}-{TC_ID or NN}
      needs_review:  needs-review-run-{run_id}-{TC_ID or NN}
    When failure bundle has error.tc_id (e.g. TC010), it is used; otherwise NN = 01.. from failure_index + 1.
    run_id is normalized (strip 'run-' for the middle segment to avoid 'run-run-').
    """
    rid = (run_id or "unknown").strip().replace("/", "_").replace("\\", "_")
    if rid.startswith("run-"):
        rid = rid[4:]
    b = (ai_bucket or "bug").strip().lower()
    fi = 0 if failure_index is None else int(failure_index)
    label = _sanitize_tc_id_stem(tc_id)
    if not label:
        label = f"{fi + 1:02d}"
    if b == "flaky":
        return f"flaky-run-{rid}-{label}"
    if b == "needs_review":
        return f"needs-review-run-{rid}-{label}"
    return f"bug-run-{rid}-{label}"


def _load_run_log_excerpt(project_root, run_id, max_chars=16000):
    """Load ./logs/run-{id}_test_log.log (run_id may include 'run-' prefix)."""
    if not project_root or not run_id:
        return ""
    clean = str(run_id).strip()
    if clean.startswith("run-"):
        clean = clean[4:]
    log_path = os.path.join(project_root, "logs", f"run-{clean}_test_log.log")
    if not os.path.isfile(log_path):
        alt = os.path.join(project_root, "logs", f"{run_id}_test_log.log".replace("run-run-", "run-"))
        if os.path.isfile(alt):
            log_path = alt
        else:
            return ""
    try:
        with open(log_path, "r", encoding="utf-8", errors="replace") as f:
            text = f.read()
        if len(text) > max_chars:
            text = text[-max_chars:]
        return text
    except OSError:
        return ""


@app.route('/api/analyze-failure', methods=['POST'])
def analyze_failure():
    """
    Analyze test failure from failure_bundle.json and predict root cause
    
    Request:
    {
        "bundle_path": "/path/to/failure_bundle.json",
        "failure_index": 0,
        "screenshot_url": "http://...",
        "test_metadata": {
            "test_name": "SignUp.verifySignUpValidationMessage",
            "browser": "chrome",
            "os": "linux"
        }
    }

    failure_index: optional, default 0. Use 0..N-1 for each entry in bundle "failures" (multi-failure runs).
    Artifacts are written as reports/AI/<bug|flaky|needs_review>/*/bug-run-{id}-{TC_ID|NN}_* or flaky-run-*
    or needs-review-run-* (summary: *_ai_summary.json, analysis: *_ai_rca.json, report: *_ai_report.html).
    
    Response:
    {
        "root_cause": "Element not ready - Explicit wait needed",
        "confidence": 0.85,
        "recommendations": ["Add WebDriverWait", "Check selector"],
        "evidence": "NoSuchElement exception with selector...",
        "test_focus": "password",
        "data_status": "invalid",
        "test_data": "occupation=Engineer...",
        "expected": "*Please enter Special Character",
        "actual": ""
    }
    """
    import time
    import sys
    request_start = time.time()
    
    print("\n" + "="*70, flush=True)
    print("[MCP-REQUEST] /api/analyze-failure", flush=True)
    print("="*70, flush=True)
    sys.stdout.flush()
    
    try:
        data = request.get_json()
        bundle_path = data.get('bundle_path', '')
        screenshot_url = data.get('screenshot_url', '')
        test_metadata = data.get('test_metadata', {})
        
        print(f"📂 Raw bundle path received: {bundle_path}", flush=True)
        print(f"🔗 Screenshot URL: {screenshot_url}", flush=True)
        print(f"🏷️  Metadata: {test_metadata}", flush=True)
        
        # Step 1 timing
        step1_start = time.time()
        
        # Step 1: Parse failure_bundle.json
        print(f"\n⏳ Step 1: Parsing failure bundle...", flush=True)
        
        # Normalize path: Windows backslashes → forward slashes, handle absolute paths
        normalized_path = bundle_path.replace('\\', '/')
        print(f"📂 Normalized path: {normalized_path}", flush=True)
        
        # Check if file exists
        if not os.path.exists(normalized_path):
            # Try alternative: replace forward slashes back to backslashes for Windows
            alt_path = bundle_path.replace('/', '\\')
            print(f"⚠️  Path not found at normalized location, trying: {alt_path}", flush=True)
            if os.path.exists(alt_path):
                normalized_path = alt_path
                print(f"✅ Found at: {normalized_path}", flush=True)
            else:
                error_msg = f"Bundle file not found at either:\n  - {normalized_path}\n  - {alt_path}"
                print(f"❌ {error_msg}", flush=True)
                return jsonify({"error": error_msg}), 404
        else:
            print(f"✅ Bundle file found at normalized path", flush=True)
        
        with open(normalized_path, 'r', encoding='utf-8') as f:
            bundle_data = json.load(f)
        
        print(f"✅ Bundle JSON loaded successfully", flush=True)

        run_id = (bundle_data.get('run_id') or test_metadata.get('run_id') or '').strip()
        failure_dir = os.path.dirname(normalized_path)
        reports_dir = os.path.dirname(failure_dir)
        if not run_id:
            bn = os.path.basename(normalized_path)
            if bn.endswith('_failure_bundle.json'):
                run_id = bn[: -len('_failure_bundle.json')]
        triage_data_full = None
        triage_excerpt = ''
        consolidated_excerpt = ''
        try:
            cpath = os.path.join(reports_dir, 'triage', 'failure_bundle_consolidated.json')
            if os.path.exists(cpath):
                with open(cpath, 'r', encoding='utf-8') as cf:
                    consolidated_excerpt = json.dumps(json.load(cf), indent=2)[:2500]
        except Exception as _e:
            print(f'[WARN] consolidated bundle: {_e}', flush=True)
        try:
            if run_id:
                tpath = os.path.join(reports_dir, 'triage', f'{run_id}_triage_report.json')
                if os.path.exists(tpath):
                    with open(tpath, 'r', encoding='utf-8') as tf:
                        triage_data_full = json.load(tf)
                        triage_excerpt = json.dumps(triage_data_full, indent=2)[:4000]
        except Exception as _e:
            print(f'[WARN] triage report: {_e}', flush=True)

        triage_block = ''
        if triage_excerpt:
            triage_block += f'\n\n### Triage report (this run, excerpt)\n{triage_excerpt}\n'
        if consolidated_excerpt:
            triage_block += f'\n### Consolidated failure history (excerpt)\n{consolidated_excerpt}\n'
        
        # Extract failures array — support failure_index for multi-failure bundles
        failures = bundle_data.get('failures', [])
        if not failures:
            return jsonify({"error": "No failures in bundle"}), 400
        try:
            failure_index = int(data.get("failure_index", 0))
        except (TypeError, ValueError):
            failure_index = 0
        if failure_index < 0 or failure_index >= len(failures):
            return (
                jsonify(
                    {
                        "error": "failure_index out of range",
                        "failures_in_bundle": len(failures),
                        "valid_range": f"0..{len(failures) - 1}",
                    }
                ),
                400,
            )
        failure = failures[failure_index]
        test_id = (failure.get("meta") or {}).get("test_id") or ""
        request_bucket = (data.get("ai_bucket") or data.get("failure_classification") or "").strip()
        error_obj = failure.get("error") or {}
        tc_id_raw = str(error_obj.get("tc_id") or "").strip()
        project_root = os.path.dirname(reports_dir)
        log_excerpt = _load_run_log_excerpt(project_root, run_id)
        ai_bucket = _resolve_ai_bucket(triage_data_full, test_id, request_bucket)
        file_stem = _artifact_file_stem(run_id, failure_index, ai_bucket, tc_id_raw)
        log_block = ""
        if log_excerpt:
            log_block = (
                f"\n\n### Run log excerpt (same run_id; cite line numbers in LOG_LINE_REFERENCES)\n"
                f"```\n{log_excerpt}\n```\n"
            )
        environment = failure.get('environment', {})
        screenshot_obj = failure.get('screenshot', {})
        
        # Extract screenshot file path
        screenshot_path = screenshot_obj.get('file_path', '')
        screenshot_base64 = None
        if screenshot_path and os.path.exists(screenshot_path):
            try:
                with open(screenshot_path, 'rb') as f:
                    screenshot_base64 = base64.b64encode(f.read()).decode()
                print(f"✅ Screenshot loaded: {len(screenshot_base64)} bytes")
            except Exception as e:
                print(f"⚠️ Screenshot loading failed: {str(e)}")
        else:
            print(f"⚠️ Screenshot not found at: {screenshot_path}")
        
        # Extract key information
        exception_message = error_obj.get('exception_message', 'N/A')
        stack_trace = error_obj.get('stack_trace', 'N/A')
        test_focus = error_obj.get('test_focus', 'N/A')
        data_status = error_obj.get('data_status', 'N/A')
        test_data = error_obj.get('test_data', 'N/A')
        expected = error_obj.get('expected', 'N/A')
        actual = error_obj.get('actual', 'N/A')
        if exception_message and str(exception_message).strip() not in ('', 'N/A'):
            pe, pa = parse_testng_expected_actual_from_message(str(exception_message))
            if pe and (not expected or str(expected).strip() in ('', 'N/A')):
                expected = pe
            if pa is not None and (not actual or str(actual).strip() in ('', 'N/A')):
                actual = pa
        test_params = error_obj.get('test_parameters', 'N/A')
        invalid_reason = extract_invalid_reason(test_params, test_data)
        
        browser = environment.get('browser', 'unknown')
        os_name = environment.get('os', 'unknown')
        
        step1_elapsed = time.time() - step1_start
        print(f"✅ Step 1 (Bundle parsing): {step1_elapsed:.2f}s")

        # Determine if this is a Negative Test (invalid data)
        is_negative_test = data_status.lower() == 'invalid'

        # Build prompt: FLAKY / NEEDS_REVIEW use QA-oriented JSON + log references for Slack
        if ai_bucket == "needs_review":
            prompt = f"""You are a QA Automation Analyst specializing in ambiguous failures that need human triage.

## Classification: NEEDS_REVIEW
The triage engine could not confidently label this as BUG or FLAKY — a reviewer must interpret evidence and decide next steps.

### Failure snapshot
- test_id: {test_id}
- Exception: {exception_message}
- Expected: {expected}
- Actual: {actual}
- test_focus: {test_focus}, data_status: {data_status}
- Browser: {browser}, OS: {os_name}

### Your task
1. Explain **why human review is required** (mixed signals, unclear product expectation, test vs environment, conflicting history).
2. Describe **what evidence** would resolve classification (repro steps, baseline pass, product owner input).
3. Give **LOG_LINE_REFERENCES** pointing into the RUN LOG EXCERPT when useful.

### Stack trace (excerpt)
{stack_trace[:3500] if stack_trace else "N/A"}
""" + NEEDS_REVIEW_AI_JSON_SUFFIX + triage_block + log_block
        elif ai_bucket == "flaky":
            prompt = f"""You are a QA Automation Analyst specializing in FLAKY and intermittent test failures.

## Classification: FLAKY
The triage engine flagged this failure as non-deterministic — not a stable, reproducible product defect.

### Failure snapshot
- test_id: {test_id}
- Exception: {exception_message}
- Expected: {expected}
- Actual: {actual}
- test_focus: {test_focus}, data_status: {data_status}
- Browser: {browser}, OS: {os_name}

### Your task
1. Explain why this pattern matches **flakiness** (timing, environment, history in triage/consolidated data, or ambiguous product behavior).
2. Describe **impact on system quality**: CI noise, false negatives, wasted triage time.
3. Give **LOG_LINE_REFERENCES** pointing into the RUN LOG EXCERPT (file + line + short excerpt + relevance).
4. If the log does not contain useful lines, return an empty array and say so in FLAKY_DESCRIPTION.

### Stack trace (excerpt)
{stack_trace[:3500] if stack_trace else "N/A"}
""" + FLAKY_AI_JSON_SUFFIX + triage_block + log_block
        elif is_negative_test:
            prompt = f"""You are a Test Automation Root Cause Analyst.

## **THIS IS A VALIDATION TEST** 
(Intentional invalid input to verify error handling)

### What the test does:
1. Enters intentionally INVALID PASSWORD: "abcdefg" (missing special chars, capitals, numbers)
2. Clicks Register button
3. Expects error message: "{expected}"
4. **ACTUAL RESULT: Empty string "" = NO MESSAGE APPEARED** ← THIS IS THE BUG

### The Screenshot shows:
- Registration form is complete
- All fields filled normally
- BUT: The validation error message area is **EMPTY/BLANK**

### Analysis Framework:
✓ Test Data intentionally invalid: YES
✓ Form submitted: YES
✗ Validation message displayed: NO ← **FAILURE POINT**

### Root Cause Hypothesis:
The validation error message failed to appear. This indicates:
1. **Client-side validation not triggered** (JavaScript not working?)
2. **DOM element selector broken** (Can't find error message element?)
3. **Error handler failed** (Exception in validation logic?)
4. **Message element not rendering** (CSS/display issue?)

### Your analysis:
Look at the screenshot carefully. The validation message area should show:
"{expected}"

But it's showing: "" (empty/blank)

What is the most likely root cause? Consider:
- Selector xpath/CSS changed?
- JavaScript validation function disabled?
- Error handler not catching validation?
- Message element id/class wrong?

Be very specific about what's broken.
""" + JIRA_AI_JSON_SUFFIX + triage_block
        else:
            prompt = f"""You are a Test Automation Root Cause Analyst.

## Failure snapshot (assertion / UI text / validation)
- test_id: {test_id}
- Exception: {exception_message}
- Expected: {expected}
- Actual: {actual}
- test_focus: {test_focus}, data_status: {data_status}
- Browser: {browser}, OS: {os_name}
- Test data / parameters: {test_data}

The test compared **expected** vs **actual** and they differ. Common causes: product copy changed, localization, A/B text, timing, or test expectation out of date.

### Your task
1. **BUG_DESCRIPTION** (3–8 sentences): What failed, where in the user flow, and why the assertion fired.
2. **IMPACT**: Effect on users, brand, and CI (blocked pipelines, false failures).
3. **ROOT_CAUSE_CANDIDATES**: At least two hypotheses with realistic probabilities. For a plain text mismatch on a confirmation screen, include a strong candidate that the application text was intentionally changed (give it a high probability when evidence supports it).
4. **SUMMARY**: Exactly one line, English, noun-phrase title only (~90 chars max): compact label like
   "Incorrect order confirmation copy" — not a full sentence, no leading "The test...", no trailing period.

### Stack trace (excerpt)
{stack_trace[:3500] if stack_trace else "N/A"}
""" + JIRA_AI_JSON_SUFFIX + triage_block + log_block
        
        # Step 2 timing - Prompt generation
        step2_elapsed = time.time() - step1_start - step1_elapsed
        
        # Step 3: Call Claude for AI analysis with screenshot
        step3_start = time.time()
        print(f"\n⏳ Step 3: Calling Claude API for analysis...", flush=True)
        print(f"🤖 Claude Model: {CLAUDE_MODEL}", flush=True)
        ai_response, claude_err = call_claude_for_analysis(prompt, screenshot_base64)
        step3_elapsed = time.time() - step3_start
        print(f"✅ Step 3 (Claude API call): {step3_elapsed:.2f}s", flush=True)
        
        # Step 4: Format result (Claude success or placeholder so reports/AI/* always exist)
        step4_start = time.time()
        print(f"\n⏳ Step 4: Formatting analysis result...", flush=True)
        common_meta = {
            "test_focus": test_focus,
            "data_status": data_status,
            "invalid_reason": invalid_reason,
            "test_parameters": test_params,
            "test_data": test_data,
            "expected": expected,
            "actual": actual,
            "browser": browser,
            "os": os_name,
            "screenshot_url": screenshot_url,
            "assertion_gap": f"Expected '{expected}' but got '{actual}'" if actual != expected else "No gap",
            "is_ui_issue": actual == "" and expected != "",
            "failure_type": "UI Validation" if actual == "" else "Logic Assertion",
        }
        if ai_response:
            print(f"✅ Claude analysis received ({len(ai_response)} characters)", flush=True)
            parsed = parse_claude_structured_response(ai_response)
            conf_val = (
                parsed.get("confidence")
                if parsed.get("confidence") is not None
                else extract_confidence(ai_response)
            )
            root_text = parsed.get("root_cause") or extract_root_cause(ai_response)
            if ai_bucket == "needs_review" and parsed.get("needs_review_description"):
                root_text = parsed.get("needs_review_description")
            elif ai_bucket == "flaky" and parsed.get("flaky_description"):
                root_text = parsed.get("flaky_description")
            if _is_ai_placeholder_text(str(root_text or "")):
                root_text = ""
            summ_text = parsed.get("summary") or extract_summary(ai_response)
            if _is_ai_placeholder_text(str(summ_text or "")):
                summ_text = ""
            fd = parsed.get("flaky_description") or parsed.get("FLAKY_DESCRIPTION")
            nrd = (
                parsed.get("needs_review_description")
                or parsed.get("NEEDS_REVIEW_DESCRIPTION")
                or parsed.get("WHY_REVIEW_REQUIRED")
                or parsed.get("WHY_HUMAN_REVIEW_REQUIRED")
            )
            llr = parsed.get("log_line_references") or parsed.get("LOG_LINE_REFERENCES")
            if not isinstance(llr, list):
                llr = []
            result = {
                **common_meta,
                "ai_bucket": ai_bucket,
                "summary": summ_text,
                "root_cause": root_text,
                "confidence": conf_val,
                "bug_description": (parsed.get("bug_description") or "")
                if ai_bucket == "bug"
                else "",
                "flaky_description": (fd or "") if ai_bucket == "flaky" else "",
                "needs_review_description": (nrd or "") if ai_bucket == "needs_review" else "",
                "impact": parsed.get("impact") or "",
                "root_cause_candidates": parsed.get("root_cause_candidates") or [],
                "log_line_references": llr,
                "root_cause_analysis_jira": build_jira_root_cause_analysis_text(
                    parsed, conf_val, root_text
                ),
                "recommendations": parsed.get("recommendations")
                if parsed.get("recommendations")
                else extract_recommendations(ai_response),
                "evidence": extract_evidence(ai_response),
                "ai_analysis": ai_response,
                "claude_ok": True,
                "error_detail": None,
            }
            repair_bug_output_from_markdown_report(result, ai_response)
        else:
            print(f"❌ Claude analysis failed: {claude_err}", flush=True)
            hint = (
                "Ensure CLAUDE_API_KEY is set for the MCP process: Jenkins job environment, Credentials, "
                "or a .env file in the project root (loaded at startup). Check logs/mcp/mcp_api_debug.log on the agent."
            )
            result = {
                **common_meta,
                "ai_bucket": ai_bucket,
                "summary": "Claude analysis unavailable (API key, network, or quota)",
                "root_cause": (claude_err or "Claude returned no text.") + "\n\n" + hint,
                "confidence": 0,
                "bug_description": "",
                "flaky_description": "",
                "needs_review_description": "",
                "impact": "",
                "root_cause_candidates": [],
                "log_line_references": [],
                "root_cause_analysis_jira": "N/A",
                "recommendations": [
                    "Set CLAUDE_API_KEY in Jenkins (global or job env) or project .env",
                    "Confirm the detached MCP process inherits env (see Jenkinsfile)",
                    "Inspect logs/mcp/mcp_api_debug.log under the project root",
                ],
                "evidence": "",
                "ai_analysis": "",
                "claude_ok": False,
                "error_detail": claude_err or "unknown",
            }
        result['run_id'] = run_id
        result['test_id'] = test_id
        result['tc_id'] = tc_id_raw
        result['failure_index'] = failure_index
        result['failures_in_bundle'] = len(failures)
        result['artifact_file_stem'] = file_stem
        result["canonical_ai_id"] = file_stem
        if triage_data_full is not None:
            result['triage_classification_summary'] = triage_data_full.get('classification_summary')
            result['triage_latest_run_id'] = triage_data_full.get('latest_run_id')

        finalize_ai_result_display(result, ai_bucket)
        strip_markdown_scaffolding_from_bug_fields(result)
        dedupe_leading_summary_from_root_cause(result)
        rebuild_root_cause_analysis_jira_from_result(result)
        ensure_bug_ticket_narrative_fields(result)
        summ_final = str(result.get("summary") or "").strip()
        if summ_final:
            result["summary"] = normalize_ai_summary_noun_phrase(summ_final)
        improve_needs_review_summary_if_generic(result)
        # Mirror long narrative for consumers that read needs_review_description (e.g. Slack)
        if (
            (result.get("ai_bucket") or "").strip().lower() == "needs_review"
            and not str(result.get("needs_review_description") or "").strip()
            and str(result.get("root_cause") or "").strip()
        ):
            result["needs_review_description"] = str(result.get("root_cause") or "").strip()[:4000]

        # Final timing
        step4_elapsed = time.time() - step4_start
        total_elapsed = time.time() - request_start
        print(f"✅ Step 4 (Result formatting): {step4_elapsed:.2f}s", flush=True)
        print(f"📊 TOTAL TIME: {total_elapsed:.2f}s (Claude: {step3_elapsed:.2f}s)", flush=True)
        print("="*70, flush=True)
        print("✅ ANALYSIS COMPLETE - Returning result to Jenkins", flush=True)
        print("="*70, flush=True)

        try:
            save_ai_artifact_files(reports_dir, file_stem, result)
        except Exception as save_err:
            print(f'[WARN] save_ai_artifact_files: {save_err}', flush=True)
        
        return jsonify(result), 200
        
    except json.JSONDecodeError:
        print(f"❌ Invalid JSON in bundle file", flush=True)
        return jsonify({"error": "Invalid JSON in bundle file"}), 400
    except Exception as e:
        print(f"❌ Failure analysis error: {str(e)}", flush=True)
        import traceback
        traceback.print_exc(file=sys.stdout)
        return jsonify({"error": str(e)}), 500


def save_ai_artifact_files(reports_dir, file_stem: str, result):
    """Persist MCP analysis under reports/AI/<bug|flaky|needs_review>/analysis|report|summary."""
    import html as html_lib

    run_id = (result.get("run_id") or "").strip()
    if not file_stem or not reports_dir:
        return
    ai_bucket = (result.get("ai_bucket") or "bug").strip().lower()
    if ai_bucket not in ("bug", "flaky", "needs_review"):
        ai_bucket = "bug"
    ai_root = os.path.join(reports_dir, 'AI', ai_bucket)
    analysis_dir = os.path.join(ai_root, 'analysis')
    report_dir = os.path.join(ai_root, 'report')
    summary_dir = os.path.join(ai_root, 'summary')
    for d in (analysis_dir, report_dir, summary_dir):
        os.makedirs(d, exist_ok=True)
    base = file_stem
    summary = html_lib.escape(str(result.get('summary') or '').strip())
    root_cause = html_lib.escape(str(result.get('root_cause') or '').strip())
    try:
        conf_num = int(result.get('confidence')) if result.get('confidence') is not None else None
    except (TypeError, ValueError):
        conf_num = None
    conf_disp = html_lib.escape(str(conf_num)) if conf_num is not None else '—'
    run_safe = html_lib.escape(str(run_id or file_stem))
    stem_safe = html_lib.escape(str(file_stem))
    raw_excerpt = html_lib.escape((result.get('ai_analysis') or '')[:12000])
    recs = result.get('recommendations') or []
    rec_html = ""
    if recs:
        items = "".join(f"<li>{html_lib.escape(str(r))}</li>" for r in recs[:12])
        rec_html = f'<div class="card"><h2>Recommendations</h2><ul class="list">{items}</ul></div>'
    fail_block = ""
    if result.get('claude_ok') is False:
        ed = html_lib.escape(str(result.get('error_detail') or ''))
        fail_block = (
            f'<div class="warn"><strong>Claude did not complete.</strong> '
            f"{ed} Check API key, model name, and logs/mcp/mcp_api_debug.log.</div>"
        )
    badge = (
        f'<span class="badge">{conf_disp}% confidence</span>' if conf_num is not None else ""
    )
    bug_desc = html_lib.escape(str(result.get("bug_description") or "").strip())
    flaky_desc = html_lib.escape(str(result.get("flaky_description") or "").strip())
    impact_html = html_lib.escape(str(result.get("impact") or "").strip())
    jira_rc = html_lib.escape(str(result.get("root_cause_analysis_jira") or "").strip())
    nr_desc = html_lib.escape(str(result.get("needs_review_description") or "").strip())
    bug_impact_block = ""
    if ai_bucket == "flaky" and flaky_desc:
        bug_impact_block += f'<div class="card"><h2>FLAKY DESCRIPTION</h2><div class="prose">{flaky_desc}</div></div>'
    elif ai_bucket == "needs_review" and nr_desc:
        bug_impact_block += f'<div class="card"><h2>NEEDS REVIEW</h2><div class="prose">{nr_desc}</div></div>'
    elif bug_desc:
        bug_impact_block += f'<div class="card"><h2>BUG DESCRIPTION</h2><div class="prose">{bug_desc}</div></div>'
    if impact_html:
        bug_impact_block += f'<div class="card"><h2>Impact</h2><div class="prose">{impact_html}</div></div>'
    if jira_rc:
        jira_title = "Root cause" if ai_bucket == "bug" else "Root cause (candidates)"
        bug_impact_block += f'<div class="card"><h2>{jira_title}</h2><div class="prose">{jira_rc}</div></div>'
    llrefs = result.get("log_line_references") or []
    if isinstance(llrefs, list) and llrefs:
        lis = []
        for item in llrefs[:24]:
            if not isinstance(item, dict):
                continue
            lf = html_lib.escape(str(item.get("log_file") or ""))
            ln = html_lib.escape(str(item.get("line_number") or ""))
            ex = html_lib.escape(str(item.get("excerpt") or ""))
            rel = html_lib.escape(str(item.get("relevance") or ""))
            lis.append(f"<li><strong>{lf}</strong> line {ln}<br/><code>{ex}</code><br/><span class='sub'>{rel}</span></li>")
        if lis:
            bug_impact_block += (
                '<div class="card"><h2>Log line references</h2><ul class="list">'
                + "".join(lis)
                + "</ul></div>"
            )
    if ai_bucket == "flaky":
        _kind, _kind_long = "Flaky", "Flaky Analysis Report by AI"
        id_label = "Flaky Id"
    elif ai_bucket == "needs_review":
        _kind, _kind_long = "Needs review", "Needs Review Analysis Report by AI"
        id_label = "Review Id"
    else:
        _kind, _kind_long = "Bug", "Bug Analysis Report by AI"
        id_label = "Bug ID"
    h1_title = html_lib.escape(_kind_long)
    doc_title = html_lib.escape(f"{_kind} Analysis Report by AI — {run_id or file_stem}")
    sub_id_line = (
        f'<p class="sub"><strong>{html_lib.escape(id_label)}:</strong> <code>{stem_safe}</code></p>'
    )
    html = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{doc_title}</title>
<style>
:root {{ --fg:#111827; --muted:#6b7280; --border:#e5e7eb; --card:#fff; --accent:#2563eb; }}
* {{ box-sizing: border-box; }}
body {{ margin:0; font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
  color: var(--fg); background:#f3f4f6; line-height:1.55; }}
.wrap {{ max-width: 42rem; margin: 0 auto; padding: 2rem 1.25rem 3rem; }}
h1 {{ font-size: 1.2rem; font-weight: 600; margin: 0 0 0.35rem; letter-spacing: -0.02em; }}
.sub {{ color: var(--muted); font-size: 0.875rem; margin: 0 0 1.5rem; }}
.card {{ background: var(--card); border: 1px solid var(--border); border-radius: 10px;
  padding: 1.15rem 1.3rem; margin-bottom: 0.9rem; box-shadow: 0 1px 2px rgba(0,0,0,.05); }}
.card h2 {{ font-size: 0.7rem; font-weight: 600; text-transform: uppercase; letter-spacing: 0.07em;
  color: var(--muted); margin: 0 0 0.6rem; }}
.summary-line {{ font-size: 1.02rem; font-weight: 500; line-height: 1.45; }}
.badge {{ display:inline-block; font-size: 0.72rem; font-weight: 600; padding: 0.2rem 0.5rem;
  border-radius: 6px; background: #eff6ff; color: var(--accent); margin-left: 0.45rem; vertical-align: middle; }}
.prose {{ font-size: 0.92rem; color: #374151; white-space: pre-wrap; word-break: break-word; }}
.list {{ margin: 0; padding-left: 1.15rem; font-size: 0.9rem; color: #374151; }}
.list li {{ margin: 0.25rem 0; }}
.warn {{ background: #fffbeb; border: 1px solid #fcd34d; color: #92400e; padding: 0.9rem 1rem;
  border-radius: 8px; font-size: 0.875rem; margin-bottom: 1rem; }}
details {{ margin-top: 0.5rem; }}
details summary {{ cursor: pointer; font-size: 0.8rem; color: var(--muted); user-select: none; }}
details pre {{ margin: 0.65rem 0 0; font-size: 0.72rem; line-height: 1.45; overflow: auto; max-height: 22rem;
  background: #f9fafb; border: 1px solid var(--border); border-radius: 8px; padding: 0.85rem; }}
footer {{ margin-top: 1.75rem; font-size: 0.72rem; color: var(--muted); }}
</style>
</head>
<body>
<div class="wrap">
  <h1>{h1_title}</h1>
  {sub_id_line}
  {fail_block}
  <div class="card">
    <h2>Summary</h2>
    <p class="summary-line">{summary}</p>
    {f'<p class="sub" style="margin-top:0.35rem">{badge}</p>' if badge else ''}
  </div>
  {bug_impact_block}
  <div class="card">
    <h2>Root cause (full)</h2>
    <div class="prose">{root_cause if root_cause else "—"}</div>
  </div>
  {rec_html}
  <details>
    <summary>Raw model response (excerpt)</summary>
    <pre>{raw_excerpt}</pre>
  </details>
  <footer>Generated by MCP orchestrator · Artifacts under <code>reports/AI/{html_lib.escape(ai_bucket)}/</code></footer>
</div>
</body>
</html>"""
    report_path = os.path.join(report_dir, f'{base}_ai_report.html')
    with open(report_path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(html)
    analysis_payload = {k: v for k, v in result.items() if k != 'ai_analysis'}
    analysis_payload['ai_analysis_excerpt'] = (result.get('ai_analysis') or '')[:8000]
    analysis_payload['artifacts'] = {
        'ai_bucket': ai_bucket,
        'canonical_ai_id': base,
        'html_report': f'reports/AI/{ai_bucket}/report/{base}_ai_report.html',
        'analysis_json': f'reports/AI/{ai_bucket}/analysis/{base}_ai_rca.json',
        'summary_json': f'reports/AI/{ai_bucket}/summary/{base}_ai_summary.json',
    }
    with open(os.path.join(analysis_dir, f'{base}_ai_rca.json'), 'w', encoding='utf-8') as f:
        json.dump(analysis_payload, f, indent=2, ensure_ascii=False)
    summary_payload = {
        'run_id': run_id,
        'test_id': result.get('test_id'),
        'tc_id': result.get('tc_id'),
        'failure_index': result.get('failure_index'),
        'failures_in_bundle': result.get('failures_in_bundle'),
        'artifact_file_stem': result.get('artifact_file_stem') or file_stem,
        'canonical_ai_id': base,
        'ai_bucket': ai_bucket,
        'summary': result.get('summary'),
        'confidence': result.get('confidence'),
        'root_cause': result.get('root_cause'),
        'root_cause_excerpt': (result.get('root_cause') or '')[:2000],
        'bug_description': result.get('bug_description'),
        'flaky_description': result.get('flaky_description'),
        'needs_review_description': result.get('needs_review_description'),
        'impact': result.get('impact'),
        'root_cause_analysis_jira': result.get('root_cause_analysis_jira'),
        'root_cause_candidates': result.get('root_cause_candidates'),
        'log_line_references': result.get('log_line_references'),
        'claude_ok': result.get('claude_ok', True),
        'error_detail': result.get('error_detail'),
        'test_focus': result.get('test_focus'),
        'data_status': result.get('data_status'),
        'expected': result.get('expected'),
        'actual': result.get('actual'),
        'browser': result.get('browser'),
        'os': result.get('os'),
        'triage_classification_summary': result.get('triage_classification_summary'),
    }
    with open(os.path.join(summary_dir, f'{base}_ai_summary.json'), 'w', encoding='utf-8') as f:
        json.dump(summary_payload, f, indent=2, ensure_ascii=False)
    print(f'[AI-ARTIFACTS] Wrote reports/AI/{ai_bucket}/* for {file_stem}', flush=True)


def call_claude_for_analysis(prompt, screenshot_base64=None):
    """Call Claude API for root cause analysis. Returns (analysis_text_or_none, error_message_or_none)."""
    import sys
    import traceback

    def fail(reason):
        log_both(f"❌ Claude: {reason}")
        return (None, reason)

    try:
        if not claude_client:
            msg = f"Claude client is None (CLAUDE_API_KEY set: {bool(CLAUDE_API_KEY)})"
            if CLAUDE_INIT_ERROR:
                msg += f" | init_error={CLAUDE_INIT_ERROR}"
            if CLAUDE_INIT_ERROR and "proxies" in str(CLAUDE_INIT_ERROR).lower():
                msg += " | Fix: upgrade anthropic per mcp/requirements.txt or pin httpx<0.28"
            return fail(msg)

        if not CLAUDE_API_KEY:
            return fail("CLAUDE_API_KEY is not set (env or .env)")

        try:
            import socket

            socket.getaddrinfo("api.anthropic.com", 443)
        except Exception as ne:
            return fail(f"DNS/network: {ne}")

        # Vision: attach failure screenshot when present (failure bundle file_path → base64 in analyze_failure).
        content = []
        _max_img = 5 * 1024 * 1024  # Anthropic typical limit for base64 images
        if screenshot_base64:
            try:
                raw = base64.b64decode(screenshot_base64)
                if len(raw) > _max_img:
                    log_both(f"⚠️ Screenshot too large ({len(raw)} bytes > {_max_img}); omitting image, text-only analysis")
                else:
                    content.append(
                        {
                            "type": "image",
                            "source": {
                                "type": "base64",
                                "media_type": "image/png",
                                "data": screenshot_base64,
                            },
                        }
                    )
                    log_both(f"📎 Claude request includes screenshot ({len(raw)} bytes PNG)")
            except Exception as img_e:
                log_both(f"⚠️ Screenshot attach skipped: {img_e}")
        else:
            log_both("ℹ️ No screenshot for Claude (path missing or not loaded) — text-only root cause")

        content.append({"type": "text", "text": prompt[:12000]})

        try:
            response = claude_client.messages.create(
                model=CLAUDE_MODEL,
                max_tokens=4096,
                messages=[{"role": "user", "content": content}],
            )
        except anthropic.AuthenticationError as ae:
            traceback.print_exc(file=sys.stdout)
            return fail(f"AuthenticationError: {ae}")
        except anthropic.RateLimitError as rle:
            traceback.print_exc(file=sys.stdout)
            return fail(f"RateLimitError: {rle}")
        except anthropic.APIConnectionError as ace:
            traceback.print_exc(file=sys.stdout)
            return fail(f"APIConnectionError: {ace}")
        except anthropic.APIStatusError as ase:
            traceback.print_exc(file=sys.stdout)
            return fail(f"APIStatusError: {ase}")
        except Exception as ge:
            traceback.print_exc(file=sys.stdout)
            return fail(f"{type(ge).__name__}: {ge}")

        if hasattr(response, "content") and response.content and len(response.content) > 0:
            first = response.content[0]
            if hasattr(first, "text") and first.text:
                return (first.text, None)
            return fail("Claude response block has no text attribute")
        return fail("Claude response has no content")

    except Exception as e:
        traceback.print_exc(file=sys.stdout)
        return fail(f"{type(e).__name__}: {e}")


def parse_testng_expected_actual_from_message(exception_message: str) -> tuple:
    """
    Parse TestNG AssertionError first line:
    expected [A] but found [B]
    Handles empty B. If A contains ']', use delimiter split (not regex greedy).
    """
    if not exception_message or not isinstance(exception_message, str):
        return ("", "")
    line = exception_message.strip().split("\n", 1)[0].strip()
    sep = "] but found ["
    idx = line.find(sep)
    if idx < 0:
        return ("", "")
    head = line[:idx]
    prefix = "expected ["
    if not head.startswith(prefix):
        return ("", "")
    exp = head[len(prefix) :]
    tail = line[idx + len(sep) :]
    if not tail.endswith("]"):
        return ("", "")
    act = tail[:-1]
    return (exp, act)


def _is_ai_placeholder_text(s: str) -> bool:
    """
    True when the model echoed a schema label / markdown heading instead of real prose.
    Catches values like 'BUG_DESCRIPTION', '## BUG_DESCRIPTION', '## BUG_DESCRIPTION92%'.
    """
    if not s or not isinstance(s, str):
        return True
    t = s.strip()
    if not t:
        return True
    tl = re.sub(r"\s+", "", t.lower())
    if t.upper() in ("BUG_DESCRIPTION", "SUMMARY", "ROOT_CAUSE", "IMPACT", "ROOT_CAUSE_CANDIDATES"):
        return True
    if re.match(r"^#+\s*bug[_\s-]*description", t, re.I):
        return True
    if re.fullmatch(r"bug[_\s-]*description", t, re.I):
        return True
    if "bug_description" in tl and len(tl) < 52:
        if re.match(r"^#*bug[_-]?description(\d+%)?(confidence)?$", tl):
            return True
    if re.match(r"^#+\s*analysis\s*$", t, re.I):
        return True
    if re.fullmatch(r"#{1,6}\s*analysis", t.strip(), re.I):
        return True
    return False


def _looks_like_markdown_noise_title(s: str) -> bool:
    """SUMMARY must not be a markdown heading or section label."""
    if not s or not isinstance(s, str):
        return True
    t = s.strip()
    if not t:
        return True
    if _is_ai_placeholder_text(t):
        return True
    if t.startswith("#"):
        return True
    if re.match(r"^#{0,3}\s*analysis\s*$", t, re.I):
        return True
    return False


def strip_markdown_headings_plain(s: str) -> str:
    """Backward-compatible alias: full markdown-to-plain for persisted AI fields."""
    return strip_markdown_to_plain_prose(s)


def strip_markdown_to_plain_prose(s: str) -> str:
    """
    Plain text for Slack/Jira/JSON: remove ## anywhere (incl. after '- (85%)'), line-leading #,
    **bold**, and `code` fences — not only line-start headings.
    """
    if not s or not isinstance(s, str):
        return ""
    t = re.sub(r"#{2,}\s*", "", s)
    lines_out = []
    for line in t.splitlines():
        x = re.sub(r"^#{1,6}\s*", "", line.strip())
        for _ in range(12):
            x2 = re.sub(r"\*\*([^*]+)\*\*", r"\1", x)
            if x2 == x:
                break
            x = x2
        x = re.sub(r"`([^`]+)`", r"\1", x)
        if x:
            lines_out.append(x)
    return " ".join(lines_out).strip()


def strip_markdown_scaffolding_from_bug_fields(result: dict) -> None:
    """
    Never persist ##/### headings in narrative fields (bug, flaky, needs_review — same Slack/report consumers).
    Mutates result in place before writing reports/AI/*/ *_ai_rca.json.
    """
    if not isinstance(result, dict):
        return
    keys = (
        "summary",
        "root_cause",
        "root_cause_excerpt",
        "bug_description",
        "impact",
        "flaky_description",
        "needs_review_description",
        "evidence",
        "root_cause_analysis_jira",
    )
    for k in keys:
        v = result.get(k)
        if not isinstance(v, str) or not v.strip():
            continue
        if (
            "##" in v
            or "###" in v
            or "**" in v
            or "`" in v
            or v.lstrip().startswith("#")
        ):
            cleaned = strip_markdown_to_plain_prose(v)
            if cleaned:
                result[k] = cleaned
    recs = result.get("recommendations")
    if isinstance(recs, list):
        out = []
        for r in recs:
            if not isinstance(r, str) or not r.strip():
                continue
            if (
                "##" in r
                or "###" in r
                or "**" in r
                or "`" in r
                or r.lstrip().startswith("#")
            ):
                r = strip_markdown_to_plain_prose(r) or r
            out.append(r)
        result["recommendations"] = out
    cands = result.get("root_cause_candidates")
    if not isinstance(cands, list):
        return
    for item in cands:
        if not isinstance(item, dict):
            continue
        for ck in ("analysis", "hypothesis"):
            a = item.get(ck)
            if not isinstance(a, str) or not a.strip():
                continue
            if (
                "##" in a
                or "###" in a
                or "**" in a
                or "`" in a
                or a.lstrip().startswith("#")
            ):
                item[ck] = strip_markdown_to_plain_prose(a) or a


def dedupe_leading_summary_from_root_cause(result: dict) -> None:
    """Remove a repeated summary phrase at the start of root_cause (common NEEDS_REVIEW model pattern)."""
    if not isinstance(result, dict):
        return
    summ = str(result.get("summary") or "").strip()
    rc = str(result.get("root_cause") or "").strip()
    if not summ or not rc:
        return
    pattern = re.compile(r"^\s*" + re.escape(summ) + r"\s*", re.I)
    new_rc = pattern.sub("", rc, count=1).strip()
    new_rc = pattern.sub("", new_rc, count=1).strip()
    if new_rc and new_rc != rc:
        result["root_cause"] = new_rc


def rebuild_root_cause_analysis_jira_from_result(result: dict) -> None:
    """Recompute Jira-style RCA line from cleaned root_cause (fixes '- (85%) ## ...' left from pre-strip build)."""
    if not isinstance(result, dict):
        return
    parsed = {"root_cause_candidates": result.get("root_cause_candidates") or []}
    rc = str(result.get("root_cause") or "").strip()
    result["root_cause_analysis_jira"] = build_jira_root_cause_analysis_text(
        parsed,
        result.get("confidence"),
        rc,
    )


def _is_generic_needs_review_summary(s: str) -> bool:
    t = (s or "").strip().lower()
    if not t:
        return True
    if t in (
        "why human review is required",
        "human review required",
        "needs human review",
    ):
        return True
    if t.startswith("why human review") and len(t) < 72:
        return True
    return False


def improve_needs_review_summary_if_generic(result: dict) -> None:
    """Replace boilerplate NEEDS_REVIEW titles with a test-method-based noun phrase."""
    if (result.get("ai_bucket") or "").strip().lower() != "needs_review":
        return
    s = str(result.get("summary") or "").strip()
    if not _is_generic_needs_review_summary(s):
        return
    tid = str(result.get("test_id") or "")
    method = tid.split("#", 1)[-1] if "#" in tid else tid
    method = re.sub(r"\[.*$", "", method).strip() or "test method"
    candidate = f"{method} — ambiguous classification signal"
    result["summary"] = normalize_ai_summary_noun_phrase(candidate)


def extract_markdown_sections_from_raw(raw: str) -> dict:
    """
    When the model returns a markdown report instead of clean JSON values, pull sections.
    Keys: summary, bug_description, impact, root_cause (plain text).
    """
    out = {}
    if not raw or not isinstance(raw, str):
        return out
    for label, key in (
        ("SUMMARY", "summary"),
        ("BUG_DESCRIPTION", "bug_description"),
        ("IMPACT", "impact"),
        ("ROOT_CAUSE", "root_cause"),
    ):
        m = re.search(
            # Accept either ## or ### section headings (models sometimes use ##).
            rf"###+?\s*{label}\s*\r?\n(.+?)(?=\r?\n###+?\s|\Z)",
            raw,
            re.DOTALL | re.IGNORECASE,
        )
        if m:
            txt = m.group(1).strip()
            if txt and not txt.startswith("```"):
                out[key] = txt
    # (Legacy) keep a simple first-line extractor as fallback.
    sm = re.search(r"(?:^|\n)###+?\s*SUMMARY\s*\r?\n(.+)", raw, re.DOTALL | re.IGNORECASE)
    if sm and "summary" not in out:
        line = sm.group(1).strip().split("\n", 1)[0].strip()
        if line:
            out["summary"] = line
    return out


def _summary_looks_like_sentence(s: str) -> bool:
    """Detect summaries that are sentences despite instructions (we want noun-phrase titles)."""
    if not s or not isinstance(s, str):
        return True
    t = " ".join(s.strip().split())
    low = t.lower()
    if low.startswith(("the ", "this ", "we ", "it ")):
        return True
    # Common sentence-y patterns from the model
    if " failed " in low or low.startswith("automated test failed") or " because " in low:
        return True
    # Ends with period usually indicates sentence (we strip it, but still treat as suspicious)
    if t.endswith("."):
        return True
    return False


def derive_summary_noun_phrase(result: dict) -> str:
    """Heuristic: produce a compact noun-phrase title when the model returns a sentence."""
    if not isinstance(result, dict):
        return ""
    test_id = str(result.get("test_id") or "")
    exp = str(result.get("expected") or "").strip()
    act = str(result.get("actual") or "").strip()
    gap = str(result.get("assertion_gap") or "").strip().lower()
    hay = " ".join([test_id.lower(), exp.lower(), act.lower(), gap])
    if exp and act:
        if "order" in hay and ("thankyou" in hay or "order confirmation" in hay or "checkout" in hay):
            return "Order confirmation message text mismatch"
        if "password" in hay and ("validation" in hay or "message" in hay):
            return "Password validation message missing"
        return "Expected vs actual assertion mismatch"
    if "checkout" in hay:
        return "Checkout flow assertion failure"
    return ""


def repair_bug_output_from_markdown_report(result: dict, raw: str) -> None:
    """
    Fix cases where Claude put markdown section titles into JSON strings (e.g. SUMMARY: '## Analysis')
    or returned a hybrid markdown+JSON reply. Mutates result.
    """
    if not isinstance(result, dict) or not raw:
        return
    if (result.get("ai_bucket") or "bug") != "bug":
        return
    sec = extract_markdown_sections_from_raw(raw)
    summ = str(result.get("summary") or "").strip()
    if _looks_like_markdown_noise_title(summ) or _summary_looks_like_sentence(summ):
        if sec.get("summary"):
            first = sec["summary"].split("\n", 1)[0].strip()
            first = strip_markdown_headings_plain(first) or first
            result["summary"] = first
        else:
            m = re.search(
                r"###+?\s*SUMMARY\s*\r?\n(.+)",
                raw,
                re.IGNORECASE,
            )
            if m:
                line = m.group(1).strip().split("\n", 1)[0].strip()
                if line and not line.startswith("#"):
                    result["summary"] = line
    bd = str(result.get("bug_description") or "").strip()
    if _looks_like_markdown_noise_title(bd) or bd.startswith("#") or (
        len(bd) < 120 and "###" in raw and "BUG_DESCRIPTION" in raw.upper()
    ):
        if sec.get("bug_description"):
            result["bug_description"] = strip_markdown_headings_plain(sec["bug_description"])
    elif "###" in bd[:200] or bd.startswith("#"):
        result["bug_description"] = strip_markdown_headings_plain(bd)
    rc = str(result.get("root_cause") or "").strip()
    if sec.get("root_cause") and (len(rc) < 80 or _looks_like_markdown_noise_title(rc) or not rc):
        result["root_cause"] = strip_markdown_headings_plain(sec["root_cause"])
    elif rc and ("##" in rc or "###" in rc or rc.startswith("#")):
        result["root_cause"] = strip_markdown_headings_plain(rc)
    imp = str(result.get("impact") or "").strip()
    if imp.startswith("#") and sec.get("impact"):
        result["impact"] = strip_markdown_headings_plain(sec["impact"])
    cands = result.get("root_cause_candidates")
    if isinstance(cands, list):
        for item in cands:
            if not isinstance(item, dict):
                continue
            a = (item.get("analysis") or item.get("hypothesis") or "").strip()
            if a.startswith("#") or "###" in a[:80]:
                item["analysis"] = strip_markdown_headings_plain(a)


def normalize_ai_summary_noun_phrase(s: str, max_len: int = 90) -> str:
    """
    Post-process the SUMMARY field: single line, noun phrase, concise English title style.
    """
    if not s or not isinstance(s, str):
        return ""
    if _is_ai_placeholder_text(s):
        return ""
    t = " ".join(s.strip().split())
    t = t.split("\n", 1)[0].strip()
    t = re.sub(r"^#{1,6}\s*", "", t).strip()
    if not t:
        return ""
    if len(t) > 1 and t.endswith(".") and not t.endswith(".."):
        t = t[:-1].rstrip()
    low = t.lower()
    for pref in ("the ", "this "):
        if low.startswith(pref) and len(t) > len(pref) + 8:
            t = t[len(pref) :].strip()
            if t:
                t = t[0].upper() + t[1:]
            break
    if len(t) > max_len:
        t = t[: max_len - 1].rstrip() + "…"
    return t


def sanitize_ai_string_fields(d: dict) -> None:
    """Clear placeholder echoes in common AI string fields. Mutates d."""
    if not isinstance(d, dict):
        return
    for key in (
        "summary",
        "bug_description",
        "root_cause",
        "flaky_description",
        "needs_review_description",
        "impact",
    ):
        v = d.get(key)
        if isinstance(v, str) and _is_ai_placeholder_text(v):
            d[key] = ""
    cands = d.get("root_cause_candidates")
    if not isinstance(cands, list):
        return
    cleaned = []
    for item in cands:
        if not isinstance(item, dict):
            continue
        a = (item.get("analysis") or item.get("hypothesis") or "").strip()
        if _is_ai_placeholder_text(a):
            continue
        cleaned.append(item)
    if len(cleaned) != len(cands):
        d["root_cause_candidates"] = cleaned


def enrich_parsed_from_fenced_json_in_strings(parsed: dict) -> None:
    """
    Claude sometimes returns JSON inside ```json fences *as the value* of ROOT_CAUSE, or wraps the
    whole reply. Merge WHY_HUMAN_REVIEW_REQUIRED / FLAKY_DESCRIPTION into structured fields.
    Mutates parsed in place.
    """
    if not isinstance(parsed, dict):
        return
    blobs = []
    rc = parsed.get("root_cause")
    if isinstance(rc, str) and "```" in rc:
        blobs.append(rc)
    ev = parsed.get("evidence")
    if isinstance(ev, str) and "```" in ev:
        blobs.append(ev)
    for blob in blobs:
        m = re.search(r"```(?:json)?\s*([\s\S]*?)\s*```", blob)
        if not m:
            continue
        raw_fence = m.group(1).strip()
        try:
            inner = json.loads(raw_fence)
        except (json.JSONDecodeError, TypeError):
            for pattern in (
                r'"WHY_HUMAN_REVIEW_REQUIRED"\s*:\s*"((?:[^"\\]|\\.)*)"',
                r'"WHY_REVIEW_REQUIRED"\s*:\s*"((?:[^"\\]|\\.)*)"',
                r'"NEEDS_REVIEW_DESCRIPTION"\s*:\s*"((?:[^"\\]|\\.)*)"',
            ):
                m2 = re.search(pattern, raw_fence, re.DOTALL)
                if m2:
                    txt = m2.group(1).replace("\\n", "\n").replace('\\"', '"')
                    parsed["needs_review_description"] = txt.strip()
                    parsed["root_cause"] = txt.strip()
                    break
            continue
        if not isinstance(inner, dict):
            continue
        why_hr = (
            inner.get("WHY_HUMAN_REVIEW_REQUIRED")
            or inner.get("why_human_review_required")
            or inner.get("WHY_REVIEW_REQUIRED")
            or inner.get("why_review_required")
        )
        if why_hr and not parsed.get("needs_review_description"):
            parsed["needs_review_description"] = str(why_hr).strip()
        why_fl = inner.get("WHY_FLAKY") or inner.get("why_flaky")
        fd = inner.get("FLAKY_DESCRIPTION") or inner.get("flaky_description")
        if why_fl and not parsed.get("flaky_description"):
            parsed["flaky_description"] = str(why_fl).strip()
        elif fd and not parsed.get("flaky_description"):
            parsed["flaky_description"] = str(fd).strip()
        summ = inner.get("SUMMARY") or inner.get("summary")
        if summ and not parsed.get("summary"):
            parsed["summary"] = normalize_ai_summary_noun_phrase(str(summ).strip())
        imp = inner.get("IMPACT") or inner.get("impact")
        if imp and not parsed.get("impact"):
            parsed["impact"] = str(imp).strip()
        evs = inner.get("EVIDENCE_TO_RESOLVE")
        if isinstance(evs, list) and evs and not parsed.get("recommendations"):
            parsed["recommendations"] = [str(x).strip() for x in evs if str(x).strip()]
        inner_root = inner.get("ROOT_CAUSE") or inner.get("root_cause")
        if inner_root and not str(inner_root).strip().startswith("```"):
            parsed["root_cause"] = str(inner_root).strip()
        elif parsed.get("needs_review_description"):
            parsed["root_cause"] = parsed["needs_review_description"]
        elif parsed.get("flaky_description"):
            parsed["root_cause"] = parsed["flaky_description"]
        ll = inner.get("LOG_LINE_REFERENCES") or inner.get("log_line_references")
        if isinstance(ll, list) and not parsed.get("log_line_references"):
            parsed["log_line_references"] = ll
        conf_inner = inner.get("CONFIDENCE") or inner.get("confidence")
        if conf_inner is not None and parsed.get("confidence") is None:
            try:
                parsed["confidence"] = min(100, max(0, int(float(conf_inner))))
            except (TypeError, ValueError):
                pass
        break


def finalize_ai_result_display(result: dict, ai_bucket: str) -> None:
    """
    After building the API result dict, strip any remaining ```json garbage from root_cause
    so HTML reports show readable prose (NEEDS_REVIEW / FLAKY).
    """
    if not isinstance(result, dict):
        return
    enrich_parsed_from_fenced_json_in_strings(result)
    rc = result.get("root_cause")
    if isinstance(rc, str) and rc.strip().startswith("{"):
        try:
            obj = json.loads(rc)
            if isinstance(obj, dict):
                nr = (
                    obj.get("WHY_REVIEW_REQUIRED")
                    or obj.get("WHY_HUMAN_REVIEW_REQUIRED")
                    or obj.get("NEEDS_REVIEW_DESCRIPTION")
                    or obj.get("FLAKY_DESCRIPTION")
                )
                if nr:
                    result["needs_review_description"] = str(nr).strip()
                    result["root_cause"] = str(nr).strip()
                    return
        except (json.JSONDecodeError, TypeError):
            for pattern in (
                r'"WHY_HUMAN_REVIEW_REQUIRED"\s*:\s*"((?:[^"\\]|\\.)*)"',
                r'"WHY_REVIEW_REQUIRED"\s*:\s*"((?:[^"\\]|\\.)*)"',
            ):
                m2 = re.search(pattern, rc, re.DOTALL)
                if m2:
                    txt = m2.group(1).replace("\\n", "\n").replace('\\"', '"')
                    result["needs_review_description"] = txt.strip()
                    result["root_cause"] = txt.strip()
                    return
    rc = result.get("root_cause")
    if not isinstance(rc, str):
        return
    if "```" in rc or (rc.strip().startswith("```")):
        if result.get("needs_review_description"):
            result["root_cause"] = str(result["needs_review_description"]).strip()
        elif ai_bucket == "flaky" and result.get("flaky_description"):
            result["root_cause"] = str(result["flaky_description"]).strip()
        elif result.get("summary"):
            result["root_cause"] = str(result["summary"]).strip()


def build_jira_root_cause_analysis_text(parsed, confidence, root_cause_fallback):
    """Jira field: list all root cause hypotheses (any confidence), sorted by probability descending."""
    if not isinstance(parsed, dict):
        parsed = {}
    candidates = parsed.get("root_cause_candidates") or []
    scored = []
    for item in candidates:
        if not isinstance(item, dict):
            continue
        pct = item.get("probability_percent")
        if pct is None:
            pct = item.get("probability")
        try:
            pct = int(float(pct))
        except (TypeError, ValueError):
            pct = 0
        txt = (item.get("analysis") or item.get("hypothesis") or "").strip()
        if not txt or _is_ai_placeholder_text(txt):
            continue
        scored.append((pct, txt))
    scored.sort(key=lambda x: -x[0])
    lines = [f"- ({p}%) {t}" for p, t in scored]
    try:
        cfb = int(confidence) if confidence is not None else 0
    except (TypeError, ValueError):
        cfb = 0
    if not lines and cfb >= 85 and (root_cause_fallback or "").strip():
        lines.append(f"- ({cfb}%) {(root_cause_fallback or '').strip()}")
    if not lines and (root_cause_fallback or "").strip():
        lines.append(f"- ({max(cfb, 85)}%) {(root_cause_fallback or '').strip()}")
    if not lines:
        return (
            "_(No root cause hypotheses available — see full AI report and analysis JSON.)_"
        )
    return "\n".join(lines)


def ensure_bug_ticket_narrative_fields(result: dict) -> None:
    """Fill Summary/Impact/Jira RCA when Claude omitted fields; recompute Jira RCA text."""
    if (result.get("ai_bucket") or "bug") != "bug":
        return
    sanitize_ai_string_fields(result)

    exp = str(result.get("expected") or "").strip()
    act = str(result.get("actual") or "").strip()
    summ = str(result.get("summary") or "").strip()
    rc = str(result.get("root_cause") or "").strip()
    bd = str(result.get("bug_description") or "").strip()
    low = summ.lower() if summ else ""
    bad_summary = (
        not summ
        or low.startswith("claude analysis unavailable")
        or low == "test failure analysis"
        or _summary_looks_like_sentence(summ)
    )
    if not bd:
        if summ and not bad_summary:
            result["bug_description"] = summ
        elif exp or act:
            result["bug_description"] = (
                "The automated test failed because the expected output did not match the actual application output. "
                f"Expected: {exp or 'N/A'}. Actual: {act or 'N/A'}. "
                "Review product copy, UI state, and whether test expectations need updating."
            )
        elif rc:
            result["bug_description"] = rc[:2000]

    bd = str(result.get("bug_description") or "").strip()
    summ = str(result.get("summary") or "").strip()
    low = summ.lower() if summ else ""
    bad_summary = (
        not summ
        or low.startswith("claude analysis unavailable")
        or low == "test failure analysis"
    )
    if bad_summary:
        # Prefer a derived noun-phrase title over copying a sentence from BUG_DESCRIPTION.
        derived = derive_summary_noun_phrase(result)
        if derived:
            result["summary"] = derived
        elif bd:
            line = bd.split("\n", 1)[0].strip()
            result["summary"] = (line[:100] + "…") if len(line) > 100 else line
    elif bad_summary and (exp or act):
        result["summary"] = "Assertion mismatch (expected vs actual)"

    if not str(result.get("root_cause") or "").strip() and bd:
        result["root_cause"] = bd[:2500]

    rc = str(result.get("root_cause") or "").strip()
    imp = str(result.get("impact") or "").strip()
    if not imp:
        result["impact"] = (
            "Regression automation failed; CI may block merges until behavior and expectations align. "
            "End users may see messaging inconsistent with documentation or prior releases."
        )
    cands = result.get("root_cause_candidates")
    if not isinstance(cands, list) or not cands:
        if rc and len(rc) > 20:
            result["root_cause_candidates"] = [{"analysis": rc[:1200], "probability_percent": 92}]
    result["root_cause_analysis_jira"] = build_jira_root_cause_analysis_text(
        {"root_cause_candidates": result.get("root_cause_candidates") or []},
        result.get("confidence"),
        result.get("root_cause"),
    )


def parse_claude_structured_response(ai_response):
    """
    Parse Claude output when it is JSON with SUMMARY / ROOT_CAUSE / CONFIDENCE
    (Anthropic often returns a JSON object even when the prompt asked for 'SUMMARY:' lines).
    """
    out = {}
    if not ai_response or not isinstance(ai_response, str):
        return out
    s = ai_response.strip()
    if s.startswith("```"):
        lines = s.split("\n")
        if lines and lines[0].startswith("```"):
            lines = lines[1:]
        while lines and lines[-1].strip() in ("```", ""):
            lines.pop()
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        s = "\n".join(lines).strip()

    def _from_obj(obj):
        if not isinstance(obj, dict):
            return
        summ = obj.get("SUMMARY") or obj.get("summary")
        root = obj.get("ROOT_CAUSE") or obj.get("root_cause")
        conf = obj.get("CONFIDENCE") or obj.get("confidence")
        recs = obj.get("RECOMMENDATIONS") or obj.get("recommendations")
        bug_desc = obj.get("BUG_DESCRIPTION") or obj.get("bug_description")
        flaky_desc = (
            obj.get("FLAKY_DESCRIPTION")
            or obj.get("flaky_description")
            or obj.get("WHY_FLAKY")
            or obj.get("why_flaky")
        )
        nr_desc = (
            obj.get("NEEDS_REVIEW_DESCRIPTION")
            or obj.get("needs_review_description")
            or obj.get("WHY_HUMAN_REVIEW_REQUIRED")
            or obj.get("why_human_review_required")
            or obj.get("WHY_REVIEW_REQUIRED")
            or obj.get("why_review_required")
        )
        impact = obj.get("IMPACT") or obj.get("impact")
        cands = obj.get("ROOT_CAUSE_CANDIDATES") or obj.get("root_cause_candidates")
        llref = obj.get("LOG_LINE_REFERENCES") or obj.get("log_line_references")
        if summ is not None:
            out["summary"] = normalize_ai_summary_noun_phrase(str(summ).strip())
        if root is not None:
            out["root_cause"] = str(root).strip()
        if bug_desc is not None:
            out["bug_description"] = str(bug_desc).strip()
        if flaky_desc is not None:
            out["flaky_description"] = str(flaky_desc).strip()
        if nr_desc is not None:
            out["needs_review_description"] = str(nr_desc).strip()
        if impact is not None:
            out["impact"] = str(impact).strip()
        if isinstance(cands, list):
            out["root_cause_candidates"] = cands
        if isinstance(llref, list):
            out["log_line_references"] = llref
        elif isinstance(llref, str) and llref.strip():
            out["log_line_references"] = [
                {
                    "log_file": "",
                    "line_number": 0,
                    "excerpt": llref.strip()[:800],
                    "relevance": "LOG_LINE_REFERENCES (string)",
                }
            ]
        if conf is not None:
            try:
                if isinstance(conf, (int, float)):
                    out["confidence"] = min(100, max(0, int(conf)))
                else:
                    m = re.search(r"\d+", str(conf))
                    if m:
                        out["confidence"] = min(100, max(0, int(m.group(0))))
            except (ValueError, TypeError):
                pass
        if isinstance(recs, list) and recs:
            out["recommendations"] = [str(x).strip() for x in recs if str(x).strip()]
        elif isinstance(recs, str) and recs.strip():
            out["recommendations"] = [x.strip() for x in re.split(r"[\n;]", recs) if x.strip()]

    try:
        obj = json.loads(s)
        _from_obj(obj)
        enrich_parsed_from_fenced_json_in_strings(out)
        if (
            out.get("summary")
            or out.get("root_cause")
            or out.get("bug_description")
            or out.get("flaky_description")
            or out.get("needs_review_description")
        ):
            sanitize_ai_string_fields(out)
            return out
    except (json.JSONDecodeError, TypeError):
        pass

    try:
        m = re.search(r"\{[\s\S]*\}", s)
        if m:
            obj = json.loads(m.group(0))
            out.clear()
            _from_obj(obj)
            enrich_parsed_from_fenced_json_in_strings(out)
            if (
                out.get("summary")
                or out.get("root_cause")
                or out.get("bug_description")
                or out.get("flaky_description")
                or out.get("needs_review_description")
            ):
                sanitize_ai_string_fields(out)
                return out
    except (json.JSONDecodeError, TypeError, AttributeError):
        pass

    return out


def extract_summary(ai_response):
    """Extract summary from AI response - NEW FORMAT"""
    try:
        cand = None
        if "SUMMARY:" in ai_response:
            parts = ai_response.split("SUMMARY:")
            if len(parts) > 1:
                summary_line = parts[1].split("\n")[0].strip()
                cand = summary_line[:100]
        if cand is None:
            lines = [
                l.strip()
                for l in ai_response.split("\n")
                if l.strip() and len(l.strip()) > 10
            ]
            if lines:
                cand = lines[0][:100]
        if cand is not None and _is_ai_placeholder_text(cand):
            return ""
        return normalize_ai_summary_noun_phrase(cand) if cand is not None else ""
    except Exception:
        return ""


def extract_root_cause(ai_response):
    """Extract root cause from AI response"""
    try:
        # New format: ROOT_CAUSE: [text]
        if 'ROOT_CAUSE:' in ai_response:
            parts = ai_response.split('ROOT_CAUSE:')
            if len(parts) > 1:
                return parts[1].strip()
        
        # Legacy format: Look for ROOT CAUSE section
        lines = ai_response.split('\n')
        for i, line in enumerate(lines):
            if 'ROOT CAUSE' in line.upper() or 'PRIMARY ISSUE' in line.upper():
                return '\n'.join(lines[i:min(i+5, len(lines))]).strip()
        return ai_response[:300]
    except:
        return ai_response[:300]


def extract_invalid_reason(test_params, test_data):
    """Extract invalid reason from test parameters"""
    try:
        if 'invalid_reason=' in test_params:
            # Extract from test_params e.g., "invalid_reason=SMALL_LETTERS_ONLY"
            parts = test_params.split('invalid_reason=')
            if len(parts) > 1:
                return parts[1].split(',')[0].strip()
        
        # Fallback: analyze test data
        if 'invalid_password=' in test_data:
            return "Invalid Password"
        
        return "Unknown Invalid Data"
    except:
        return "Unknown"


def extract_confidence(ai_response):
    """Extract confidence level from AI response"""
    try:
        # New format: CONFIDENCE: [number]
        if 'CONFIDENCE:' in ai_response:
            parts = ai_response.split('CONFIDENCE:')
            if len(parts) > 1:
                import re
                numbers = re.findall(r'\d+', parts[1].split('\n')[0])
                if numbers:
                    conf = int(numbers[0])
                    return min(100, max(0, conf))
        
        # Legacy format: look for confidence in text
        lines = ai_response.split('\n')
        for line in lines:
            if 'confidence' in line.lower() or '%' in line:
                import re
                numbers = re.findall(r'\d+', line)
                if numbers:
                    conf = int(numbers[-1])
                    return min(100, max(0, conf))
        return 75  # Default confidence
    except:
        return 75


def extract_recommendations(ai_response):
    """Extract recommendations from AI response"""
    try:
        recommendations = []
        lines = ai_response.split('\n')
        capture = False
        
        for line in lines:
            # Match EN "recommendation" or KO section headings some models emit (\uCD94\uCC9C=recommendation, \uC870\uCE58=action)
            if (
                "recommendation" in line.lower()
                or "\uCD94\uCC9C" in line
                or "\uC870\uCE58" in line
            ):
                capture = True
            elif capture:
                if line.strip().startswith('-') or line.strip().startswith('•'):
                    recommendations.append(line.strip()[1:].strip())
        
        return recommendations if recommendations else ["See AI analysis for details"]
    except:
        return ["See AI analysis for details"]


def extract_evidence(ai_response):
    """Extract evidence from AI response"""
    try:
        lines = ai_response.split('\n')
        for i, line in enumerate(lines):
            # English "evidence" or Korean heading (\uC99D\uAC70) in some model outputs
            if "evidence" in line.lower() or "\uC99D\uAC70" in line:
                return '\n'.join(lines[i:min(i+3, len(lines))]).strip()
        return ai_response[:300]
    except:
        return ai_response[:300]


@app.route('/health', methods=['GET'])
def health():
    """Health check endpoint"""
    return jsonify({"status": "healthy", "service": "MCP Orchestrator"}), 200


if __name__ == '__main__':
    print("🚀 MCP Orchestrator starting on http://localhost:5000")
    print(f"📌 Slack Webhook configured: {bool(SLACK_WEBHOOK_URL)}")
    import sys
    _flask_debug = os.getenv('MCP_FLASK_DEBUG', '').lower() in ('1', 'true', 'yes')
    print(
        f'Flask debug={_flask_debug} (MCP_FLASK_DEBUG=1 for dev; reloader off for Jenkins)',
        file=sys.stdout,
        flush=True,
    )
    app.run(host='127.0.0.1', port=5000, debug=_flask_debug, use_reloader=False, threaded=True)
