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
    app.settings.selectedLights = (app.res().lights as Map).keySet().toList()
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
    assert get.path == "/clip/v2/resource" && get.query == [type: app.RESOURCE_TYPES.join(",")] && get.query.type.startsWith("device,light,")
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

check("memory: the bridge's resource lists stay out of state (read on every execution); state keeps only the small things") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    hub.settle()
    assert app.res().lights && app.res().connectivity != null
    assert !(["lights", "connectivity", "groups", "scenes", "sensors"].any { app.state.containsKey(it) })
    assert JsonOutput.toJson(app.state).length() < 1024
}

check("memory: after a code save, the first event reads the bridge once; until then events still go through") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    app.getClass().RES.clear()                                                   // a code save or restart
    int runIns = app.callsNamed("runIn").count { it[2] == "refreshAll" }
    3.times { b.parse(sse("${30 + it}:0", [[type: "update", data: [[id: rid(101), type: "light", on: [on: it % 2 == 0]]]]])) }
    assert app.callsNamed("runIn").count { it[2] == "refreshAll" } == runIns + 1, "asked once, not per event"
    assert lightOf(app, 101).current.switch == "on"
    hub.advance(61000)
    b.parse(sse("40:0", [[type: "update", data: [[id: rid(101), type: "light", on: [on: true]]]]]))
    assert app.callsNamed("runIn").count { it[2] == "refreshAll" } == runIns + 2, "a read that never came back is asked again after a minute"
    app.refreshAllDone(resp(200, fixture), [:])
    assert app.res().lights
}

check("memory: Done before the bridge has been read again doesn't delete devices; they're synced once it has") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    app.getClass().RES.clear()
    app.settings.selectedLights = [rid(101)]
    app.updated()
    hub.settle()
    assert lightOf(app, 101) && lightOf(app, 102), "nothing deleted while the light list is unknown"
    app.refreshAllDone(resp(200, fixture), [:])
    hub.settle()
    assert lightOf(app, 101) && !lightOf(app, 102) && !app.state.syncPending
}

check("memory: builds that kept the lists in state have them removed on the next read") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    app.state.lights = [x: [name: "old"]]; app.state.scenes = [:]
    app.refreshAllDone(resp(200, fixture), [:])
    assert !app.state.containsKey("lights") && !app.state.containsKey("scenes")
}

check("refresh: Refresh on many devices is one scheduled full read, not one read each, started straight away") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    app.settings.selectedGroups = (app.res().groups as Map).keySet().toList()
    app.settings.selectedSensors = (app.res().sensors as Map).keySet().toList()
    app.updated()
    hub.settle()
    int gets = app.callsNamed("asynchttpGet").size()
    List kids = app.getChildDevices().toList()
    assert kids.size() > 3
    kids.each { it.refresh() }
    assert app.callsNamed("asynchttpGet").size() == gets, "no read started directly"
    assert app.callsNamed("runIn").last()[1..2] == [2, "refreshAll"]   // runIn replaces a pending run: one read
}

check("refresh: adds and deletes of resources Hue Connect doesn't read don't start a full read") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    int runIns = app.callsNamed("runIn").count { it[2] == "refreshAll" }
    b.parse(sse("50:0", [[type: "add", data: [[id: rid(990), type: "behavior_instance"]]],
                         [type: "delete", data: [[id: rid(991), type: "entertainment_configuration"]]]]))
    assert app.callsNamed("runIn").count { it[2] == "refreshAll" } == runIns
    b.parse(sse("51:0", [[type: "delete", data: [[id: rid(992), type: "scene"]]]]))
    assert app.callsNamed("runIn").count { it[2] == "refreshAll" } == runIns + 1
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

