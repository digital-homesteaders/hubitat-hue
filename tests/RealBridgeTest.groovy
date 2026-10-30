/**
 * The mapping against a real bridge: every light, remote, sensor, group, scene
 * and connectivity resource in tests/fixtures/resources-real.json (a scrubbed
 * `GET /clip/v2/resource` from a real bridge; see tests/fixtures/scrub-dump.py)
 * goes through the app's pure functions. The script also thins the dump (two of
 * each unread type, a few scenes per room), so counts here are the thinned ones. The targeted tests use the small
 * synthetic sample; this one finds what real data has that the sample doesn't.
 */

import groovy.json.JsonSlurper
import static Check.check

File root = new File(getClass().protectionDomain.codeSource.location.path).parentFile.parentFile
Class app = new Hub(root).compile(new File(root, "apps/hue-connect.groovy"))
List items = (new JsonSlurper().parse(new File(root, "tests/fixtures/resources-real.json")) as Map).data as List
Map byId = items.collectEntries { [(it.id): it] }
Closure ofType = { String t -> items.findAll { it.type == t } }

boolean near(double a, double b, double tol = 0.0006) { Math.abs(a - b) <= tol }
Closure deviceOf = { Map service -> byId[service.owner?.rid] as Map }
Closure infoOf = { Map light -> app.lightInfo(light, deviceOf(light)) as Map }
Closure named = { List events -> events.collectEntries { [(it.name): it.value] } }

// Resource types in the dump that Hue Connect doesn't read, each on purpose or a known gap (see the feature list in the README)
List ignoredTypes = ["bridge", "clip", "behavior_instance", "behavior_script", "device_software_update", "entertainment",
                     "geofence_client", "geolocation", "grouped_light_level", "grouped_motion", "matter", "matter_fabric",
                     "motion_area_candidate", "service_group", "sonos", "zigbee_device_discovery"]

check("every resource type on the bridge is read by the app or knowingly ignored") {
    Set types = items*.type as Set
    Set unknown = types - (app.RESOURCE_TYPES as Set) - ignoredTypes
    assert unknown.isEmpty(), "new resource types to look at: ${unknown}"
    Set stale = ignoredTypes as Set
    stale.removeAll(types)
    assert stale.isEmpty(), "ignored types no longer in the fixture: ${stale}"
}

List<Map> lights = ofType("light")

check("lights: the driver kind follows each light's own features, and plugs are on/off only") {
    assert lights.size() == 61
    lights.each { Map l ->
        Map info = infoOf(l)
        String expected = l.color ? "color" : l.color_temperature ? "ct" : l.dimming ? "dim" : "onoff"
        assert info.kind == expected, "${l.metadata.name}"
        if (deviceOf(l).product_data.product_archetype == "plug") assert info.kind == "onoff", "${l.metadata.name}"
        if (info.kind == "color") assert info.gamut?.red && info.gamut?.green && info.gamut?.blue, "${l.metadata.name} has no gamut"
        if (l.color_temperature?.mirek_schema) assert info.mirekMin < info.mirekMax, "${l.metadata.name}"
        assert info.model && info.firmware, "${l.metadata.name} lacks device details"
    }
    assert lights.count { infoOf(it).kind == "color" } == 48
    assert lights.count { infoOf(it).kind == "onoff" } == 3
}

check("lights: events from every light's real state are complete and in range") {
    lights.each { Map l ->
        Map info = infoOf(l)
        Map ev = named(app.lightEvents(l, info) as List)
        assert ev.switch in ["on", "off"], l.metadata.name
        if (l.dimming) assert ev.level instanceof Integer && ev.level >= 1 && ev.level <= 100, "${l.metadata.name} level ${ev.level}"
        else assert !ev.containsKey("level"), l.metadata.name
        if (ev.containsKey("colorTemperature")) assert ev.colorTemperature >= 1500 && ev.colorTemperature <= 10000, "${l.metadata.name} ${ev.colorTemperature} K"
        if (ev.containsKey("hue")) {
            assert ev.hue >= 0 && ev.hue <= 100 && ev.saturation >= 0 && ev.saturation <= 100, "${l.metadata.name} ${ev.hue}/${ev.saturation}"
        }
        if (info.kind == "color") assert ev.colorMode in ["RGB", "CT"], "${l.metadata.name} colorMode ${ev.colorMode}"
        else assert !ev.containsKey("colorMode"), l.metadata.name
    }
}

