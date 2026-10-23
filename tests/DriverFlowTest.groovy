/**
 * Hue Connect, the Hue Bridge device and the light drivers together against a
 * fake bridge: discovery, linking, devices, the event stream (replay, gaps,
 * partial chunks), commands with pacing and retries, and the bridge check.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import hubitat.helper.NetworkUtils
import static Check.check

File root = new File(getClass().protectionDomain.codeSource.location.path).parentFile.parentFile
@Field Map fixture
fixture = new JsonSlurper().parse(new File(root, "tests/fixtures/resources-sample.json")) as Map
@Field String BID = "001788fffec52bfc"
@Field String KEY = "test-application-key-0123456789"
String rid(int n) { String.format("%08x-0000-4000-8000-%012x", n, n) }

FakeResp resp(int status, def json = null) { new FakeResp(status: status, json: json) }

/** The bridge's /api/0/config answer arriving, as discovery delivers it. */
void found(HubitatScript app, String ip = "172.16.213.108") {
    app.configDone(resp(200, [bridgeid: BID.toUpperCase(), name: "Hue Bridge Pro", modelid: "BSB003"]), [ip: ip])
}

/** A linked Hue Connect with every sample light added, its Hue Bridge device started. */
HubitatScript linked(Hub hub) {
    HubitatScript app = hub.app()
    found(app)
    app.settings.bridgeId = BID
    app.linkDone(resp(200, [[success: [username: KEY]]]), [id: BID])
    app.refreshAllDone(resp(200, fixture), [:])
    app.settings.selectedLights = (app.state.lights as Map).keySet().toList()
    app.updated()
    hub.settle()
    return app
}

FakeDevice bridgeOf(HubitatScript app) { app.getChildDevice("hue-${BID}") }
FakeDevice lightOf(HubitatScript app, int n) { app.getChildDevice("hue-${BID}-${rid(n)}") }
FakeEventStream esOf(FakeDevice b) { b.driver.interfaces.eventStream }
List puts(FakeDevice b) { b.driver.callsNamed("asynchttpPut") }

/** Start the stream and deliver START. */
void started(Hub hub, FakeDevice b) {
    b.connectStream()
    b.eventStreamStatus("START: connected")
    hub.settle()
}

String sse(String id, List containers) { "id: ${id}\ndata: ${JsonOutput.toJson(containers)}\n\n" }

// ---------------------------------------------------------------- discovery and linking

check("discovery: mDNS from the hub's cache, the cloud endpoint once per 15 minutes, and the manual IP, each checked by /api/0/config") {
    Hub hub = new Hub(root)
    HubitatScript app = hub.app()
    NetworkUtils.mdns = ["ECB5FA0A0B0C": [ip4Address: "172.16.213.108"]]
    app.settings.manualIp = " 172.16.213.50 "
    app.discover()
    List gets = app.callsNamed("asynchttpGet")
    assert gets.find { it[2].uri == "https://discovery.meethue.com" }
    assert gets.findAll { it[1] == "configDone" }.collect { it[3].ip } == ["172.16.213.108", "172.16.213.50"]
    assert gets.findAll { it[1] == "configDone" }.every { it[2].path == "/api/0/config" && it[2].uri.startsWith("https://") }
    app.discover()
    assert app.callsNamed("asynchttpGet").count { it[2].uri == "https://discovery.meethue.com" } == 1, "the cloud endpoint is rate-limited"
    app.configDone(resp(200, [bridgeid: "001788FFFEC52BFC", name: "Hue Bridge Pro", modelid: "BSB003"]), [ip: "172.16.213.108"])
    app.configDone(resp(200, [bridgeid: "001788FFFE000001", modelid: "BSB001"]), [ip: "172.16.213.9"])
    assert app.foundBridges().keySet() == [BID] as Set
    NetworkUtils.mdns = [:]
}

