#!/usr/bin/env python3
"""Scrub a raw `GET /clip/v2/resource` dump so it can be committed.

Usage: scrub-dump.py RAW.json OUT.json --names Real=Fake[,Real=Fake...]

--names lists the first names of the people in the home and what to call them instead. They are
an argument, never part of this file, so the repo does not carry them. The script stops if a
resource name still has some other name in it (a capitalised word before an apostrophe-s).

Replaces everything that identifies a home, a person or a bridge and keeps the
shape of the data (types, features, ranges, enums, how resources refer to each
other), which is what the tests need:

- every UUID, consistently, so references between resources still resolve
- MAC addresses, the Zigbee extended PAN id and the bridge id
- the people's first names given with --names, in resource names (names of rooms, lights, scenes stay)
- the time zone, the sun times, every timestamp and the geofence client name

It also thins the dump, so the fixture stays small and loses the most personal
resources: two of each type the app doesn't read (automations, entertainment
areas, firmware status, motion areas) and, per room or zone, two static scenes
and one dynamic one (plus any scene a kept smart scene uses). References to
resources it dropped are removed from lists.

Run it on the raw file only, never on its own output, and keep the raw file
outside the repo. It exits non-zero if anything it replaced is still present.
"""
import collections, json, re, sys, uuid

UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
MAC = re.compile(r"\b[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5,7}\b")
STAMP = re.compile(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z")
NAMESPACE = uuid.UUID("00000000-0000-0000-0000-000000000000")
THIN = {"behavior_instance", "behavior_script", "device_software_update", "entertainment", "motion_area_candidate"}
KEEP_EACH = 2          # of each THIN type
STATIC_SCENES = 2      # per room or zone, plus one dynamic palette scene


def rids(o):
    if isinstance(o, dict):
        if "rid" in o:
            yield o["rid"]
        for v in o.values():
            yield from rids(v)
    elif isinstance(o, list):
        for v in o:
            yield from rids(v)


def thin(items):
    dropped, seen, keep = set(), collections.Counter(), []
    for r in items:
        if r["type"] in THIN:
            seen[r["type"]] += 1
            if seen[r["type"]] > KEEP_EACH:
                dropped.add(r["id"])
                continue
        keep.append(r)
    used = {x for r in keep if r["type"] != "scene" for x in rids(r)}   # scenes a smart scene or automation uses stay
    taken, out = collections.defaultdict(lambda: [0, 0]), []
    for r in keep:
        if r["type"] == "scene":
            dynamic = 1 if r.get("palette", {}).get("color") else 0
            slots = taken[r["group"]["rid"]]
            if slots[dynamic] >= (1 if dynamic else STATIC_SCENES) and r["id"] not in used:
                dropped.add(r["id"])
                continue
            slots[dynamic] += 1
        out.append(r)

    def prune(o):
        if isinstance(o, dict):
            return {k: prune(v) for k, v in o.items()}
        if isinstance(o, list):
            return [prune(v) for v in o if not (isinstance(v, dict) and set(rids(v)) & dropped)]
        return o
    return [prune(r) for r in out]


def main(src, dst, NAMES):
    raw = open(src).read()
    data = json.loads(raw)
    before = len(data["data"])
    data["data"] = thin(data["data"])
    originals = set(UUID.findall(raw)) | set(MAC.findall(raw))
    macs = {m: "00:17:88:01:00:00:00:%02x" % i for i, m in enumerate(sorted(set(MAC.findall(raw))), 1)}

    def text(t):   # ids and MACs can be dictionary keys as well as values
        t = UUID.sub(lambda m: str(uuid.uuid5(NAMESPACE, m.group(0))), t)
        return MAC.sub(lambda m: macs[m.group(0)], t)

    def scrub(o, key=""):
        if isinstance(o, dict):
            return {text(k): scrub(v, k) for k, v in o.items()}
        if isinstance(o, list):
            return [scrub(v, key) for v in o]
        if not isinstance(o, str):
            return o
        if key == "bridge_id":
            originals.add(o); return "001788fffe000001"
        if key == "extended_pan_id":
            originals.add(o); return "0000000000000001"
        if key == "time_zone":
            originals.add(o); return "UTC"
        if key == "name" and o.startswith("aiohue_"):
            originals.add(o); return "geofence_client_1"
        if key.endswith("_time") and re.fullmatch(r"\d\d:\d\d:\d\d", o):
            return "12:00:00"
        o = STAMP.sub("2026-01-01T00:00:00Z", text(o))
        for real, fake in NAMES.items():
            o = re.sub(r"\b%s\b" % real, fake, o)
        return o

    out = json.dumps(scrub(data), indent=2, ensure_ascii=False) + "\n"
    shown = set(NAMES.values())
    others = sorted(set(re.findall(r"([A-Z][a-z]+)[\u2019']s\b", out)) - shown)
    if others:
        sys.exit("possible names still in resource names, add them to --names or ignore them if they are not names: %s" % others)
    left = [v for v in originals if v in out] + [n for n in NAMES if re.search(r"\b%s\b" % n, out)]
    if left:
        sys.exit("still present after scrubbing: %s" % sorted(left))
    open(dst, "w").write(out)
    print("wrote %s: %d of %d resources kept, %d ids, %d MACs replaced" % (dst, len(data["data"]), before, len(set(UUID.findall(raw))), len(macs)))


if __name__ == "__main__":
    args = sys.argv[1:]
    if len(args) != 4 or args[2] != "--names" or not all("=" in pair for pair in args[3].split(",")):
        sys.exit(__doc__)
    main(args[0], args[1], dict(pair.split("=", 1) for pair in args[3].split(",")))
