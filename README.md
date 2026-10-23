# Hue Connect for Hubitat

Philips Hue on Hubitat Elevation, talking to your Hue Bridge on your own
network with Hue's current API (V2). Changes made anywhere (the Hue app, a
Hue switch, a schedule) show up in Hubitat within a second or two, because
Hue Connect listens to the bridge's live event stream instead of polling it.

> **Status: 0.1.0 preview, not yet tested on a hub.** Lights, rooms, zones,
> scenes, remotes and sensors are in. See [TESTING.md](TESTING.md).

## What it does

- **Lights:** on/off, brightness, colour temperature and colour, with a
  driver matched to each light (colour, white ambiance, white, plug).
- **Rooms and zones:** a Hue Group device per room or zone (and one for all
  lights) changes every light together in one command, the way the Hue app
  does. Its scenes are its light effects: pick one with **Set Effect**, or step
  through them with next/previous.
- **Scenes:** a Hue Scene device per scene (smart scenes too). Push or switch it
  on to recall it, optionally "dynamically" for scenes with a palette; switch it
  off to turn its room off. Its switch shows whether the scene is active.
- **Remotes:** Dimmer switch, Smart button, Tap Dial, wall switch module and
  Friends of Hue switches as Hue Button devices: pushed, held, released and
  double-tapped, numbered as on the device. A Tap Dial's ring is buttons 5
  (clockwise) and 6 (anticlockwise), with how far it turned in `rotation`.
- **Sensors:** motion sensors (motion, lux, temperature) and contact sensors
  (contact, tamper), each with battery and health.
- **Live updates** from the bridge's event stream. A short drop is caught up
  by the bridge itself; after a longer one Hue Connect reads everything once.
- **Kind to your Hue network:** commands are paced the way Hue recommends
  (about 10 a second to lights, 1 a second to groups), only what changes is
  sent, and a busy bridge's "try again" answers are retried.
- **Health:** the Hue Bridge device checks the bridge every 5 minutes and
  shows `online`, `offline` or `unauthorized`; each light reports
  `healthStatus` from the bridge's Zigbee connectivity.

Needs a square Hue Bridge (2015 or later) or a Hue Bridge Pro. The original
round bridge isn't supported.

## Install

Paste each file into Hubitat (**Apps Code** / **Drivers Code** → New → paste →
Save), or use HPM once the package is published:

| File | Where |
|---|---|
| `apps/hue-connect.groovy` | Apps Code |
| `drivers/hue-bridge.groovy` | Drivers Code |
| `drivers/hue-color-light.groovy` | Drivers Code |
| `drivers/hue-white-ambiance-light.groovy` | Drivers Code |
| `drivers/hue-white-light.groovy` | Drivers Code |
| `drivers/hue-plug.groovy` | Drivers Code |
| `drivers/hue-group.groovy` | Drivers Code |
| `drivers/hue-scene.groovy` | Drivers Code |
| `drivers/hue-button.groovy` | Drivers Code (if you have Hue remotes) |
| `drivers/hue-motion-sensor.groovy` | Drivers Code (if you have motion sensors) |
| `drivers/hue-contact-sensor.groovy` | Drivers Code (if you have contact sensors) |

Then **Apps → Add User App → Hue Connect**:
1. Press **Search** (or enter the bridge's IP address) and pick your bridge.
2. Press **Link**, then the round button on top of the bridge within 30 s.
3. Tick the lights, rooms and zones, scenes, and remotes and sensors you want,
   then press **Done**.

## Security

Everything stays on your network over HTTPS. The bridge's key is kept in a
hidden (password) setting. Hue asks apps to verify the bridge's certificate
against Hue's own certificate authority; Hubitat's HTTP client can't do that
(it can only skip certificate checks), so Hue Connect instead confirms the
bridge's full ID every 5 minutes and whenever it rediscovers the bridge. Keep
the bridge and the hub on a network you trust. See
[docs/SPEC.md](docs/SPEC.md#compliance-with-hues-design-guidance) for how Hue
Connect follows each of Hue's design guidelines.

## Credits and licence

MIT, © 2026 Digital Homesteaders. Written from Signify's Hue developer
documentation; ideas from aiohue and CoCoHue (see [NOTICE](NOTICE)). Philips
Hue is a trademark of Signify; this project isn't affiliated with Signify.
