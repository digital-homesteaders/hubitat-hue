/**
 * Hue Connect
 *
 * Philips Hue for Hubitat, written from Hue's CLIP v2 documentation: local only,
 * V2 and HTTPS only, the event stream as the source of truth. This app finds and
 * links the bridge, lets you pick devices, holds all Hue <-> Hubitat mapping as
 * pure functions, and routes events and commands. The Hue Bridge device keeps the
 * event stream and paces commands. Design: docs/SPEC.md.
 *
 * Copyright (c) 2026 Digital Homesteaders. MIT licence:
 * https://github.com/digital-homesteaders/hubitat-hue/blob/main/LICENSE
 */

import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

@Field static final String VERSION = "0.1.0"

// Resource types fetched in one typed request (GET /clip/v2/resource?type=...)
@Field static final List RESOURCE_TYPES = ["device", "light", "zigbee_connectivity", "room", "zone", "bridge_home", "grouped_light",
                                          "scene", "smart_scene", "button", "relative_rotary", "bell_button", "motion", "light_level",
                                          "temperature", "contact", "tamper", "device_power", "zgp_connectivity"]
// Link: Hue's devicetype is "<app>#<device>", at most 20 and 19 characters
@Field static final String APP_NAME = "hue_connect"
@Field static final int LINK_WINDOW_MS = 35000
// discovery.meethue.com allows one request per 15 minutes per client
@Field static final long CLOUD_DISCOVERY_MIN_MS = 15 * 60 * 1000L
@Field static final String DRIVER_BRIDGE = "Hue Bridge"
@Field static final Map LIGHT_DRIVERS = [color: "Hue Color Light", ct: "Hue White Ambiance Light", dim: "Hue White Light", onoff: "Hue Plug"]
@Field static final String DRIVER_GROUP = "Hue Group"
@Field static final Map SENSOR_KINDS_TEXT = [button: "remote", motion: "motion sensor", contact: "contact sensor"]
@Field static final String DRIVER_SCENE = "Hue Scene"
@Field static final Map SENSOR_DRIVERS = [button: "Hue Button", motion: "Hue Motion Sensor", contact: "Hue Contact Sensor"]
// Services that belong to a remote or sensor device (events for them go to that device)
@Field static final List SENSOR_SERVICES = ["button", "relative_rotary", "bell_button", "motion", "light_level", "temperature", "contact",
                                            "tamper", "device_power", "zigbee_connectivity", "zgp_connectivity"]
// A full 0-100% hold-to-dim ramp takes this long, so a release partway stops it partway
@Field static final int LEVEL_CHANGE_MS = 4000
// Hue and saturation just asked for, per light: until the bridge confirms, setHue then setSaturation must use the new
// hue rather than the device's stale one. Kept briefly, in memory.
@Field static final ConcurrentHashMap REQUESTED = new ConcurrentHashMap()
@Field static final long REQUESTED_MS = 5000
// Bridges found by discovery, per app: several answers arrive at once, so this is not kept in state
@Field static final ConcurrentHashMap FOUND = new ConcurrentHashMap()
// What the last full read found (lights, connectivity, groups, scenes, sensors), per app. Not in state: an app's state is
// read on every execution, and this is tens of KB that every event and command would parse again. A restart or code
// save empties it; the first use reads the bridge again (RES_READ: when that was asked, so it's asked once a minute at most).
@Field static final ConcurrentHashMap RES = new ConcurrentHashMap()
@Field static final ConcurrentHashMap RES_READ = new ConcurrentHashMap()
@Field static final long RES_RETRY_MS = 60000L

// Colour gamuts (Hue docs, "Color Conversion Formulas"; C's red per the API reference)
@Field static final Map GAMUTS = [
    A: [red: [0.704d, 0.296d], green: [0.2151d, 0.7106d], blue: [0.138d, 0.08d]],
    B: [red: [0.675d, 0.322d], green: [0.409d, 0.518d], blue: [0.167d, 0.04d]],
    C: [red: [0.6915d, 0.3083d], green: [0.17d, 0.7d], blue: [0.1532d, 0.0475d]],
    other: [red: [1.0d, 0.0d], green: [0.0d, 1.0d], blue: [0.0d, 0.0d]],
]

definition(
    name: "Hue Connect",
    namespace: "hue",
    author: "Digital Homesteaders",
    description: "Philips Hue lights, groups, scenes and sensors over the local Hue API (V2), with live updates.",
    category: "Convenience",
    singleInstance: false,
    installOnOpen: true,
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/digital-homesteaders/hubitat-hue/main/apps/hue-connect.groovy"
)

preferences {
    page(name: "mainPage")
}

// ---------------------------------------------------------------- pages

def mainPage() {
    handlePendingButton()
    boolean linked = bridgeKey() && state.bridge && !state.relinking
    boolean waiting = state.linkUntil || now() < ((state.searchUntil ?: 0L) as long) || (linked && !res().lights)
    dynamicPage(name: "mainPage", title: "Hue Connect", install: linked, uninstall: true, refreshInterval: waiting ? 3 : 0) {
        if (!linked) {
            section("1. Find your Hue Bridge") {
                Map found = foundBridges()
                if (found.size() == 1 && !settings.bridgeId) app.updateSetting("bridgeId", [type: "enum", value: found.keySet().first()])
                if (found) {
                    input "bridgeId", "enum", title: "Bridge", required: false, submitOnChange: true,
                        options: found.collectEntries { id, b -> [(id): "${b.name} (${b.ip})"] }
                } else {
                    paragraph "No bridges found yet. Press Search, or enter the bridge's IP address below."
                    if (state.searchUntil && now() > (state.searchUntil as long)) {
                        paragraph "<small>Still nothing? Check the bridge's address in your router, and that the bridge's middle " +
                            "(network) light is on. Hubitat and the bridge must be on the same network.</small>"
                    }
                }
                input "manualIp", "text", title: "Bridge IP address (optional)", required: false
                input "btnSearch", "button", title: "Search"
            }
            if (settings.bridgeId) {
                section("2. Link") {
                    if (state.linkUntil) paragraph "<b>Press the round button on top of the bridge now.</b> Waiting..."
                    else paragraph "Press <b>Link</b>, then the round button on top of the bridge within 30 seconds."
                    if (state.linkError) paragraph "<span style='color:#b00'>${state.linkError}</span>"
                    input "btnLink", "button", title: "Link"
                }
            }
        } else {
            section("Bridge") {
                def b = bridgeDevice()
                String status = b?.currentValue('status') ?: 'starting'
                paragraph "${state.bridge.name} (${state.bridge.ip}): ${status}"
                if (status == "unauthorized") paragraph "<span style='color:#b00'>The bridge no longer accepts Hue Connect's key. Press Link again.</span>"
                input "btnRelink", "button", title: "Link again"
            }
            if (state.missingDrivers) {
                section("Drivers to install") {
                    paragraph "<span style='color:#b00'>Not added, because these drivers aren't installed: <b>${((state.missingDrivers ?: []) as List).join(', ')}</b>.</span> " +
                        "Open Hubitat Package Manager, choose Modify for Hue Connect, tick them under the optional drivers, then press Done here again."
                }
            }
            section("Lights") {
                Map lights = (res().lights ?: [:]) as Map
                if (lights) {
                    input "selectedLights", "enum", title: "Lights to add", multiple: true, required: false,
                        options: lights.collectEntries { rid, l -> [(rid): l.name] }.sort { it.value }
                } else {
                    paragraph "Reading the bridge's lights..."
                }
            }
            section("Rooms and zones") {
                Map groups = (res().groups ?: [:]) as Map
                if (groups) {
                    input "selectedGroups", "enum", title: "Groups to add (each controls its room or zone together, with its scenes as effects)",
                        multiple: true, required: false, options: groups.collectEntries { rid, g -> [(rid): g.name] }.sort { it.value }
                }
            }
            section("Scenes") {
                Map scenes = (res().scenes ?: [:]) as Map
                if (scenes) {
                    input "selectedScenes", "enum", title: "Scenes to add (each is a button and a switch)", multiple: true, required: false,
                        options: scenes.collectEntries { rid, sc -> [(rid): sceneLabel(sc as Map)] }.sort { it.value }
                }
            }
            section("Remotes and sensors") {
                Map sensors = (res().sensors ?: [:]) as Map
                if (sensors) {
                    input "selectedSensors", "enum", title: "Remotes and sensors to add", multiple: true, required: false,
                        options: sensors.collectEntries { rid, d -> [(rid): "${d.name} (${SENSOR_KINDS_TEXT[d.kind]})"] }.sort { it.value }
                }
            }
            section("Options") {
                input "syncNames", "bool", title: "Rename devices when they are renamed in the Hue app", defaultValue: true
                input "logEnable", "bool", title: "Debug logging", defaultValue: false
            }
        }
    }
}

