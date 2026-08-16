#!/usr/bin/env bash
#
# Installs the KoSIT validator (ADR-008, CV-11, T-012) used to check generated XRechnung XML for
# real EN 16931 / XRechnung 3.0 conformance — the reference implementation of the German
# ecosystem, not this project's own self-checking.
#
# Two artefacts, both third-party, both treated as supply chain (same reasoning as
# install-gitleaks.sh, which this script mirrors):
#   1. The validator engine itself (itplr-kosit/validator) — the executable that runs the checks.
#   2. The XRechnung validator configuration (itplr-kosit/validator-configuration-xrechnung) — the
#      XSD + Schematron scenario package the engine needs to know what "XRechnung 3.0 conformant"
#      actually means. A compromised or substituted config could make the engine silently accept
#      non-conformant documents just as effectively as a compromised engine binary could — the
#      threat T-012 describes applies to both, not just the jar.
#
# Fail-closed: any checksum mismatch aborts before either archive is unpacked or installed.

set -euo pipefail

VALIDATOR_VERSION="1.6.2"
VALIDATOR_JAR="validator-${VALIDATOR_VERSION}-standalone.jar"
VALIDATOR_URL="https://github.com/itplr-kosit/validator/releases/download/v${VALIDATOR_VERSION}/${VALIDATOR_JAR}"

CONFIG_RELEASE="v2026-01-31"
CONFIG_ARCHIVE="xrechnung-3.0.2-validator-configuration-2026-01-31.zip"
CONFIG_URL="https://github.com/itplr-kosit/validator-configuration-xrechnung/releases/download/${CONFIG_RELEASE}/${CONFIG_ARCHIVE}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
VALIDATOR_DIR="${REPO_ROOT}/validator"
CHECKSUM_FILE="${VALIDATOR_DIR}/kosit.sha256"

if [ ! -f "${CHECKSUM_FILE}" ]; then
  echo "CHECKSUM FILE MISSING — refusing to install without ${CHECKSUM_FILE}" >&2
  exit 1
fi

if [ -f "${VALIDATOR_DIR}/${VALIDATOR_JAR}" ] && [ -f "${VALIDATOR_DIR}/scenarios/scenarios.xml" ]; then
  echo "KoSIT validator ${VALIDATOR_VERSION} and its XRechnung config already installed at ${VALIDATOR_DIR}"
  exit 0
fi

workdir="$(mktemp -d)"
trap 'rm -rf "${workdir}"' EXIT

echo "Downloading the KoSIT validator ${VALIDATOR_VERSION} from the official release..."
curl --fail --silent --show-error --location --max-time 120 \
  -o "${workdir}/${VALIDATOR_JAR}" "${VALIDATOR_URL}"

echo "Downloading the XRechnung validator configuration ${CONFIG_RELEASE} from the official release..."
curl --fail --silent --show-error --location --max-time 120 \
  -o "${workdir}/${CONFIG_ARCHIVE}" "${CONFIG_URL}"

echo "Verifying both artefacts' SHA-256 against ${CHECKSUM_FILE}..."
if ! (cd "${workdir}" && sha256sum -c "${CHECKSUM_FILE}"); then
  echo "CHECKSUM MISMATCH — refusing to install either artefact." >&2
  exit 1
fi
echo "  ok: both artefacts verified"

mkdir -p "${VALIDATOR_DIR}/scenarios"
install -m 0644 "${workdir}/${VALIDATOR_JAR}" "${VALIDATOR_DIR}/${VALIDATOR_JAR}"
unzip -q -o "${workdir}/${CONFIG_ARCHIVE}" -d "${VALIDATOR_DIR}/scenarios"

echo "Installed: ${VALIDATOR_DIR}/${VALIDATOR_JAR}"
echo "Installed: ${VALIDATOR_DIR}/scenarios/ (scenarios.xml + XSD/Schematron resources)"
