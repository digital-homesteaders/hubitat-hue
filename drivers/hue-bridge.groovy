/**
 * Hue Bridge (Hue Connect)
 *
 * The connection to one Hue bridge: the event stream (the source of truth), a small
 * check every 5 minutes, and the paced command queue. Hue Connect does the mapping;
 * this device only moves data. Design: docs/SPEC.md.
 *
 * Copyright (c) 2026 Digital Homesteaders. MIT licence:
 * https://github.com/digital-homesteaders/hubitat-hue/blob/main/LICENSE
 */

import groovy.json.JsonSlurper
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

@Field static final String DRIVER_NAME = "Hue Bridge"
@Field static final String DRIVER_VERSION = "0.1.0"

// Event stream (Hue: GET once, then events; no keepalive is sent)
@Field static final int CONNECT_TIMEOUT_S = 30
@Field static final int DISCONNECT_GRACE_S = 8           // a STOP is often followed by a quick recovery
@Field static final int REPLAY_MAX_GAP_S = 60            // longer gaps (or no event id): fetch all states once
@Field static final int QUIET_RECONNECT_S = 1800         // a stream silent this long is reconnected (Last-Event-ID)
@Field static final int RECONNECT_MAX_S = 900

// Command pacing (Hue system performance guide; aiohue): lights 100 ms apart, groups 1 s, at most 3 in flight
@Field static final Map SPACING_MS = [light: 100L, grouped_light: 1000L]
@Field static final int MAX_IN_FLIGHT = 3
@Field static final int MAX_RETRIES = 8

// Per bridge device: the receive buffer, the command queue, requests in flight and last send per kind
@Field static final ConcurrentHashMap MEM = new ConcurrentHashMap()

metadata {
    definition(name: "Hue Bridge", namespace: "hue", author: "Digital Homesteaders", singleThreaded: true,
               importUrl: "https://raw.githubusercontent.com/digital-homesteaders/hubitat-hue/main/drivers/hue-bridge.groovy") {
        capability "Initialize"
        capability "Refresh"
        attribute "status", "enum", ["online", "offline", "unauthorized"]
        attribute "eventStream", "enum", ["connected", "disconnected"]
        attribute "healthStatus", "enum", ["online", "offline"]
        command "reconnect"
    }
    preferences {
        input "logEnable", "bool", title: "Debug logging (turns off after 30 minutes)", defaultValue: false
    }
}

// ---------------------------------------------------------------- lifecycle

void installed() { initialize() }

void updated() {
    if (logEnable) runIn(1800, "logsOff")
    initialize()
}

void initialize() {
    unschedule("reconnect")
    unschedule("streamTimeout")
    if (state.streamOpen) state.lastAliveAt = now()   // a deliberate close isn't an outage: the reconnect replays
    closeStream()
    mem().queue = []
    mem().inFlight = 0
    runEvery5Minutes("checkBridge")
    runIn(2, "connectStream")
}

void logsOff() { device.updateSetting("logEnable", [value: "false", type: "bool"]) }

void refresh() { parent?.refreshAll() }

void reconnect() {
    state.retryS = null
    connectStream()
}

private Map mem() {
    Long id = device.idAsLong
    Map m = MEM.get(id)
    if (m == null) {
        MEM.putIfAbsent(id, [buf: "", queue: [], inFlight: 0, lastSent: [:]])
        m = MEM.get(id)
    }
    return m
}

// ---------------------------------------------------------------- event stream

void connectStream() {
    Map c = parent?.bridgeConnection()
    if (!c?.key) return
    state.wanted = true
    state.connectPending = true
    runIn(CONNECT_TIMEOUT_S, "streamTimeout")
    closeStream()
    mem().buf = ""
    Map headers = ["Accept": "text/event-stream", "hue-application-key": c.key]
    if (state.lastEventId) headers["Last-Event-ID"] = state.lastEventId as String   // the bridge replays what was missed
    logDebug "connecting to the event stream${state.lastEventId ? ' (resuming)' : ''}"
    try {
        interfaces.eventStream.connect("https://${c.ip}/eventstream/clip/v2", [headers: headers, rawData: true,
                                       readTimeout: 0, ignoreSSLIssues: true])
    } catch (e) {
        log.warn "${device.displayName}: event stream connect failed: ${e.message}"
        streamDown()
    }
}

private void closeStream() {
    try { interfaces.eventStream.close() } catch (ignored) { }
}