void appButtonHandler(String btn) { state.pendingButton = btn }   // acted on in mainPage, after typed values are saved

private void handlePendingButton() {
    String btn = state.remove("pendingButton")
    if (btn == "btnSearch") discover()
    else if (btn == "btnLink") startLink()
    else if (btn == "btnRelink") {
        // Same bridge, new key: devices stay. The page shows the Link section until it's done.
        rememberFound([id: state.bridge.id, ip: state.bridge.ip, name: state.bridge.name])
        app.updateSetting("bridgeId", [type: "enum", value: state.bridge.id])
        state.relinking = true
    }
}

// ---------------------------------------------------------------- lifecycle

void installed() { updated() }

void updated() {
    if (!bridgeKey() || !state.bridge) return
    def b = bridgeDevice()
    boolean isNew = !b
    if (isNew) {   // its installed() starts it
        b = addChildDevice("hue", DRIVER_BRIDGE, bridgeDni(), [name: DRIVER_BRIDGE, label: "Hue Bridge (${state.bridge.name})", isComponent: false])
    }
    // Until the bridge has been read (after a restart or code save), syncing would see no lights and delete every
    // device: applyResources syncs instead.
    if (!res()) state.syncPending = true
    else if (syncDevices()) refreshAll()   // new devices need their current state
    if (!isNew) b.initialize()
}

void uninstalled() {
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
    RES.remove(app.id)
    RES_READ.remove(app.id)
}

// ---------------------------------------------------------------- discovery

/** mDNS from the hub's cache, discovery.meethue.com (at most every 15 min) and the manual IP; each answer is checked. */
void discover() {
    state.searchUntil = now() + 15000   // the page refreshes while answers come in
    hubitat.helper.NetworkUtils.getRawMDNSEndpointsByMACForServiceType("_hue._tcp.local.")?.each { k, v ->
        if (v?.ip4Address) checkCandidate(v.ip4Address as String)
    }
    if (now() - ((state.lastCloudDiscovery ?: 0L) as long) >= CLOUD_DISCOVERY_MIN_MS) {
        state.lastCloudDiscovery = now()
        asynchttpGet("cloudDiscoveryDone", [uri: "https://discovery.meethue.com", contentType: "application/json", timeout: 10])
    }
    if (settings.manualIp) checkCandidate((settings.manualIp as String).trim())
}

void cloudDiscoveryDone(resp, Map data) {
    if (resp.status != 200 || !(resp.json instanceof List)) return
    resp.json.each { if (it.internalipaddress) checkCandidate(it.internalipaddress as String) }
}

/** GET https://<ip>/api/0/config needs no key and gives the bridge's full ID. */
private void checkCandidate(String ip) {
    if (!(ip ==~ /\d{1,3}(\.\d{1,3}){3}/)) return
    asynchttpGet("configDone", [uri: "https://${ip}", path: "/api/0/config", contentType: "application/json", timeout: 10, ignoreSSLIssues: true], [ip: ip])
}

void configDone(resp, Map data) {
    if (resp.status != 200 || !(resp.json instanceof Map)) return
    Map b = bridgeFromConfig(resp.json as Map, data.ip as String)
    if (!b) return
    rememberFound(b)
    // Our bridge at a new address (DHCP): follow it, keeping the key and every device
    if (state.bridge?.id == b.id && state.bridge.ip != b.ip) {
        log.warn "Hue Connect: ${b.name} moved from ${state.bridge.ip} to ${b.ip}"
        state.bridge = [id: b.id, ip: b.ip, name: state.bridge.name]
        bridgeDevice()?.initialize()
    }
}

private Map foundMap() {
    Long id = app.id as Long
    if (FOUND.get(id) == null) FOUND.putIfAbsent(id, new ConcurrentHashMap())
    return FOUND.get(id) as Map
}

private void rememberFound(Map b) { foundMap().put(b.id, b) }

/** Bridges found so far (in memory; discovery is quick to repeat). */
private Map foundBridges() { new LinkedHashMap(foundMap()) }

/** Called by the Hue Bridge device when the bridge stops answering: look for it again, by ID (rate limits apply). */
void bridgeUnreachable() {
    // Hue: discover only when the stored address stops answering, and rarely. Waits 5, 10, 20 ... up to 60 minutes.
    long waitMs = ((state.rediscoverWaitMs ?: 5 * 60 * 1000L) as long)
    if (now() - ((state.lastRediscovery ?: 0L) as long) < waitMs) return
    state.lastRediscovery = now()
    state.rediscoverWaitMs = Math.min(waitMs * 2, 60 * 60 * 1000L)
    discover()
}

/** Called by the Hue Bridge device when the bridge answers again. */
void bridgeReachable() {
    state.remove("rediscoverWaitMs")
    state.remove("lastRediscovery")
}

/** A V2-capable bridge from /api/0/config, or null (the round V1 bridge, BSB001, has no V2). */
static Map bridgeFromConfig(Map c, String ip) {
    String id = (c.bridgeid as String)?.toLowerCase()
    if (!(id ==~ /[0-9a-f]{16}/) || c.modelid == "BSB001") return null
    return [id: id, ip: ip, name: (c.name ?: "Hue Bridge") as String, model: c.modelid as String]
}

// ---------------------------------------------------------------- linking

private void startLink() {
    state.remove("linkError")
    if (!settings.bridgeId || !foundBridges()[settings.bridgeId]) return
    state.linkUntil = now() + LINK_WINDOW_MS
    sendLink()
}

void sendLink() {
    Map b = foundBridges()[settings.bridgeId] as Map
    if (!b) return
    asynchttpPost("linkDone", [uri: "https://${b.ip}", path: "/api", contentType: "application/json",
                               requestContentType: "application/json", body: [devicetype: deviceType()],
                               timeout: 10, ignoreSSLIssues: true], [id: b.id])
}

