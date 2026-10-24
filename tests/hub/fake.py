#!/usr/bin/env python3
"""A stand-in hub (Maker API) and Hue bridge (CLIP v2) in one plain-HTTP server, so hub-test.py
itself can be checked without hardware: tests/hub/selftest.sh. The hub side applies commands
to the bridge the way Hue Connect does, and reads attributes back from the bridge as its event
stream would. It checks the script, not Hue Connect."""

import json
import os
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOCK = threading.Lock()


def light(rid, name, kind):
    l = {"id": rid, "type": "light", "metadata": {"name": name}, "on": {"on": False}, "mode": "normal"}
    if kind != "plug":
        l["dimming"] = {"brightness": 80.0}
    if kind in ("color", "ct"):
        l["color_temperature"] = {"mirek": 366, "mirek_valid": True}
        l["effects_v2"] = {"action": {"effect_values": ["no_effect", "candle", "fire"]}, "status": {"effect": "no_effect"}}
        l["timed_effects"] = {"effect_values": ["no_effect", "sunrise", "sunset"], "effect": "no_effect"}
        l["signaling"] = {"signal_values": ["no_signal", "on_off"], "status": {"signal": "no_signal"}}
    if kind == "color":
        l["color"] = {"xy": {"x": 0.45, "y": 0.41}, "gamut_type": "C"}
    return l


B = {
    "light": [light("101", "Desk lamp", "color"), light("102", "Hall bulb", "ct")],
    "device": [{"id": "1", "type": "device", "metadata": {"name": "Desk lamp"}, "services": [{"rid": "101", "rtype": "light"}]},
               {"id": "2", "type": "device", "metadata": {"name": "Hall bulb"}, "services": [{"rid": "102", "rtype": "light"}]},
               {"id": "3", "type": "device", "metadata": {"name": "Hall sensor"}, "services": [{"rid": "751", "rtype": "motion"}]}],
    "room": [{"id": "301", "type": "room", "metadata": {"name": "Office"},
              "children": [{"rid": "1", "rtype": "device"}, {"rid": "2", "rtype": "device"}],
              "services": [{"rid": "401", "rtype": "grouped_light"}]}],
    "zone": [],
    "grouped_light": [{"id": "401", "type": "grouped_light", "owner": {"rid": "301", "rtype": "room"}}],
    "scene": [{"id": "501", "type": "scene", "metadata": {"name": "Bright"}, "group": {"rid": "301", "rtype": "room"},
               "status": {"active": "inactive"}}],
    "smart_scene": [{"id": "601", "type": "smart_scene", "metadata": {"name": "Natural light"},
                     "group": {"rid": "301", "rtype": "room"}, "state": "inactive"}],
    "motion": [{"id": "751", "type": "motion", "enabled": True, "sensitivity": {"sensitivity": 2, "sensitivity_max": 4}}],
    "bridge": [{"id": "900", "type": "bridge", "bridge_id": "ecb5fafffe000001"}],
}
# Hubitat device id -> [label, driver, rid]
D = {"1": ["Hue Bridge (Fake)", "Hue Bridge", None], "11": ["Desk lamp", "Hue Color Light", "101"],
     "12": ["Hall bulb", "Hue White Ambiance Light", "102"], "21": ["Office", "Hue Group", "401"],
     "31": ["Bright (Office)", "Hue Scene", "501"], "32": ["Natural light (Office)", "Hue Scene", "601"],
     "41": ["Hall sensor", "Hue Motion Sensor", "751"]}
HS = {}            # requested hue/saturation per colour light (the app keeps these from the bridge's xy)
STREAM = {"down_until": 0}


def res(rtype, rid):
    return next(x for x in B[rtype] if x["id"] == rid)


def room_lights():
    return [res("light", "101"), res("light", "102")]


def put_light(l, body):
    for k, v in body.items():
        if k == "color":
            l["color"]["xy"] = v["xy"]
            l["color_temperature"]["mirek_valid"] = False
        elif k == "color_temperature":
            l["color_temperature"] = {"mirek": v["mirek"], "mirek_valid": True}
        elif k == "effects_v2":
            l["effects_v2"]["status"]["effect"] = v["action"]["effect"]
        elif k == "timed_effects":
            l["timed_effects"]["effect"] = v["effect"]
        elif k == "signaling":
            l["signaling"]["status"] = {"signal": v["signal"]}
        elif k in ("on", "dimming"):
            l[k].update(v)
    if not l["on"]["on"] and "timed_effects" in l:
        l["timed_effects"]["effect"] = "no_effect"
    res("scene", "501")["status"]["active"] = "inactive"


