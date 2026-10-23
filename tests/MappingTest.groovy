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
    assert ev == [switch: "off", level: 46, colorTemperature: 2732]   // no colorMode: only colour lights have one
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
    assert app.lightCommand("on", [], info, [switch: "off"], 400) == [on: [on: true]]
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

Check.finish()