/** "hue_connect#<hub name>", within Hue's 20 + 19 character limits. */
String deviceType() {
    String hub = ((location?.hub?.name ?: "hubitat") as String).replaceAll(/[^A-Za-z0-9 _-]/, "")
    return "${APP_NAME}#${hub.take(19)}"
}

void linkDone(resp, Map data) {
    def r = (resp.json instanceof List && resp.json) ? resp.json[0] : null
    String key = r?.success?.username
    if (key) {
        app.updateSetting("bridgeKey", [type: "password", value: key])
        Map b = foundBridges()[data.id] as Map
        boolean relink = state.relinking && state.bridge?.id == b.id
        state.bridge = [id: b.id, ip: b.ip, name: b.name]
        state.remove("linkUntil")
        state.remove("relinking")
        log.info "Hue Connect: ${relink ? 're-linked' : 'linked'} to ${b.name}"
        refreshAll()
        if (relink) bridgeDevice()?.initialize()
    } else if (r?.error?.type == 101 && now() < ((state.linkUntil ?: 0L) as long)) {
        runIn(2, "sendLink")   // the button hasn't been pressed yet
    } else {
        state.remove("linkUntil")
        state.linkError = r?.error?.type == 101 ? "The bridge's button wasn't pressed in time. Press Link and try again." :
            "The bridge didn't link (${r?.error?.description ?: 'HTTP ' + resp.status})."
    }
}

// ---------------------------------------------------------------- the bridge connection (called by the Hue Bridge device)

private String bridgeKey() { settings.bridgeKey as String }

/** What the Hue Bridge device needs to reach the bridge. */
Map bridgeConnection() { state.bridge ? [ip: state.bridge.ip, id: state.bridge.id, key: bridgeKey()] : null }

/** What the last full read found: [lights:, connectivity:, groups:, scenes:, sensors:], or [:] while it's being read. */
Map res() {
    Map r = RES.get(app.id) as Map
    if (r != null) return r
    Long asked = RES_READ.get(app.id) as Long
    if (asked == null || now() - asked > RES_RETRY_MS) {
        RES_READ.put(app.id, now())
        runIn(1, "refreshAll")
    }
    return [:]
}

/**
 * A refresh asked for by a device or by a change on the bridge. Each full read parses every light, group, scene and
 * sensor and updates every device, so a burst of them (a rule refreshing many devices) becomes one: runIn replaces a
 * pending run.
 */
void requestRefresh() { runIn(2, "refreshAll") }

/** One typed request for everything Hue Connect uses (startup, after long stream gaps, add/delete events). */
void refreshAll() {
    Map c = bridgeConnection()
    if (!c?.key) return
    asynchttpGet("refreshAllDone", [uri: "https://${c.ip}", path: "/clip/v2/resource", query: [type: RESOURCE_TYPES.join(",")],
                                    headers: ["hue-application-key": c.key], contentType: "application/json", timeout: 20, ignoreSSLIssues: true])
}

void refreshAllDone(resp, Map data) {
    if (resp.status != 200 || !(resp.json?.data instanceof List)) {
        log.warn "Hue Connect: couldn't read the bridge's devices (HTTP ${resp.status})"
        return
    }
    applyResources(resp.json.data as List)
}

/** A full resource list: rebuild what Hue Connect knows, then bring every device up to date. */
void applyResources(List items) {
    Map devices = items.findAll { it.type == "device" }.collectEntries { [(it.id): it] }
    Map lights = [:], connectivity = [:]
    items.findAll { it.type == "light" }.each { l -> lights[l.id] = lightInfo(l as Map, devices[l.owner?.rid] as Map) }
    items.findAll { it.type in ["zigbee_connectivity", "zgp_connectivity"] }.each { z -> connectivity[z.id] = z.owner?.rid }
    Map owners = items.findAll { it.type in ["room", "zone", "bridge_home"] }.collectEntries { [(it.id): it] }
    Map groups = [:], groupOf = [:]
    items.findAll { it.type == "grouped_light" }.each { g ->
        Map owner = owners[g.owner?.rid] as Map
        if (!owner) return
        groups[g.id] = [name: owner.type == "bridge_home" ? "All Hue lights" : (owner.metadata?.name ?: "Hue group"), owner: owner.id, ownerType: owner.type]
        groupOf[owner.id] = g.id
    }
    Map scenes = [:]
    items.findAll { it.type in ["scene", "smart_scene"] }.each { sc ->
        String roomRid = sc.group?.rid
        scenes[sc.id] = [name: (sc.metadata?.name ?: "Scene") as String, smart: sc.type == "smart_scene", groupedLight: groupOf[roomRid],
                         room: (owners[roomRid]?.metadata?.name ?: "") as String, roomRid: roomRid, dynamic: sc.palette?.color ? true : false]
    }
    Map services = items.findAll { it.type in SENSOR_SERVICES }.collectEntries { [(it.id): it] }
    Map sensors = [:]
    devices.each { String devRid, Map dev ->
        Map info = sensorInfo(dev, services)
        if (info) sensors[devRid] = info
    }
    RES.put(app.id, [lights: lights, connectivity: connectivity, groups: groups, scenes: scenes, sensors: sensors])
    RES_READ.remove(app.id)
    // Earlier builds kept these in state (only the developer's hub ran them). Remove before publishing.
    ["lights", "connectivity", "groups", "scenes", "sensors"].each { if (state.containsKey(it)) state.remove(it) }
    if (state.remove("syncPending")) syncDevices()   // a Done that came before this read
    // Device details, and each light's effect list, on the Hubitat devices (only when they change)
    (lights + sensors).each { String rid, Map info ->
        def d = getChildDevice(dni(rid))
        if (!d) return
        [model: info.model, productName: info.productName, firmware: info.firmware].each { k, v ->
            if (v != null && d.getDataValue(k) != v.toString()) d.updateDataValue(k, v.toString())
        }
        if (info.effects) d.parse(lightEffectEvents(info))
    }
    groups.each { rid, g -> getChildDevice(dni(rid as String))?.parse(effectListEvents(rid as String, scenes)) }
    hueEvents("update", items)
}

/**
 * A remote or sensor device: its kind (from its services, never its model: Hue supports over 1000 models), its buttons by
 * control_id, whether it has a rotary ring, and which buttons can't report a short release (they push on the press).
 * Null for a device with no such services.
 */
static Map sensorInfo(Map device, Map services) {
    List refs = (device.services ?: []) as List
    List types = refs*.rtype
    String kind = "contact" in types ? "contact" : "motion" in types ? "motion" :
        types.any { it in ["button", "relative_rotary", "bell_button"] } ? "button" : null
    if (!kind) return null
    Map buttons = [:], pushOnPress = [:]
    refs.findAll { it.rtype in ["button", "bell_button"] }.each { ref ->
        Map b = services[ref.rid] as Map
        int id = ((b?.metadata?.control_id ?: (buttons.size() + 1)) as int)
        buttons[ref.rid] = id
        List values = (b?.button?.event_values ?: []) as List
        if (values && !("short_release" in values)) pushOnPress[ref.rid] = true
    }
    int count = buttons ? (buttons.values().max() as int) : 0
    boolean rotary = "relative_rotary" in types
    return [name: (device.metadata?.name ?: "Hue device") as String, kind: kind, buttons: buttons, pushOnPress: pushOnPress,
            rotary: rotary, numberOfButtons: count + (rotary ? 2 : 0), services: refs*.rid,
            motion: refs.find { it.rtype == "motion" }?.rid,
            sensitivityMax: services[refs.find { it.rtype == "motion" }?.rid]?.sensitivity?.sensitivity_max] + productData(device)
}

