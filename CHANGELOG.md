# Changelog

## Unreleased

- **Light features:** effects from each light's own list (setEffect, next,
  previous; `effectName` follows the Hue app), Flash (Hue's signaling for the
  chosen seconds, else a breathe alert), Identify, sunrise/sunset timed effects
  up to 6 hours, and `hueMode` (normal or streaming).
- **Motion sensors:** enable or disable motion detection and set sensitivity;
  `motionDetection` and `sensitivity` attributes.
- **Device details:** model, product name and firmware as device data values.
- **Release tooling:** `scripts/release.sh` stamps one version in the app, every
  driver and the HPM manifest; `tests/PackageCheck.groovy` checks the manifest
  against the code; a GitHub workflow runs the tests.
- Only group commands and scene recalls use the 1-a-second broadcast budget;
  sensor settings are paced like lights.
- **Remotes and sensors:** Hue Button (Dimmer switch, Smart button, Tap Dial with
  its ring as buttons 5/6 plus `rotation`, wall switch module, Friends of Hue),
  Hue Motion Sensor (motion, lux, temperature) and Hue Contact Sensor (contact,
  tamper), each with battery and health (zigbee or zgp connectivity). Kinds come
  from each device's services, never its model.
- **Rooms, zones and scenes:** Hue Group (a room's or zone's grouped_light, its
  scenes as light effects) and Hue Scene (scenes and smart scenes as a button
  and a switch). Group commands and scene recalls share Hue's 1-a-second
  broadcast budget.
- Checked against every guideline in Hue's design guidance (see docs/SPEC.md):
  a level equal to the current one isn't re-sent with colour or colour
  temperature; a light that was off after all ("may have no effect") gets the
  command once more with on; on carries the transition time too; rediscovery
  backs off from 5 to 60 minutes; one bridge found is selected automatically;
  "not found" advice (router, bridge LED); error events from the bridge are
  logged.
- Review fixes: a retried command can't land after a newer one; after a hub
  restart the stream reads all states (a Save still only replays); new lights get
  their state at once; setHue then setSaturation keep both; **Link again** on the
  app page (devices stay); the bridge is followed to a new IP by its ID; hold-to-dim
  ramps over 4 s so a release stops it; several bridges found at once are all
  kept; the page refreshes while searching; white ambiance lights don't get a
  colour mode; less work per event-stream chunk; removing the bridge device
  closes its stream.

## 0.1.0 (preview)

- Hue Connect app: bridge discovery (mDNS, discovery.meethue.com, manual IP),
  linking with the bridge button, the key kept in a hidden setting.
- Hue Bridge device: the V2 event stream with Last-Event-ID replay and a full
  read only after a gap over 60 s; a check every 5 minutes (reachable, key
  still accepted, same bridge); a paced command queue (lights 100 ms apart,
  groups 1 s, 3 in flight) with retries for 429 and 503.
- Light drivers matched to each light's features: Hue Color Light, Hue White
  Ambiance Light, Hue White Light, Hue Plug.