check("linking: waits for the button (error 101), then keeps the key in a hidden setting, never in state") {
    Hub hub = new Hub(root)
    HubitatScript app = hub.app()
    found(app)
    app.settings.bridgeId = BID
    app.appButtonHandler("btnLink")
    app.mainPage()
    Map post = app.callsNamed("asynchttpPost").last()[2]
    assert post.path == "/api" && post.uri == "https://172.16.213.108" && post.body.devicetype ==~ /hue_connect#.{1,19}/
    assert !post.body.containsKey("generateclientkey"), "the client key is only for Entertainment; not requested"
    app.linkDone(resp(200, [[error: [type: 101, description: "link button not pressed"]]]), [id: BID])
    assert app.callsNamed("runIn").last()[1..2] == [2, "sendLink"]
    app.linkDone(resp(200, [[success: [username: KEY]]]), [id: BID])
    assert app.settings.bridgeKey == KEY && app.app.settingTypes.bridgeKey == "password"
    assert !JsonOutput.toJson(app.state).contains(KEY)
    assert app.state.bridge == [id: BID, ip: "172.16.213.108", name: "Hue Bridge Pro"]
    Map get = app.callsNamed("asynchttpGet").last()[2]
    assert get.path == "/clip/v2/resource" && get.query == [type: "device,light,zigbee_connectivity"]
    assert get.headers["hue-application-key"] == KEY
}

check("linking times out with a clear message when the button isn't pressed") {
    Hub hub = new Hub(root)
    HubitatScript app = hub.app()
    found(app)
    app.settings.bridgeId = BID
    app.appButtonHandler("btnLink")
    app.mainPage()
    hub.advance(40000)
    app.linkDone(resp(200, [[error: [type: 101]]]), [id: BID])
    assert !app.state.linkUntil && app.state.linkError.contains("wasn't pressed in time")
}

// ---------------------------------------------------------------- devices

check("devices: a Hue Bridge device plus one light device each, with the driver matching its features") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    assert bridgeOf(app)?.driverName == "Hue Bridge"
    assert [101, 102, 103, 104, 105].collect { lightOf(app, it)?.driverName } ==
        ["Hue Color Light", "Hue White Ambiance Light", "Hue White Light", "Hue Plug", "Hue Color Light"]
    assert lightOf(app, 102).label == "Hall Ceiling"
}

check("devices: the initial resource list sets every light's state and health") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    app.refreshAllDone(resp(200, fixture), [:])
    hub.settle()
    assert lightOf(app, 101).current.subMap(["switch", "level", "colorMode", "healthStatus"]) == [switch: "on", level: 80, colorMode: "RGB", healthStatus: "online"]
    assert lightOf(app, 102).current.subMap(["switch", "colorTemperature", "healthStatus"]) == [switch: "off", colorTemperature: 2732, healthStatus: "offline"]
}

check("devices: unticking a light removes its device") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    app.settings.selectedLights = [rid(101)]
    app.updated()
    assert lightOf(app, 101) && !lightOf(app, 102) && bridgeOf(app)
}

// ---------------------------------------------------------------- the event stream

check("stream: connects over HTTPS with the key; the first start (nothing to resume from) reads all states once") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    FakeDevice b = bridgeOf(app)
    assert b.driver.callsNamed("runEvery5Minutes") == [["runEvery5Minutes", "checkBridge"]]
    int reads = app.callsNamed("asynchttpGet").size()
    started(hub, b)
    Map c = esOf(b).connects.last()
    assert c.url == "https://172.16.213.108/eventstream/clip/v2" && c.opts.headers["hue-application-key"] == KEY
    assert !c.opts.headers.containsKey("Last-Event-ID") && c.opts.rawData == true
    assert b.current.eventStream == "connected"
    assert app.callsNamed("asynchttpGet").size() == reads + 1
}

check("stream: events update devices; the last id is kept and sent as Last-Event-ID on reconnect") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    b.parse(sse("1700000000:0", [[type: "update", data: [[id: rid(102), type: "light", on: [on: true], dimming: [brightness: 20]]]]]))
    hub.settle()
    assert lightOf(app, 102).current.subMap(["switch", "level"]) == [switch: "on", level: 20]
    b.connectStream()
    assert esOf(b).connects.last().opts.headers["Last-Event-ID"] == "1700000000:0"
}