check("stream: one line per call, newlines stripped (how Hubitat delivers rawData), applies each event") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    String one = sse("8:0", [[type: "update", data: [[id: rid(103), type: "light", on: [on: true], dimming: [brightness: 33]]]]])
    String two = sse("9:0", [[type: "update", data: [[id: rid(104), type: "light", on: [on: false]]]]])
    (one + two).split("\n", -1).each { b.parse(it) }
    hub.settle()
    assert lightOf(app, 103).current.subMap(["switch", "level"]) == [switch: "on", level: 33]
    assert lightOf(app, 104).current.switch == "off"
    assert b.driver.getClass().MEM[b.idAsLong].lastEventId == "9:0"
    String data = "data: " + JsonOutput.toJson([[type: "update", data: [[id: rid(103), type: "light", on: [on: false]]]]])
    b.parse(data.substring(0, 30)); b.parse(data.substring(30))   // a long line handed over in two pieces
    hub.settle()
    assert lightOf(app, 103).current.switch == "off"
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

// ---------------------------------------------------------------- Hue design guidance (2026-10-23 analysis)

check("guidance: a light that turned out to be off ('may have no effect') gets the command once more with on") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    app.refreshAllDone(resp(200, fixture), [:]); hub.settle()   // the catch-up read: Living Lamp is on
    lightOf(app, 101).setLevel(40)                       // looks on: no "on" in the body
    Map data = puts(b).last()[3]
    assert !data.body.containsKey("on")
    b.putDone(resp(200, [errors: [[error_code: "attribute_may_have_no_effect", description: "device is off"]], data: []]), data)
    hub.advance(100); b.pump()
    assert puts(b).last()[2].body.on == [on: true] && puts(b).last()[2].body.dimming == [brightness: 40d]
    b.putDone(resp(200, [errors: [[error_code: "attribute_may_have_no_effect", description: "still"]], data: []]), puts(b).last()[3])
    hub.advance(5000); b.pump()
    assert puts(b).size() == 2, "only once"
}

check("guidance: rediscovery after the bridge stops answering backs off (5, 10, 20 ... 60 min) and resets when it's back") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    List times = []
    6.times { app.bridgeUnreachable(); times << (app.state.rediscoverWaitMs as long); hub.advance(app.state.rediscoverWaitMs as long) }
    assert times.collect { it.intdiv(60000) } == [10, 20, 40, 60, 60, 60]
    app.bridgeReachable()
    assert !app.state.rediscoverWaitMs
}

check("guidance: a single bridge found is selected automatically; nothing found after a search gives the router/LED advice") {
    Hub hub = new Hub(root)
    HubitatScript app = hub.app()
    app.appButtonHandler("btnSearch"); app.mainPage()
    hub.advance(20000)
    app.mainPage()
    found(app)
    app.mainPage()
    assert app.settings.bridgeId == BID
}

check("guidance: an error event from the bridge is logged, not passed on as an update") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    b.parse(sse("30:0", [[type: "error", data: [[description: "something went wrong"]]]]))
    assert b.driver.log.at("warn").any { it.contains("error event") }
}

// ---------------------------------------------------------------- phase 2: groups and scenes

/** Linked with groups and scenes chosen too. */
HubitatScript linkedAll(Hub hub) {
    HubitatScript app = linked(hub)
    app.settings.selectedGroups = (app.res().groups as Map).keySet().toList()
    app.settings.selectedScenes = (app.res().scenes as Map).keySet().toList()
    app.updated()
    app.refreshAllDone(resp(200, fixture), [:])
    hub.settle()
    return app
}

FakeDevice deviceOf(HubitatScript app, int n) { app.getChildDevice("hue-${BID}-${rid(n)}") }

/** The bridge answers the last request (frees its in-flight slot). */
void ack(FakeDevice b) { b.putDone(resp(200, [errors: [], data: []]), puts(b).last()[3]) }

check("groups: one Hue Group per room/zone (and All Hue lights), named from the room; scenes listed with their room") {
    Hub hub = new Hub(root)
    HubitatScript app = linkedAll(hub)
    assert (app.res().groups as Map).collectEntries { k, g -> [(g.name): g.ownerType] } ==
        ["Living Room": "room", "Hall": "room", "Downstairs": "zone", "All Hue lights": "bridge_home"]
    assert deviceOf(app, 401).driverName == "Hue Group" && deviceOf(app, 401).label == "Living Room"
    assert deviceOf(app, 501).driverName == "Hue Scene" && deviceOf(app, 501).label == "Relax (Living Room)"
    assert deviceOf(app, 601).label == "Natural light (Hall)"
}