check("lights: commands stay inside what each light reports, with on only for a light that is off") {
    lights.each { Map l ->
        Map info = infoOf(l)
        String who = l.metadata.name
        Map off = [switch: "off", level: 50, hue: 10, saturation: 50]
        Map on = [switch: "on", level: 50, hue: 10, saturation: 50]
        assert app.lightCommand("off", [], info, on, 400) == [on: [on: false]], who
        assert app.lightCommand("on", [], info, off, 400).on == [on: true], who
        if (info.kind != "onoff") {
            Map b = app.lightCommand("setLevel", [30], info, off, 400)
            assert b.dimming.brightness == 30d && b.on == [on: true] && b.dynamics.duration == 400, who
            assert app.lightCommand("setLevel", [30], info, on, 400).on == null, "${who}: on sent for a light already on"
        }
        if (info.kind == "ct" || info.kind == "color") {
            int k = app.kelvinFromMirek(info.mirekMax as int) + 50
            Map b = app.lightCommand("setColorTemperature", [k], info, on, 400)
            assert b.color_temperature.mirek >= info.mirekMin && b.color_temperature.mirek <= info.mirekMax, who
            assert !b.containsKey("color"), "${who}: xy and ct together"
        }
        if (info.kind == "color") {
            [[0, 100], [120, 100], [240, 100], [300, 50], [60, 0]].each { hs ->
                Map b = app.lightCommand("setColor", [[hue: hs[0], saturation: hs[1], level: 40]], info, on, 400)
                double x = b.color.xy.x, y = b.color.xy.y
                List inside = app.clampToGamut(x, y, info.gamut as Map)
                assert near(x, inside[0] as double) && near(y, inside[1] as double), "${who}: ${hs} outside the gamut"
                assert !b.containsKey("color_temperature"), who
            }
        }
        Map flash = app.lightCommand("flash", [3000], info, on, null)
        assert ("on_off" in (info.signals ?: [])) ? flash.signaling.signal == "on_off" : flash.alert.action == "breathe", who
        assert app.lightCommand("identify", [], info, on, null) == [identify: [action: "identify"]], who
        if (info.effects) {
            assert app.lightCommand("setEffect", [1], info, on, null).effects_v2.action.effect == info.effects[0], who
            assert app.lightCommand("setEffect", [0], info, on, null).effects_v2.action.effect == "no_effect", who
        } else {
            assert app.lightCommand("setEffect", [1], info, on, null) == null, who
        }
        if (info.timedEffects) {
            assert app.lightCommand("startTimedEffect", [info.timedEffects[0], 30], info, on, null).timed_effects.duration == 1800000, who
        }
    }
}

check("names: a light is named by its device, as Hue says (13 real lights carry another name of their own), and a device rename is found") {
    assert lights.count { it.metadata.name != deviceOf(it).metadata.name } == 13, "the dump has lights whose own name is not their device's"
    lights.each { Map l ->
        assert infoOf(l).name == deviceOf(l).metadata.name, "${l.metadata.name} vs ${deviceOf(l).metadata.name}"
        Map res = [lights: [(l.id): infoOf(l)]]
        assert app.renamed([type: "device", id: l.owner.rid, metadata: [name: "Renamed"]], res) == [[section: "lights", rid: l.id, set: [name: "Renamed"]]], l.metadata.name
        assert app.renamed([type: "device", id: l.owner.rid, metadata: [name: deviceOf(l).metadata.name]], res) == [], l.metadata.name
    }
}

