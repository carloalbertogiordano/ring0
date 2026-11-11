#!/usr/bin/env bash
# Downloads the Colmi R09 firmware image from the vendor's own update server, as their app does. RUN IT YOURSELF.
#
# The server only answers logged-in clients, so this registers a throwaway account with an invented address and a random password,
# logs in, asks for the update for hardware RT09CR2_V3.0 and downloads the file. Nothing personal is sent: no MAC, no country, no real
# email. The image is stored in refs/firmware/ (git-ignored) and is never flashed by this script.
#
#   ./scripts/fetch-r09-firmware.sh
set -euo pipefail
umask 077
BASE="https://api1.qcwxkjvip.com/qcwx"
HW="${HW:-RT09CR2_V3.0}"
ROM="${ROM:-RT09CR2_3.00.00_000000}"   # an old version on purpose: the server then offers the newest image
OUT="$(dirname "$0")/../refs/firmware"
command -v curl >/dev/null && command -v jq >/dev/null || { echo "needs curl and jq" >&2; exit 1; }
echo "This creates a throwaway account on the vendor's server (api1.qcwxkjvip.com) and downloads one firmware file."
read -r -p "Continue? [y/N] " a; [ "$a" = "y" ] || exit 1
mkdir -p "$OUT"
ACCOUNT="ring0.fwresearch.$(openssl rand -hex 5)@example.org"
PASSWORD="$(openssl rand -base64 18 | tr -d '=+/')"
post() { curl -sS -m 40 -X POST "$BASE/$1" -H 'Content-Type: application/json' ${TOKEN:+-H "token: $TOKEN"} -d "$2"; }

echo "1/4 register"; post users/register/v1 "{\"account\":\"$ACCOUNT\",\"password\":\"$PASSWORD\",\"type\":1}" | jq -c '{retCode,message}'
echo "2/4 login";    RESP="$(post users/login/v1 "{\"account\":\"$ACCOUNT\",\"password\":\"$PASSWORD\",\"type\":1}")"
echo "$RESP" | jq -c '{retCode,message}'
TOKEN="$(echo "$RESP" | jq -r '.data.token // .data.userToken // empty')"
[ -n "$TOKEN" ] || { echo "no token in the login response (see keys below)"; echo "$RESP" | jq 'del(.data.password)' | head -30; exit 1; }
echo "3/4 ask for the firmware"
OTA="$(post app-update/last-ota "{\"appId\":1,\"uid\":1,\"hardwareVersion\":\"$HW\",\"romVersion\":\"$ROM\",\"os\":1,\"mac\":\"\",\"country\":\"\",\"dev\":2}")"
echo "$OTA" | jq .
URL="$(echo "$OTA" | jq -r '.data.downloadUrl // empty')"; VER="$(echo "$OTA" | jq -r '.data.version // "unknown"')"
[ -n "$URL" ] || { echo "the server offered no image (it may think the ring is up to date)"; exit 1; }
echo "4/4 download $VER"
curl -sS -m 300 -L -o "$OUT/${VER}.bin" "$URL"
ls -l "$OUT/${VER}.bin"; sha256sum "$OUT/${VER}.bin"
echo "Saved. Tell Claude the file name; it will only analyse it (no flashing)."
