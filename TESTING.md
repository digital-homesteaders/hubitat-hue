# Testing

## Covered locally (no hub needed)

`tests/run.sh` runs everything under Groovy 2.4 (the line Hubitat runs). Run
`tests/setup-tools.sh` once first, unless java and groovy are on your PATH.

- **CompileCheck:** the app and every driver compile and load; the Hue Bridge
  driver is singleThreaded; no post-2.4 Groovy or sandbox-risky calls; ASCII
  only; one version everywhere; every parent method a driver calls exists;
  the Hue docs folder stays out of git.
- **MappingTest:** brightness/level, kelvin/mirek with each light's range,
  colour with Hue's formulas (gamut corners, every result inside the gamut,
  round trips, edge clamping), a light's gamut when it doesn't report one,
  driver choice by features, events from full and partial resources, health
  from Zigbee connectivity, every command body (only what changes; `on` only
  for a light that's off; no transition with off), discovery parsing.
- **DriverFlowTest:** the app, the Hue Bridge device and the light drivers
  against a fake bridge: discovery and its rate limit, linking (button wait,
  timeout, hidden key), devices per feature, the event stream (replay, long
  gaps, split chunks, connectivity, add/delete, stale STOPs, backoff), paced
  commands (spacing, merging, 3 in flight, retries, refusals, updates while the
  stream is down) and the bridge check (online, unauthorized, a different
  bridge, a silent stream).
- **PackageCheck:** packageManifest.json against the code: every file listed
  once with its name, namespace and import URL, one version everywhere,
  required and optional drivers, unique ids, the licence and NOTICE.

## On the hub, automatically

`tests/hub/hub-test.py` (see tests/hub/README.md) runs most of the list below
against the hub and bridge through Maker API. It checks both directions, watches
the hub log, and puts everything back afterwards. `--tick` ticks the lines here
whose tests all passed.

## Confirmed on a real hub

2026-10-23, Doug's C-8 (platform 2.5.2.139) with a Hue Bridge Pro:

- [x] The app and all ten drivers save in Hubitat's sandbox.
- [x] Discovery finds the bridge (hub mDNS cache, then `/api/0/config` over HTTPS).
- [x] Linking with the bridge button.
- [x] The event stream works in the sandbox: Hubitat hands `rawData` over one line per call, without the newline.
- [x] Lights switch from Hubitat, and Hubitat follows the Hue app.

## Needs a real hub and bridge

- [ ] Lights follow the Hue app within a second or two; commands work.
- [ ] Colour matches the Hue app for a few colours.
- [ ] A rule switching many lights at once: they all respond, no errors.
- [ ] Rooms and zones: on/off/level for the whole room; level shows the average of the lights that are on.
- [ ] Scenes: push recalls it; Set Effect on the room recalls the right scene; effectName follows scenes recalled in the Hue app.
- [ ] Smart scenes activate and deactivate.
- [ ] Dimmer switch: pushed, held, released per button; held repeats only with the preference on.
- [ ] Tap Dial: buttons 1-4, and the ring as 5/6 with rotation degrees.
- [ ] Smart button: double press as doubleTapped.
- [ ] Wall switch module and Friends of Hue switches: presses arrive.
- [ ] Motion sensor: motion, lux and temperature match the Hue app.
- [ ] Effects: Set Effect candle/fire on a colour light; effectName follows the Hue app.
- [ ] Flash and Identify: the light blinks; flash stops after the chosen seconds.
- [ ] Sunrise timed effect over a few minutes; switching off ends it.
- [ ] hueMode turns `streaming` during a Sync/Entertainment session.
- [ ] Motion sensor: disable/enable detection and set sensitivity; the Hue app agrees.
- [ ] Device data: model, product name and firmware look right.
- [ ] Replay: drop the stream under 60 s, change a light, reconnect.
- [ ] Memory flat over a day.