check("colour temperature ramp: every real light with colour temperature ramps its own range, others refuse") {
    lights.each { Map l ->
        Map info = infoOf(l)
        Map up = app.lightCommand("startColorTemperatureChange", ["warmer"], info, [switch: "on"], 400)
        Map stop = app.lightCommand("stopColorTemperatureChange", [], info, [switch: "on"], 400)
        if (l.color_temperature) {
            assert up.color_temperature_delta.mirek_delta == info.mirekMax - info.mirekMin && up.color_temperature_delta.action == "up", l.metadata.name
            assert stop == [color_temperature_delta: [action: "stop"]], l.metadata.name
        } else {
            assert up == null && stop == null, l.metadata.name
        }
    }
    assert lights.count { it.color_temperature } == 48
}

check("power-up: every real light offers it, reports a preset the driver can show, and can be set to each behaviour") {
    Map shown = [:]
    lights.each { Map l ->
        Map info = infoOf(l)
        assert info.powerUp == true, l.metadata.name
        String now = named(app.lightEvents(l, info) as List).powerUp
        assert now in ["previous", "lastOnState", "safety", "off", "custom"], "${l.metadata.name}: ${l.powerup}"
        shown[now] = (shown[now] ?: 0) + 1
        ["previous", "lastOnState", "safety", "off"].each { String b ->
            Map body = app.lightCommand("setPowerUp", [b], info, [switch: "off"], 400)
            assert body.keySet() == ["powerup"] as Set && app.powerUpName(body.powerup as Map) == b, "${l.metadata.name} ${b}"
        }
    }
    assert shown == [previous: 50, safety: 11], "the bridge's lights as dumped: ${shown}"
}

check("scene speed: dynamic scenes show a whole percentage, static scenes and smart scenes show none") {
    List<Map> scenes = ofType("scene")
    int dynamic = 0
    scenes.each { Map s ->
        boolean isDynamic = s.palette?.color ? true : false
        List ev = app.sceneEvents(s, [dynamic: isDynamic]) as List
        Map speed = ev.find { it.name == "speed" }
        if (isDynamic) {
            dynamic++
            assert speed && speed.value instanceof Integer && speed.value >= 0 && speed.value <= 100, s.metadata.name
            assert Math.abs(app.speedFromPercent(speed.value) - (s.speed as double)) <= 0.0051d, s.metadata.name   // a whole percent
        } else {
            assert speed == null, s.metadata.name
        }
    }
    assert dynamic == 17
    ofType("smart_scene").each { Map s -> assert !app.sceneEvents(s, [dynamic: true]).any { it.name == "speed" }, s.metadata.name }
}

check("lights: every light's effect, timed effect and signal lists come from the light, not the model") {
    Map byModel = [:]
    lights.each { Map l ->
        Map info = infoOf(l)
        assert !(info.effects ?: []).contains("no_effect") && !(info.timedEffects ?: []).contains("no_effect"), l.metadata.name
        String m = info.model
        byModel[m] = (byModel[m] ?: [] as Set) + [[info.effects, info.timedEffects, info.signals].toString()]
    }
    assert byModel.size() >= 3
}

List<Map> devices = ofType("device")
Map servicesById = items.findAll { it.type != "device" }.collectEntries { [(it.id): it] }

check("remotes and sensors: every dimmer, Smart button and motion sensor gets its kind and buttons") {
    List remotes = devices.findAll { d -> d.services.any { it.rtype in ["button", "relative_rotary", "bell_button"] } }
    List sensors = devices.findAll { d -> d.services.any { it.rtype == "motion" } }
    assert remotes.size() == 12 && sensors.size() == 6
    remotes.each { Map d ->
        Map info = app.sensorInfo(d, servicesById) as Map
        assert info.kind == "button", d.metadata.name
        int buttons = d.services.count { it.rtype == "button" }
        assert info.buttons.size() == buttons && info.numberOfButtons == info.buttons.values().max(), "${d.metadata.name}: ${info.buttons}"
        assert info.buttons.values().toSet().size() == buttons, "${d.metadata.name}: two buttons share a number"
    }
    sensors.each { Map d ->
        Map info = app.sensorInfo(d, servicesById) as Map
        assert info.kind == "motion" && info.motion && info.sensitivityMax >= 1, d.metadata.name
    }
}

