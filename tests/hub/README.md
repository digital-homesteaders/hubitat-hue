# Hub tests

`hub-test.py` tests Hue Connect on a real hub and bridge. It sends commands
through Hubitat's Maker API and reads the result from the bridge itself. It
also changes lights on the bridge, as the Hue app would, and checks that
Hubitat follows. While it runs it watches the hub's live log and fails on any
warning or error from Hue Connect. At the end it puts every light it touched
back the way it was.

It needs Python 3.9 or later and nothing else.

## Setup (once)

1. **Maker API:** Apps → Add Built-In App → Maker API.
   - Select the Hue Bridge device and the Hue devices you'll test.
   - Leave "Allow access via the following IP address" empty, or enter your Mac's IP address.
   - Note the app id and the `access_token` from its local URLs.
2. Copy the example config and fill it in:

   ```sh
   cp tests/hub/hubtest.example.json tests/hub/hubtest.json
   ```

   It's git-ignored. It names only the devices the script may change, using
   their Hubitat labels.
   - If you renamed a device in Hubitat, use `{"device": "<Hubitat label>", "hue": "<Hue name>"}`.
   - Leave `room`, `scene`, `smartScene` or `motionSensor` empty to skip that test.
   - Add `hubUser` and `hubPassword` only if hub security is on. The memory figures and the log watch need them.
3. **Test key:** run the command below and press the bridge button within 30 s.

   ```sh
   tests/hub/hub-test.py link
   ```

   This gets the script its own key (`hue_connect_test#<mac>`), separate from
   Hue Connect's. The key is saved to `hubtest.json`, readable only by you.
4. Run `tests/hub/hub-test.py check`. It lists the devices it found and checks
   the bridge, the key and the Hue Bridge device.

## Running

| Command | What it does |
|---|---|
| `hub-test.py all` | Every automatic group: bridge, lights, groups, scenes, features, motion, pacing, replay |
| `hub-test.py lights scenes` | Just those groups |
| `hub-test.py interactive` | Remotes, motion and contact. It asks you to press, wave or open, and waits for the events. Add `--step` to press Enter before each one. |
| `hub-test.py soak --hours 24` | Free memory every 10 minutes and a light check every 30 minutes |
| `hub-test.py deploy --dry-run`, then `deploy` | Updates the app and drivers already on the hub with the repo's code. This uses the hub's own code-editor endpoints, which Hubitat doesn't document, and new files must be added by hand once. |

Options:
- `--yes` skips the "this will change these lights" prompt.
- `--tick` ticks the TESTING.md lines whose tests all passed.

Each run writes a report to `tests/hub/results/` (git-ignored).

**What it can't check:**
- whether a colour *looks* right
- whether identify blinked
- a stream gap of over 60 s (the Hue Bridge device's `reconnect` makes only a short one)

Model, product name and firmware are device data values, which Maker API
doesn't expose. Check them on the device page.

## The script's own test

`tests/hub/selftest.sh` runs the script against `fake.py`, a stand-in hub and
bridge, so changes to the script can be checked without hardware. CI runs it.
To see the script catch a fault, run it with some fake commands ignored:

```sh
FAKE_BROKEN=setLevel tests/hub/selftest.sh
```
