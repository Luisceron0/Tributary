#!/usr/bin/env bash
# T-707: generates docs/openapi.json from the real, running application — never hand-written,
# so contract drift shows up as a diff against this file instead of silently going stale.
#
# /v3/api-docs is reachable ONLY when tributary.openapi.export-enabled=true (see SecurityConfig)
# — this script is the one place that sets it, against a throwaway local instance never exposed
# beyond localhost. A real deployment never sets this flag, so ADR-009's "exactly one
# unauthenticated route" claim stays true outside this script's own run.
set -euo pipefail
cd "$(dirname "$0")/.."

WORKDIR=$(mktemp -d)
trap 'docker rm -f openapi-export-pg >/dev/null 2>&1 || true; kill "${APP_PID:-0}" >/dev/null 2>&1 || true; rm -rf "$WORKDIR"' EXIT

openssl genrsa -out "$WORKDIR/key.pem" 2048 2>/dev/null
openssl rsa -in "$WORKDIR/key.pem" -pubout -out "$WORKDIR/pub.pem" 2>/dev/null
PUBLIC_KEY=$(tr -d '\n' < "$WORKDIR/pub.pem")

echo "Starting a throwaway Postgres..."
docker run -d --name openapi-export-pg -e POSTGRES_DB=tributary -e POSTGRES_USER=tributary_owner \
  -e POSTGRES_PASSWORD=export-only -p 15433:5432 postgres:16 >/dev/null
until docker exec openapi-export-pg pg_isready -U tributary_owner >/dev/null 2>&1; do sleep 1; done

mvn -q -pl tributary-api -am package -DskipTests

echo "Starting the app with OpenAPI export enabled..."
TRIBUTARY_DB_URL="jdbc:postgresql://localhost:15433/tributary" \
TRIBUTARY_DB_USERNAME="tributary_owner" \
TRIBUTARY_DB_PASSWORD="export-only" \
TRIBUTARY_JWT_PUBLIC_KEY="$PUBLIC_KEY" \
TRIBUTARY_ALLOWED_HOSTS="localhost" \
TRIBUTARY_REGIME="ES" \
TRIBUTARY_SERVER_PORT="18081" \
  java -jar tributary-api/target/tributary-api-*.jar \
  --tributary.openapi.export-enabled=true > "$WORKDIR/app.log" 2>&1 &
APP_PID=$!

echo "Waiting for the app to become ready..."
for _ in $(seq 1 60); do
  if curl -s -o /dev/null "http://localhost:18081/v3/api-docs"; then break; fi
  sleep 1
done

mkdir -p docs
curl -s "http://localhost:18081/v3/api-docs" -o docs/openapi.json
python3 -m json.tool docs/openapi.json > "$WORKDIR/pretty.json" && mv "$WORKDIR/pretty.json" docs/openapi.json

OPENAPI_VERSION=$(python3 -c "import json; print(json.load(open('docs/openapi.json'))['openapi'])")
echo "Wrote docs/openapi.json (openapi: ${OPENAPI_VERSION})"