check("remotes: every real button reports each press kind as the right button number") {
    devices.each { Map d ->
        Map info = app.sensorInfo(d, servicesById) as Map
        if (info?.kind != "button") return
        info.buttons.each { String rid, Integer n ->
            Map use = [initial_press: info.pushOnPress[rid] ? "pushed" : null, short_release: info.pushOnPress[rid] ? null : "pushed",
                       long_press: "held", long_release: "released", double_short_release: "doubleTapped"]
            use.each { String event, String name ->
                List ev = app.sensorEvents([id: rid, type: "button", button: [button_report: [event: event]]], info, "F") as List
                if (name) assert ev.size() == 1 && ev[0].name == name && ev[0].value == n, "${d.metadata.name} ${event}"
                else assert ev.isEmpty(), "${d.metadata.name} ${event} should not report"
            }
        }
    }
}

check("sensors: motion, light level, temperature and battery from the real state") {
    ofType("motion").each { Map m ->
        Map ev = named(app.sensorEvents(m, [:], "F") as List)
        assert ev.motion in ["active", "inactive"] && ev.motionDetection in ["enabled", "disabled"] && ev.sensitivity != null, m.id
    }
    ofType("light_level").each { Map s ->
        Map ev = named(app.sensorEvents(s, [:], "F") as List)
        assert ev.illuminance instanceof Integer && ev.illuminance >= 0, s.id
    }
    ofType("temperature").each { Map s ->
        double c = s.temperature.temperature_report.temperature
        assert named(app.sensorEvents(s, [:], "C") as List).temperature == Math.round(c * 10) / 10d, s.id
        double f = named(app.sensorEvents(s, [:], "F") as List).temperature
        assert near(f, c * 9 / 5 + 32, 0.06), s.id
    }
    ofType("device_power").each { Map p ->
        Map ev = named(app.sensorEvents(p, [:], "F") as List)
        assert ev.battery >= 0 && ev.battery <= 100, p.id
    }
}

check("health: every device's connectivity maps to online or offline") {
    List<Map> links = ofType("zigbee_connectivity")
    assert links.size() == 80
    links.each { Map z -> assert named(app.connectivityEvents(z) as List).healthStatus == "online", z.id }
}

check("rooms, zones, groups and scenes: owners and rooms resolve, and the states map") {
    List<Map> groups = ofType("grouped_light")
    // The bridge keeps one grouped_light for a private_group it never lists; the app has no owner for it and skips it
    List<Map> hidden = groups.findAll { !byId[it.owner.rid] }
    assert hidden.size() == 1 && hidden[0].owner.rtype == "private_group"
    groups.each { Map g ->
        assert byId[g.owner.rid]?.type in ["room", "zone", "bridge_home"] || g in hidden, g.id
        Map ev = named(app.groupEvents(g) as List)
        assert ev.switch in ["on", "off"], g.id
        if (ev.containsKey("level")) assert ev.level >= 1 && ev.level <= 100, g.id
    }
    Set groupOwners = (groups - hidden).collect { it.owner.rid } as Set
    // A room or zone gets a grouped_light only when it holds lights (Family Room is empty, Dungeon Desk has just a sensor)
    (ofType("room") + ofType("zone")).each { Map r ->
        boolean hasLights = r.children.any { c ->
            Map child = byId[c.rid] as Map
            child?.type == "light" || child?.services?.any { it.rtype == "light" }
        }
        assert (r.id in groupOwners) == hasLights, "${r.metadata.name}: lights ${hasLights}, grouped_light ${r.id in groupOwners}"
    }
    List<Map> scenes = ofType("scene") + ofType("smart_scene")
    assert scenes.any { it.type == "smart_scene" } && scenes.any { it.palette?.color } && scenes.any { it.type == "scene" && !it.palette?.color }
    scenes.each { Map s ->
        assert byId[s.group.rid]?.type in ["room", "zone"], s.metadata.name
        assert named(app.sceneEvents(s) as List).switch in ["on", "off"], s.metadata.name
    }
}

Check.finish()
