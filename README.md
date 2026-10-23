# Hue Connect for Hubitat

Philips Hue on Hubitat Elevation, talking to your Hue Bridge on your own
network with Hue's current API (V2). Changes made anywhere (the Hue app, a
Hue switch, a schedule) show up in Hubitat within a second or two, because
Hue Connect listens to the bridge's live event stream instead of polling it.

> **Status: 0.1.0 preview, lights only, not yet tested on a hub.** Groups,
> scenes, sensors and buttons come next. See [TESTING.md](TESTING.md).

## What it does

- **Lights:** on/off, brightness, colour temperature and colour, with a
  driver matched to each light (colour, white ambiance, white, plug).
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

Then **Apps → Add User App → Hue Connect**:
1. Press **Search** (or enter the bridge's IP address) and pick your bridge.
2. Press **Link**, then the round button on top of the bridge within 30 s.
3. Tick the lights you want, then press **Done**.

## Security

Everything stays on your network over HTTPS. The bridge's key is kept in a
hidden (password) setting. Hubitat can't check the bridge's certificate
against Hue's own certificate authority, so Hue Connect checks the bridge's
ID every 5 minutes instead.

## Credits and licence

MIT, © 2026 Digital Homesteaders. Written from Signify's Hue developer
documentation; ideas from aiohue and CoCoHue (see [NOTICE](NOTICE)). Philips
Hue is a trademark of Signify; this project isn't affiliated with Signify.