check("groups: state from grouped_light (on = any light on, level = average of those on); scenes as effects") {
    Hub hub = new Hub(root)
    HubitatScript app = linkedAll(hub)
    FakeDevice living = deviceOf(app, 401)
    assert living.current.subMap(["switch", "level"]) == [switch: "on", level: 65]
    assert deviceOf(app, 402).current.switch == "off" && deviceOf(app, 402).current.level == null, "an all-off room keeps its last level"
    assert new JsonSlurper().parseText(living.current.lightEffects as String) == ["1": "Concentrate", "2": "Fireplace", "3": "Relax"]
}

check("scenes: switch follows status.active; the room's effectName follows its active scene") {
    Hub hub = new Hub(root)
    HubitatScript app = linkedAll(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    assert deviceOf(app, 501).current.switch == "on" && deviceOf(app, 502).current.switch == "off"
    b.parse(sse("40:0", [[type: "update", data: [[id: rid(502), type: "scene", status: [active: "static"]], [id: rid(501), type: "scene", status: [active: "inactive"]]]]]))
    hub.settle()
    assert deviceOf(app, 502).current.switch == "on" && deviceOf(app, 501).current.switch == "off"
    assert deviceOf(app, 401).current.effectName == "Concentrate"
    b.parse(sse("41:0", [[type: "update", data: [[id: rid(601), type: "smart_scene", state: "active"]]]]))
    hub.settle()
    assert deviceOf(app, 601).current.switch == "on"
}

check("group commands: PUT on its grouped_light, with on so lights that are off join in") {
    Hub hub = new Hub(root)
    HubitatScript app = linkedAll(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    deviceOf(app, 401).setLevel(30)
    List p = puts(b).last()
    assert p[2].path == "/clip/v2/resource/grouped_light/${rid(401)}"
    assert p[2].body == [dimming: [brightness: 30d], dynamics: [duration: 400], on: [on: true]]
    deviceOf(app, 402).startLevelChange("up")
    hub.advance(1000); b.pump()
    assert puts(b).last()[2].body.dimming_delta == [action: "up", brightness_delta: 100]
}

check("scenes: push/on recalls (dynamic if asked and the scene has a palette); off turns its room off; smart scenes activate/deactivate") {
    Hub hub = new Hub(root)
    HubitatScript app = linkedAll(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    deviceOf(app, 501).push()
    assert puts(b).last()[2].path == "/clip/v2/resource/scene/${rid(501)}" && puts(b).last()[2].body == [recall: [action: "active"]]
    deviceOf(app, 504).driver.settings.dynamic = true
    ack(b)
    hub.advance(1000); deviceOf(app, 504).on(); b.pump()
    assert puts(b).last()[2].body == [recall: [action: "dynamic_palette"]]
    ack(b)
    hub.advance(1000); deviceOf(app, 501).off(); b.pump()
    assert puts(b).last()[2].path == "/clip/v2/resource/grouped_light/${rid(401)}" && puts(b).last()[2].body == [on: [on: false]]
    ack(b)
    hub.advance(1000); deviceOf(app, 601).on(); b.pump()
    assert puts(b).last()[2].path == "/clip/v2/resource/smart_scene/${rid(601)}" && puts(b).last()[2].body == [recall: [action: "activate"]]
    ack(b)
    hub.advance(1000); deviceOf(app, 601).off(); b.pump()
    assert puts(b).last()[2].body == [recall: [action: "deactivate"]]
}

check("effects: setEffect(n) recalls the room's nth scene; next/previous wrap around") {
    Hub hub = new Hub(root)
    HubitatScript app = linkedAll(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    FakeDevice living = deviceOf(app, 401)
    living.setEffect(2)
    assert puts(b).last()[2].path == "/clip/v2/resource/scene/${rid(504)}"                 // Fireplace
    living.current.effectName = "Relax"
    hub.advance(1000); living.setNextEffect(); b.pump()
    assert puts(b).last()[2].path == "/clip/v2/resource/scene/${rid(502)}"                 // wraps to Concentrate
    hub.advance(1000); living.setPreviousEffect(); b.pump()
    assert puts(b).last()[2].path == "/clip/v2/resource/scene/${rid(504)}"                 // Relax -> Fireplace
}

check("pacing: group commands and scene recalls share one 1-a-second budget; lights keep their own") {
    Hub hub = new Hub(root)
    HubitatScript app = linkedAll(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    deviceOf(app, 401).on()
    deviceOf(app, 502).push()
    lightOf(app, 103).on()
    assert puts(b).collect { it[2].path.split("/")[-2] } == ["grouped_light", "light"], "the scene waits; the light doesn't"
    hub.advance(900); b.pump()
    assert puts(b).size() == 2
    hub.advance(100); b.pump()
    assert puts(b).last()[2].path.contains("/scene/")
}

// ---------------------------------------------------------------- phase 3: remotes and sensors

HubitatScript linkedSensors(Hub hub) {
    HubitatScript app = linked(hub)
    app.settings.selectedSensors = (app.res().sensors as Map).keySet().toList()
    app.updated()
    app.refreshAllDone(resp(200, fixture), [:])
    hub.settle()
    return app
}

check("remotes and sensors: a device each, with the matching driver; initial battery, motion, lux, contact and health") {
    Hub hub = new Hub(root)
    HubitatScript app = linkedSensors(hub)
    assert [6, 7, 8, 9, 10, 11, 12].collect { deviceOf(app, it)?.driverName } ==
        ["Hue Button", "Hue Button", "Hue Button", "Hue Button", "Hue Button", "Hue Motion Sensor", "Hue Contact Sensor"]
    assert deviceOf(app, 7).current.numberOfButtons == 6 && deviceOf(app, 6).current.numberOfButtons == 4
    assert deviceOf(app, 6).current.battery == 92 && deviceOf(app, 8).current.batteryState == "low"
    assert deviceOf(app, 11).current.subMap(["motion", "illuminance", "temperature", "healthStatus"]) ==
        [motion: "inactive", illuminance: 100, temperature: 70.7d, healthStatus: "online"]
    assert deviceOf(app, 12).current.subMap(["contact", "tamper"]) == [contact: "closed", tamper: "clear"]
    assert deviceOf(app, 10).current.healthStatus == "online", "battery-free switches report through zgp_connectivity"
}

check("remotes: live button events reach the right device and button; repeats only if the device asks for them") {
    Hub hub = new Hub(root)
    HubitatScript app = linkedSensors(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    b.parse(sse("50:0", [[type: "update", data: [[id: rid(703), type: "button", owner: [rid: rid(6), rtype: "device"], button: [button_report: [event: "short_release"]]]]]]))
    b.parse(sse("51:0", [[type: "update", data: [[id: rid(703), type: "button", owner: [rid: rid(6), rtype: "device"], button: [button_report: [event: "repeat"]]]]]]))
    hub.settle()
    FakeDevice dimmer = deviceOf(app, 6)
    assert dimmer.events.findAll { it.name in ["pushed", "held"] }.collect { [it.name, it.value] } == [["pushed", 3]]
    dimmer.driver.settings.repeatHeld = true
    b.parse(sse("52:0", [[type: "update", data: [[id: rid(703), type: "button", owner: [rid: rid(6), rtype: "device"], button: [button_report: [event: "repeat"]]]]]]))
    hub.settle()
    assert dimmer.events.last().subMap(["name", "value"]) == [name: "held", value: 3] && !dimmer.events.last().containsKey("repeat")
}

check("remotes: the Tap Dial ring, and a button update without its owner still finds its device") {
    Hub hub = new Hub(root)
    HubitatScript app = linkedSensors(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    b.parse(sse("53:0", [[type: "update", data: [[id: rid(715), type: "relative_rotary", owner: [rid: rid(7), rtype: "device"],
        relative_rotary: [rotary_report: [action: "start", rotation: [direction: "clock_wise", steps: 250, duration: 300]]]]]]]))
    b.parse(sse("54:0", [[type: "update", data: [[id: rid(712), type: "button", button: [button_report: [event: "long_press"]]]]]]))
    hub.settle()
    FakeDevice dial = deviceOf(app, 7)
    assert dial.events.findAll { it.name in ["pushed", "held", "rotation"] }.collect { [it.name, it.value] } == [["pushed", 5], ["rotation", 90], ["held", 2]]
}

check("sensors: live motion, lux, temperature, contact, tamper and battery") {
    Hub hub = new Hub(root)
    HubitatScript app = linkedSensors(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    b.parse(sse("55:0", [[type: "update", data: [
        [id: rid(751), type: "motion", owner: [rid: rid(11), rtype: "device"], motion: [motion_report: [motion: true]]],
        [id: rid(752), type: "light_level", owner: [rid: rid(11), rtype: "device"], light: [light_level_report: [light_level: 30001]]],
        [id: rid(761), type: "contact", owner: [rid: rid(12), rtype: "device"], contact_report: [state: "no_contact"]],
        [id: rid(762), type: "tamper", owner: [rid: rid(12), rtype: "device"], tamper_reports: [[state: "tampered"]]],
        [id: rid(805), type: "device_power", owner: [rid: rid(11), rtype: "device"], power_state: [battery_level: 70]]]]]))
    b.parse(sse("56:0", [[type: "update", data: [[id: rid(753), type: "temperature", temperature: [temperature_report: [temperature: 20.0]]]]]]))
    hub.settle()
    assert deviceOf(app, 11).current.subMap(["motion", "illuminance", "battery"]) == [motion: "active", illuminance: 1000, battery: 70]
    assert deviceOf(app, 11).current.temperature == 68.0d, "an update without its owner still finds its device"
    assert deviceOf(app, 12).current.subMap(["contact", "tamper"]) == [contact: "open", tamper: "detected"]
}

check("remotes: pressing a button from Hubitat records a digital event") {
    Hub hub = new Hub(root)
    HubitatScript app = linkedSensors(hub)
    deviceOf(app, 6).push(2)
    assert deviceOf(app, 6).events.last().subMap(["name", "value", "type"]) == [name: "pushed", value: 2, type: "digital"]
}

// ---------------------------------------------------------------- phase 4

check("light effects, flash and identify from the device go to the bridge; the effect list and details are on the device") {
    Hub hub = new Hub(root)
    HubitatScript app = linked(hub)
    app.refreshAllDone(resp(200, fixture), [:]); hub.settle()
    FakeDevice lamp = lightOf(app, 101), b = bridgeOf(app)
    assert new JsonSlurper().parseText(lamp.current.lightEffects as String) == ["1": "candle", "2": "fire", "3": "sparkle", "4": "prism"]
    assert lamp.data.subMap(["model", "productName", "firmware"]) == [model: "LCA001", productName: "Hue color lamp", firmware: "1.122.2"]
    started(hub, b)
    lamp.setEffect(1)
    assert puts(b).last()[2].body == [effects_v2: [action: [effect: "candle"]]]
    ack(b)
    hub.advance(100); lamp.flash(); b.pump()
    assert puts(b).last()[2].body == [signaling: [signal: "on_off", duration: 10000]]
    ack(b)
    hub.advance(100); lamp.identify(); b.pump()
    assert puts(b).last()[2].body == [identify: [action: "identify"]]
    ack(b)
    hub.advance(100); lamp.startTimedEffect("sunset", 15); b.pump()
    assert puts(b).last()[2].body == [timed_effects: [effect: "sunset", duration: 900000]]
}

check("motion sensor: turning detection off and setting sensitivity PUT on its motion service") {
    Hub hub = new Hub(root)
    HubitatScript app = linkedSensors(hub)
    FakeDevice b = bridgeOf(app), m = deviceOf(app, 11)
    started(hub, b)
    assert m.current.subMap(["motionDetection", "sensitivity"]) == [motionDetection: "enabled", sensitivity: 2]
    m.disableMotionDetection()
    assert puts(b).last()[2].path == "/clip/v2/resource/motion/${rid(751)}" && puts(b).last()[2].body == [enabled: false]
    hub.advance(100); m.setSensitivity(9); b.pump()
    assert puts(b).last()[2].body == [sensitivity: [sensitivity: 4]]
}

// ---------------------------------------------------------------- phase 5: power-up and scene speed

check("power-up: the light shows what it does after an outage; setPowerUp is one PUT with only the preset") {
    Map light = fixture.data.find { it.id == rid(102) }
    light.powerup = [preset: "safety", configured: true, on: [mode: "on", on: [on: true]]]
    try {
        Hub hub = new Hub(root)
        HubitatScript app = linked(hub)
        app.refreshAllDone(resp(200, fixture), [:])
        hub.settle()
        FakeDevice b = bridgeOf(app)
        started(hub, b)
        assert lightOf(app, 102).current.powerUp == "safety"
        assert lightOf(app, 101).current.powerUp == null, "a light without the setting shows nothing"
        lightOf(app, 102).setPowerUp("off")
        assert puts(b).last()[2].path == "/clip/v2/resource/light/${rid(102)}"
        assert puts(b).last()[2].body == [powerup: [preset: "custom", on: [mode: "on", on: [on: false]]]], "no transition time, no on"
        b.parse(sse("60:0", [[type: "update", data: [[id: rid(102), type: "light", powerup: [preset: "custom", on: [mode: "on", on: [on: false]]]]]]]))
        hub.settle()
        assert lightOf(app, 102).current.powerUp == "off"
        int sent = puts(b).size()
        lightOf(app, 101).setPowerUp("off")
        hub.advance(1000); b.pump()
        assert puts(b).size() == sent, "a light that doesn't offer power-up sends nothing"
    } finally {
        light.remove("powerup")
    }
}

check("scene speed: a dynamic scene shows and sets its palette speed; others refuse") {
    Hub hub = new Hub(root)
    HubitatScript app = linkedAll(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    assert deviceOf(app, 504).current.speed == 40 && deviceOf(app, 501).current.speed == null, "only the scene with a palette has a speed"
    b.parse(sse("61:0", [[type: "update", data: [[id: rid(504), type: "scene", speed: 0.8d]]]]))
    hub.settle()
    assert deviceOf(app, 504).current.speed == 80
    assert deviceOf(app, 401).current.effectName != "Fireplace", "a speed change alone doesn't move the room's effect"
    deviceOf(app, 504).setSpeed(25)
    assert puts(b).last()[2].path == "/clip/v2/resource/scene/${rid(504)}" && puts(b).last()[2].body == [speed: 0.25d]
    ack(b)
    int sent = puts(b).size()
    deviceOf(app, 501).setSpeed(25)
    deviceOf(app, 601).setSpeed(25)
    hub.advance(1000); b.pump()
    assert puts(b).size() == sent, "a static scene and a smart scene have no speed to set"
}

// ---------------------------------------------------------------- renames and drivers that aren't installed

void rename(HubitatScript app, FakeDevice b, int n, Map item) {
    b.parse(sse("7${n}:0", [[type: "update", data: [item]]]))
    app.hub.settle()
}

check("renames: a name changed in the Hue app relabels the device, a room's group and scenes, and the group's effect list") {
    Hub hub = new Hub(root)
    HubitatScript app = linkedAll(hub)
    app.settings.selectedSensors = [rid(11)]
    app.updated(); app.refreshAllDone(resp(200, fixture), [:]); hub.settle()
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    assert lightOf(app, 101).label == "Living Lamp" && deviceOf(app, 11).label == "Hall Motion"
    b.parse(sse("70:0", [[type: "update", data: [[id: rid(1), type: "device", metadata: [name: "Reading Lamp"]]]]])); hub.settle()
    assert lightOf(app, 101).label == "Reading Lamp" && app.res().lights[rid(101)].name == "Reading Lamp"
    b.parse(sse("71:0", [[type: "update", data: [[id: rid(102), type: "light", metadata: [name: "Stale", archetype: "ceiling_round"]]]]])); hub.settle()
    assert lightOf(app, 102).label == "Hall Ceiling", "a light's own name is deprecated; only its device's name counts"
    b.parse(sse("75:0", [[type: "update", data: [[id: rid(2), type: "device", metadata: [name: "Hall Light"]]]]])); hub.settle()
    assert lightOf(app, 102).label == "Hall Light"
    b.parse(sse("72:0", [[type: "update", data: [[id: rid(301), type: "room", metadata: [name: "Lounge"]]]]])); hub.settle()
    assert deviceOf(app, 401).label == "Lounge" && deviceOf(app, 501).label == "Relax (Lounge)" && deviceOf(app, 504).label == "Fireplace (Lounge)"
    assert deviceOf(app, 503).label == "Nightlight (Hall)", "another room's scenes keep their label"
    b.parse(sse("73:0", [[type: "update", data: [[id: rid(501), type: "scene", metadata: [name: "Unwind"]]]]])); hub.settle()
    assert deviceOf(app, 501).label == "Unwind (Lounge)"
    assert deviceOf(app, 401).current.lightEffects.contains("Unwind") && !deviceOf(app, 401).current.lightEffects.contains("Relax")
    b.parse(sse("74:0", [[type: "update", data: [[id: rid(11), type: "device", metadata: [name: "Hallway Motion"]]]]])); hub.settle()
    assert deviceOf(app, 11).label == "Hallway Motion"
}

check("renames: a label set in Hubitat stays until Hue renames that device; the option turns it off") {
    Hub hub = new Hub(root)
    HubitatScript app = linkedAll(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    lightOf(app, 103).label = "Front Porch"
    b.parse(sse("80:0", [[type: "update", data: [[id: rid(3), type: "device", metadata: [name: "Porch"]], [id: rid(103), type: "light", on: [on: true]]]]])); hub.settle()
    assert lightOf(app, 103).label == "Front Porch", "Hue's name didn't change"
    app.settings.syncNames = false
    b.parse(sse("81:0", [[type: "update", data: [[id: rid(3), type: "device", metadata: [name: "Porch Light"]]]]])); hub.settle()
    assert lightOf(app, 103).label == "Front Porch" && app.res().lights[rid(103)].name == "Porch"
    app.settings.syncNames = true
    b.parse(sse("82:0", [[type: "update", data: [[id: rid(3), type: "device", metadata: [name: "Porch Light"]]]]])); hub.settle()
    assert lightOf(app, 103).label == "Porch Light"
}

check("missing drivers: devices whose driver isn't installed are skipped with a notice, the rest are added, and Done again adds them") {
    Hub hub = new Hub(root)
    hub.missingDrivers << "Hue Plug" << "Hue Scene"
    HubitatScript app = linkedAll(hub)
    assert lightOf(app, 101) && lightOf(app, 104) == null, "the plug driver is missing, the other lights are added"
    assert deviceOf(app, 401) && deviceOf(app, 501) == null && deviceOf(app, 601) == null
    assert app.state.missingDrivers == ["Hue Plug", "Hue Scene"]
    assert app.log.at("warn").any { it.contains("Hue Plug, Hue Scene") && it.contains("Package Manager") }
    hub.missingDrivers.clear()
    app.updated(); hub.settle()
    assert lightOf(app, 104) && deviceOf(app, 501) && deviceOf(app, 601)
    assert !app.state.containsKey("missingDrivers")
}

// ---------------------------------------------------------------- colour temperature ramp

check("colour temperature ramp: one PUT for a light and for a group; a newer ramp or set replaces a waiting one") {
    Hub hub = new Hub(root)
    HubitatScript app = linkedAll(hub)
    FakeDevice b = bridgeOf(app)
    started(hub, b)
    Map info = app.res().lights[rid(102)] as Map
    int range = (info.mirekMax as int) - (info.mirekMin as int)
    lightOf(app, 102).startColorTemperatureChange("warmer")
    assert puts(b).last()[2].path == "/clip/v2/resource/light/${rid(102)}"
    assert puts(b).last()[2].body == [color_temperature_delta: [action: "up", mirek_delta: range], dynamics: [duration: 4000]], "no on: a ramp doesn't turn a light on"
    lightOf(app, 102).setColorTemperature(3000)                // waits for the pacing
    lightOf(app, 102).startColorTemperatureChange("cooler")    // replaces the waiting set
    lightOf(app, 102).stopColorTemperatureChange()             // replaces the waiting ramp
    ack(b)
    hub.advance(1000); b.pump()
    Map body = puts(b).last()[2].body
    assert body.color_temperature_delta == [action: "stop"] && !body.containsKey("color_temperature"), "${body}"
    ack(b)
    hub.advance(1000); lightOf(app, 102).setColorTemperature(3000); lightOf(app, 102).setColorTemperature(2700); b.pump()
    ack(b)
    hub.advance(1000); b.pump()
    int before = puts(b).size()
    deviceOf(app, 401).startColorTemperatureChange("cooler")
    assert puts(b).size() == before + 1 && puts(b).last()[2].path == "/clip/v2/resource/grouped_light/${rid(401)}"
    assert puts(b).last()[2].body == [color_temperature_delta: [action: "down", mirek_delta: 347], dynamics: [duration: 4000]]
}

// ---------------------------------------------------------------- bulk power-up and relabel

check("bulk power-up: the button shows for a behaviour and matching lights; it sends the preset to the picked types, paced") {
    List real = fixture.data.findAll { it.type == "light" }
    real.each { it.powerup = [preset: "powerfail", configured: true, on: [mode: "previous"]] }
    try {
        Hub hub = new Hub(root)
        HubitatScript app = linked(hub)
        FakeDevice b = bridgeOf(app)
        started(hub, b)
        Map lights = app.res().lights as Map
        List bulbs = lights.findAll { k, l -> l.kind in ["color", "ct", "dim"] }.keySet().toList()
        List plugs = lights.findAll { k, l -> l.kind == "onoff" }.keySet().toList()
        assert bulbs && plugs
        app.mainPage()
        assert !app.declaredInputs.containsKey("btnPowerUp"), "no behaviour picked: no button"
        app.settings.powerUpBehavior = "off"
        app.mainPage()
        assert app.declaredInputs.containsKey("btnPowerUp")
        int before = puts(b).size()
        app.appButtonHandler("btnPowerUp"); app.mainPage()
        List sent = []
        for (int i = 0; i < 30 && sent.size() < bulbs.size(); i++) {
            hub.advance(200); b.pump()
            puts(b).drop(before + sent.size()).each { sent << it[2]; ack(b) }
        }
        assert sent*.path.sort() == bulbs.collect { "/clip/v2/resource/light/${it}".toString() }.sort(), "bulbs only by default, not the plug"
        assert sent.every { it.body == [powerup: [preset: "custom", on: [mode: "on", on: [on: false]]]] }
        assert app.state.powerUpResult.contains("Stay off") && app.state.powerUpResult.contains("${bulbs.size()} lights")
        app.settings.powerUpKinds = ["onoff"]
        app.settings.selectedLights = []        // none added, and only added lights are targeted...
        app.appButtonHandler("btnPowerUp"); app.mainPage()
        assert app.state.powerUpResult.startsWith("Nothing sent")
        int quiet = puts(b).size()
        hub.advance(1000); b.pump()
        assert puts(b).size() == quiet
        app.settings.powerUpScope = "all"       // ...until all lights on the bridge are
        app.appButtonHandler("btnPowerUp"); app.mainPage()
        hub.advance(1000); b.pump()
        assert puts(b).last()[2].path == "/clip/v2/resource/light/${plugs[0]}"
        assert app.state.powerUpResult.contains("${plugs.size()}")
    } finally {
        real.each { it.remove("powerup") }
    }
}

check("relabel: the button puts every device's label back to its Hue name") {
    Hub hub = new Hub(root)
    HubitatScript app = linkedAll(hub)
    lightOf(app, 101).label = "Custom"
    deviceOf(app, 401).label = "Lounge"
    deviceOf(app, 501).label = "Whatever"
    app.appButtonHandler("btnRelabel"); app.mainPage()
    assert lightOf(app, 101).label == "Living Lamp" && deviceOf(app, 401).label == "Living Room" && deviceOf(app, 501).label == "Relax (Living Room)"
    assert app.state.relabelResult == "Relabelled 3 devices."
    assert bridgeOf(app).label.startsWith("Hue Bridge"), "the bridge device is not renamed"
    app.appButtonHandler("btnRelabel"); app.mainPage()
    assert app.state.relabelResult == "Every device already matches the Hue app."
}

Check.finish()
