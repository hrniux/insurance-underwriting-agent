#!/usr/bin/env bash
# Build, own one loopback-only Mock process, verify, and clean it up.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
PORT="${DEMO_PORT:-18080}"
OUTPUT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --output-dir) [[ $# -ge 2 ]] || { echo '--output-dir requires a path' >&2; exit 2; }; OUTPUT="$2"; shift 2 ;;
    *) echo "Unknown argument: $1 (supported: --output-dir PATH)" >&2; exit 2 ;;
  esac
done
if [[ -z "$OUTPUT" ]]; then
  OUTPUT="$ROOT/target/demo-evidence/$(python3 -c 'import datetime,uuid; print(datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")+"-"+uuid.uuid4().hex[:10])')"
fi
mkdir -p "$OUTPUT"
OUTPUT="$(cd "$OUTPUT" && pwd)"
# Never mix runs or overwrite an earlier report.
python3 - "$OUTPUT" <<'PY'
from pathlib import Path
import sys
if any(Path(sys.argv[1]).iterdir()):
    raise SystemExit('Output directory must be empty; choose a new directory.')
PY
APP_PID=""
STAGE="preflight"
cleanup() {
  local result=$?
  trap - EXIT INT TERM
  if [[ -n "$APP_PID" ]]; then
    if kill -0 "$APP_PID" 2>/dev/null; then
      kill "$APP_PID" 2>/dev/null || true
      for ((attempt=0; attempt<50; attempt++)); do
        kill -0 "$APP_PID" 2>/dev/null || break
        sleep 0.1
      done
      kill -KILL "$APP_PID" 2>/dev/null || true
    fi
    wait "$APP_PID" 2>/dev/null || true
  fi
  if [[ ! -f "$OUTPUT/summary.json" ]]; then
    python3 - "$OUTPUT" "$STAGE" "$result" <<'PY'
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import sys
try:
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
except Exception:
    commit = 'unavailable'
summary = {'passed': False, 'finishedAtUtc': datetime.now(timezone.utc).isoformat(),
           'gitCommit': commit, 'models': [], 'stage': sys.argv[2],
           'error': 'Launcher failed at ' + sys.argv[2], 'exitCode': int(sys.argv[3]),
           'scope': 'Fictitious data; deterministic Mock HTTP acceptance, not real LLM quality.'}
(Path(sys.argv[1]) / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
PY
  fi
  echo "Evidence: $OUTPUT"
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
python3 - "$PORT" <<'PY'
import socket
import sys
try:
    port = int(sys.argv[1])
    if not 1 <= port <= 65535:
        raise ValueError()
except ValueError:
    raise SystemExit('DEMO_PORT must be between 1 and 65535')
with socket.socket() as probe:
    probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        probe.bind(('127.0.0.1', port))
    except OSError:
        raise SystemExit('Requested port is occupied; refusing to reuse another service.')
PY
STAGE="build"
echo 'Building and running the Java test suite (see build.log)...'
# MAVEN_ARGS is handled by Maven itself; no shell eval or repository-specific proxy configuration.
python3 - "$OUTPUT/build.log" <<'PY'
import subprocess
import sys
with open(sys.argv[1], 'wb') as log:
    try:
        result = subprocess.run(['bash', './mvnw', '-B', 'verify'], stdout=log, stderr=subprocess.STDOUT, timeout=600)
    except subprocess.TimeoutExpired:
        raise SystemExit('Maven build exceeded 600 seconds; inspect build.log')
if result.returncode:
    raise SystemExit('Maven build failed; inspect build.log')
PY
JAVA_BIN="java"
if [[ -n "${JAVA_HOME:-}" ]]; then JAVA_BIN="$JAVA_HOME/bin/java"; fi
STAGE="startup"
# Command-line properties override inherited application config. Clear JVM option injection for the child.
env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS \
  "$JAVA_BIN" -jar target/insurance-underwriting-agent-demo-1.0.0-SNAPSHOT.jar \
  --server.address=127.0.0.1 --server.port="$PORT" \
  --spring.profiles.active=default --spring.profiles.include= \
  --app.agent.model.provider=mock --app.agent.model.model=deterministic-underwriter-v1 \
  --app.agent.model.fallback-to-mock=false >"$OUTPUT/application.log" 2>&1 &
APP_PID=$!
BASE_URL="http://127.0.0.1:$PORT"
ready=false
for ((attempt=0; attempt<60; attempt++)); do
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    echo 'Owned application process exited during startup; inspect application.log.' >&2
    exit 1
  fi
  if python3 - "$BASE_URL" "$OUTPUT/application.log" <<'PY'
import runpy
import sys
try:
    runpy.run_path('scripts/verify-demo.py')['probe_ready'](sys.argv[1], sys.argv[2])
except Exception:
    raise SystemExit(1)
PY
  then
    kill -0 "$APP_PID" 2>/dev/null || exit 1
    ready=true
    break
  fi
  sleep 1
done
if [[ "$ready" != true ]]; then
  echo 'Application did not become ready within 60 bounded probes; inspect application.log.' >&2
  exit 1
fi
STAGE="verification"
python3 scripts/verify-demo.py --base-url "$BASE_URL" --output-dir "$OUTPUT"
