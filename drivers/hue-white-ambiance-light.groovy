/**
 * Hue White Ambiance Light (Hue Connect)
 *
 * A Hue light with colour temperature, no colour. Thin by design: commands go to Hue Connect, which builds Hue's
 * request and queues it on the Hue Bridge device; state comes back from the
 * bridge's event stream through parse(). Design: docs/SPEC.md.
 *
 * Copyright (c) 2026 Digital Homesteaders. MIT licence:
 * https://github.com/digital-homesteaders/hubitat-hue/blob/main/LICENSE
 */

import groovy.transform.Field

@Field static final String DRIVER_NAME = "Hue White Ambiance Light"
@Field static final String DRIVER_VERSION = "0.1.0"

metadata {
    definition(name: "Hue White Ambiance Light", namespace: "hue", author: "Digital Homesteaders",
               importUrl: "https://raw.githubusercontent.com/digital-homesteaders/hubitat-hue/main/drivers/hue-white-ambiance-light.groovy") {
        capability "Actuator"
        capability "Light"
        capability "Switch"
        capability "SwitchLevel"
        capability "ChangeLevel"
        capability "ColorTemperature"
        capability "Refresh"
        capability "LightEffects"
        capability "Flash"
        attribute "hueMode", "enum", ["normal", "streaming"]
        command "identify"
        command "startTimedEffect", [[name: "Effect*", type: "ENUM", constraints: ["sunrise", "sunset"]],
                                     [name: "Minutes", type: "NUMBER", description: "1-360, default 30"]]
        attribute "powerUp", "enum", ["previous", "lastOnState", "safety", "off", "custom"]
        command "setPowerUp", [[name: "Behavior*", type: "ENUM", constraints: ["previous", "lastOnState", "safety", "off"],
                                description: "What the light does when power returns"]]
        command "startColorTemperatureChange", [[name: "Direction*", type: "ENUM", constraints: ["warmer", "cooler"]]]
        command "stopColorTemperatureChange"
        attribute "healthStatus", "enum", ["online", "offline"]
    }
    preferences {
        input "transitionTime", "enum", title: "Transition time", defaultValue: "400",
            options: ["0": "Instant", "200": "0.2 s", "400": "0.4 s (Hue's default)", "1000": "1 s", "2000": "2 s", "5000": "5 s"]
        input "flashSeconds", "enum", title: "Flash for", defaultValue: "10", options: ["5": "5 s", "10": "10 s", "30": "30 s", "60": "1 min"]
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

/** Flash: Hue's V2 signalling for the time set in preferences (Hue picks the rate). */
void flash(rateToggle = null) { toHue("flash", [((settings.flashSeconds ?: "10") as int) * 1000]) }
/** One breathe cycle, to find which light this is. */
void identify() { toHue("identify") }
/** This light's own effects (see lightEffects); 0 stops the effect. */
void setEffect(effectNumber) { toHue("setEffect", [effectNumber]) }
void setNextEffect() { toHue("setNextEffect") }
void setPreviousEffect() { toHue("setPreviousEffect") }
/** Sunrise or sunset, run by the light itself over the given minutes (only if the light offers it). */
void startTimedEffect(String effect, minutes = 30) { toHue("startTimedEffect", [effect, minutes]) }
/** What this light does when power returns: previous state, last on state, safety (warm white) or off. */
void setPowerUp(String behavior) { toHue("setPowerUp", [behavior]) }
/** Ramp the colour temperature warmer or cooler until stopColorTemperatureChange (or the light's limit). */
void startColorTemperatureChange(String direction) { toHue("startColorTemperatureChange", [direction]) }
void stopColorTemperatureChange() { toHue("stopColorTemperatureChange") }