check("stream: a message split across chunks is put back together; several in one chunk all apply") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    String one = sse("5:0", [[type: "update", data: [[id: rid(103), type: "light", on: [on: false]]]]])
    String two = sse("6:0", [[type: "update", data: [[id: rid(104), type: "light", on: [on: true]]]]])
    String all = one + two
    b.parse(all.substring(0, 20)); b.parse(all.substring(20, 70)); b.parse(all.substring(70))
    hub.settle()
    assert lightOf(app, 103).current.switch == "off" && lightOf(app, 104).current.switch == "on"
    assert b.driver.getClass().MEM[b.idAsLong].lastEventId == "6:0"
}

check("stream: connectivity updates reach the lights of that device") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    b.parse(sse("7:0", [[type: "update", data: [[id: rid(203), type: "zigbee_connectivity", owner: [rid: rid(3), rtype: "device"], status: "disconnected"]]]]))
    hub.settle()
    assert lightOf(app, 103).current.healthStatus == "offline"
}

check("stream: add and delete events read the device list again (once, shortly after)") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    b.parse(sse("8:0", [[type: "add", data: [[id: rid(999), type: "light"]]]]))
    assert app.callsNamed("runIn").last()[1..2] == [2, "refreshAll"]
}

check("stream: a short drop is replayed by the bridge; after more than 60 s, all states are read once") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    b.parse(sse("9:0", [[type: "update", data: [[id: rid(101), type: "light", on: [on: true]]]]]))
    int reads = app.callsNamed("asynchttpGet").size()
    b.eventStreamStatus("STOP: closed"); b.streamDown(); hub.advance(30000)
    b.connectStream(); b.eventStreamStatus("START: connected")
    assert app.callsNamed("asynchttpGet").size() == reads, "short gap: replayed"
    b.eventStreamStatus("STOP: closed"); b.streamDown(); hub.advance(120000)
    b.connectStream(); b.eventStreamStatus("START: connected")
    assert app.callsNamed("asynchttpGet").size() == reads + 1, "long gap: one full read"
}

check("stream: a STOP while connecting is the old stream's; the 30 s timeout handles a real failure; reconnects back off") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    FakeDevice b = bridgeOf(app)
    b.connectStream()
    b.eventStreamStatus("STOP: old stream")
    assert !b.driver.callsNamed("runIn").any { it[2] == "streamDown" }
    b.streamTimeout()
    b.connectStream(); b.streamTimeout()
    b.connectStream(); b.streamTimeout()
    assert b.driver.callsNamed("runIn").findAll { it[2] == "reconnectStream" }.collect { it[1] } == [5, 10, 20]
}

// ---------------------------------------------------------------- commands

check("commands: a light command becomes one paced PUT with only what changes") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    lightOf(app, 102).setLevel(30)                      // Hall Ceiling is off: on is included
    List p = puts(b).last()
    assert p[2].path == "/clip/v2/resource/light/${rid(102)}" && p[2].uri == "https://172.16.213.108"
    assert p[2].headers["hue-application-key"] == KEY
    assert p[2].body == [dimming: [brightness: 30d], dynamics: [duration: 400], on: [on: true]]
    lightOf(app, 101).off()
    assert puts(b).size() == 1, "the second light waits 100 ms"
    assert b.driver.callsNamed("runInMillis").last()[2] == "pump"
    hub.advance(100); b.pump()
    assert puts(b).last()[2].body == [on: [on: false]]
}

check("commands: a newer command for the same light merges into one still waiting (a slider being dragged)") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    lightOf(app, 103).setLevel(10)         // sent
    lightOf(app, 101).setLevel(20)         // waits for the 100 ms spacing
    lightOf(app, 101).setLevel(25)
    lightOf(app, 101).setLevel(30)
    hub.advance(100); b.pump()
    assert puts(b).size() == 2 && puts(b).last()[2].body.dimming == [brightness: 30d]
}

check("commands: at most 3 in flight; the rest go as answers come back") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    [101, 102, 103, 104].each { lightOf(app, it).on(); hub.advance(100); b.pump() }
    assert puts(b).size() == 3
    b.putDone(resp(200, [errors: [], data: []]), puts(b)[0][3])
    assert puts(b).size() == 4
}