static String sceneLabel(Map sc) { sc.room ? "${sc.name} (${sc.room})" : sc.name }

/** A light's own effects as Hubitat light effects: the lightEffects map (number -> name), 0 is "none". */
static List lightEffectEvents(Map info) {
    List fx = (info.effects ?: []) as List
    if (!fx) return []
    Map numbered = [:]
    fx.eachWithIndex { e, i -> numbered[(i + 1) as String] = e }
    return [[name: "lightEffects", value: groovy.json.JsonOutput.toJson(numbered)]]
}

/** A group's scenes as Hubitat light effects: the lightEffects map (number -> name), in name order. */
static List groupEffects(String groupedLightRid, Map scenes) {
    scenes.findAll { rid, sc -> sc.groupedLight == groupedLightRid }.collect { rid, sc -> [rid: rid, name: sc.name] }.sort { it.name }
}

static List effectListEvents(String groupedLightRid, Map scenes) {
    List fx = groupEffects(groupedLightRid, scenes)
    Map numbered = [:]
    fx.eachWithIndex { e, i -> numbered[(i + 1) as String] = e.name }
    return [[name: "lightEffects", value: groovy.json.JsonOutput.toJson(numbered)]]
}

/**
 * What doesn't change about a light: name, kind (which driver), colour gamut and CT range. The name is the device's: Hue marks
 * the light's own name deprecated ("use metadata on device level") and a rename goes to the device. They differ on real
 * bridges (13 of 61 lights on the one Hue Connect was tested with), and the Hue app shows the device's.
 */
static Map lightInfo(Map l, Map device) {
    String kind = l.color ? "color" : l.color_temperature ? "ct" : l.dimming ? "dim" : "onoff"
    Map info = [name: (device?.metadata?.name ?: l.metadata?.name ?: "Hue light") as String, kind: kind, device: l.owner?.rid] +
               productData(device)
    // Effects, timed effects and signals as this light reports them (Hue: never assume from the model)
    List fx = ((l.effects_v2?.action?.effect_values ?: l.effects?.effect_values ?: []) as List) - "no_effect"
    if (fx) info.effects = fx
    List timed = ((l.timed_effects?.effect_values ?: []) as List) - "no_effect"
    if (timed) info.timedEffects = timed
    if (l.signaling?.signal_values) info.signals = l.signaling.signal_values
    if (l.powerup instanceof Map) info.powerUp = true
    if (l.color) info.gamut = gamutFor(l.color as Map)
    Map schema = l.color_temperature?.mirek_schema as Map
    if (schema) { info.mirekMin = schema.mirek_minimum; info.mirekMax = schema.mirek_maximum }
    return info
}

/** Model, product name and firmware from a device resource, for the Hubitat device's data. */
static Map productData(Map device) {
    Map p = (device?.product_data ?: [:]) as Map
    return [model: p.model_id, productName: p.product_name, firmware: p.software_version].findAll { k, v -> v != null }
}

/** The light's own gamut; if missing, the corners for its gamut_type (Hue: "some bulbs do not properly return the Gamut"). */
static Map gamutFor(Map color) {
    Map g = color.gamut as Map
    if (g?.red && g?.green && g?.blue) {
        return [red: [g.red.x as double, g.red.y as double], green: [g.green.x as double, g.green.y as double], blue: [g.blue.x as double, g.blue.y as double]]
    }
    return (GAMUTS[color.gamut_type] ?: GAMUTS.other) as Map
}

// ---------------------------------------------------------------- devices

private String bridgeDni() { "hue-${state.bridge.id}" }
String dni(String rid) { "hue-${state.bridge.id}-${rid}" }
private def bridgeDevice() { getChildDevice(bridgeDni()) }

/** Adds and removes light, group and scene devices to match the selection; returns true if any were added. */
private boolean syncDevices() {
    Map lights = (res().lights ?: [:]) as Map, groups = (res().groups ?: [:]) as Map, scenes = (res().scenes ?: [:]) as Map
    Map wanted = [:]   // rid -> [driver, label]
    ((settings.selectedLights ?: []) as List).each { String rid -> if (lights[rid]) wanted[rid] = [LIGHT_DRIVERS[lights[rid].kind], lights[rid].name] }
    ((settings.selectedGroups ?: []) as List).each { String rid -> if (groups[rid]) wanted[rid] = [DRIVER_GROUP, groups[rid].name] }
    ((settings.selectedScenes ?: []) as List).each { String rid -> if (scenes[rid]) wanted[rid] = [DRIVER_SCENE, sceneLabel(scenes[rid] as Map)] }
    Map sensors = (res().sensors ?: [:]) as Map
    ((settings.selectedSensors ?: []) as List).each { String rid -> if (sensors[rid]) wanted[rid] = [SENSOR_DRIVERS[sensors[rid].kind], sensors[rid].name] }
    boolean added = false
    Set missing = [] as Set   // drivers the user didn't install (HPM lists most of them as optional)
    wanted.each { String rid, List d ->
        if (!getChildDevice(dni(rid))) {
            def cd
            try {
                cd = addChildDevice("hue", d[0], dni(rid), [name: d[0], label: d[1], isComponent: false])
            } catch (Exception e) {
                if (!isMissingDriver(e)) throw e
                missing << d[0]
                return
            }
            if (d[0] == DRIVER_GROUP) cd.parse(effectListEvents(rid, scenes))
            if (lights[rid]?.effects) cd.parse(lightEffectEvents(lights[rid] as Map))
            if (d[0] == SENSOR_DRIVERS.button) cd.parse([[name: "numberOfButtons", value: sensors[rid].numberOfButtons]])
            added = true
        }
    }
    if (missing) {
        state.missingDrivers = missing.sort()
        log.warn "Hue Connect: not added, because these drivers aren't installed: ${missing.sort().join(', ')}. Install them with Hubitat Package Manager (Modify, then pick the optional drivers) and press Done again."
    } else {
        state.remove("missingDrivers")
    }
    getChildDevices().findAll { ridOf(it.deviceNetworkId as String) && !wanted.containsKey(ridOf(it.deviceNetworkId as String)) }.each {
        deleteChildDevice(it.deviceNetworkId)
    }
    return added
}

/**
 * A name changed on the bridge (in the Hue app): update what the app remembers and relabel the Hubitat device. Only a
 * change relabels, so a label set in Hubitat stays until the same thing is renamed in Hue. Optional: "Rename devices ...".
 */
private void applyRenames(Map item) {
    if (settings.syncNames == false) return
    Map r = res()
    List changes = renamed(item, r)
    changes.each { Map c ->
        Map info = (r[c.section] as Map)[c.rid] as Map
        info.putAll(c.set as Map)
        def cd = getChildDevice(dni(c.rid as String))
        String label = c.section == "scenes" ? sceneLabel(info) : info.name
        if (cd && cd.label != label) {
            log.info "Hue Connect: ${cd.label} is now ${label}"
            cd.setLabel(label)
        }
        if (c.section == "scenes" && c.set.containsKey("name") && info.groupedLight) {   // a room's effects are its scenes' names
            getChildDevice(dni(info.groupedLight as String))?.parse(effectListEvents(info.groupedLight as String, r.scenes as Map))
        }
    }
}

