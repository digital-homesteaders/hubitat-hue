/**
 * Hue Connect's pure functions: conversions, colour (Hue's formulas, gamut
 * clamping), resource-to-event mapping and command bodies.
 */

import groovy.json.JsonSlurper
import static Check.check

File root = new File(getClass().protectionDomain.codeSource.location.path).parentFile.parentFile
Class app = new Hub(root).compile(new File(root, "apps/hue-connect.groovy"))
List items = (new JsonSlurper().parse(new File(root, "tests/fixtures/resources-sample.json")) as Map).data as List
Map byName = items.findAll { it.type == "light" }.collectEntries { [(it.metadata.name): it] }
Map C = app.GAMUTS.C as Map

boolean near(double a, double b, double tol = 0.002) { Math.abs(a - b) <= tol }

check("brightness and level: 0 is Hue's lowest, Hubitat shows 1-100") {
    assert app.levelFromBrightness(0d) == 1 && app.levelFromBrightness(45.5d) == 46 && app.levelFromBrightness(100d) == 100
    assert app.brightnessFromLevel(150d) == 100d && app.brightnessFromLevel(-3d) == 0d
}

check("kelvin and mirek, kept inside the light's range") {
    assert app.kelvinFromMirek(366) == 2732 && app.kelvinFromMirek(153) == 6536
    assert app.mirekFromKelvin(2700, 153, 454) == 370
    assert app.mirekFromKelvin(1500, 153, 454) == 454 && app.mirekFromKelvin(10000, 153, 454) == 153
    assert app.mirekFromKelvin(2000, null, null) == 500
}

check("colour: red goes to (or onto the edge of) gamut C's red corner; white to the white point") {
    List red = app.hueSatToXy(0d, 100d, C)
    assert near(red[0], 0.6915d, 0.02) && near(red[1], 0.3083d, 0.02)
    List white = app.hueSatToXy(0d, 0d, C)
    assert near(white[0], 0.3227d, 0.01) && near(white[1], 0.329d, 0.01)
}

check("colour: every result lies inside the light's gamut") {
    [app.GAMUTS.A, app.GAMUTS.B, C].each { Map g ->
        (0..100).step(5).each { h ->
            List xy = app.hueSatToXy(h as double, 100d, g)
            assert app.clampToGamut(xy[0] as double, xy[1] as double, g) == xy, "hue ${h} outside ${g}"
        }
    }
}

check("colour: gamut C's corners are red, green and blue at full saturation; the white point has none") {
    assert app.xyToHueSat(0.6915d, 0.3083d) == [3, 100] && app.xyToHueSat(0.17d, 0.7d) == [38, 100]
    assert app.xyToHueSat(0.1532d, 0.0475d) == [70, 100] && app.xyToHueSat(0.3227d, 0.329d)[1] == 0
}

check("colour: hue and saturation survive a round trip for colours inside the gamut") {
    [[0, 100], [8, 90], [33, 100], [55, 70], [66, 100], [83, 60]].each { List hs ->
        List xy = app.hueSatToXy(hs[0] as double, hs[1] as double, app.GAMUTS.other)
        List back = app.xyToHueSat(xy[0] as double, xy[1] as double)
        assert Math.abs((back[0] as int) - (hs[0] as int)) <= 1 || Math.abs((back[0] as int) - (hs[0] as int)) >= 99, "hue ${hs} -> ${back}"
        assert Math.abs((back[1] as int) - (hs[1] as int)) <= 2, "saturation ${hs} -> ${back}"
    }
}

check("an out-of-gamut point is moved to the closest point on the triangle's edge") {
    List p = app.clampToGamut(0.1d, 0.85d, C)
    assert p != [0.1d, 0.85d] && app.clampToGamut(p[0] as double, p[1] as double, C) == p
    assert near(p[0], 0.17d, 0.02) && near(p[1], 0.7d, 0.02)
}

check("a light's gamut: its own, else its gamut_type's corners (some bulbs don't report it), else the full triangle") {
    assert app.gamutFor(byName["Living Lamp"].color).red == [0.6915d, 0.3083d]
    assert app.gamutFor(byName["Old Bloom"].color) == app.GAMUTS.B
    assert app.gamutFor([xy: [x: 0.3, y: 0.3]]) == app.GAMUTS.other
}

