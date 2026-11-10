#!/usr/bin/env bash
# Quality gate: lint, tipi, code smell, duplicati, complessita', dead code, security, test+coverage.
set -euo pipefail
cd "$(dirname "$0")"
PY=.venv/bin
step() { printf '\n== %s ==\n' "$1"; }
step ruff;    $PY/ruff check ringproto tests && $PY/ruff format --check ringproto tests
step mypy;    $PY/mypy
step pylint;  $PY/pylint ringproto   # include R0801 (codice duplicato)
step radon;   out=$($PY/radon cc ringproto -n C -s); [ -z "$out" ] || { echo "$out"; echo "complessita' >= C"; exit 1; }
step vulture; $PY/vulture ringproto --min-confidence 80
step bandit;  $PY/bandit -q -r ringproto
step pytest;  $PY/pytest -q
echo; echo "ALL GREEN"
