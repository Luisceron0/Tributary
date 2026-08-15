#!/usr/bin/env bash
#
# Installs the gitleaks binary used by the pre-commit hook and by CI (CV-06).
#
# The version and the SHA-256 are pinned here and versioned in the repository;
# the binary itself is not committed. A secret scanner that is fetched without
# verification is a supply-chain sink with the aggravating property that a
# tampered one reports "0 findings" and nobody looks again — the same reasoning
# SRS T-012 applies to the KoSIT validator.
#
# Fail-closed: any mismatch aborts before the archive is unpacked.

set -euo pipefail

GITLEAKS_VERSION="8.30.1"
GITLEAKS_SHA256="551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb"
GITLEAKS_ARCHIVE="gitleaks_${GITLEAKS_VERSION}_linux_x64.tar.gz"
GITLEAKS_URL="https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}/${GITLEAKS_ARCHIVE}"

INSTALL_DIR="${GITLEAKS_INSTALL_DIR:-$HOME/.local/bin}"

if command -v gitleaks >/dev/null 2>&1 &&
   gitleaks version 2>/dev/null | grep -qx "${GITLEAKS_VERSION}"; then
  echo "gitleaks ${GITLEAKS_VERSION} already installed at $(command -v gitleaks)"
  exit 0
fi

workdir="$(mktemp -d)"
trap 'rm -rf "${workdir}"' EXIT

echo "Downloading gitleaks ${GITLEAKS_VERSION} from the official release..."
curl --fail --silent --show-error --location --max-time 120 \
  -o "${workdir}/${GITLEAKS_ARCHIVE}" "${GITLEAKS_URL}"

echo "Verifying SHA-256 against the pinned value..."
actual="$(sha256sum "${workdir}/${GITLEAKS_ARCHIVE}" | awk '{print $1}')"
if [ "${actual}" != "${GITLEAKS_SHA256}" ]; then
  echo "CHECKSUM MISMATCH — refusing to install." >&2
  echo "  expected: ${GITLEAKS_SHA256}" >&2
  echo "  actual:   ${actual}" >&2
  exit 1
fi
echo "  ok: ${actual}"

tar -xzf "${workdir}/${GITLEAKS_ARCHIVE}" -C "${workdir}" gitleaks
mkdir -p "${INSTALL_DIR}"
install -m 0755 "${workdir}/gitleaks" "${INSTALL_DIR}/gitleaks"

echo "Installed: ${INSTALL_DIR}/gitleaks ($("${INSTALL_DIR}/gitleaks" version))"
if ! command -v gitleaks >/dev/null 2>&1; then
  echo "WARNING: ${INSTALL_DIR} is not on your PATH. Add it before committing." >&2
fi
