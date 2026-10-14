#!/usr/bin/env bash
# Re-renders the store icon and feature graphic from docs/brand/ with headless Chromium.
#   scripts/release/render-store-images.sh [path/to/chromium-or-headless_shell]
set -euo pipefail
CHROME="${1:-chromium}"
OUT=fastlane/metadata/android/en-US/images
TMP="$(mktemp -d)"
printf '<!doctype html><style>html,body{margin:0;width:512px;height:512px;overflow:hidden}svg{display:block}</style>%s' \
  "$(cat docs/brand/icon.svg)" > "$TMP/icon.html"
"$CHROME" --headless --no-sandbox --hide-scrollbars --window-size=512,512 --screenshot="$OUT/icon.png" "file://$TMP/icon.html"
"$CHROME" --headless --no-sandbox --hide-scrollbars --window-size=1024,500 --screenshot="$OUT/featureGraphic.png" "file://$PWD/docs/brand/feature-graphic.html"
echo "Rendered $OUT/icon.png and $OUT/featureGraphic.png"
