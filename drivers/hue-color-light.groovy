/**
 * Hue Color Light (Hue Connect)
 *
 * A Hue light with colour and colour temperature. Thin by design: commands go to Hue Connect, which builds Hue's
 * request and queues it on the Hue Bridge device; state comes back from the
 * bridge's event stream through parse(). Design: docs/SPEC.md.
 *
 * Copyright (c) 2026 Digital Homesteaders. MIT licence:
 * https://github.com/digital-homesteaders/hubitat-hue/blob/main/LICENSE
 */

import groovy.transform.Field

@Field static final String DRIVER_NAME = "Hue Color Light"
@Field static final String DRIVER_VERSION = "0.1.0"

metadata {
    definition(name: "Hue Color Light", namespace: "hue", author: "Digital Homesteaders",
               importUrl: "https://raw.githubusercontent.com/digital-homesteaders/hubitat-hue/main/drivers/hue-color-light.groovy") {
        capability "Actuator"
        capability "Light"
        capability "Switch"
        capability "SwitchLevel"
        capability "ChangeLevel"
        capability "ColorTemperature"
        capability "ColorControl"
        capability "ColorMode"
        capability "Refresh"
        attribute "healthStatus", "enum", ["online", "offline"]
    }
    preferences {
        input "transitionTime", "enum", title: "Transition time", defaultValue: "400",
            options: ["0": "Instant", "200": "0.2 s", "400": "0.4 s (Hue's default)", "1000": "1 s", "2000": "2 s", "5000": "5 s"]
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

private void toHue(String command, List args = []) { parent?.hueLightCommand(device, command, args, (settings.transitionTime ?: "400") as Integer) }

void on() { toHue("on") }
void off() { toHue("off") }
void refresh() { toHue("refresh") }

void setLevel(level, duration = null) { toHue("setLevel", [level, duration]) }
void startLevelChange(String direction) { toHue("startLevelChange", [direction]) }
void stopLevelChange() { toHue("stopLevelChange") }
void setColorTemperature(temperature, level = null, transitionTime = null) { toHue("setColorTemperature", [temperature, level, transitionTime]) }
void setColor(Map color) { toHue("setColor", [color]) }
void setHue(hue) { toHue("setHue", [hue]) }
void setSaturation(saturation) { toHue("setSaturation", [saturation]) }
