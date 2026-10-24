#!/bin/sh
# Runs hub-test.py against tests/hub/fake.py (a stand-in hub and bridge), to check the script itself.
set -eu
cd "$(dirname "$0")"
tmp=$(mktemp -d)
trap 'kill $pid 2>/dev/null; rm -rf "$tmp"' EXIT
python3 fake.py > "$tmp/port" &
pid=$!
while [ ! -s "$tmp/port" ]; do sleep 0.1; done
port=$(cat "$tmp/port")
cat > "$tmp/hubtest.json" <<EOF
{"hub": "127.0.0.1:$port", "hubUrl": "http://127.0.0.1:$port", "makerApiId": 5, "makerToken": "fake-token",
 "bridge": "127.0.0.1", "bridgeUrl": "http://127.0.0.1:$port", "logs": false,
 "lights": ["Desk lamp", "Hall bulb"], "room": "Office", "scene": "Bright (Office)",
 "smartScene": "Natural light (Office)", "motionSensor": "Hall sensor"}
EOF
python3 hub-test.py --config "$tmp/hubtest.json" link
python3 hub-test.py --config "$tmp/hubtest.json" check
python3 hub-test.py --config "$tmp/hubtest.json" deploy --dry-run
python3 hub-test.py --config "$tmp/hubtest.json" --yes all