/**
 * What a rename on the bridge changes in the app's maps: [[section: lights|groups|scenes|sensors, rid:, set: [...]]].
 * A light is named by its device (the light's own name is deprecated), so only a device rename relabels it. A room or
 * zone name is its group's name and part of its scenes' labels. Names that already match give nothing (the full read at start sends every name).
 */
static List renamed(Map item, Map res) {
    String name = item.metadata?.name as String
    if (!name) return []
    List out = []
    Map lights = (res.lights ?: [:]) as Map, groups = (res.groups ?: [:]) as Map
    Map scenes = (res.scenes ?: [:]) as Map, sensors = (res.sensors ?: [:]) as Map
    switch (item.type) {
        case "device":
            lights.each { rid, l -> if (l.device == item.id && l.name != name) out << [section: "lights", rid: rid, set: [name: name]] }
            if (sensors[item.id] && sensors[item.id].name != name) out << [section: "sensors", rid: item.id, set: [name: name]]
            break
        case "room":
        case "zone":
            groups.each { rid, g -> if (g.owner == item.id && g.name != name) out << [section: "groups", rid: rid, set: [name: name]] }
            scenes.each { rid, s -> if (s.roomRid == item.id && s.room != name) out << [section: "scenes", rid: rid, set: [room: name]] }
            break
        case "scene":
        case "smart_scene":
            if (scenes[item.id] && scenes[item.id].name != name) out << [section: "scenes", rid: item.id, set: [name: name]]
            break
    }
    return out
}

/** True for what Hubitat throws when a driver isn't installed (UnknownDeviceTypeException: "Device type ... not found"). */
static boolean isMissingDriver(Exception e) { e.class.simpleName == "UnknownDeviceTypeException" || (e.message ?: "").contains("not found") }

/** The Hue resource id in one of this bridge's device network ids, or null (e.g. the bridge device itself). */
private String ridOf(String deviceNetworkId) {
    String prefix = "hue-${state.bridge.id}-"
    return deviceNetworkId?.startsWith(prefix) ? deviceNetworkId.substring(prefix.length()) : null
}

// ---------------------------------------------------------------- events from the bridge

/** Called by the Hue Bridge device for each event container: type update | add | delete. */
void hueEvents(String type, List items) {
    if (type == "add" || type == "delete") {
        // Only for what Hue Connect reads: the bridge also adds and deletes things it ignores (entertainment, behaviors, ...)
        if (items.any { it.type in RESOURCE_TYPES }) requestRefresh()   // the device list changed; one typed GET picks it up
        return
    }
    items.each { Map item ->
        if (item.metadata?.name) applyRenames(item)
        switch (item.type) {
            case "light":
                def d = getChildDevice(dni(item.id as String))
                if (d) d.parse(lightEvents(item, (res().lights?.get(item.id) ?: [:]) as Map))
                break
            case "grouped_light":
                getChildDevice(dni(item.id as String))?.parse(groupEvents(item))
                break
            case "scene":
            case "smart_scene":
                Map sc = res().scenes?.get(item.id) as Map
                List ev = sceneEvents(item, sc ?: [:])
                if (!ev) break
                getChildDevice(dni(item.id as String))?.parse(ev)
                Map sw = ev.find { it.name == "switch" } as Map
                def g = sw && sc?.groupedLight ? getChildDevice(dni(sc.groupedLight as String)) : null
                if (g) g.parse(effectNameEvents(sc.name as String, sw.value == "on", g.currentValue("effectName") as String))
                break
            case "zigbee_connectivity":
            case "zgp_connectivity":
                String devRid = (res().connectivity?.get(item.id) ?: item.owner?.rid) as String
                List ev = connectivityEvents(item)
                if (ev) {
                    lightsOfDevice(devRid).each { getChildDevice(dni(it))?.parse(ev) }
                    getChildDevice(dni(devRid))?.parse(ev)
                }
                break
            case "button":
            case "bell_button":
            case "relative_rotary":
            case "motion":
            case "light_level":
            case "temperature":
            case "contact":
            case "tamper":
            case "device_power":
                String owner = (item.owner?.rid ?: ownerOf(item.id as String)) as String
                def d = owner ? getChildDevice(dni(owner)) : null
                if (d) d.parse(sensorEvents(item, (res().sensors?.get(owner) ?: [:]) as Map, location?.temperatureScale ?: "F"))
                break
        }
    }
}

/** The device a service belongs to, for updates that don't carry their owner. */
private String ownerOf(String serviceRid) {
    ((res().sensors ?: [:]) as Map).find { rid, d -> serviceRid in ((d.services ?: []) as List) }?.key as String
}

private List lightsOfDevice(String deviceRid) {
    ((res().lights ?: [:]) as Map).findAll { rid, l -> l.device == deviceRid }.collect { it.key as String }
}

/** Hubitat events from a light resource or update (an update carries only what changed). */
static List lightEvents(Map item, Map info) {
    List ev = []
    if (item.on instanceof Map && item.on.on != null) ev << [name: "switch", value: item.on.on ? "on" : "off"]
    if (item.dimming instanceof Map && item.dimming.brightness != null) ev << [name: "level", value: levelFromBrightness(item.dimming.brightness as double), unit: "%"]
    boolean ctNow = false
    if (item.color_temperature instanceof Map && item.color_temperature.containsKey("mirek")) {
        def m = item.color_temperature.mirek
        if (m != null && item.color_temperature.mirek_valid != false) {
            ev << [name: "colorTemperature", value: kelvinFromMirek(m as int), unit: "K"]
            if (info.kind == "color") ev << [name: "colorMode", value: "CT"]   // only colour lights have a colour mode
            ctNow = true
        } else if (info.kind == "color") {
            ev << [name: "colorMode", value: "RGB"]
        }
    }
    def effect = item.effects_v2?.status?.effect ?: item.effects?.status
    if (effect != null) ev << [name: "effectName", value: effect == "no_effect" ? "none" : effect]
    if (item.powerup instanceof Map && item.powerup.preset) ev << [name: "powerUp", value: powerUpName(item.powerup as Map)]
    if (item.mode != null) ev << [name: "hueMode", value: item.mode]   // "streaming" while an entertainment session runs
    if (!ctNow && info.kind == "color" && item.color?.xy?.x != null) {
        List hs = xyToHueSat(item.color.xy.x as double, item.color.xy.y as double)
        ev << [name: "hue", value: hs[0]]
        ev << [name: "saturation", value: hs[1], unit: "%"]
        if (!(item.color_temperature instanceof Map)) ev << [name: "colorMode", value: "RGB"]
    }
    return ev
}

