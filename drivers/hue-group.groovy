/**
 * Hue Group (Hue Connect)
 *
 * A Hue room or zone (its grouped_light): every light in it changes together in
 * one Zigbee broadcast. Its scenes are the light effects, so setEffect recalls
 * one. "on" means any light in it is on; level is the average of the lights that
 * are on. Thin by design: Hue Connect does the mapping. Design: docs/SPEC.md.
 *
 * Copyright (c) 2026 Digital Homesteaders. MIT licence:
 * https://github.com/digital-homesteaders/hubitat-hue/blob/main/LICENSE
 */

import groovy.transform.Field

@Field static final String DRIVER_NAME = "Hue Group"
@Field static final String DRIVER_VERSION = "0.1.0"

metadata {
    definition(name: "Hue Group", namespace: "hue", author: "Digital Homesteaders",
               importUrl: "https://raw.githubusercontent.com/digital-homesteaders/hubitat-hue/main/drivers/hue-group.groovy") {
        capability "Actuator"
        capability "Light"
        capability "Switch"
        capability "SwitchLevel"
        capability "ChangeLevel"
        capability "ColorTemperature"
        capability "ColorControl"
        capability "LightEffects"
        capability "Refresh"
        command "startColorTemperatureChange", [[name: "Direction*", type: "ENUM", constraints: ["warmer", "cooler"]]]
        command "stopColorTemperatureChange"
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
        if (txtEnable && e.name != "lightEffects" && device.currentValue(e.name as String)?.toString() != e.value?.toString()) {
            log.info "${device.displayName} ${e.name} is ${e.value}${e.unit && e.unit != '%' ? ' ' + e.unit : (e.unit ?: '')}"
        }
        sendEvent(e)
    }
}

private void toHue(String command, List args = []) { parent?.hueGroupCommand(device, command, args, (settings.transitionTime ?: "400") as Integer) }

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

/** Recall the room's scene with this number (see lightEffects). */
void setEffect(effectNumber) { toHue("setEffect", [effectNumber]) }
void setNextEffect() { toHue("setNextEffect") }
void setPreviousEffect() { toHue("setPreviousEffect") }
/** Ramp the colour temperature warmer or cooler until stopColorTemperatureChange (or the light's limit). */
void startColorTemperatureChange(String direction) { toHue("startColorTemperatureChange", [direction]) }
void stopColorTemperatureChange() { toHue("stopColorTemperatureChange") }
