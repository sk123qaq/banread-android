#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TLA_VERSION="v1.8.0"
TLA_JAR="${TLA2TOOLS_JAR:-${SCRIPT_DIR}/.cache/tla2tools-${TLA_VERSION}.jar}"
CFG_NAME="${1:-ReaderWatermark.cfg}"

if [[ ! -f "${TLA_JAR}" ]]; then
  mkdir -p "$(dirname "${TLA_JAR}")"
  echo "Downloading tla2tools.jar (${TLA_VERSION})..."
  curl -fsSL -o "${TLA_JAR}" \
    "https://github.com/tlaplus/tlaplus/releases/download/${TLA_VERSION}/tla2tools.jar"
fi

echo "Running TLC on ${SCRIPT_DIR}/ReaderWatermark.tla with ${CFG_NAME}..."
java -XX:+UseParallelGC -cp "${TLA_JAR}" tlc2.TLC \
  -deadlock \
  -workers auto \
  -cleanup \
  -config "${SCRIPT_DIR}/${CFG_NAME}" \
  "${SCRIPT_DIR}/ReaderWatermark.tla"