/** Hubitat events from a remote's or sensor's service (an update carries only what changed). */
static List sensorEvents(Map item, Map info, String scale) {
    switch (item.type) {
        case "button":
        case "bell_button":
            String event = item.button?.button_report?.event
            Integer n = ((info.buttons ?: [:]) as Map)[item.id] as Integer
            if (!event || n == null) return []
            boolean pushOnPress = ((info.pushOnPress ?: [:]) as Map)[item.id] == true
            switch (event) {
                case "short_release": return pushOnPress ? [] : [buttonEvent("pushed", n)]
                case "initial_press": return pushOnPress ? [buttonEvent("pushed", n)] : []
                case "long_press": return [buttonEvent("held", n)]
                case "repeat": return [buttonEvent("held", n) + [repeat: true]]   // the driver passes it on only if asked
                case "long_release": return [buttonEvent("released", n)]
                case "double_short_release": return [buttonEvent("doubleTapped", n)]
            }
            return []
        case "relative_rotary":
            Map r = item.relative_rotary?.rotary_report?.rotation as Map
            if (!r) return []
            boolean cw = r.direction == "clock_wise"
            int base = ((info.numberOfButtons ?: 2) as int) - 2
            int degrees = Math.round(((r.steps ?: 0) as int) * 360d / 1000d) as int
            return [buttonEvent("pushed", base + (cw ? 1 : 2)),
                    [name: "rotation", value: cw ? degrees : -degrees, unit: "degrees", isStateChange: true]]
        case "motion":
            List ev = []
            def m = item.motion?.motion_report?.motion
            if (m != null) ev << [name: "motion", value: m ? "active" : "inactive"]
            if (item.enabled != null) ev << [name: "motionDetection", value: item.enabled ? "enabled" : "disabled"]
            if (item.sensitivity?.sensitivity != null) ev << [name: "sensitivity", value: item.sensitivity.sensitivity]
            return ev
        case "light_level":
            def v = item.light?.light_level_report?.light_level
            return v == null ? [] : [[name: "illuminance", value: luxFromLightLevel(v as int), unit: "lux"]]
        case "temperature":
            def c = item.temperature?.temperature_report?.temperature
            if (c == null) return []
            double t = scale == "C" ? (c as double) : (c as double) * 9d / 5d + 32d
            return [[name: "temperature", value: Math.round(t * 10) / 10d, unit: "\u00B0${scale}".toString()]]
        case "contact":
            def st = item.contact_report?.state
            return st == null ? [] : [[name: "contact", value: st == "contact" ? "closed" : "open"]]
        case "tamper":
            List reports = (item.tamper_reports ?: []) as List
            return reports ? [[name: "tamper", value: reports.any { it.state == "tampered" } ? "detected" : "clear"]] : []
        case "device_power":
            List ev = []
            if (item.power_state?.battery_level != null) ev << [name: "battery", value: item.power_state.battery_level, unit: "%"]
            if (item.power_state?.battery_state != null) ev << [name: "batteryState", value: item.power_state.battery_state]
            return ev
    }
    return []
}

private static Map buttonEvent(String name, int n) { [name: name, value: n, isStateChange: true, type: "physical"] }

/** Hue light_level (10000 x log10(lux) + 1) to lux. */
static int luxFromLightLevel(int v) { Math.round(Math.pow(10d, (v - 1) / 10000d)) as int }

/** A room's or zone's grouped_light: on = any light on; brightness = the average of the lights that are on. */
static List groupEvents(Map item) {
    List ev = []
    if (item.on instanceof Map && item.on.on != null) ev << [name: "switch", value: item.on.on ? "on" : "off"]
    if (item.dimming instanceof Map && item.dimming.brightness != null && item.on?.on != false) {
        ev << [name: "level", value: levelFromBrightness(item.dimming.brightness as double), unit: "%"]
    }
    return ev
}

/**
 * A scene (status.active: inactive | static | dynamic_palette) or smart scene (state: active | inactive) as a switch, and
 * a dynamic scene's palette speed (0-1 on the bridge) as a percentage. A scene's info says whether it is dynamic; an
 * update carries only what changed, so a speed alone is a valid event list.
 */
static List sceneEvents(Map item, Map info = [:]) {
    List ev = []
    def active = item.type == "smart_scene" ? item.state : item.status?.active
    if (active != null) ev << [name: "switch", value: active in ["inactive"] ? "off" : "on"]
    if (item.type == "scene" && info.dynamic && item.speed != null) ev << [name: "speed", value: speedPercent(item.speed), unit: "%"]
    return ev
}

/** A dynamic scene's palette speed: Hue's 0-1 as a whole percentage, and back (clamped, to three places). */
static int speedPercent(def speed) { Math.max(0, Math.min(100, Math.round((speed as double) * 100) as int)) }
static double speedFromPercent(def percent) { Math.round(Math.max(0d, Math.min(100d, percent as double)) * 10) / 1000d }

/**
 * What a light does when power returns, as Hue's presets name it: previous (powerfail: back to the state before the
 * outage), lastOnState, safety (on at a warm white), off (a custom preset that stays off), else custom.
 */
static String powerUpName(Map powerup) {
    switch (powerup.preset) {
        case "powerfail": return "previous"
        case "last_on_state": return "lastOnState"
        case "safety": return "safety"
        case "custom":
            Map on = (powerup.on ?: [:]) as Map
            return on.mode == "on" && on.on instanceof Map && on.on.on == false ? "off" : "custom"
    }
    return "custom"
}

/** The PUT body for a power-up behaviour from powerUpName's list, or null for anything else ("custom" can't be built blind). */
static Map powerUpBody(String behavior) {
    switch (behavior) {
        case "previous": return [powerup: [preset: "powerfail"]]
        case "lastOnState": return [powerup: [preset: "last_on_state"]]
        case "safety": return [powerup: [preset: "safety"]]
        case "off": return [powerup: [preset: "custom", on: [mode: "on", on: [on: false]]]]
    }
    return null
}

/** The group's effectName follows its scenes: the one just recalled, or "none" when the one shown is no longer active. */
static List effectNameEvents(String sceneName, boolean active, String shown) {
    if (active) return [[name: "effectName", value: sceneName]]
    return shown == sceneName ? [[name: "effectName", value: "none"]] : []
}

/** healthStatus from zigbee_connectivity: online only when "connected" (Hue also reports connectivity_issue, disconnected, ...). */
static List connectivityEvents(Map item) {
    if (item.status == null) return []
    return [[name: "healthStatus", value: item.status == "connected" ? "online" : "offline"]]
}

// ---------------------------------------------------------------- commands from devices

/** A light driver's command (with its transition-time setting, in ms): build the PUT body and queue it on the Hue Bridge device. */
void hueLightCommand(cd, String command, List args = [], Integer transitionMs = null) {
    String rid = ridOf(cd.deviceNetworkId as String)
    Map info = (res().lights?.get(rid) ?: [:]) as Map
    Map asked = REQUESTED.get(rid) as Map
    boolean fresh = asked && now() - ((asked.at ?: 0L) as long) < REQUESTED_MS
    Map current = [switch: cd.currentValue("switch"), level: cd.currentValue("level"), effectName: cd.currentValue("effectName"),
                   hue: fresh && asked.hue != null ? asked.hue : cd.currentValue("hue"),
                   saturation: fresh && asked.saturation != null ? asked.saturation : cd.currentValue("saturation")]
    Map hs = requestedHueSat(command, args, current)
    if (hs) REQUESTED.put(rid, hs + [at: now()])
    Map body = lightCommand(command, args, info, current, transitionMs)
    if (body == null) {
        if (command == "refresh") requestRefresh()
        return
    }
    bridgeDevice()?.enqueue("light", rid, body)
}