void eventStreamStatus(String message) {
    logDebug "event stream: ${message}"
    if (message.startsWith("START")) {
        streamUp()
    } else if (message.startsWith("STOP") || message.startsWith("ERROR")) {
        // While connecting, this is usually the old stream closing; the connect timeout covers a real failure.
        if (state.connectPending) return
        runIn(DISCONNECT_GRACE_S, "streamDown")
    }
}

void streamTimeout() { if (state.connectPending) streamDown() }

private void streamUp() {
    unschedule("streamDown")
    unschedule("streamTimeout")
    if (state.connectPending) {
        state.connectPending = false
        state.retryS = null
        long gapMs = now() - ((state.lastAliveAt ?: 0L) as long)
        if (!state.lastEventId || gapMs > REPLAY_MAX_GAP_S * 1000L) {
            logDebug "stream back after ${gapMs.intdiv(1000)} s${state.lastEventId ? '' : ' (nothing to resume from)'}; reading all states"
            parent?.refreshAll()
        }
    }
    state.lastAliveAt = now()
    if (!state.streamOpen) {
        state.streamOpen = true
        sendEvent(name: "eventStream", value: "connected")
    }
}

void streamDown() {
    unschedule("streamTimeout")
    state.connectPending = false
    if (state.streamOpen) {
        state.streamOpen = false
        sendEvent(name: "eventStream", value: "disconnected")
    }
    if (state.wanted == false) return
    int wait = Math.min(RECONNECT_MAX_S, state.retryS ? (state.retryS as int) * 2 : 5)
    state.retryS = wait
    logDebug "reconnecting the event stream in ${wait} s"
    runIn(wait, "reconnectStream")
}

void reconnectStream() { connectStream() }

/** Raw event-stream text: complete messages end with a blank line; a partial one waits for the rest. */
void parse(String raw) {
    if (state.wanted == false) return
    streamUp()
    state.lastDataAt = now()
    Map m = mem()
    String text = (m.buf as String) + raw.replace("\r\n", "\n")
    int end = text.lastIndexOf("\n\n")
    if (end < 0) { m.buf = text; return }
    m.buf = text.substring(end + 2)
    text.substring(0, end).split("\n\n").each { String message ->
        StringBuilder data = new StringBuilder()
        message.split("\n").each { String line ->
            if (line.startsWith("id:")) state.lastEventId = line.substring(3).trim()
            else if (line.startsWith("data:")) data << line.substring(5).trim()
        }
        if (!data) return
        def containers
        try {
            containers = new JsonSlurper().parseText(data.toString())
        } catch (e) {
            log.warn "${device.displayName}: unreadable event data (${data.length()} characters)"
            return
        }
        (containers instanceof List ? containers : [containers]).each { c ->
            if (c instanceof Map && c.data instanceof List) parent?.hueEvents(c.type as String, c.data as List)
        }
    }
}

// ---------------------------------------------------------------- bridge check (every 5 minutes)

/** One small request: is the bridge there, does it still accept the key, is it the same bridge? */
void checkBridge() {
    Map c = parent?.bridgeConnection()
    if (!c?.key) return
    asynchttpGet("checkDone", [uri: "https://${c.ip}", path: "/clip/v2/resource/bridge", headers: ["hue-application-key": c.key],
                               contentType: "application/json", timeout: 10, ignoreSSLIssues: true])
}

void checkDone(resp, Map data) {
    Integer code = resp?.status as Integer
    if (code == 401 || code == 403) {
        if (device.currentValue("status") != "unauthorized") {
            log.error "${device.displayName}: the bridge no longer accepts Hue Connect's key (HTTP ${code}). Open Hue Connect and link again."
            setStatus("unauthorized")
        }
        return
    }
    List items = (code == 200 && resp.json?.data instanceof List) ? resp.json.data as List : null
    if (!items) {
        setStatus("offline")
        if (state.streamOpen) streamDown()   // an open-looking stream to a bridge that doesn't answer is dead
        return
    }
    String id = (items[0]?.bridge_id as String)?.toLowerCase()
    String expected = parent?.bridgeConnection()?.id as String
    if (expected && id != expected) {
        log.error "${device.displayName}: a different bridge (${id}) answers at this address, not ${expected}"
        setStatus("offline")
        return
    }
    setStatus("online")
    long quietMs = now() - ((state.lastDataAt ?: state.lastAliveAt ?: now()) as long)
    if (state.streamOpen && quietMs > QUIET_RECONNECT_S * 1000L) {
        logDebug "no events for ${quietMs.intdiv(60000)} min; reconnecting to be sure the stream is alive"
        state.lastAliveAt = now()
        state.lastDataAt = now()
        connectStream()
    }
}

