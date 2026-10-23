# Hue Connect for Hubitat: design

A Philips Hue integration for Hubitat Elevation written from Hue's CLIP v2
documentation. Local only (no Hue cloud), V2 and HTTPS only, with the event
stream as the source of truth. Same shape as Digital Homesteaders' Govee and
Dreo integrations: one app holds all Hue-to-Hubitat mapping as pure functions,
a connection device talks to the bridge, and the device drivers stay thin.

Hue's docs are the spec. A local copy lives in `docs/hue-api/` (see its
README). It is login-gated Signify content, excluded from git through
`.git/info/exclude`: never commit or publish it. Paths below like
`v2/resource_light` refer to it.

## Goals

- **Hue's guidance, followed exactly.** GET once, then events; never stay
  current with repeated GETs. Pace commands (about 10 per second to lights,
  1 per second to groups). One property is one Zigbee message, so send only
  what changes. HTTPS only, with no fallback to HTTP.
- **Lean on the hub.** One event-stream connection per bridge, one small check
  every 5 minutes, no polling. Fetches are typed (`?type=`) so they carry only
  what Hubitat uses. No V1 code, apart from the two V1 endpoints Hue itself
  uses for linking (`POST /api`) and checking a bridge (`GET /api/0/config`).
- **Clean, testable code.** Pure mapping functions with unit tests, a hub
  stand-in for flows, and fixtures from real bridge data, all running under
  Groovy 2.4 like the hub.

Out of scope: V1-only (round) bridges, the Remote/cloud API, the Entertainment
streaming API, creating or editing rooms, zones, scenes or bridge rules
(manage those in the Hue app), and migration from CoCoHue or Hubitat's
built-in Hue integration.

## Pieces

| File | Name (Hubitat) | Job |
|---|---|---|
| `apps/hue-connect.groovy` | **Hue Connect** | Discovery, linking, device selection; **all mapping** (Hue resource to Hubitat events, Hubitat command to Hue PUT body) as pure functions; routes events to devices. One app instance per bridge. |
| `drivers/hue-bridge.groovy` | **Hue Bridge** | The bridge connection: event stream, the 5-minute check, typed resyncs, the paced command queue with retries. `singleThreaded`. |
| `drivers/hue-color-light.groovy` | **Hue Color Light** | A light with colour (and usually colour temperature): on/off, level, CT, colour, effects, flash |
| `drivers/hue-white-ambiance-light.groovy` | **Hue White Ambiance Light** | A light with colour temperature but no colour |
| `drivers/hue-white-light.groovy` | **Hue White Light** | A dimmable white light |
| `drivers/hue-plug.groovy` | **Hue Plug** | On/off only (smart plugs, relays) |
| `drivers/hue-group.groovy` | **Hue Group** | A room or zone (its grouped_light), with its scenes as effects |
| `drivers/hue-scene.groovy` | **Hue Scene** | One scene or smart scene, recalled with push/on |
| `drivers/hue-motion-sensor.groovy` | **Hue Motion Sensor** | motion, light level (lux), temperature, battery |
| `drivers/hue-contact-sensor.groovy` | **Hue Contact Sensor** | contact, tamper, battery |
| `drivers/hue-button.groovy` | **Hue Button** | Dimmer switch, Tap Dial, Smart Button, wall switch module: buttons and the rotary |

Hubitat dashboards and voice assistants act on a device's capabilities, so a
light gets the driver matching what it can do (from its `color`,
`color_temperature` and `dimming` features); the drivers are thin and share
the app's mapping.

Device network IDs: `hue-<bridge_id>-<rid>`, where `rid` is the V2 id of the
light/grouped_light/scene/device. Stable for the life of the bridge.

## Bridge connection

**Discovery** (`design-guidance/hue-bridge-discovery`), only at setup or when
the stored address stops answering:
1. mDNS from the hub's cache (`NetworkUtils.getRawMDNSEndpointsByMACForServiceType("_hue._tcp.local.")`).
2. `https://discovery.meethue.com`, at most once per 15 minutes.
3. Manual IP.

Each candidate is checked with `GET https://<ip>/api/0/config` (no key needed);
its `bridgeid` (16 hex) identifies it.

**Linking:** the user presses the bridge's button, then the app sends
`POST /api {"devicetype": "hue_connect#<hub name, up to 19 characters>"}`
until it answers with a `username` (error 101 means the button wasn't pressed
in the last 30 s). No `generateclientkey`: the client key is only for the
Entertainment API, so it isn't requested or stored.
The key goes in a password-type setting, never in `state`.

**Startup and resync:** `GET /clip/v2/resource?type=<the used types>`, one request.

**Event stream** (`/eventstream/clip/v2`, `rawData: true`): keep each `id:`
and reconnect with `Last-Event-ID`. Resync only after a gap over 60 s or with
no ID to resume from. Handle `update`, `add` and `delete` events; `add` and
`delete` refresh the device list. Reconnect waits 5, 10, 20 s and so on up to
15 minutes. A stream silent for 30 minutes is reconnected (Hue sends no
keepalive).

**Bridge check, every 5 minutes:** `GET /clip/v2/resource/bridge`.
Unreachable: offline, and the stream reconnects. 401/403: status
`unauthorized`, logged once with how to re-link. `bridge_id` different from
the linked one: offline, and rediscovery starts.

**Command queue** (`design-guidance/hue-system-performance`, aiohue):
- light PUTs at least 100 ms apart, grouped_light PUTs at least 1 s apart, at
  most 3 requests in flight;
- 429 and 503 are retried after 0.25 s x attempt, up to 8 times; 207 is
  accepted (the errors are logged); other 4xx, 500 and 507 aren't retried;
- a newer command for the same resource and property replaces a queued one
  (a slider being dragged).

## Mapping (v2 resources)