/** The hue and saturation a colour command asks for (combined with the other, current or just requested), or null. */
static Map requestedHueSat(String command, List args, Map current) {
    switch (command) {
        case "setColor": return [hue: args[0]?.hue ?: 0, saturation: args[0]?.saturation ?: 0]
        case "setHue": return [hue: args[0], saturation: current.saturation != null ? current.saturation : 100]
        case "setSaturation": return [hue: current.hue != null ? current.hue : 0, saturation: args[0]]
    }
    return null
}

/** A Hue Group's command. Group commands are Zigbee broadcasts (at most 1 a second), so `on` always goes with level,
 * colour temperature and colour: in a partly-on room, the lights that are off would otherwise ignore them. */
void hueGroupCommand(cd, String command, List args = [], Integer transitionMs = null) {
    String rid = ridOf(cd.deviceNetworkId as String)
    if (command in ["setEffect", "setNextEffect", "setPreviousEffect"]) {
        List fx = groupEffects(rid, (res().scenes ?: [:]) as Map)
        if (!fx) return
        int count = fx.size()
        int current = fx.findIndexOf { it.name == cd.currentValue("effectName") }
        int index = command == "setEffect" ? ((args[0] ?: 1) as int) - 1 :
            ((current < 0 ? (command == "setNextEffect" ? -1 : 0) : current) + (command == "setNextEffect" ? 1 : -1) + count) % count
        if (index < 0 || index >= count) return
        bridgeDevice()?.enqueue("scene", fx[index].rid as String, [recall: [action: "active"]])
        return
    }
    Map body = lightCommand(command, args, [kind: "color", gamut: GAMUTS.other, mirekMin: 153, mirekMax: 500], [switch: "off"], transitionMs)
    if (body == null) {
        if (command == "refresh") requestRefresh()
        return
    }
    bridgeDevice()?.enqueue("grouped_light", rid, body)
}

/** A Hue Motion Sensor's command: turn motion detection on or off, or set its sensitivity (0 to the sensor's maximum). */
void hueSensorCommand(cd, String command, List args = []) {
    String rid = ridOf(cd.deviceNetworkId as String)
    Map info = (res().sensors?.get(rid) ?: [:]) as Map
    if (command == "refresh") { requestRefresh(); return }
    if (!info.motion) return
    Map body = command == "enableMotionDetection" ? [enabled: true] : command == "disableMotionDetection" ? [enabled: false] :
        command == "setSensitivity" ? [sensitivity: [sensitivity: Math.max(0, Math.min((info.sensitivityMax ?: 4) as int, (args[0] ?: 0) as int))]] : null
    if (body) bridgeDevice()?.enqueue("motion", info.motion as String, body)
}

/** A Hue Scene's command: on/push recalls it; off turns its room or zone off (a smart scene is deactivated). */
void hueSceneCommand(cd, String command, boolean dynamic = false) {
    String rid = ridOf(cd.deviceNetworkId as String)
    Map sc = (res().scenes?.get(rid) ?: [:]) as Map
    if (command == "refresh") { requestRefresh(); return }
    if (sc.smart) {
        bridgeDevice()?.enqueue("smart_scene", rid, [recall: [action: command == "off" ? "deactivate" : "activate"]])
    } else if (command == "off") {
        if (sc.groupedLight) bridgeDevice()?.enqueue("grouped_light", sc.groupedLight as String, [on: [on: false]])
    } else {
        bridgeDevice()?.enqueue("scene", rid, [recall: [action: dynamic && sc.dynamic ? "dynamic_palette" : "active"]])
    }
}

/** A dynamic scene's palette speed, 0-100 %. Smart scenes and scenes without a palette have none. */
void hueSceneSpeed(cd, def percent) {
    String rid = ridOf(cd.deviceNetworkId as String)
    Map sc = (res().scenes?.get(rid) ?: [:]) as Map
    if (sc.smart || !sc.dynamic) {
        log.warn "${cd.displayName}: only a scene with a palette has a speed"
        return
    }
    bridgeDevice()?.enqueue("scene", rid, [speed: speedFromPercent(percent)])
}

/**
 * The PUT body for a light command, or null if there's nothing to send. Only what changes is sent: one property is one
 * Zigbee message (Hue performance guide), so `on` is added only for a light that isn't on (an off light ignores other
 * properties), and no transition goes with `off` (it can reset brightness).
 */
static Map lightCommand(String command, List args, Map info, Map current, Integer transitionMs) {
    Map body = [:]
    boolean isOn = current.switch == "on"
    switch (command) {
        case "on":
            body = [on: [on: true]]
            withDuration(body, null, transitionMs)   // Hue: set the transition with every command, or 400 ms applies
            return body
        case "off": return [on: [on: false]]
        case "setLevel":
            double level = (args[0] ?: 0) as double
            if (level <= 0) return [on: [on: false]]
            body.dimming = [brightness: brightnessFromLevel(level)]
            withDuration(body, args.size() > 1 ? args[1] : null, transitionMs)
            break
        case "setColorTemperature":
            body.color_temperature = [mirek: mirekFromKelvin(args[0] as int, info.mirekMin as Integer, info.mirekMax as Integer)]
            if (args.size() > 1 && args[1] != null && !sameLevel(args[1], current.level)) body.dimming = [brightness: brightnessFromLevel(args[1] as double)]
            withDuration(body, args.size() > 2 ? args[2] : null, transitionMs)
            break
        case "setColor":
            Map c = args[0] as Map
            body.color = [xy: xyMap(hueSatToXy((c.hue ?: 0) as double, (c.saturation ?: 0) as double, info.gamut as Map))]
            if (c.level != null && !sameLevel(c.level, current.level)) body.dimming = [brightness: brightnessFromLevel(c.level as double)]
            withDuration(body, null, transitionMs)
            break
        case "setHue":
            body.color = [xy: xyMap(hueSatToXy(args[0] as double, (current.saturation != null ? current.saturation : 100) as double, info.gamut as Map))]
            withDuration(body, null, transitionMs)
            break
        case "setSaturation":
            body.color = [xy: xyMap(hueSatToXy((current.hue != null ? current.hue : 0) as double, args[0] as double, info.gamut as Map))]
            withDuration(body, null, transitionMs)
            break
        case "setEffect":
        case "setNextEffect":
        case "setPreviousEffect":
            List fx = (info.effects ?: []) as List
            if (!fx) return null
            int n
            if (command == "setEffect") {
                n = (args[0] ?: 0) as int
                if (n == 0) return [effects_v2: [action: [effect: "no_effect"]]]
            } else {
                int cur = fx.indexOf(current.effectName) + 1   // 0 when no effect is running
                n = command == "setNextEffect" ? (cur % fx.size()) + 1 : (cur <= 1 ? fx.size() : cur - 1)
            }
            if (n < 1 || n > fx.size()) return null
            body.effects_v2 = [action: [effect: fx[n - 1]]]
            break
        case "flash":
            // Hue's V2 signalling (blinks for the duration); lights without it breathe once instead
            int ms = Math.max(1000, Math.min(65534000, ((args[0] ?: 10000) as int)))
            return ("on_off" in ((info.signals ?: []) as List)) ? [signaling: [signal: "on_off", duration: ms]] : [alert: [action: "breathe"]]
        case "identify":
            return [identify: [action: "identify"]]
        case "setPowerUp":
            return info.powerUp ? powerUpBody(args[0] as String) : null
        case "startTimedEffect":
            String e = args[0] as String
            if (!(e in ((info.timedEffects ?: []) as List))) return null
            int minutes = Math.max(1, Math.min(360, ((args.size() > 1 && args[1] != null ? args[1] : 30) as int)))
            return [timed_effects: [effect: e, duration: minutes * 60000]]   // runs on the light itself, up to 6 hours
        case "startLevelChange":
            // The whole range over LEVEL_CHANGE_MS, so stopLevelChange on release stops it partway
            return [dimming_delta: [action: args[0] == "down" ? "down" : "up", brightness_delta: 100], dynamics: [duration: LEVEL_CHANGE_MS]]
        case "stopLevelChange":
            return [dimming_delta: [action: "stop"]]
        default:
            return null
    }
    if (!isOn) body.on = [on: true]
    return body
}

