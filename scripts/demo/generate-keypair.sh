#!/usr/bin/env bash
# T-705: generates the RSA keypair the demo stack signs/verifies JWTs with. This service has no
# login endpoint by design (SecurityConfig.java's own Javadoc: "pure OAuth2 resource server that
# verifies tokens issued elsewhere") — a real deployment gets its public key from an actual
# authorization server; a demo walkthrough needs a throwaway keypair minted locally instead.
#
# Output goes under .demo/, which is gitignored (*.pem is already covered, .demo/ added
# explicitly) — this keypair is generated fresh per clone, never versioned, never reused as a
# real credential.
set -euo pipefail
cd "$(dirname "$0")/../.."

DEMO_DIR=".demo"
mkdir -p "$DEMO_DIR"

if [ -f "$DEMO_DIR/private.pem" ] && [ -f "$DEMO_DIR/public.pem" ]; then
  echo "Demo keypair already exists at $DEMO_DIR/ — leaving it in place."
  exit 0
fi

openssl genrsa -out "$DEMO_DIR/private.pem" 2048 2>/dev/null
openssl rsa -in "$DEMO_DIR/private.pem" -pubout -out "$DEMO_DIR/public.pem" 2>/dev/null
chmod 600 "$DEMO_DIR/private.pem"

echo "Demo keypair generated at $DEMO_DIR/."
