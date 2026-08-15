#!/usr/bin/env bash
#
# Points git at the versioned hooks in .githooks/ and makes sure the scanner
# they depend on is present. Run once after cloning.

set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "${repo_root}"

./scripts/install-gitleaks.sh

chmod +x .githooks/*
git config core.hooksPath .githooks

echo "core.hooksPath = $(git config core.hooksPath)"
echo "Hooks installed. Secrets are scanned on every commit (CV-06)."