/** True when a requested level matches the current one: sending it again would be a superfluous Zigbee message. */
private static boolean sameLevel(def asked, def current) {
    current != null && Math.round(asked as double) == Math.round(current as double)
}

private static void withDuration(Map body, def seconds, Integer defaultMs) {
    Integer ms = seconds != null ? Math.round((seconds as double) * 1000) as Integer : defaultMs
    if (ms != null && ms >= 0) body.dynamics = [duration: ms]
}

/** Called by the Hue Bridge device when a command went through while the event stream is down, so state still updates. */
void assumeApplied(String rtype, String rid, Map body) {
    def d = getChildDevice(dni(rid))
    if (!d) return
    if (rtype == "light") d.parse(lightEvents(body, (res().lights?.get(rid) ?: [:]) as Map))
    else if (rtype == "grouped_light") d.parse(groupEvents(body))
}

// ---------------------------------------------------------------- conversions (pure)

static int levelFromBrightness(double b) { Math.max(1, Math.min(100, Math.round(b) as int)) }
static double brightnessFromLevel(double level) { Math.max(0d, Math.min(100d, level)) }
static int kelvinFromMirek(int mirek) { Math.round(1000000d / mirek) as int }

/** Kelvin to mirek, inside the light's range (Hue's limits 153-500 when the light gives none). */
static int mirekFromKelvin(int kelvin, Integer min, Integer max) {
    int m = Math.round(1000000d / Math.max(1, kelvin)) as int
    return Math.max(min ?: 153, Math.min(max ?: 500, m))
}

private static Map xyMap(List xy) { [x: Math.round(xy[0] * 10000) / 10000d, y: Math.round(xy[1] * 10000) / 10000d] }

/** Hubitat hue (0-100) and saturation (0-100) to CIE xy, using Hue's RGB-to-xy formulas, kept inside the gamut. */
static List hueSatToXy(double hue, double sat, Map gamut) {
    List rgb = hsvToRgb(hue * 3.6d, sat / 100d)
    List lin = rgb.collect { double c -> c > 0.04045d ? Math.pow((c + 0.055d) / 1.055d, 2.4d) : c / 12.92d }
    double r = lin[0], g = lin[1], b = lin[2]
    double X = r * 0.664511d + g * 0.154324d + b * 0.162028d
    double Y = r * 0.283881d + g * 0.668433d + b * 0.047685d
    double Z = r * 0.000088d + g * 0.072310d + b * 0.986039d
    double sum = X + Y + Z
    List xy = sum > 0 ? [X / sum, Y / sum] : [0.3227d, 0.329d]
    return clampToGamut(xy[0] as double, xy[1] as double, (gamut ?: GAMUTS.C) as Map)
}

/** CIE xy to Hubitat hue (0-100) and saturation (0-100), with Hue's xy-to-RGB formulas (full brightness). */
static List xyToHueSat(double x, double y) {
    if (y <= 0) return [0, 0]
    double Y = 1d, X = (Y / y) * x, Z = (Y / y) * (1d - x - y)
    List lin = [X * 1.656492d - Y * 0.354851d - Z * 0.255038d,
                -X * 0.707196d + Y * 1.655397d + Z * 0.036152d,
                X * 0.051713d - Y * 0.121364d + Z * 1.011530d].collect { Math.max(0d, it as double) }
    double peak = Math.max(lin[0] as double, Math.max(lin[1] as double, lin[2] as double))
    if (peak <= 0) return [0, 0]
    List rgb = lin.collect { double c ->
        double v = c / peak
        v <= 0.0031308d ? 12.92d * v : 1.055d * Math.pow(v, 1d / 2.4d) - 0.055d
    }
    double max = rgb.max() as double, min = rgb.min() as double, delta = max - min
    double h = 0
    if (delta > 0) {
        if (max == rgb[0]) h = 60d * ((((rgb[1] as double) - (rgb[2] as double)) / delta) % 6d)
        else if (max == rgb[1]) h = 60d * (((rgb[2] as double) - (rgb[0] as double)) / delta + 2d)
        else h = 60d * (((rgb[0] as double) - (rgb[1] as double)) / delta + 4d)
        if (h < 0) h += 360d
    }
    double s = max > 0 ? delta / max : 0
    return [Math.round(h / 3.6d) as int, Math.round(s * 100) as int]
}

private static List hsvToRgb(double h, double s) {
    double c = s, x = c * (1 - Math.abs(((h / 60d) % 2d) - 1)), m = 1 - c
    List rgb
    if (h < 60) rgb = [c, x, 0d] else if (h < 120) rgb = [x, c, 0d] else if (h < 180) rgb = [0d, c, x]
    else if (h < 240) rgb = [0d, x, c] else if (h < 300) rgb = [x, 0d, c] else rgb = [c, 0d, x]
    return rgb.collect { (it as double) + m }
}

/** Inside the triangle: unchanged. Outside: the closest point on its edges (Hue docs). */
static List clampToGamut(double x, double y, Map g) {
    List r = g.red as List, gr = g.green as List, b = g.blue as List
    if (inTriangle(x, y, r, gr, b)) return [x, y]
    List best = null
    double bestD = Double.MAX_VALUE
    [[r, gr], [gr, b], [b, r]].each { List e ->
        List p = closestOnSegment(e[0] as List, e[1] as List, x, y)
        double d = Math.pow((p[0] as double) - x, 2) + Math.pow((p[1] as double) - y, 2)
        if (d < bestD) { bestD = d; best = p }
    }
    return best
}

private static boolean inTriangle(double x, double y, List r, List g, List b) {
    double v1x = g[0] - r[0], v1y = g[1] - r[1], v2x = b[0] - r[0], v2y = b[1] - r[1], qx = x - r[0], qy = y - r[1]
    double den = v1x * v2y - v1y * v2x
    double s = (qx * v2y - qy * v2x) / den, t = (v1x * qy - v1y * qx) / den
    double eps = 1e-9   // points placed exactly on an edge count as inside despite rounding
    return s >= -eps && t >= -eps && s + t <= 1 + eps
}

private static List closestOnSegment(List a, List b, double x, double y) {
    double abx = b[0] - a[0], aby = b[1] - a[1]
    double t = ((x - a[0]) * abx + (y - a[1]) * aby) / (abx * abx + aby * aby)
    t = Math.max(0d, Math.min(1d, t))
    return [(a[0] as double) + abx * t, (a[1] as double) + aby * t]
}
