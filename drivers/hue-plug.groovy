/**
 * Hue Plug (Hue Connect)
 *
 * A Hue smart plug or other on/off-only light service. Thin by design: commands go to Hue Connect, which builds Hue's
 * request and queues it on the Hue Bridge device; state comes back from the
 * bridge's event stream through parse(). Design: docs/SPEC.md.
 *
 * Copyright (c) 2026 Digital Homesteaders. MIT licence:
 * https://github.com/digital-homesteaders/hubitat-hue/blob/main/LICENSE
 */

import groovy.transform.Field

@Field static final String DRIVER_NAME = "Hue Plug"
@Field static final String DRIVER_VERSION = "0.1.0"

metadata {
    definition(name: "Hue Plug", namespace: "hue", author: "Digital Homesteaders",
               importUrl: "https://raw.githubusercontent.com/digital-homesteaders/hubitat-hue/main/drivers/hue-plug.groovy") {
        capability "Actuator"
        capability "Outlet"
        capability "Switch"
        capability "Refresh"
        attribute "powerUp", "enum", ["previous", "lastOnState", "safety", "off", "custom"]
        command "setPowerUp", [[name: "Behavior*", type: "ENUM", constraints: ["previous", "lastOnState", "safety", "off"],
                                description: "What the light does when power returns"]]
        attribute "healthStatus", "enum", ["online", "offline"]
    }
    preferences {
        input "txtEnable", "bool", title: "Log changes", defaultValue: true
    }
}

void installed() { }
void updated() { }

/** Events from Hue Connect. */
void parse(List<Map> events) {
    events.each { Map e ->
        if (txtEnable && device.currentValue(e.name as String)?.toString() != e.value?.toString()) {
            log.info "${device.displayName} ${e.name} is ${e.value}${e.unit && e.unit != '%' ? ' ' + e.unit : (e.unit ?: '')}"
        }
        sendEvent(e)
    }
}

private void toHue(String command, List args = []) { parent?.hueLightCommand(device, command, args, null) }

void on() { toHue("on") }
void off() { toHue("off") }
void refresh() { toHue("refresh") }
/** What this light does when power returns: previous state, last on state, safety (warm white) or off. */
void setPowerUp(String behavior) { toHue("setPowerUp", [behavior]) }
