#!/usr/bin/env bash
# T-904/T-906: scheduled reset for the public demo — a fresh database (no accumulated state from
# strangers on the internet poking at it), a fresh JWT keypair, and freshly minted tokens
# (invalidating every previously published one, including any leaked beyond the page itself —
# T-013's own compensating control, not just a hygiene habit).
#
# Run by deploy/tributary-reset.timer (see docs/deployment.md for installing it). Idempotent and
# safe to run manually.
set -euo pipefail
cd "$(dirname "$0")/.."

DOMAIN="${1:?usage: deploy/reset.sh <domain> — same one passed to setup-prod-env.sh}"

echo "$(date -u +%FT%TZ) reset starting"
docker compose -f docker-compose.prod.yml down -v
./deploy/setup-prod-env.sh "$DOMAIN"
docker compose -f docker-compose.prod.yml up --build -d
echo "$(date -u +%FT%TZ) reset complete"