check("light info: the driver kind follows its features") {
    Map kinds = byName.collectEntries { n, l -> [(n): app.lightInfo(l, null).kind] }
    assert kinds == ["Living Lamp": "color", "Hall Ceiling": "ct", "Porch": "dim", "Fountain Plug": "onoff", "Old Bloom": "color"]
    Map hall = app.lightInfo(byName["Hall Ceiling"], null)
    assert hall.mirekMin == 153 && hall.mirekMax == 454 && hall.name == "Hall Ceiling"
}

check("events from a full colour light in colour mode") {
    Map info = app.lightInfo(byName["Living Lamp"], null)
    Map ev = app.lightEvents(byName["Living Lamp"], info).collectEntries { [(it.name): it.value] }
    assert ev.switch == "on" && ev.level == 80 && ev.colorMode == "RGB"
    assert ((ev.hue as int) <= 5 || (ev.hue as int) >= 95) && (ev.saturation as int) >= 90   // gamut C's red corner is hue 3 (11 degrees)
}

check("events from a white ambiance light in CT mode") {
    Map ev = app.lightEvents(byName["Hall Ceiling"], app.lightInfo(byName["Hall Ceiling"], null)).collectEntries { [(it.name): it.value] }
    assert ev == [switch: "off", level: 46, colorTemperature: 2732, effectName: "candle", hueMode: "normal"]   // no colorMode: only colour lights have one
}

check("events from partial updates: only what changed; CT becoming invalid switches a colour light to RGB") {
    Map info = [kind: "color"]
    assert app.lightEvents([on: [on: false]], info) == [[name: "switch", value: "off"]]
    assert app.lightEvents([color_temperature: [mirek: null, mirek_valid: false]], info) == [[name: "colorMode", value: "RGB"]]
    List ct = app.lightEvents([color_temperature: [mirek: 250, mirek_valid: true], color: [xy: [x: 0.4, y: 0.4]]], info)
    assert ct*.name == ["colorTemperature", "colorMode"] && ct[1].value == "CT"
    assert app.lightEvents([dimming: [brightness: 0.4]], [kind: "dim"]) == [[name: "level", value: 1, unit: "%"]]
}

check("health from zigbee_connectivity: online only when connected") {
    assert app.connectivityEvents([status: "connected"]) == [[name: "healthStatus", value: "online"]]
    ["disconnected", "connectivity_issue", "unidirectional_incoming", "pending_discovery"].each {
        assert app.connectivityEvents([status: it]) == [[name: "healthStatus", value: "offline"]]
    }
    assert app.connectivityEvents([:]) == []
}

check("commands: only what changes, with on only for a light that isn't on") {
    Map info = app.lightInfo(byName["Hall Ceiling"], null)
    assert app.lightCommand("on", [], info, [switch: "off"], 400) == [on: [on: true], dynamics: [duration: 400]]   // transition with every command
    assert app.lightCommand("on", [], [kind: "onoff"], [switch: "off"], null) == [on: [on: true]]                  // plugs: none
    assert app.lightCommand("off", [], info, [switch: "on"], 400) == [on: [on: false]]   // no transition with off
    assert app.lightCommand("setLevel", [30, null], info, [switch: "on"], 400) == [dimming: [brightness: 30d], dynamics: [duration: 400]]
    assert app.lightCommand("setLevel", [30, 2], info, [switch: "off"], 400) == [dimming: [brightness: 30d], dynamics: [duration: 2000], on: [on: true]]
    assert app.lightCommand("setLevel", [0, null], info, [switch: "on"], 400) == [on: [on: false]]
    assert app.lightCommand("setColorTemperature", [2000, null, null], info, [switch: "on"], 0) == [color_temperature: [mirek: 454], dynamics: [duration: 0]]
    assert app.lightCommand("setColorTemperature", [4000, 50, 1], info, [switch: "off"], 400) ==
        [color_temperature: [mirek: 250], dimming: [brightness: 50d], dynamics: [duration: 1000], on: [on: true]]
}

