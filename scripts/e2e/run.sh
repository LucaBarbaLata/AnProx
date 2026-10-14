#!/usr/bin/env bash
# Starts the mock Proxmox servers, runs the end-to-end tour on the connected emulator/device,
# then stops the mock. Used by CI (.github/workflows/ci.yml, release.yml); also works locally:
#
#   ./gradlew :app:assembleRelease :tools:mock-server:installDist -Panprox.signWithDebugKey=true
#   scripts/e2e/run.sh app/build/outputs/apk/release/app-release.apk
set -euo pipefail

APK="${1:?usage: run.sh path/to/app.apk}"
OUT="${ANPROX_E2E_OUT:-e2e-output}"
mkdir -p "$OUT"

MOCK=tools/mock-server/build/install/mock-server/bin/mock-server
if [ ! -x "$MOCK" ]; then
  echo "Mock server not built. Run: ./gradlew :tools:mock-server:installDist" >&2
  exit 1
fi

"$MOCK" > "$OUT/mock-server.log" 2>&1 &
MOCK_PID=$!
trap 'kill $MOCK_PID 2>/dev/null || true' EXIT

for _ in $(seq 1 60); do
  if curl -sk --max-time 2 https://127.0.0.1:8006/api2/json/access/domains > /dev/null; then break; fi
  sleep 1
done
curl -sk --max-time 2 https://127.0.0.1:8007/api2/json/access/domains > /dev/null || { echo "Mock server did not start" >&2; cat "$OUT/mock-server.log" >&2; exit 1; }

python3 scripts/e2e/tour.py "$APK"