def recall(scene):
    for l in room_lights():
        put_light(l, {"on": {"on": True}, "dimming": {"brightness": 100.0}})
    scene["status"]["active"] = "static"


def attrs(dev_id):
    label, kind, rid = D[dev_id]
    if kind == "Hue Bridge":
        return {"status": "online", "healthStatus": "online",
                "eventStream": "disconnected" if time.time() < STREAM["down_until"] else "connected"}
    if kind in ("Hue Color Light", "Hue White Ambiance Light"):
        l = res("light", rid)
        a = {"switch": "on" if l["on"]["on"] else "off", "level": max(1, round(l["dimming"]["brightness"])),
             "healthStatus": "online", "hueMode": l["mode"],
             "effectName": "none" if l["effects_v2"]["status"]["effect"] == "no_effect" else l["effects_v2"]["status"]["effect"],
             "lightEffects": json.dumps({"0": "none", "1": "candle", "2": "fire"})}
        ct = l["color_temperature"]
        if ct["mirek_valid"]:
            a["colorTemperature"] = round(1e6 / ct["mirek"])
        if kind == "Hue Color Light":
            a["colorMode"] = "CT" if ct["mirek_valid"] else "RGB"
            a.update(HS.get(rid, {"hue": 10, "saturation": 70}))
        return a
    if kind == "Hue Group":
        on = [l for l in room_lights() if l["on"]["on"]]
        sc = res("scene", "501")
        return {"switch": "on" if on else "off", "level": round(sum(l["dimming"]["brightness"] for l in on) / len(on)) if on else 0,
                "lightEffects": json.dumps({"1": "Bright"}), "effectName": "Bright" if sc["status"]["active"] != "inactive" else "none"}
    if kind == "Hue Scene":
        sc = res("scene", rid) if rid == "501" else res("smart_scene", rid)
        active = sc["status"]["active"] != "inactive" if rid == "501" else sc["state"] == "active"
        return {"switch": "on" if active else "off"}
    if kind == "Hue Motion Sensor":
        m = res("motion", rid)
        return {"motion": "inactive", "motionDetection": "enabled" if m["enabled"] else "disabled",
                "sensitivity": m["sensitivity"]["sensitivity"]}
    return {}


BROKEN = os.environ.get("FAKE_BROKEN", "").split(",")   # commands to ignore, to see the script report a failure


