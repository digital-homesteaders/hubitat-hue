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

## Needs a real hub and bridge

Nothing yet (0.1.0 preview):

- [ ] Discovery finds the bridge; linking works with the button.
- [ ] The sandbox accepts everything (`hubitat.helper.NetworkUtils`, the event
      stream with `rawData`, `asynchttpPut` with a JSON body).
- [ ] Lights follow the Hue app within a second or two; commands work.
- [ ] Colour matches the Hue app for a few colours.
- [ ] A rule switching many lights at once: they all respond, no errors.
- [ ] Rooms and zones: on/off/level for the whole room; level shows the average of the lights that are on.
- [ ] Scenes: push recalls it; Set Effect on the room recalls the right scene; effectName follows scenes recalled in the Hue app.
- [ ] Smart scenes activate and deactivate.
- [ ] Replay: drop the stream under 60 s, change a light, reconnect.
- [ ] Memory flat over a day.