private void setStatus(String s) {
    if (device.currentValue("status") != s) sendEvent(name: "status", value: s)
    String health = s == "online" ? "online" : "offline"
    if (device.currentValue("healthStatus") != health) sendEvent(name: "healthStatus", value: health)
}

// ---------------------------------------------------------------- command queue

/** Queue a PUT. A newer command for the same resource merges into one still waiting (a slider being dragged). */
void enqueue(String rtype, String rid, Map body) {
    Map m = mem()
    List q = m.queue as List
    Map waiting = q.find { it.rtype == rtype && it.rid == rid && !it.notBefore } as Map
    if (waiting) waiting.body = merge(waiting.body as Map, body)
    else q << [rtype: rtype, rid: rid, body: body, attempts: 0]
    pump()
}

private static Map merge(Map a, Map b) {
    Map out = new LinkedHashMap(a)
    b.each { k, v -> out[k] = v }
    if (b.containsKey("dimming_delta")) out.remove("dimming")       // a level change replaces a set level
    if (b.containsKey("dimming")) out.remove("dimming_delta")
    return out
}

/** Send what the pacing allows now; come back for the rest. */
void pump() {
    Map m = mem()
    List q = m.queue as List
    long wake = 0L
    while ((m.inFlight as int) < MAX_IN_FLIGHT && q) {
        long t = now()
        Map next = q.find { Map e -> t >= readyAt(m, e) } as Map
        if (!next) {
            wake = (q.collect { readyAt(m, it as Map) }.min() as long) - t
            break
        }
        q.remove(next)
        send(m, next)
    }
    if (wake > 0) runInMillis(Math.max(20L, wake), "pump")
}

private static long readyAt(Map m, Map e) {
    long spacing = (SPACING_MS[e.rtype] ?: 100L) as long
    long last = ((m.lastSent as Map)[e.rtype] ?: 0L) as long
    return Math.max(last + spacing, (e.notBefore ?: 0L) as long)
}

private void send(Map m, Map e) {
    Map c = parent?.bridgeConnection()
    if (!c?.key) return
    m.inFlight = (m.inFlight as int) + 1
    (m.lastSent as Map)[e.rtype] = now()
    logDebug "PUT ${e.rtype}/${e.rid} ${e.body}"
    asynchttpPut("putDone", [uri: "https://${c.ip}", path: "/clip/v2/resource/${e.rtype}/${e.rid}",
                             headers: ["hue-application-key": c.key], contentType: "application/json",
                             requestContentType: "application/json", body: e.body, timeout: 10, ignoreSSLIssues: true],
                 [rtype: e.rtype, rid: e.rid, body: e.body, attempts: e.attempts])
}

void putDone(resp, Map data) {
    Map m = mem()
    m.inFlight = Math.max(0, (m.inFlight as int) - 1)
    Integer code = resp?.status as Integer
    if (code == 200 || code == 207) {
        if (code == 207) log.warn "${device.displayName}: ${data.rtype} command partly applied: ${errorText(resp)}"
        if (!state.streamOpen) parent?.assumeApplied(data.rtype as String, data.rid as String, data.body as Map)
    } else if (code == 401 || code == 403) {
        setStatus("unauthorized")
    } else if (code == 429 || code == 503 || code == null || code == 408) {
        int attempts = ((data.attempts ?: 0) as int) + 1
        if (attempts <= MAX_RETRIES) {
            // Busy or rate-limited: try again a little later (0.25 s x attempt), ahead of newer commands
            (m.queue as List).add(0, [rtype: data.rtype, rid: data.rid, body: data.body, attempts: attempts, notBefore: now() + 250L * attempts])
        } else {
            log.warn "${device.displayName}: ${data.rtype} command dropped after ${MAX_RETRIES} retries (HTTP ${code})"
        }
    } else {
        log.warn "${device.displayName}: ${data.rtype} command refused (HTTP ${code}): ${errorText(resp)}"
    }
    pump()
}

private static String errorText(resp) {
    try {
        def errors = resp?.json?.errors
        return errors ? errors.collect { it.description }.join("; ") : ""
    } catch (ignored) {
        return ""
    }
}

private void logDebug(String msg) { if (logEnable) log.debug "${device.displayName}: ${msg}" }