def command(dev_id, cmd, args):
    label, kind, rid = D[dev_id]
    if cmd in BROKEN:
        return
    if kind == "Hue Bridge":
        if cmd == "reconnect":
            STREAM["down_until"] = time.time() + 1.5
        return
    if kind in ("Hue Color Light", "Hue White Ambiance Light"):
        l = res("light", rid)
        if cmd == "on":
            put_light(l, {"on": {"on": True}})
        elif cmd == "off":
            put_light(l, {"on": {"on": False}})
        elif cmd == "setLevel":
            put_light(l, {"on": {"on": True}, "dimming": {"brightness": float(args[0])}})
        elif cmd == "setColorTemperature":
            put_light(l, {"on": {"on": True}, "color_temperature": {"mirek": round(1e6 / float(args[0]))}})
        elif cmd in ("setColor", "setHue", "setSaturation"):
            cur = HS.get(rid, {"hue": 10, "saturation": 70})
            if cmd == "setColor":
                c = json.loads(args[0])
                cur = {"hue": c["hue"], "saturation": c["saturation"]}
                if "level" in c:
                    l["dimming"]["brightness"] = float(c["level"])
            elif cmd == "setHue":
                cur = dict(cur, hue=float(args[0]))
            else:
                cur = dict(cur, saturation=float(args[0]))
            HS[rid] = cur
            put_light(l, {"on": {"on": True}, "color": {"xy": {"x": 0.2, "y": 0.2}}})
        elif cmd == "setEffect":
            name = {"0": "no_effect", "1": "candle", "2": "fire"}[args[0]]
            put_light(l, {"on": {"on": True}, "effects_v2": {"action": {"effect": name}}})
        elif cmd == "flash":
            put_light(l, {"signaling": {"signal": "on_off"}})
        elif cmd == "startTimedEffect":
            put_light(l, {"on": {"on": True}, "timed_effects": {"effect": args[0]}})
        elif cmd != "identify":
            raise KeyError(cmd)
        return
    if kind == "Hue Group":
        if cmd == "setEffect":
            recall(res("scene", "501"))
            return
        for l in room_lights():
            command({"101": "11", "102": "12"}[l["id"]], cmd, args)
        return
    if kind == "Hue Scene":
        if rid == "501":
            if cmd in ("push", "on"):
                recall(res("scene", rid))
            else:
                for l in room_lights():
                    put_light(l, {"on": {"on": False}})
        else:
            res("smart_scene", rid)["state"] = "active" if cmd in ("push", "on") else "inactive"
        return
    if kind == "Hue Motion Sensor":
        m = res("motion", rid)
        if cmd == "setSensitivity":
            m["sensitivity"]["sensitivity"] = int(float(args[0]))
        else:
            m["enabled"] = cmd == "enableMotionDetection"


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def reply(self, code, body):
        data = (body if isinstance(body, str) else json.dumps(body)).encode()
        self.send_response(code)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"null")

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        parts = [urllib.parse.unquote(p) for p in u.path.strip("/").split("/")]
        with LOCK:
            if u.path == "/api/0/config":
                return self.reply(200, {"name": "Fake bridge", "bridgeid": "ECB5FAFFFE000001", "modelid": "BSB002", "apiversion": "1.70.0"})
            if u.path == "/hub/advanced/freeOSMemory":
                return self.reply(200, "812345")
            if u.path == "/hub2/userAppTypes":
                return self.reply(200, [{"id": 7, "name": "Hue Connect", "namespace": "hue"}])
            if u.path == "/hub2/userDeviceTypes":
                return self.reply(200, [{"id": 70 + i, "name": n, "namespace": "hue"} for i, n in enumerate(
                    ["Hue Bridge", "Hue Color Light", "Hue White Ambiance Light", "Hue White Light", "Hue Plug", "Hue Group", "Hue Scene",
                     "Hue Button", "Hue Motion Sensor", "Hue Contact Sensor"])])
            if parts[:3] == ["clip", "v2", "resource"]:
                if self.headers.get("hue-application-key") != "fake-key":
                    return self.reply(403, {"errors": [{"description": "unauthorized user"}]})
                items = B.get(parts[3], [])
                if len(parts) > 4:
                    items = [x for x in items if x["id"] == parts[4]]
                return self.reply(200, {"errors": [], "data": json.loads(json.dumps(items))})
            if parts[:2] == ["apps", "api"]:
                if urllib.parse.parse_qs(u.query).get("access_token") != ["fake-token"]:
                    return self.reply(401, {"error": "token"})
                rest = parts[3:]
                if rest == ["devices", "all"]:
                    return self.reply(200, [{"id": i, "label": d[0], "name": d[1], "type": d[1], "attributes": attrs(i)} for i, d in D.items()])
                if len(rest) == 2:
                    return self.reply(200, {"id": rest[1], "label": D[rest[1]][0], "type": D[rest[1]][1],
                                            "attributes": [{"name": k, "currentValue": v} for k, v in attrs(rest[1]).items()]})
                if len(rest) >= 3 and rest[2] == "events":
                    return self.reply(200, [])
                if len(rest) >= 3:
                    try:
                        command(rest[1], rest[2], rest[3].split(",", 1) if len(rest) > 3 and rest[2] == "startTimedEffect"
                                else ([rest[3]] if len(rest) > 3 else []))
                    except KeyError as e:
                        return self.reply(404, {"error": "no command %s" % e})
                    return self.reply(200, {})
        self.reply(404, {"error": u.path})

    def do_PUT(self):
        parts = self.path.strip("/").split("/")
        with LOCK:
            if parts[:3] == ["clip", "v2", "resource"] and self.headers.get("hue-application-key") == "fake-key":
                body, r = self.body(), res(parts[3], parts[4])
                if parts[3] == "light":
                    put_light(r, body)
                elif parts[3] == "motion":
                    r["enabled"] = body.get("enabled", r["enabled"])
                    if "sensitivity" in body:
                        r["sensitivity"]["sensitivity"] = body["sensitivity"]["sensitivity"]
                return self.reply(200, {"errors": [], "data": [{"rid": parts[4], "rtype": parts[3]}]})
        self.reply(403, {"errors": [{"description": "unauthorized user"}]})

    def do_POST(self):
        if self.path == "/api":
            return self.reply(200, [{"success": {"username": "fake-key"}}])
        self.reply(404, {})


if __name__ == "__main__":
    srv = ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1]) if len(sys.argv) > 1 else 0), Handler)
    print(srv.server_address[1], flush=True)
    srv.serve_forever()
