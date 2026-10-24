#!/usr/bin/env python3
"""Hue Connect on a real hub and bridge, end to end.

Sends commands through Hubitat's Maker API and checks the result on the Hue
bridge itself; changes lights on the bridge (as the Hue app would) and checks
Hubitat follows. Watches the hub's live log for warnings and errors from Hue
Connect while it runs, and puts every light back the way it found it.

    tests/hub/hub-test.py link              get a test-only key from the bridge (press its button)
    tests/hub/hub-test.py check             can it reach everything; which devices it will use
    tests/hub/hub-test.py all               every automatic test (lights, groups, scenes, ...)
    tests/hub/hub-test.py lights scenes     just these groups
    tests/hub/hub-test.py interactive       remotes and sensors: it asks you to press, wave, open
    tests/hub/hub-test.py soak --hours 24   memory and a light check every 30 minutes
    tests/hub/hub-test.py deploy [--dry-run]   paste the app and drivers into the hub for you

Settings live in tests/hub/hubtest.json (git-ignored; see hubtest.example.json).
Only the devices named there are changed. Python 3.9+, standard library only.
"""

import argparse
import base64
import glob
import http.cookiejar
import json
import os
import re
import socket
import ssl
import struct
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
RESULTS = os.path.join(HERE, "results")
LIGHT_TYPES = ["Hue Color Light", "Hue White Ambiance Light", "Hue White Light", "Hue Plug"]
HUE_TYPES = LIGHT_TYPES + ["Hue Bridge", "Hue Group", "Hue Scene", "Hue Button", "Hue Motion Sensor", "Hue Contact Sensor"]
FOLLOW_S = 5.0   # how long a change may take to show up on the other side


class Fail(Exception):
    pass


class Skip(Exception):
    pass


# ---------------------------------------------------------------- settings

def load_config(path):
    if not os.path.exists(path):
        sys.exit("No %s yet: copy hubtest.example.json to it and fill it in." % path)
    with open(path) as f:
        return json.load(f)


def save_config(path, cfg):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)   # it holds keys: owner only
    with os.fdopen(fd, "w") as f:
        json.dump(cfg, f, indent=2)
        f.write("\n")


# ---------------------------------------------------------------- hub (Maker API and hub pages)

class Hub:
    def __init__(self, cfg):
        self.host = cfg["hub"]
        self.base = cfg.get("hubUrl") or "http://%s" % self.host
        self.maker = "%s/apps/api/%s" % (self.base, cfg.get("makerApiId", ""))
        self.token = cfg.get("makerToken", "")
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        if cfg.get("hubUser"):
            body = urllib.parse.urlencode({"username": cfg["hubUser"], "password": cfg.get("hubPassword", ""), "submit": "Login"})
            self.opener.open(self.base + "/login", body.encode(), timeout=10).read()

    def cookie_header(self):
        return "; ".join("%s=%s" % (c.name, c.value) for c in self.jar)

    def page(self, path, data=None, timeout=15):
        body = urllib.parse.urlencode(data).encode() if data is not None else None
        with self.opener.open(self.base + path, body, timeout=timeout) as r:
            return r.read().decode("utf-8", "replace")

    def maker_get(self, path):
        url = "%s%s%saccess_token=%s" % (self.maker, path, "&" if "?" in path else "?", urllib.parse.quote(self.token))
        try:
            with self.opener.open(url, timeout=15) as r:
                text = r.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            raise Fail("Maker API %s: HTTP %s" % (path, e.code))
        try:
            return json.loads(text) if text.strip() else None
        except ValueError:
            raise Fail("Maker API %s: not JSON: %s" % (path, text[:120]))

    def devices(self):
        return self.maker_get("/devices/all") or []

    def attrs(self, dev_id):
        d = self.maker_get("/devices/%s" % dev_id) or {}
        a = d.get("attributes")
        if isinstance(a, list):
            return {x.get("name"): x.get("currentValue") for x in a}
        return a or {}

    def attr(self, dev_id, name):
        return self.attrs(dev_id).get(name)

    def command(self, dev_id, cmd, *args):
        path = "/devices/%s/%s" % (dev_id, cmd)
        if args:
            parts = [json.dumps(a, separators=(",", ":")) if isinstance(a, dict) else str(a) for a in args]
            path += "/" + ",".join(urllib.parse.quote(p, safe="") for p in parts)
        self.maker_get(path)

    def events(self, dev_id):
        return self.maker_get("/devices/%s/events" % dev_id) or []

    def free_memory_kb(self):
        text = self.page("/hub/advanced/freeOSMemory").strip()
        return int(re.sub(r"[^0-9]", "", text) or 0)


# ---------------------------------------------------------------- bridge (CLIP v2)

class Bridge:
    def __init__(self, cfg):
        self.base = cfg.get("bridgeUrl") or "https://%s" % cfg["bridge"]
        self.key = cfg.get("hueKey", "")
        # The bridge's certificate is signed by Hue's own CA, which isn't in the system store; like the
        # app, this checks the bridge ID instead (check_identity), and the key never leaves the LAN.
        self.ctx = ssl.create_default_context()
        self.ctx.check_hostname = False
        self.ctx.verify_mode = ssl.CERT_NONE

    def request(self, method, path, body=None, auth=True):
        req = urllib.request.Request(self.base + path, method=method,
                                     data=json.dumps(body).encode() if body is not None else None)
        req.add_header("Content-Type", "application/json")
        if auth:
            req.add_header("hue-application-key", self.key)
        try:
            with urllib.request.urlopen(req, context=self.ctx, timeout=10) as r:
                return r.status, json.loads(r.read().decode() or "null")
        except urllib.error.HTTPError as e:
            try:
                return e.code, json.loads(e.read().decode() or "null")
            except ValueError:
                return e.code, None

    def get(self, rtype, rid=None):
        status, body = self.request("GET", "/clip/v2/resource/%s%s" % (rtype, "/" + rid if rid else ""))
        if status != 200:
            raise Fail("bridge GET %s: HTTP %s %s" % (rtype, status, (body or {}).get("errors", "")))
        return body.get("data", [])

    def one(self, rtype, rid):
        data = self.get(rtype, rid)
        if not data:
            raise Fail("bridge has no %s %s" % (rtype, rid))
        return data[0]

    def put(self, rtype, rid, body):
        status, resp = self.request("PUT", "/clip/v2/resource/%s/%s" % (rtype, rid), body)
        if status != 200:
            raise Fail("bridge PUT %s: HTTP %s %s" % (rtype, status, (resp or {}).get("errors", "")))
        time.sleep(0.12)   # Hue's pacing: about 10 a second to lights

    def config(self):
        status, body = self.request("GET", "/api/0/config", auth=False)
        if status != 200 or not isinstance(body, dict):
            raise Fail("bridge /api/0/config: HTTP %s" % status)
        return body


