#!/usr/bin/env bash
# T-705: mints an RS256 JWT signed with the demo keypair (scripts/demo/generate-keypair.sh),
# for walking through the API without a real authorization server. Pure bash + openssl — no
# language runtime or library dependency beyond what generate-keypair.sh already needs.
#
# Usage: mint-token.sh <subject> <role> [ttl-seconds]
set -euo pipefail
cd "$(dirname "$0")/../.."

SUBJECT="${1:?usage: mint-token.sh <subject> <role> [ttl-seconds]}"
ROLE="${2:?usage: mint-token.sh <subject> <role> [ttl-seconds]}"
TTL="${3:-3600}"

PRIVATE_KEY=".demo/private.pem"
if [ ! -f "$PRIVATE_KEY" ]; then
  echo "No demo keypair at $PRIVATE_KEY — run scripts/demo/generate-keypair.sh first." >&2
  exit 1
fi

b64url() {
  openssl base64 -A | tr '+/' '-_' | tr -d '='
}

NOW=$(date +%s)
EXP=$((NOW + TTL))

HEADER=$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)
PAYLOAD=$(printf '{"sub":"%s","role":"%s","iat":%d,"exp":%d}' "$SUBJECT" "$ROLE" "$NOW" "$EXP" | b64url)
SIGNING_INPUT="${HEADER}.${PAYLOAD}"
SIGNATURE=$(printf '%s' "$SIGNING_INPUT" | openssl dgst -sha256 -sign "$PRIVATE_KEY" | b64url)

echo "${SIGNING_INPUT}.${SIGNATURE}"