check("commands: 429 and 503 are retried after 0.25 s x attempt; other refusals aren't") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    lightOf(app, 101).on()
    Map data = puts(b).last()[3]
    b.putDone(resp(429, [errors: [[description: "too many requests"]]]), data)
    assert puts(b).size() == 1
    hub.advance(250); b.pump()
    assert puts(b).size() == 2 && puts(b).last()[3].attempts == 1
    b.putDone(resp(503), puts(b).last()[3])
    hub.advance(500); b.pump()
    assert puts(b).size() == 3
    b.putDone(resp(400, [errors: [[description: "invalid body"]]]), puts(b).last()[3])
    hub.advance(5000); b.pump()
    assert puts(b).size() == 3 && b.driver.log.at("warn").any { it.contains("invalid body") }
}

check("commands: with the stream down, a command that went through still updates the device") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    FakeDevice b = bridgeOf(app)
    lightOf(app, 104).on()
    b.putDone(resp(200, [errors: [], data: []]), puts(b).last()[3])
    hub.settle()
    assert lightOf(app, 104).current.switch == "on"
}

check("commands: colour goes as xy inside the light's gamut; hold-to-dim uses dimming_delta") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    lightOf(app, 105).setColor([hue: 33, saturation: 100])
    Map body = puts(b).last()[2].body
    Map g = app.GAMUTS.B
    assert app.clampToGamut(body.color.xy.x as double, body.color.xy.y as double, g).collect { Math.round((it as double) * 10000) / 10000d } ==
        [body.color.xy.x, body.color.xy.y]
    hub.advance(100)
    lightOf(app, 102).startLevelChange("up")
    assert puts(b).last()[2].body == [dimming_delta: [action: "up", brightness_delta: 100], dynamics: [duration: 4000]]
}

// ---------------------------------------------------------------- the bridge check

check("check: one small V2 request; online when it's the linked bridge") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    FakeDevice b = bridgeOf(app)
    b.checkBridge()
    Map get = b.driver.callsNamed("asynchttpGet").last()[2]
    assert get.path == "/clip/v2/resource/bridge" && get.headers["hue-application-key"] == KEY
    b.checkDone(resp(200, [errors: [], data: [[type: "bridge", bridge_id: BID]]]), [:])
    assert b.current.status == "online" && b.current.healthStatus == "online"
}

check("check: a revoked key (401/403) is 'unauthorized', logged once") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    FakeDevice b = bridgeOf(app)
    b.checkDone(resp(401), [:]); hub.settle()
    b.checkDone(resp(403), [:]); hub.settle()
    assert b.current.status == "unauthorized" && b.driver.log.at("error").size() == 1
}

check("check: a different bridge at the address, or none, is offline; an open-looking stream to it is restarted") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    FakeDevice b = bridgeOf(app)
    b.checkDone(resp(200, [errors: [], data: [[type: "bridge", bridge_id: "001788fffe000000"]]]), [:]); hub.settle()
    assert b.current.status == "offline" && b.driver.log.at("error").any { it.contains("different bridge") }
    started(hub, b)
    b.checkDone(resp(408), [:]); hub.settle()
    assert b.current.eventStream == "disconnected" && b.driver.callsNamed("runIn").last()[2] == "reconnectStream"
}

check("check: a stream silent for 30 minutes is reconnected with Last-Event-ID, without reading all states") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    b.parse(sse("11:0", [[type: "update", data: [[id: rid(101), type: "light", on: [on: true]]]]]))
    int connects = esOf(b).connects.size(), reads = app.callsNamed("asynchttpGet").size()
    hub.advance(31 * 60000L)
    b.checkDone(resp(200, [errors: [], data: [[type: "bridge", bridge_id: BID]]]), [:])
    b.eventStreamStatus("START: connected")
    assert esOf(b).connects.size() == connects + 1 && esOf(b).connects.last().opts.headers["Last-Event-ID"] == "11:0"
    assert app.callsNamed("asynchttpGet").size() == reads
}

// ---------------------------------------------------------------- review fixes (2026-10-23)

check("review: a retry never lands after a newer command for the same light and undoes it") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    lightOf(app, 101).setLevel(50)
    Map first = puts(b).last()[3]
    b.putDone(resp(429), first)                 // retry waits 250 ms
    lightOf(app, 101).setLevel(80)              // newer, during the wait
    hub.advance(250); b.pump()
    assert puts(b).size() == 2 && puts(b).last()[2].body.dimming == [brightness: 80d]
    hub.advance(5000); b.pump()
    assert puts(b).size() == 2, "no stale retry afterwards"
}