| Hue | Hubitat |
|---|---|
| light `on` | `switch` |
| light `dimming.brightness` (0-100, 0 = lowest) | `level` 1-100 |
| light `color_temperature.mirek` (null when not in CT mode), `mirek_schema` | `colorTemperature` K, range from the schema |
| light `color.xy`, `color.gamut` / `gamut_type` | `hue`, `saturation`, `color`, `colorMode` (Hue's RGB-xy formulas, clamped to the light's gamut; a missing gamut falls back to its `gamut_type` corners) |
| light `effects_v2` | `lightEffects`, `effectName` |
| light `signaling` | `flash()` |
| `dimming_delta` | `startLevelChange` / `stopLevelChange` |
| zigbee_connectivity `status` | `healthStatus` (online when `connected`) |
| grouped_light `on` (any light on), `dimming` (average of lights on) | the Hue Group's `switch`, `level` |
| scene `recall.action` active / dynamic_palette; smart_scene `recall.action` activate | Hue Scene `push()` / `on()` |
| motion `motion_report.motion` | `motion` |
| light_level `light_level_report.light_level` | `illuminance` lux = 10^((v - 1) / 10000) |
| temperature `temperature_report.temperature` (°C) | `temperature` in the hub's scale |
| device_power `battery_state`, `battery_level` | `battery` |
| contact `contact_report.state`, tamper `tamper_reports` | `contact`, `tamper` |
| button `button_report.event` | `pushed` (short_release), `held` (long_press), `released` (long_release), `doubleTapped` (double_short_release) |
| relative_rotary `rotary_report` | `level`-style steps on the Hue Button (TBD) |

**Commands carry only what changes:** no `on` for a light already on; `on` is
included when the light is off, because an off light ignores other properties
(`design-guidance/hue-groups-rooms-and-scene-control`). Transition time goes
on every level, CT and colour command (`watch-that-transition-time`).

## Compliance with Hue's design guidance

Checked 2026-10-23 against every "should/must/avoid/recommend" in `docs/hue-api/design-guidance/`.

| Guidance | Status |
|---|---|
| Performance: about 10 commands/s to lights, 100 ms apart; groups at most 1/s | Done (queue spacing; phase 2 shares one 1 s budget across groups and scene recalls) |
| Performance: no superfluous parameters (one property = one Zigbee message) | Done: `on` only for a light that isn't on; a level equal to the current one isn't re-sent with colour or CT; if the bridge answers `attribute_may_have_no_effect` because the light was off after all, the command is sent once more with `on` |
| Performance: buffering, then dropped commands, when sending too fast | 429/503 retried with growing waits (V2's equivalent of V1 error 901); a newer command replaces a waiting one |
| Performance: Entertainment API for fast updates over a long time | Out of scope; the queue coalesces rapid commands to at most 10/s |
| Events: GET once, then events; don't stay current with repeated GETs | Done; the only periodic GET is the tiny 5-minute `/resource/bridge` health check |
| Discovery: mDNS, then discovery.meethue.com (at most every 15 min), then manual IP | Done |
| Discovery: only at setup, or when the stored address stops answering | Done; rediscovery waits 5, 10, 20 ... 60 minutes while it keeps failing |
| Discovery: validate candidates (`/api/0/config`) before offering them | Done |
| Discovery: one bridge found -> carry on with it | Done (selected automatically) |
| Discovery: not found -> advise checking the router and the bridge's network LED | Done |
| Discovery: store the IP; on restart check the bridge still answers | Done (stored; the check runs at start and every 5 minutes) |
| Linking: devicetype `<app>#<device>` named after the app | Done (`hue_connect#<hub>`); no client key requested (Entertainment only) |
| HTTPS always, no fallback to HTTP | Done |
| HTTPS: validate the certificate against Hue's root CAs with CN = bridge ID; `--insecure` "must never be used in production" | **Not possible on Hubitat**: its HTTP and event-stream clients only offer `ignoreSSLIssues`. Mitigation: the bridge ID is checked over the connection every 5 minutes and on every discovery; the key never leaves the LAN. Revisit if Hubitat adds custom CAs. |
| HTTPS: keep connections alive / TLS session tickets | Up to Hubitat's HTTP client; Hue Connect's request volume is low |
| Transition time: set it with every command (default 400 ms otherwise) | Done for on, level, CT and colour; not with off (a transition with off can reset brightness) |
| Groups: use group commands for synchronous changes | Phase 2 (Hue Group drives the room's grouped_light) |
| Groups: an off light ignores other properties unless the same command turns it on | Done (`on` added for lights that aren't on) |
| Colour: xy > ct > hs; send xy inside the light's gamut; unknown gamut -> full triangle | Done (never sends xy and ct together; gamut from the light, else its gamut_type, else the full triangle) |
| Bridge limits: 100 app keys, least recently used deleted | Detected (401/403 -> `unauthorized`, logged once); **Link again** keeps the devices |
| V2 scenes only | Phase 2 uses CLIP v2 `scene`/`smart_scene` |

## Phases

1. **Bridge and lights:** discovery, linking, Hue Bridge (stream, check,
   queue, resync), the four light drivers.
2. **Groups and scenes:** Hue Group, Hue Scene (scenes and smart scenes).
3. **Sensors and buttons:** Hue Motion Sensor, Hue Contact Sensor, Hue Button.
4. **Polish:** effects, flash, HPM package, README, tester guide.

Each phase: tests under Groovy 2.4 (pure mapping, flows against a fake bridge,
fixtures from a real bridge), then Doug's hub, then the next phase.

## Fixtures

Real data beats doc examples. A dump of `GET /clip/v2/resource` from Doug's
bridge (with names and ids kept, nothing secret in it) goes in
`tests/fixtures/`. The app key is never stored there.
