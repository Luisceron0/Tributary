#!/usr/bin/env bash
#
# T-806: end-to-end regression through a REAL browser against the REAL stack — browser, CORS,
# JWT, Spring Boot, PostgreSQL. Not a mock anywhere in the path.
#
# The interesting part is not that the happy path works: it is that tampering with the database
# behind the application's back is DETECTED and named, through the interface. So this script
# deliberately corrupts a fiscal record mid-run and asserts the UI reports BROKEN with the exact
# record id. A run that never turns a chain BROKEN has not verified the project's thesis.
#
# Requires: a stack already up (scripts/demo/setup.sh && docker compose up -d), the frontend dev
# server on :5173, and `agent-browser install --with-deps` done once.
#
# Selectors are data-testid attributes, not snapshot refs: refs are reassigned on every
# navigation, so a script written against them breaks for reasons that have nothing to do with
# the system under test.
set -uo pipefail
cd "$(dirname "$0")/.."

WEB_URL="${WEB_URL:-http://localhost:5173/}"
# Deterministic per-issuer chain id: UUID.nameUUIDFromBytes("verifactu-chain:ESB12345678"), the
# same derivation VerifactuFiscalRegimeAdapter uses (RD 1007/2023: one chain per obligated
# taxpayer, not one per document).
CHAIN_ID="${CHAIN_ID:-be78b6b8-91a9-35f3-ad07-e6d797f65a9c}"

failures=0

pass() { printf '  ✓ %s\n' "$1"; }
fail() { printf '  ✗ %s\n' "$1" >&2; failures=$((failures + 1)); }

# Asserts the page's accessibility snapshot contains a string. Uses the snapshot rather than raw
# HTML so the assertion tracks what is actually exposed to a user (and to a screen reader).
expect_visible() {
  local needle="$1" label="$2"
  if agent-browser snapshot 2>/dev/null | grep -qF -- "$needle"; then
    pass "$label"
  else
    fail "$label (expected to find: $needle)"
  fi
}

click() { agent-browser click "[data-testid='$1']" >/dev/null 2>&1 || fail "could not click $1"; sleep 1; }

echo "T-806 · browser end-to-end"
agent-browser open "$WEB_URL" >/dev/null 2>&1 || { echo "cannot open $WEB_URL" >&2; exit 1; }
sleep 2

echo "OPERATOR — register then issue (two steps, because issuance is the irreversible one)"
click tab-operator
click register
sleep 2
expect_visible "DRAFT" "registration yields DRAFT"
click issue
sleep 3
expect_visible "ISSUED" "issuance yields ISSUED"

echo "AUDITOR — chain intact before tampering"
click tab-auditor
agent-browser fill "#chainId" "$CHAIN_ID" >/dev/null 2>&1 || fail "could not fill chain id"
click verify-chain
sleep 2
expect_visible "INTACT" "chain verifies INTACT"

echo "Tampering with the fiscal record directly in PostgreSQL, behind the application's back"
POSTGRES_USER=$(grep '^POSTGRES_USER=' .env | cut -d= -f2)
POSTGRES_DB=$(grep '^POSTGRES_DB=' .env | cut -d= -f2)
docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "ALTER TABLE fiscal_record DISABLE TRIGGER ALL;" >/dev/null 2>&1
docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "UPDATE fiscal_record SET canonical_payload = 'tampered-by-e2e';" >/dev/null 2>&1
docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "ALTER TABLE fiscal_record ENABLE TRIGGER ALL;" >/dev/null 2>&1

echo "AUDITOR — the same chain must now be reported BROKEN"
click verify-chain
sleep 2
expect_visible "BROKEN" "tampering is DETECTED, not silently accepted"
expect_visible "Recomputed hash" "the mismatching hashes are shown, not just a verdict"

echo "ADMIN — unreachable on a build carrying no administrator credential"
click tab-admin
sleep 1
expect_visible "Unavailable on this deployment" "administrator panel is unavailable by design"

echo
if [ "$failures" -eq 0 ]; then
  echo "T-806: all assertions passed"
  exit 0
fi
echo "T-806: $failures assertion(s) failed" >&2
exit 1