check("review: after a hub restart (memory empty) the stream reads all states; a Save doesn't") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    b.parse(sse("12:0", [[type: "update", data: [[id: rid(101), type: "light", on: [on: true]]]]]))
    int reads = app.callsNamed("asynchttpGet").size()
    b.initialize(); b.connectStream(); b.eventStreamStatus("START: connected")    // Save
    assert app.callsNamed("asynchttpGet").size() == reads
    b.driver.getClass().MEM.clear()                                              // a reboot
    hub.advance(5000)
    b.initialize(); b.connectStream(); b.eventStreamStatus("START: connected")
    assert app.callsNamed("asynchttpGet").size() == reads + 1
}

check("review: initialize cancels a pending backoff reconnect; data on an open stream doesn't touch the scheduler") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    b.initialize()
    assert b.driver.callsNamed("unschedule").collect { it[1] }.contains("reconnectStream")
    b.connectStream(); b.eventStreamStatus("START: connected")
    int unschedules = b.driver.callsNamed("unschedule").size()
    3.times { b.parse(sse("${20 + it}:0", [[type: "update", data: [[id: rid(101), type: "light", on: [on: true]]]]])) }
    assert b.driver.callsNamed("unschedule").size() == unschedules
}

check("review: removing the Hue Bridge device closes its stream and frees its memory") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    b.uninstalled()
    assert !b.driver.getClass().MEM.containsKey(b.idAsLong) && b.driver.calls.last()[0] == "es.close"
}

check("review: a light added later gets its state straight away") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    app.settings.selectedLights = [rid(101)]
    app.updated()
    int reads = app.callsNamed("asynchttpGet").size()
    app.settings.selectedLights = [rid(101), rid(102)]
    app.updated()
    assert app.callsNamed("asynchttpGet").size() == reads + 1
}

check("review: setHue then setSaturation in one go: the second command keeps the new hue") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    lightOf(app, 101).setHue(66)
    lightOf(app, 101).setSaturation(80)
    hub.advance(100); b.pump()
    Map xy = puts(b).last()[2].body.color.xy
    List hs = app.xyToHueSat(xy.x as double, xy.y as double)
    assert Math.abs((hs[0] as int) - 66) <= 3, "blue (66), not red: ${hs}"
}

check("review: Link again keeps the devices and swaps in the new key") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    app.appButtonHandler("btnRelink"); app.mainPage()
    assert app.state.relinking && app.settings.bridgeId == BID
    app.appButtonHandler("btnLink"); app.mainPage()
    app.linkDone(resp(200, [[success: [username: "new-key"]]]), [id: BID])
    assert app.settings.bridgeKey == "new-key" && !app.state.relinking && lightOf(app, 101)
}

check("review: the bridge at a new address is followed by its ID; unreachable triggers a (rate-limited) search") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    found(app, "172.16.213.200")
    assert app.state.bridge.ip == "172.16.213.200" && app.bridgeConnection().ip == "172.16.213.200"
    app.bridgeUnreachable(); app.bridgeUnreachable()
    assert app.callsNamed("asynchttpGet").count { it[2].uri == "https://discovery.meethue.com" } <= 1
    assert app.state.searchUntil
}

check("review: answers from several bridges at once are all kept") {
    Hub hub = new Hub(root)
    HubitatScript app = hub.app()
    app.configDone(resp(200, [bridgeid: "001788FFFE000011", name: "Upstairs", modelid: "BSB002"]), [ip: "10.0.0.11"])
    app.configDone(resp(200, [bridgeid: "001788FFFE000022", name: "Downstairs", modelid: "BSB002"]), [ip: "10.0.0.22"])
    assert app.foundBridges().keySet() == ["001788fffe000011", "001788fffe000022"] as Set
}

check("review: the page refreshes while searching or reading lights") {
    Hub hub = new Hub(root)
    HubitatScript app = hub.app()
    app.appButtonHandler("btnSearch")
    assert app.mainPage().refreshInterval == 3
    hub.advance(20000)
    assert app.mainPage().refreshInterval == 0
}

Check.finish()