check("colour commands use the light's gamut; level changes use dimming_delta; refresh sends nothing") {
    Map info = app.lightInfo(byName["Living Lamp"], null)
    Map body = app.lightCommand("setColor", [[hue: 66, saturation: 100, level: 70]], info, [switch: "on"], null)
    assert body.color.xy.x instanceof Double && body.dimming == [brightness: 70d] && !body.containsKey("on") && !body.containsKey("dynamics")
    assert app.clampToGamut(body.color.xy.x as double, body.color.xy.y as double, info.gamut) == [body.color.xy.x, body.color.xy.y] ||
        near(body.color.xy.x, 0.1532d, 0.01)
    assert app.lightCommand("startLevelChange", ["down"], info, [switch: "on"], 400) ==
        [dimming_delta: [action: "down", brightness_delta: 100], dynamics: [duration: 4000]]   // a release partway stops partway
    assert app.lightCommand("stopLevelChange", [], info, [switch: "on"], 400) == [dimming_delta: [action: "stop"]]
    assert app.lightCommand("refresh", [], info, [switch: "on"], 400) == null
}

check("discovery: a V2 bridge from /api/0/config; the round V1 bridge and junk are skipped") {
    assert app.bridgeFromConfig([bridgeid: "001788FFFE25B8F8", name: "Hue Bridge Pro", modelid: "BSB003"], "172.16.213.108") ==
        [id: "001788fffe25b8f8", ip: "172.16.213.108", name: "Hue Bridge Pro", model: "BSB003"]
    assert app.bridgeFromConfig([bridgeid: "001788FFFE25B8F8", modelid: "BSB001"], "1.2.3.4") == null
    assert app.bridgeFromConfig([name: "Not a bridge"], "1.2.3.4") == null
}

check("setHue then setSaturation (before the bridge answers) combine what was asked, not the device's stale values") {
    Map hs = app.requestedHueSat("setHue", [66], [hue: 0, saturation: 50])
    assert hs == [hue: 66, saturation: 50]
    assert app.requestedHueSat("setSaturation", [80], [hue: hs.hue, saturation: hs.saturation]) == [hue: 66, saturation: 80]
    assert app.requestedHueSat("setHue", [10], [hue: 0, saturation: 0]) == [hue: 10, saturation: 0], "saturation 0 is a value, not unset"
    assert app.requestedHueSat("setLevel", [10], [:]) == null
}

check("Hue guidance: no superfluous parameters (a level equal to the current one isn't sent with colour or CT)") {
    Map info = app.lightInfo(byName["Living Lamp"], null)
    Map body = app.lightCommand("setColor", [[hue: 66, saturation: 100, level: 80]], info, [switch: "on", level: 80], null)
    assert !body.containsKey("dimming")
    body = app.lightCommand("setColor", [[hue: 66, saturation: 100, level: 50]], info, [switch: "on", level: 80], null)
    assert body.dimming == [brightness: 50d]
    body = app.lightCommand("setColorTemperature", [3000, 80, null], app.lightInfo(byName["Hall Ceiling"], null), [switch: "on", level: 80], null)
    assert !body.containsKey("dimming")
}

check("groups and scenes: events, effect lists and effect names") {
    assert app.groupEvents([on: [on: true], dimming: [brightness: 64.6]]) == [[name: "switch", value: "on"], [name: "level", value: 65, unit: "%"]]
    assert app.groupEvents([on: [on: false], dimming: [brightness: 0]]) == [[name: "switch", value: "off"]]
    assert app.sceneEvents([type: "scene", status: [active: "dynamic_palette"]]) == [[name: "switch", value: "on"]]
    assert app.sceneEvents([type: "scene", status: [active: "inactive"]]) == [[name: "switch", value: "off"]]
    assert app.sceneEvents([type: "smart_scene", state: "active"]) == [[name: "switch", value: "on"]]
    assert app.sceneEvents([type: "scene", metadata: [name: "x"]]) == []
    Map scenes = [a: [name: "Relax", groupedLight: "g1"], b: [name: "Bright", groupedLight: "g1"], c: [name: "Night", groupedLight: "g2"]]
    assert app.groupEffects("g1", scenes)*.name == ["Bright", "Relax"]
    assert app.effectListEvents("g1", scenes) == [[name: "lightEffects", value: '{"1":"Bright","2":"Relax"}']]
    assert app.effectNameEvents("Relax", true, "none") == [[name: "effectName", value: "Relax"]]
    assert app.effectNameEvents("Relax", false, "Relax") == [[name: "effectName", value: "none"]]
    assert app.effectNameEvents("Relax", false, "Bright") == []
}

