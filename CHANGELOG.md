# Changelog

## Unreleased

## 0.1.0 (preview)

- Hue Connect app: bridge discovery (mDNS, discovery.meethue.com, manual IP),
  linking with the bridge button, the key kept in a hidden setting.
- Hue Bridge device: the V2 event stream with Last-Event-ID replay and a full
  read only after a gap over 60 s; a check every 5 minutes (reachable, key
  still accepted, same bridge); a paced command queue (lights 100 ms apart,
  groups 1 s, 3 in flight) with retries for 429 and 503.
- Light drivers matched to each light's features: Hue Color Light, Hue White
  Ambiance Light, Hue White Light, Hue Plug.
