#!/usr/bin/env bash
# Open Birdy settings GUI (source or next to a packaged binary).
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -x "$HERE/Birdy" ]]; then
  exec "$HERE/Birdy" --settings
elif [[ -x "$HERE/dist/Birdy" ]]; then
  exec "$HERE/dist/Birdy" --settings
else
  exec python3 "$HERE/birdweather_local.py" --settings
fi
