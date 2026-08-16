#!/usr/bin/env bash
# T-703: runs this project's custom Semgrep rules against real source, and separately proves
# each rule still fires on its own deliberately-vulnerable fixture before trusting the "clean"
# result on real code — a rule that silently stopped matching would otherwise look identical to
# a genuinely clean codebase.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== verifying rules still fire on deliberately-vulnerable fixtures =="
FIXTURE_FINDINGS=$(semgrep --config .semgrep/tributary-rules.yml .semgrep/fixtures/ --json --quiet | python3 -c "import json,sys; print(len(json.load(sys.stdin)['results']))")
EXPECTED_FIXTURE_FINDINGS=4
if [ "$FIXTURE_FINDINGS" -ne "$EXPECTED_FIXTURE_FINDINGS" ]; then
  echo "FAIL: expected $EXPECTED_FIXTURE_FINDINGS findings on the vulnerable fixtures, got $FIXTURE_FINDINGS — a rule stopped matching."
  exit 1
fi
echo "OK: $FIXTURE_FINDINGS/$EXPECTED_FIXTURE_FINDINGS expected findings on fixtures."

echo "== scanning real source with the same rules =="
semgrep --config .semgrep/tributary-rules.yml \
  tributary-domain tributary-application tributary-adapter-co-factus \
  tributary-adapter-es-verifactu tributary-adapter-de-en16931 tributary-persistence tributary-api \
  --error