// ---------------------------------------------------------------- phase 3: remotes and sensors

Map servicesById = items.findAll { it.type != "device" }.collectEntries { [(it.id): it] }
Map deviceNamed = items.findAll { it.type == "device" }.collectEntries { [(it.metadata.name): it] }
String rid(int n) { String.format("%08x-0000-4000-8000-%012x", n, n) }

check("remotes and sensors: kind from services (not the model), buttons by control_id, the ring adds buttons 5 and 6") {
    Map dimmer = app.sensorInfo(deviceNamed["Hall Dimmer"], servicesById)
    assert dimmer.kind == "button" && dimmer.numberOfButtons == 4 && dimmer.buttons[rid(703)] == 3 && !dimmer.rotary
    Map dial = app.sensorInfo(deviceNamed["Living Tap Dial"], servicesById)
    assert dial.rotary && dial.numberOfButtons == 6
    assert app.sensorInfo(deviceNamed["Hall Motion"], servicesById).kind == "motion"
    assert app.sensorInfo(deviceNamed["Back Door"], servicesById).kind == "contact"
    assert app.sensorInfo(deviceNamed["Friends of Hue Switch"], servicesById).pushOnPress.size() == 4
    assert app.sensorInfo(deviceNamed["Living Lamp"], servicesById) == null
}

check("buttons: short release pushes, long press holds, long release releases, double press double-taps; repeat is marked") {
    Map info = app.sensorInfo(deviceNamed["Bedside Button"], servicesById)
    def ev = { String e -> app.sensorEvents([id: rid(721), type: "button", button: [button_report: [event: e]]], info, "F") }
    assert ev("initial_press") == []
    assert ev("short_release") == [[name: "pushed", value: 1, isStateChange: true, type: "physical"]]
    assert ev("long_press")[0].subMap(["name", "value"]) == [name: "held", value: 1]
    assert ev("repeat")[0].repeat == true && ev("repeat")[0].name == "held"
    assert ev("long_release")[0].name == "released" && ev("double_short_release")[0].name == "doubleTapped"
}

check("buttons that never report a short release push on the press") {
    Map info = app.sensorInfo(deviceNamed["Friends of Hue Switch"], servicesById)
    def ev = { String e -> app.sensorEvents([id: rid(743), type: "button", button: [button_report: [event: e]]], info, "F") }
    assert ev("initial_press") == [[name: "pushed", value: 3, isStateChange: true, type: "physical"]]
    assert ev("short_release") == []
}

check("the Tap Dial ring: clockwise is button 5, anticlockwise 6, with signed degrees (1000 steps per turn)") {
    Map info = app.sensorInfo(deviceNamed["Living Tap Dial"], servicesById)
    List cw = app.sensorEvents([id: rid(715), type: "relative_rotary", relative_rotary: [rotary_report: [action: "start", rotation: [direction: "clock_wise", steps: 125, duration: 400]]]], info, "F")
    assert cw[0].subMap(["name", "value"]) == [name: "pushed", value: 5] && cw[1].subMap(["name", "value"]) == [name: "rotation", value: 45]
    List ccw = app.sensorEvents([id: rid(715), type: "relative_rotary", relative_rotary: [rotary_report: [action: "repeat", rotation: [direction: "counter_clock_wise", steps: 250, duration: 400]]]], info, "F")
    assert ccw[0].value == 6 && ccw[1].value == -90
}

check("sensors: motion, lux from Hue's light level, temperature in the hub's scale, contact, tamper, battery") {
    assert app.sensorEvents([type: "motion", motion: [motion_report: [motion: true]]], [:], "F") == [[name: "motion", value: "active"]]
    assert app.luxFromLightLevel(1) == 1 && app.luxFromLightLevel(20001) == 100 && app.luxFromLightLevel(40001) == 10000
    assert app.sensorEvents([type: "temperature", temperature: [temperature_report: [temperature: 21.5]]], [:], "F")[0].value == 70.7d
    assert app.sensorEvents([type: "temperature", temperature: [temperature_report: [temperature: 21.5]]], [:], "C")[0].value == 21.5d
    assert app.sensorEvents([type: "contact", contact_report: [state: "no_contact"]], [:], "F") == [[name: "contact", value: "open"]]
    assert app.sensorEvents([type: "tamper", tamper_reports: [[state: "tampered"]]], [:], "F") == [[name: "tamper", value: "detected"]]
    assert app.sensorEvents([type: "device_power", power_state: [battery_level: 15, battery_state: "low"]], [:], "F") ==
        [[name: "battery", value: 15, unit: "%"], [name: "batteryState", value: "low"]]
    assert app.sensorEvents([type: "motion", motion: [motion_valid: false]], [:], "F") == []
}

