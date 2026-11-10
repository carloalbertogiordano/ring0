#!/usr/bin/env bash
# Kotlin quality gate: ktlint (style), detekt (smells, complexity), unit tests, debug build.
set -euo pipefail
cd "$(dirname "$0")"
step() { printf '\n== %s ==\n' "$1"; }
step "ktlint + detekt"
./gw.sh qualityGate
step "unit tests"
./gw.sh :protocol:runTests :domain:runTests
step "app build"
./gw.sh :app:assembleDebug
echo
echo "ALL GREEN"