# ---------------------------------------------------------------- hub log (a minimal WebSocket client)

class LogWatch(threading.Thread):
    """Collects log lines from ws://<hub>/logsocket for the given device ids and the Hue Connect app."""

    def __init__(self, host, cookie, ids):
        threading.Thread.__init__(self, daemon=True)
        self.host, self.cookie, self.ids = host, cookie, set(str(i) for i in ids)
        self.lines, self.error, self.connected = [], None, threading.Event()

    def run(self):
        try:
            h, _, p = self.host.partition(":")
            s = socket.create_connection((h, int(p or 80)), timeout=10)
            key = base64.b64encode(os.urandom(16)).decode()
            req = ("GET /logsocket HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                   "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n" % (self.host, key))
            if self.cookie:
                req += "Cookie: %s\r\n" % self.cookie
            s.sendall((req + "\r\n").encode())
            head = b""
            while b"\r\n\r\n" not in head:
                chunk = s.recv(1024)
                if not chunk:
                    raise IOError("closed during handshake")
                head += chunk
            if b" 101 " not in head.split(b"\r\n")[0]:
                raise IOError(head.split(b"\r\n")[0].decode())
            buf = head.split(b"\r\n\r\n", 1)[1]
            s.settimeout(None)
            self.connected.set()
            msg = b""

            def need(n):
                nonlocal buf
                while len(buf) < n:
                    chunk = s.recv(65536)
                    if not chunk:
                        raise IOError("closed")
                    buf += chunk
                out, buf = buf[:n], buf[n:]
                return out

            while True:
                b1, b2 = need(2)
                op, n = b1 & 0x0F, b2 & 0x7F
                if n == 126:
                    n = struct.unpack(">H", need(2))[0]
                elif n == 127:
                    n = struct.unpack(">Q", need(8))[0]
                mask = need(4) if b2 & 0x80 else None
                data = need(n)
                if mask:
                    data = bytes(c ^ mask[i % 4] for i, c in enumerate(data))
                if op == 8:
                    break
                if op == 9:   # ping -> pong (client frames are masked)
                    m = os.urandom(4)
                    s.sendall(bytes([0x8A, 0x80 | len(data)]) + m + bytes(c ^ m[i % 4] for i, c in enumerate(data)))
                    continue
                if op in (0, 1):
                    msg += data
                    if b1 & 0x80:
                        self.take(msg.decode("utf-8", "replace"))
                        msg = b""
        except Exception as e:   # the log is a bonus: report it, don't stop the tests
            self.error = str(e)
            self.connected.set()

    def take(self, text):
        try:
            e = json.loads(text)
        except ValueError:
            return
        mine = (e.get("type") == "dev" and str(e.get("id")) in self.ids) or \
               (e.get("type") == "app" and "Hue Connect" in str(e.get("name")))
        if mine:
            self.lines.append(e)

    def problems(self, since=0):
        return [e for e in self.lines[since:] if e.get("level") in ("warn", "error")]


# ---------------------------------------------------------------- the run

class Ctx:
    def __init__(self, cfg, args):
        self.cfg, self.args = cfg, args
        self.hub, self.bridge = Hub(cfg), Bridge(cfg)
        self.results = []          # [group, name, status, detail, seconds]
        self.snapshots = {}        # light rid -> original state
        self.restores = []         # extra undo steps (motion settings ...)
        self.log = None
        self.devs = {}             # label -> Maker API device
        self.lights = []           # [{dev, rid, kind, name}]
        self.room = self.scene = self.smart = self.motion = None
        self.buttons, self.contacts = [], []


def wait_for(what, check, timeout=FOLLOW_S, every=0.25):
    """Polls check() -> (ok, seen) until ok; returns the seconds it took, or fails with the last value seen."""
    start, seen = time.time(), None
    while True:
        ok, seen = check()
        if ok:
            return time.time() - start
        if time.time() - start > timeout:
            raise Fail("%s: still %s after %.0f s" % (what, seen, timeout))
        time.sleep(every)


def near(a, b, tol):
    try:
        return abs(float(a) - float(b)) <= tol
    except (TypeError, ValueError):
        return False


def num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def light_state(ctx, rid):
    return ctx.bridge.one("light", rid)


def resolve(ctx):
    """Matches the devices named in hubtest.json to Maker API devices and bridge resources."""
    cfg, hub, br = ctx.cfg, ctx.hub, ctx.bridge
    devs = hub.devices()
    ctx.devs = {(d.get("label") or d.get("name")): d for d in devs}
    ctx.hue_ids = [d["id"] for d in devs if d.get("type") in HUE_TYPES]
    ctx.bridge_dev = next((d for d in devs if d.get("type") == "Hue Bridge"), None)
    hue_devices = {d["id"]: d for d in br.get("device")}

    def dev(label, types):
        d = ctx.devs.get(label)
        if not d:
            raise Fail("no device labelled %r in Maker API (add it to Maker API's devices)" % label)
        if d.get("type") not in types:
            raise Fail("%r uses the %s driver, not %s" % (label, d.get("type"), " or ".join(types)))
        return d

    def named(entry):
        return (entry, entry) if isinstance(entry, str) else (entry["device"], entry.get("hue", entry["device"]))

    lights = br.get("light")
    for entry in cfg.get("lights", []):
        label, hue_name = named(entry)
        d = dev(label, LIGHT_TYPES)
        l = next((x for x in lights if x.get("metadata", {}).get("name") == hue_name), None)
        if not l:
            raise Fail("no Hue light named %r (if you renamed it in Hubitat, use {\"device\": ..., \"hue\": ...})" % hue_name)
        ctx.lights.append({"dev": d["id"], "rid": l["id"], "type": d["type"], "name": label})

    groups = br.get("room") + br.get("zone")
    grouped = br.get("grouped_light")
    if cfg.get("room"):
        label, hue_name = named(cfg["room"])
        d = dev(label, ["Hue Group"])
        g = next((x for x in groups if x.get("metadata", {}).get("name") == hue_name), None)
        if not g:
            raise Fail("no Hue room or zone named %r" % hue_name)
        gl = next(s["rid"] for s in g.get("services", []) if s["rtype"] == "grouped_light")
        light_rids = []
        for c in g.get("children", []):
            if c["rtype"] == "light":
                light_rids.append(c["rid"])
            elif c["rtype"] == "device" and c["rid"] in hue_devices:
                light_rids += [s["rid"] for s in hue_devices[c["rid"]].get("services", []) if s["rtype"] == "light"]
        ctx.room = {"dev": d["id"], "rid": gl, "owner": g["id"], "name": hue_name, "lights": light_rids}

    for key, rtype in (("scene", "scene"), ("smartScene", "smart_scene")):
        if not cfg.get(key):
            continue
        d = dev(cfg[key], ["Hue Scene"])
        names = {g["id"]: g.get("metadata", {}).get("name") for g in groups}
        for sc in br.get(rtype):
            name, room = sc.get("metadata", {}).get("name"), names.get(sc.get("group", {}).get("rid"))
            if cfg[key] in (name, "%s (%s)" % (name, room)):
                info = {"dev": d["id"], "rid": sc["id"], "name": name, "group": sc.get("group", {}).get("rid"),
                        "room": room, "type": rtype}
                if rtype == "scene":
                    ctx.scene = info
                else:
                    ctx.smart = info
                break
        else:
            raise Fail("no Hue %s matching %r" % (rtype.replace("_", " "), cfg[key]))

    if cfg.get("motionSensor"):
        label, hue_name = named(cfg["motionSensor"])
        d = dev(label, ["Hue Motion Sensor"])
        hd = next((x for x in hue_devices.values() if x.get("metadata", {}).get("name") == hue_name), None)
        rid = hd and next((s["rid"] for s in hd.get("services", []) if s["rtype"] == "motion"), None)
        if not rid:
            raise Fail("no Hue motion sensor named %r" % hue_name)
        ctx.motion = {"dev": d["id"], "rid": rid, "name": label}
    ctx.buttons = [dev(b, ["Hue Button"]) for b in cfg.get("buttons", [])]
    ctx.contacts = [dev(c, ["Hue Contact Sensor"]) for c in cfg.get("contactSensors", [])]


def snapshot(ctx, rids):
    for rid in rids:
        if rid in ctx.snapshots:
            continue
        l = light_state(ctx, rid)
        s = {"on": l.get("on", {}).get("on")}
        if "dimming" in l:
            s["brightness"] = l["dimming"].get("brightness")
        ct = l.get("color_temperature") or {}
        if ct.get("mirek_valid") and ct.get("mirek"):
            s["mirek"] = ct["mirek"]
        elif "color" in l:
            s["xy"] = l["color"]["xy"]
        ctx.snapshots[rid] = s


def restore(ctx):
    for undo in reversed(ctx.restores):
        try:
            undo()
        except Exception as e:
            print("  could not undo a setting: %s" % e)
    for rid, s in ctx.snapshots.items():
        body = {"on": {"on": True}, "dynamics": {"duration": 0}}
        if s.get("brightness") is not None:
            body["dimming"] = {"brightness": s["brightness"]}
        if s.get("mirek"):
            body["color_temperature"] = {"mirek": s["mirek"]}
        elif s.get("xy"):
            body["color"] = {"xy": s["xy"]}
        try:
            ctx.bridge.put("light", rid, body)
            if not s.get("on"):
                ctx.bridge.put("light", rid, {"on": {"on": False}})
        except Exception as e:
            print("  could not put back light %s: %s" % (rid, e))


# ---------------------------------------------------------------- tests

TESTS = []   # (group, name, function, TESTING.md line it covers or None)


def test(group, name, testing=None):
    def wrap(f):
        TESTS.append((group, name, f, testing))
        return f
    return wrap


def each_light(ctx, kinds=None):
    ls = [l for l in ctx.lights if kinds is None or l["type"] in kinds]
    if not ls:
        raise Skip("no test light of type %s in hubtest.json" % (" or ".join(kinds) if kinds else "any"))
    return ls


@test("bridge", "Hue Bridge device: online, stream connected")
def t_bridge_device(ctx):
    if not ctx.bridge_dev:
        raise Fail("no Hue Bridge device in Maker API (add it to Maker API's devices)")
    a = ctx.hub.attrs(ctx.bridge_dev["id"])
    bad = {k: a.get(k) for k, v in (("status", "online"), ("eventStream", "connected"), ("healthStatus", "online")) if a.get(k) != v}
    if bad:
        raise Fail("Hue Bridge device shows %s" % bad)


@test("bridge", "Bridge ID and the test key")
def t_bridge_identity(ctx):
    c = ctx.bridge.config()
    want = ctx.cfg.get("bridgeId")
    if want and c.get("bridgeid", "").lower() != want.lower():
        raise Fail("bridge at %s is %s, expected %s" % (ctx.bridge.base, c.get("bridgeid"), want))
    ctx.bridge.get("bridge")
    return "%s, API %s" % (c.get("modelid"), c.get("apiversion"))


@test("lights", "Hub -> bridge: on, level, off", "Lights follow the Hue app")
def t_light_basic(ctx):
    took = []
    for l in each_light(ctx):
        snapshot(ctx, [l["rid"]])
        ctx.hub.command(l["dev"], "on")
        took.append(wait_for("%s on at the bridge" % l["name"],
                             lambda: (light_state(ctx, l["rid"])["on"]["on"], "off")))
        if l["type"] != "Hue Plug":
            ctx.hub.command(l["dev"], "setLevel", 37)
            took.append(wait_for("%s at 37%% on the bridge" % l["name"], lambda: (lambda b: (near(b, 37, 1.5), b))(
                light_state(ctx, l["rid"]).get("dimming", {}).get("brightness"))))
        ctx.hub.command(l["dev"], "off")
        took.append(wait_for("%s off at the bridge" % l["name"],
                             lambda: (not light_state(ctx, l["rid"])["on"]["on"], "on")))
    return "slowest %.1f s" % max(took)


@test("lights", "Bridge -> hub: on, level, off (as the Hue app)", "Lights follow the Hue app")
def t_light_follow(ctx):
    took = []
    for l in each_light(ctx):
        snapshot(ctx, [l["rid"]])
        body = {"on": {"on": True}}
        if l["type"] != "Hue Plug":
            body["dimming"] = {"brightness": 63}
        ctx.bridge.put("light", l["rid"], body)
        took.append(wait_for("%s on in Hubitat" % l["name"], lambda: (lambda a: (
            a.get("switch") == "on" and (l["type"] == "Hue Plug" or near(a.get("level"), 63, 1)), [a.get("switch"), a.get("level")]))(
            ctx.hub.attrs(l["dev"]))))
        ctx.bridge.put("light", l["rid"], {"on": {"on": False}})
        took.append(wait_for("%s off in Hubitat" % l["name"], lambda: (lambda v: (v == "off", v))(ctx.hub.attr(l["dev"], "switch"))))
    return "slowest %.1f s" % max(took)


@test("lights", "setLevel on an off light turns it on at that level")
def t_level_from_off(ctx):
    for l in each_light(ctx, LIGHT_TYPES[:3]):
        snapshot(ctx, [l["rid"]])
        ctx.bridge.put("light", l["rid"], {"on": {"on": False}})
        wait_for("%s off in Hubitat" % l["name"], lambda: (lambda v: (v == "off", v))(ctx.hub.attr(l["dev"], "switch")))
        ctx.hub.command(l["dev"], "setLevel", 44)
        wait_for("%s on at 44%% on the bridge" % l["name"], lambda: (lambda s: (
            s["on"]["on"] and near(s["dimming"]["brightness"], 44, 1.5), [s["on"]["on"], s["dimming"]["brightness"]]))(
            light_state(ctx, l["rid"])))


@test("lights", "Colour temperature both ways")
def t_ct(ctx):
    for l in each_light(ctx, LIGHT_TYPES[:2]):
        snapshot(ctx, [l["rid"]])
        ctx.hub.command(l["dev"], "on")
        ctx.hub.command(l["dev"], "setColorTemperature", 2700)
        wait_for("%s at 2700 K (370 mirek) on the bridge" % l["name"], lambda: (lambda m: (near(m, 370, 3), m))(
            light_state(ctx, l["rid"]).get("color_temperature", {}).get("mirek")))
        wait_for("%s shows about 2700 K" % l["name"], lambda: (lambda k: (near(k, 2700, 30), k))(ctx.hub.attr(l["dev"], "colorTemperature")))
        ctx.bridge.put("light", l["rid"], {"color_temperature": {"mirek": 250}})
        wait_for("%s shows 4000 K after the Hue app sets it" % l["name"], lambda: (lambda k: (near(k, 4000, 20), k))(
            ctx.hub.attr(l["dev"], "colorTemperature")))
        if l["type"] == "Hue Color Light":
            wait_for("%s colorMode CT" % l["name"], lambda: (lambda m: (m == "CT", m))(ctx.hub.attr(l["dev"], "colorMode")))


@test("lights", "Colour: setColor, setHue then setSaturation, and back to CT")
def t_color(ctx):
    for l in each_light(ctx, ["Hue Color Light"]):
        snapshot(ctx, [l["rid"]])
        ctx.hub.command(l["dev"], "setColor", {"hue": 66, "saturation": 100, "level": 50})
        wait_for("%s in colour mode on the bridge" % l["name"], lambda: (lambda s: (
            not (s.get("color_temperature") or {}).get("mirek_valid") and near(s["dimming"]["brightness"], 50, 1.5),
            s.get("color", {}).get("xy")))(light_state(ctx, l["rid"])))
        wait_for("%s shows hue about 66, colorMode RGB" % l["name"], lambda: (lambda a: (
            near(a.get("hue"), 66, 4) and num(a.get("saturation")) is not None and num(a.get("saturation")) >= 85 and a.get("colorMode") == "RGB",
            [a.get("hue"), a.get("saturation"), a.get("colorMode")]))(ctx.hub.attrs(l["dev"])))
        ctx.hub.command(l["dev"], "setHue", 33)        # green, then less saturated: the second must keep the first
        ctx.hub.command(l["dev"], "setSaturation", 60)
        wait_for("%s shows hue about 33 at 60%%" % l["name"], lambda: (lambda a: (
            near(a.get("hue"), 33, 4) and near(a.get("saturation"), 60, 8), [a.get("hue"), a.get("saturation")]))(ctx.hub.attrs(l["dev"])))
        ctx.hub.command(l["dev"], "setColorTemperature", 3000)
        wait_for("%s back in CT mode" % l["name"], lambda: (lambda a: (a.get("colorMode") == "CT", a.get("colorMode")))(ctx.hub.attrs(l["dev"])))


@test("lights", "Health and hueMode", None)
def t_health(ctx):
    for l in each_light(ctx):
        a = ctx.hub.attrs(l["dev"])
        if a.get("healthStatus") != "online":
            raise Fail("%s healthStatus %s" % (l["name"], a.get("healthStatus")))
        if l["type"] != "Hue Plug" and a.get("hueMode") not in ("normal", "streaming"):
            raise Fail("%s hueMode %s" % (l["name"], a.get("hueMode")))


@test("groups", "Room: on, level, off reach every light", "Rooms and zones:")
def t_room(ctx):
    r = ctx.room
    if not r:
        raise Skip("no room in hubtest.json")
    snapshot(ctx, r["lights"])
    ctx.hub.command(r["dev"], "setLevel", 50)
    for rid in r["lights"]:
        wait_for("light %s at 50%%" % rid, lambda: (lambda s: (
            s["on"]["on"] and ("dimming" not in s or near(s["dimming"]["brightness"], 50, 1.5)), [s["on"]["on"], s.get("dimming")]))(
            light_state(ctx, rid)))
    wait_for("room shows on at 50%", lambda: (lambda a: (a.get("switch") == "on" and near(a.get("level"), 50, 1),
                                                         [a.get("switch"), a.get("level")]))(ctx.hub.attrs(r["dev"])))
    ctx.hub.command(r["dev"], "off")
    for rid in r["lights"]:
        wait_for("light %s off" % rid, lambda: (not light_state(ctx, rid)["on"]["on"], "on"))
    wait_for("room shows off", lambda: (lambda v: (v == "off", v))(ctx.hub.attr(r["dev"], "switch")))


@test("groups", "Room follows one light switched on in the Hue app", "Rooms and zones:")
def t_room_follow(ctx):
    r = ctx.room
    if not r:
        raise Skip("no room in hubtest.json")
    snapshot(ctx, r["lights"])
    for rid in r["lights"]:
        ctx.bridge.put("light", rid, {"on": {"on": False}})
    wait_for("room shows off", lambda: (lambda v: (v == "off", v))(ctx.hub.attr(r["dev"], "switch")))
    ctx.bridge.put("light", r["lights"][0], {"on": {"on": True}})
    wait_for("room shows on (any light on)", lambda: (lambda v: (v == "on", v))(ctx.hub.attr(r["dev"], "switch")))


@test("scenes", "Scene: push recalls it; the switch follows", "Scenes: push recalls it")
def t_scene(ctx):
    sc = ctx.scene
    if not sc:
        raise Skip("no scene in hubtest.json")
    if ctx.room and ctx.room["owner"] == sc["group"]:
        snapshot(ctx, ctx.room["lights"])
    ctx.hub.command(sc["dev"], "push", 1)
    wait_for("scene active on the bridge", lambda: (lambda s: (s.get("active") not in (None, "inactive"), s.get("active")))(
        ctx.bridge.one("scene", sc["rid"]).get("status", {})))
    wait_for("scene switch on", lambda: (lambda v: (v == "on", v))(ctx.hub.attr(sc["dev"], "switch")))


@test("scenes", "Room Set Effect recalls the scene; effectName follows", "Scenes: push recalls it")
def t_room_effect(ctx):
    sc, r = ctx.scene, ctx.room
    if not sc or not r or r["owner"] != sc["group"]:
        raise Skip("needs a scene in the test room")
    snapshot(ctx, r["lights"])
    effects = json.loads(ctx.hub.attr(r["dev"], "lightEffects") or "{}")
    n = next((k for k, v in effects.items() if v == sc["name"]), None)
    if n is None:
        raise Fail("room's lightEffects has no %r: %s" % (sc["name"], effects))
    ctx.hub.command(r["dev"], "off")
    wait_for("scene inactive", lambda: (lambda s: (s.get("active") == "inactive", s.get("active")))(
        ctx.bridge.one("scene", sc["rid"]).get("status", {})), timeout=8)
    ctx.hub.command(r["dev"], "setEffect", n)
    wait_for("scene active again", lambda: (lambda s: (s.get("active") not in (None, "inactive"), s.get("active")))(
        ctx.bridge.one("scene", sc["rid"]).get("status", {})))
    wait_for("room effectName %r" % sc["name"], lambda: (lambda v: (v == sc["name"], v))(ctx.hub.attr(r["dev"], "effectName")))


@test("scenes", "Scene off turns its room off")
def t_scene_off(ctx):
    sc = ctx.scene
    if not sc or not ctx.room or ctx.room["owner"] != sc["group"]:
        raise Skip("needs a scene in the test room")
    snapshot(ctx, ctx.room["lights"])
    ctx.hub.command(sc["dev"], "push", 1)
    wait_for("scene switch on", lambda: (lambda v: (v == "on", v))(ctx.hub.attr(sc["dev"], "switch")))
    ctx.hub.command(sc["dev"], "off")
    for rid in ctx.room["lights"]:
        wait_for("light %s off" % rid, lambda: (not light_state(ctx, rid)["on"]["on"], "on"))
    wait_for("scene switch off", lambda: (lambda v: (v == "off", v))(ctx.hub.attr(sc["dev"], "switch")))


@test("scenes", "Smart scene: activate and deactivate", "Smart scenes activate")
def t_smart(ctx):
    sc = ctx.smart
    if not sc:
        raise Skip("no smartScene in hubtest.json")
    if ctx.room and ctx.room["owner"] == sc["group"]:
        snapshot(ctx, ctx.room["lights"])
    ctx.hub.command(sc["dev"], "on")
    wait_for("smart scene active", lambda: (lambda v: (v == "active", v))(ctx.bridge.one("smart_scene", sc["rid"]).get("state")))
    ctx.hub.command(sc["dev"], "off")
    wait_for("smart scene inactive", lambda: (lambda v: (v == "inactive", v))(ctx.bridge.one("smart_scene", sc["rid"]).get("state")))


@test("features", "Effects: candle on, then none", "Effects: Set Effect")
def t_effects(ctx):
    done = 0
    for l in each_light(ctx, LIGHT_TYPES[:2]):
        effects = json.loads(ctx.hub.attr(l["dev"], "lightEffects") or "{}")
        n = next((k for k, v in effects.items() if v in ("candle", "fire", "sparkle")), None)
        if n is None:
            continue
        snapshot(ctx, [l["rid"]])
        ctx.hub.command(l["dev"], "on")
        ctx.hub.command(l["dev"], "setEffect", n)
        wait_for("%s %s on the bridge" % (l["name"], effects[n]), lambda: (lambda e: (e == effects[n], e))(
            light_state(ctx, l["rid"]).get("effects_v2", {}).get("status", {}).get("effect")))
        wait_for("%s effectName %s" % (l["name"], effects[n]), lambda: (lambda v: (v == effects[n], v))(ctx.hub.attr(l["dev"], "effectName")))
        ctx.hub.command(l["dev"], "setEffect", 0)
        wait_for("%s effect off" % l["name"], lambda: (lambda e: (e in (None, "no_effect"), e))(
            light_state(ctx, l["rid"]).get("effects_v2", {}).get("status", {}).get("effect")))
        done += 1
    if not done:
        raise Skip("no test light offers effects")


@test("features", "Flash signals on the bridge (watch it blink)", "Flash and Identify")
def t_flash(ctx):
    for l in each_light(ctx, LIGHT_TYPES[:3]):
        snapshot(ctx, [l["rid"]])
        state = light_state(ctx, l["rid"])
        ctx.hub.command(l["dev"], "flash")
        if "on_off" in state.get("signaling", {}).get("signal_values", []):
            wait_for("%s signalling" % l["name"], lambda: (lambda s: (s.get("signal") == "on_off", s))(
                light_state(ctx, l["rid"]).get("signaling", {}).get("status", {})))
        ctx.hub.command(l["dev"], "identify")
        time.sleep(1)
    return "identify sent too: check it blinked"


@test("features", "Sunrise over 1 minute starts on the light", "Sunrise timed effect")
def t_sunrise(ctx):
    done = 0
    for l in each_light(ctx, LIGHT_TYPES[:3]):
        state = light_state(ctx, l["rid"])
        if "sunrise" not in state.get("timed_effects", {}).get("effect_values", []):
            continue
        snapshot(ctx, [l["rid"]])
        ctx.bridge.put("light", l["rid"], {"on": {"on": False}})
        ctx.hub.command(l["dev"], "startTimedEffect", "sunrise", 1)
        wait_for("%s sunrise running" % l["name"], lambda: (lambda t: ("sunrise" in (t.get("status"), t.get("effect")), t))(
            light_state(ctx, l["rid"]).get("timed_effects", {})))
        ctx.hub.command(l["dev"], "off")
        done += 1
    if not done:
        raise Skip("no test light offers timed effects")


@test("motion", "Motion detection off and on; sensitivity", "Motion sensor: disable/enable")
def t_motion_settings(ctx):
    m = ctx.motion
    if not m:
        raise Skip("no motionSensor in hubtest.json")
    orig = ctx.bridge.one("motion", m["rid"])
    sens = orig.get("sensitivity") or {}
    ctx.restores.append(lambda: ctx.bridge.put("motion", m["rid"], dict(
        {"enabled": orig.get("enabled", True)}, **({"sensitivity": {"sensitivity": sens["sensitivity"]}} if sens else {}))))
    ctx.hub.command(m["dev"], "disableMotionDetection")
    wait_for("detection off on the bridge", lambda: (lambda v: (v is False, v))(ctx.bridge.one("motion", m["rid"]).get("enabled")))
    wait_for("motionDetection disabled", lambda: (lambda v: (v == "disabled", v))(ctx.hub.attr(m["dev"], "motionDetection")))
    ctx.hub.command(m["dev"], "enableMotionDetection")
    wait_for("detection on again", lambda: (lambda v: (v is True, v))(ctx.bridge.one("motion", m["rid"]).get("enabled")))
    if sens:
        want = (sens["sensitivity"] + 1) % (sens.get("sensitivity_max", 4) + 1)
        ctx.hub.command(m["dev"], "setSensitivity", want)
        wait_for("sensitivity %s on the bridge" % want, lambda: (lambda v: (v == want, v))(
            ctx.bridge.one("motion", m["rid"]).get("sensitivity", {}).get("sensitivity")))
        wait_for("sensitivity %s in Hubitat" % want, lambda: (lambda v: (near(v, want, 0), v))(ctx.hub.attr(m["dev"], "sensitivity")))


@test("pacing", "A burst of 30 commands: every light ends right, no refusals", "A rule switching many lights at once")
def t_burst(ctx):
    ls = each_light(ctx, LIGHT_TYPES[:3])
    snapshot(ctx, [l["rid"] for l in ls])
    since = len(ctx.log.lines) if ctx.log else 0
    start, last = time.time(), {}
    for i in range(30):
        l = ls[i % len(ls)]
        last[l["rid"]] = 20 + (i * 7) % 70
        ctx.hub.command(l["dev"], "setLevel", last[l["rid"]])
    sent = time.time() - start
    for l in ls:
        wait_for("%s settles at %s%%" % (l["name"], last[l["rid"]]), lambda: (lambda b: (near(b, last[l["rid"]], 1.5), b))(
            light_state(ctx, l["rid"]).get("dimming", {}).get("brightness")), timeout=15)
    if ctx.log:
        time.sleep(1)
        bad = ctx.log.problems(since)
        if bad:
            raise Fail("log during the burst: %s" % bad[0].get("msg"))
    return "sent in %.1f s, settled in %.1f s" % (sent, time.time() - start)


@test("replay", "Reconnect the stream; a change made meanwhile still arrives", "Replay:")
def t_replay(ctx):
    if not ctx.bridge_dev:
        raise Fail("no Hue Bridge device in Maker API")
    l = each_light(ctx, LIGHT_TYPES[:3])[0]
    snapshot(ctx, [l["rid"]])
    ctx.hub.command(ctx.bridge_dev["id"], "reconnect")
    ctx.bridge.put("light", l["rid"], {"on": {"on": True}, "dimming": {"brightness": 71}})
    wait_for("stream connected again", lambda: (lambda v: (v == "connected", v))(ctx.hub.attr(ctx.bridge_dev["id"], "eventStream")), timeout=40)
    wait_for("%s at 71%% in Hubitat" % l["name"], lambda: (lambda a: (a.get("switch") == "on" and near(a.get("level"), 71, 1),
                                                                    [a.get("switch"), a.get("level")]))(ctx.hub.attrs(l["dev"])), timeout=10)


# ---------------------------------------------------------------- interactive (someone at the devices)

def ask(ctx, dev, prompt, want, timeout=30):
    """Asks someone at the device to do something; waits until every event in want ("pushed 1", ...) arrives."""
    seen = set((e.get("date"), e.get("name"), e.get("value")) for e in ctx.hub.events(dev["id"]))
    if ctx.args.step:
        input("\n  %s: %s, then press Enter here ... " % (dev.get("label"), prompt))
    else:
        print("\n  %s: %s (%d s)" % (dev.get("label"), prompt, timeout))
    missing, end = list(want), time.time() + timeout
    while missing and time.time() < end:
        for e in ctx.hub.events(dev["id"]):
            key = (e.get("date"), e.get("name"), e.get("value"))
            got = "%s %s" % (e.get("name"), e.get("value"))
            if key not in seen:
                seen.add(key)
                if got in missing:
                    missing.remove(got)
        time.sleep(0.5)
    if missing:
        raise Fail("%s: never saw %s" % (dev.get("label"), ", ".join(missing)))
    return ", ".join(want)


@test("interactive", "Remotes: press, hold, release, double press", "Dimmer switch:")
def t_buttons(ctx):
    if not ctx.buttons:
        raise Skip("no buttons in hubtest.json")
    out = []
    for b in ctx.buttons:
        out.append(ask(ctx, b, "short-press button 1", ["pushed 1"]))
        out.append(ask(ctx, b, "hold button 1 for 2 s, then let go", ["held 1", "released 1"]))
        if (num(ctx.hub.attr(b["id"], "numberOfButtons")) or 0) >= 6:
            out.append(ask(ctx, b, "turn the dial clockwise a little", ["pushed 5"]))
    return ", ".join(out)


@test("interactive", "Motion sensor sees you", "Motion sensor: motion, lux")
def t_motion_live(ctx):
    if not ctx.motion:
        raise Skip("no motionSensor in hubtest.json")
    d = ctx.devs[ctx.motion["name"]]
    return ask(ctx, d, "wave in front of the sensor", ["motion active"])


@test("interactive", "Contact sensors: open, close")
def t_contact(ctx):
    if not ctx.contacts:
        raise Skip("no contactSensors in hubtest.json")
    return ", ".join(ask(ctx, c, "open it, then close it", ["contact open", "contact closed"]) for c in ctx.contacts)


# ---------------------------------------------------------------- running and reporting

def run_tests(ctx, groups):
    picked = [t for t in TESTS if t[0] in groups]
    for group, name, f, line in picked:
        start = time.time()
        since = len(ctx.log.lines) if ctx.log else 0
        try:
            detail, status = f(ctx) or "", "PASS"
            if ctx.log and group != "pacing":
                time.sleep(0.3)
                bad = ctx.log.problems(since)
                if bad:
                    status, detail = "FAIL", "logged %s: %s" % (bad[0].get("level"), bad[0].get("msg"))
        except Skip as e:
            status, detail = "SKIP", str(e)
        except Fail as e:
            status, detail = "FAIL", str(e)
        except Exception as e:
            status, detail = "FAIL", "%s: %s" % (type(e).__name__, e)
        took = time.time() - start
        ctx.results.append([group, name, status, detail, took, line])
        print("%-4s  %-9s %s%s" % (status, group, name, " - " + detail if detail else ""))
        time.sleep(0.5)   # let the house breathe between tests


def report(ctx, title, extra=""):
    os.makedirs(RESULTS, exist_ok=True)
    stamp = datetime.now().strftime("%Y-%m-%d-%H%M")
    path = os.path.join(RESULTS, "%s-%s.md" % (stamp, title))
    counts = {s: sum(1 for r in ctx.results if r[2] == s) for s in ("PASS", "FAIL", "SKIP")}
    with open(path, "w") as f:
        f.write("# Hue Connect hub test: %s\n\n%s\n\n" % (title, datetime.now().strftime("%Y-%m-%d %H:%M")))
        f.write("%(PASS)d passed, %(FAIL)d failed, %(SKIP)d skipped\n\n| | Group | Test | Detail |\n|---|---|---|---|\n" % counts)
        for g, n, s, d, t, _ in ctx.results:
            f.write("| %s | %s | %s | %s (%.1f s) |\n" % (s, g, n, d.replace("|", "/"), t))
        if extra:
            f.write("\n" + extra + "\n")
    print("\n%(PASS)d passed, %(FAIL)d failed, %(SKIP)d skipped" % counts + "  ->  " + os.path.relpath(path, ROOT))
    return counts["FAIL"] == 0


def tick_testing(ctx):
    """Ticks TESTING.md lines whose tests all passed (none failed or skipped)."""
    path = os.path.join(ROOT, "TESTING.md")
    text = open(path).read()
    by_line = {}
    for r in ctx.results:
        if r[5]:
            by_line.setdefault(r[5], []).append(r[2])
    ticked = []
    for line, statuses in by_line.items():
        if all(s == "PASS" for s in statuses) and ("- [ ] " + line) in text:
            text = text.replace("- [ ] " + line, "- [x] " + line, 1)
            ticked.append(line)
    open(path, "w").write(text)
    for line in ticked:
        print("  ticked in TESTING.md: %s ..." % line)


def start_log(ctx):
    if ctx.cfg.get("logs") is False:
        return
    ctx.log = LogWatch(ctx.hub.host, ctx.hub.cookie_header(), ctx.hue_ids)
    ctx.log.start()
    ctx.log.connected.wait(10)
    if ctx.log.error:
        print("(hub log not watched: %s)" % ctx.log.error)
        ctx.log = None


def confirm(ctx):
    names = [l["name"] for l in ctx.lights]
    if ctx.room:
        names.append("room %s (%d lights)" % (ctx.room["name"], len(ctx.room["lights"])))
    for k in ("scene", "smart"):
        if getattr(ctx, k):
            names.append("scene %s" % getattr(ctx, k)["name"])
    if ctx.motion:
        names.append("motion sensor %s (settings, put back after)" % ctx.motion["name"])
    print("This switches, dims, colours and flashes: " + ", ".join(names) + ".\nEverything is put back afterwards.")
    if not ctx.args.yes:
        input("Enter to start, Ctrl-C to stop ... ")


# ---------------------------------------------------------------- commands

def cmd_link(cfg, args):
    br = Bridge(cfg)
    c = br.config()
    print("Bridge %s (%s). Press its round button now (30 s) ..." % (c.get("name"), c.get("bridgeid")))
    name = "hue_connect_test#%s" % socket.gethostname().split(".")[0][:19]
    end = time.time() + 30
    while time.time() < end:
        status, body = br.request("POST", "/api", {"devicetype": name}, auth=False)
        ok = isinstance(body, list) and body and "success" in body[0]
        if ok:
            cfg["hueKey"], cfg["bridgeId"] = body[0]["success"]["username"], c.get("bridgeid")
            save_config(args.config, cfg)
            print("Linked as %s; key saved to %s (owner-only)." % (name, os.path.relpath(args.config, ROOT)))
            return 0
        time.sleep(1)
    print("No button press seen.")
    return 1


def cmd_check(ctx):
    print("Hub %s: %d Hue Connect devices in Maker API" % (ctx.hub.host, len(ctx.hue_ids)))
    for l in ctx.lights:
        print("  light   %-28s %s" % (l["name"], l["type"]))
    if ctx.room:
        print("  room    %-28s %d lights" % (ctx.room["name"], len(ctx.room["lights"])))
    for k in ("scene", "smart"):
        if getattr(ctx, k):
            print("  scene   %-28s %s" % (getattr(ctx, k)["name"], getattr(ctx, k)["type"]))
    if ctx.motion:
        print("  motion  %s" % ctx.motion["name"])
    for b in ctx.buttons + ctx.contacts:
        print("  sensor  %-28s %s" % (b.get("label"), b.get("type")))
    try:
        print("Hub free memory: %d MB" % (ctx.hub.free_memory_kb() // 1024))
    except Exception as e:
        print("Hub free memory: unavailable (%s; set hubUser/hubPassword if hub security is on)" % e)
    run_tests(ctx, ["bridge"])
    return all(r[2] != "FAIL" for r in ctx.results)


def cmd_soak(ctx, hours):
    rows, end, next_check = [], time.time() + hours * 3600, 0
    print("Soak for %g h: free memory every 10 minutes, a light check every 30 (Ctrl-C ends it early)." % hours)
    try:
        while time.time() < end:
            kb = ctx.hub.free_memory_kb()
            rows.append((datetime.now().strftime("%Y-%m-%d %H:%M"), kb))
            print("  %s  %d MB free" % (rows[-1][0], kb // 1024))
            if time.time() >= next_check:
                run_tests(ctx, ["bridge"])
                if ctx.lights:
                    run_tests(ctx, ["lights"])
                restore(ctx)
                ctx.snapshots = {}
                next_check = time.time() + 1800
            time.sleep(600)
    except KeyboardInterrupt:
        print("  stopped")
    drop = (rows[0][1] - rows[-1][1]) // 1024 if len(rows) > 1 else 0
    table = "| Time | Free MB |\n|---|---|\n" + "".join("| %s | %d |\n" % (t, kb // 1024) for t, kb in rows)
    ctx.results.append(["soak", "Free memory over %g h" % hours, "PASS" if drop < 100 else "FAIL",
                        "%d MB less free at the end" % drop, hours * 3600, "Memory flat over a day" if hours >= 20 else None])
    return report(ctx, "soak", table)


def cmd_deploy(cfg, args):
    """Updates each app and driver already on the hub (matched by name and namespace) with the repo's code."""
    hub = Hub(cfg)
    kinds = {"app": json.loads(hub.page("/hub2/userAppTypes")), "driver": json.loads(hub.page("/hub2/userDeviceTypes"))}
    status = 0
    for kind, folder in (("app", "apps"), ("driver", "drivers")):
        for path in sorted(glob.glob(os.path.join(ROOT, folder, "*.groovy"))):
            src = open(path).read()
            name = re.search(r'definition\s*\(\s*name:\s*"([^"]+)"', src).group(1)
            ns = re.search(r'namespace:\s*"([^"]+)"', src).group(1)
            entry = next((e for e in kinds[kind] if e.get("name") == name and e.get("namespace") == ns), None)
            if not entry:
                print("  %-26s not on the hub yet: add it once by hand (%s Code -> New)" % (name, kind.title()))
                status = 1
                continue
            if args.dry_run:
                print("  %-26s would update %s id %s" % (name, kind, entry["id"]))
                continue
            code = json.loads(hub.page("/%s/ajax/code?id=%s" % (kind, entry["id"])))
            if code.get("source") == src:
                print("  %-26s unchanged" % name)
                continue
            r = json.loads(hub.page("/%s/ajax/update" % kind, {"id": entry["id"], "version": code.get("version"), "source": src}, timeout=60))
            ok = r.get("status") == "success"
            print("  %-26s %s" % (name, "updated" if ok else "FAILED: %s" % (r.get("errorMessage") or r)))
            status = status or (0 if ok else 1)
    return status


GROUPS = ["bridge", "lights", "groups", "scenes", "features", "motion", "pacing", "replay"]


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("what", nargs="+", help="link, check, deploy, all, interactive, soak, or test groups: " + ", ".join(GROUPS))
    p.add_argument("--config", default=os.path.join(HERE, "hubtest.json"))
    p.add_argument("--hours", type=float, default=24)
    p.add_argument("--yes", action="store_true", help="don't ask before changing lights")
    p.add_argument("--tick", action="store_true", help="tick TESTING.md lines whose tests all passed")
    p.add_argument("--step", action="store_true", help="interactive: wait for Enter before each check")
    p.add_argument("--dry-run", action="store_true", help="deploy: only say what would change")
    args = p.parse_args()
    args.config = os.path.abspath(args.config)
    cfg = load_config(args.config)
    what = args.what

    if what == ["link"]:
        return cmd_link(cfg, args)
    if what == ["deploy"]:
        return cmd_deploy(cfg, args)
    if not cfg.get("hueKey"):
        sys.exit("No hueKey yet: run tests/hub/hub-test.py link and press the bridge button.")

    ctx = Ctx(cfg, args)
    try:
        resolve(ctx)
    except Fail as e:
        sys.exit("Setup: %s" % e)
    if what == ["check"]:
        return 0 if cmd_check(ctx) else 1

    groups = GROUPS if "all" in what else [w for w in what if w in GROUPS + ["interactive"]]
    unknown = [w for w in what if w not in GROUPS + ["all", "interactive", "soak"]]
    if unknown:
        sys.exit("Unknown: %s" % ", ".join(unknown))
    confirm(ctx)
    start_log(ctx)
    before = None
    try:
        before = ctx.hub.free_memory_kb()
    except Exception:
        pass
    try:
        if "soak" in what:
            ok = cmd_soak(ctx, args.hours)
        else:
            run_tests(ctx, groups)
            after = None
            try:
                after = ctx.hub.free_memory_kb()
            except Exception:
                pass
            extra = "Free memory: %d MB before, %d MB after." % (before // 1024, after // 1024) if before and after else ""
            ok = report(ctx, "-".join(what), extra)
    except KeyboardInterrupt:
        print("\nstopped")
        ok = False
    finally:
        print("Putting things back ...")
        restore(ctx)
    if args.tick:
        tick_testing(ctx)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