// ---------------------------------------------------------------- phase 4: effects, flash, identify, timed effects, details

check("effects: each light's own list (no_effect left out); setEffect, next/previous wrap, 0 stops") {
    Map info = app.lightInfo(byName["Living Lamp"], null)
    assert info.effects == ["candle", "fire", "sparkle", "prism"] && info.timedEffects == ["sunrise", "sunset"]
    assert app.lightEffectEvents(info) == [[name: "lightEffects", value: '{"1":"candle","2":"fire","3":"sparkle","4":"prism"}']]
    assert app.lightCommand("setEffect", [2], info, [switch: "on"], 400) == [effects_v2: [action: [effect: "fire"]]]
    assert app.lightCommand("setEffect", [2], info, [switch: "off"], 400) == [effects_v2: [action: [effect: "fire"]], on: [on: true]]
    assert app.lightCommand("setEffect", [0], info, [switch: "on"], 400) == [effects_v2: [action: [effect: "no_effect"]]]
    assert app.lightCommand("setNextEffect", [], info, [switch: "on", effectName: "prism"], 400).effects_v2.action.effect == "candle"
    assert app.lightCommand("setPreviousEffect", [], info, [switch: "on", effectName: "none"], 400).effects_v2.action.effect == "prism"
    assert app.lightCommand("setEffect", [9], info, [switch: "on"], 400) == null
    assert app.lightCommand("setEffect", [1], app.lightInfo(byName["Porch"], null), [switch: "on"], 400) == null
    assert app.lightEvents([effects_v2: [status: [effect: "no_effect"]]], [kind: "color"]) == [[name: "effectName", value: "none"]]
    assert app.lightEvents([mode: "streaming"], [kind: "color"]) == [[name: "hueMode", value: "streaming"]]
}

check("flash uses V2 signalling where the light has it (else one breathe); identify; timed effects only where offered") {
    Map lamp = app.lightInfo(byName["Living Lamp"], null), porch = app.lightInfo(byName["Porch"], null)
    assert app.lightCommand("flash", [10000], lamp, [switch: "on"], 400) == [signaling: [signal: "on_off", duration: 10000]]
    assert app.lightCommand("flash", [10000], porch, [switch: "on"], 400) == [alert: [action: "breathe"]]
    assert app.lightCommand("identify", [], porch, [switch: "on"], 400) == [identify: [action: "identify"]]
    assert app.lightCommand("startTimedEffect", ["sunrise", 20], lamp, [switch: "off"], 400) == [timed_effects: [effect: "sunrise", duration: 1200000]]
    assert app.lightCommand("startTimedEffect", ["sunrise", 999], lamp, [switch: "off"], 400).timed_effects.duration == 21600000
    assert app.lightCommand("startTimedEffect", ["sunrise", 20], porch, [switch: "off"], 400) == null
}

check("device details: model, product name and firmware from the device resource") {
    Map info = app.lightInfo(byName["Living Lamp"], deviceNamed["Living Lamp"])
    assert info.subMap(["model", "productName", "firmware"]) == [model: "LCA001", productName: "Hue color lamp", firmware: "1.122.2"]
    assert app.productData(null) == [:]
}

check("motion sensor: detection on/off and sensitivity as events") {
    assert app.sensorEvents([type: "motion", enabled: false, sensitivity: [sensitivity: 3]], [:], "F") ==
        [[name: "motionDetection", value: "disabled"], [name: "sensitivity", value: 3]]
    Map info = app.sensorInfo(deviceNamed["Hall Motion"], servicesById)
    assert info.motion == rid(751) && info.sensitivityMax == 4
}

Check.finish()
