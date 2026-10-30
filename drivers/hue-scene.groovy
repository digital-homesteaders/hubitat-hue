/**
 * Hue Scene (Hue Connect)
 *
 * One Hue scene or smart scene. Push (or on) recalls it; off turns its room or
 * zone off (a smart scene is deactivated). The switch shows whether the scene is
 * active. Thin by design: Hue Connect does the mapping. Design: docs/SPEC.md.
 *
 * Copyright (c) 2026 Digital Homesteaders. MIT licence:
 * https://github.com/digital-homesteaders/hubitat-hue/blob/main/LICENSE
 */

import groovy.transform.Field

@Field static final String DRIVER_NAME = "Hue Scene"
@Field static final String DRIVER_VERSION = "0.1.0"

metadata {
    definition(name: "Hue Scene", namespace: "hue", author: "Digital Homesteaders",
               importUrl: "https://raw.githubusercontent.com/digital-homesteaders/hubitat-hue/main/drivers/hue-scene.groovy") {
        capability "Actuator"
        capability "Switch"
        capability "PushableButton"
        capability "Refresh"
        attribute "speed", "number"
        command "setSpeed", [[name: "Speed*", type: "NUMBER", description: "0-100 %, for a scene with a palette"]]
    }
    preferences {
        input "dynamic", "bool", title: "Play dynamically (scenes with a palette)", defaultValue: false
        input "txtEnable", "bool", title: "Log changes", defaultValue: true
    }
}

void installed() { sendEvent(name: "numberOfButtons", value: 1) }
void updated() { }

/** Events from Hue Connect. */
void parse(List<Map> events) {
    events.each { Map e ->
        if (txtEnable && device.currentValue(e.name as String)?.toString() != e.value?.toString()) {
            if (e.name == "switch") log.info "${device.displayName} is ${e.value == 'on' ? 'active' : 'not active'}"
            else log.info "${device.displayName} ${e.name} is ${e.value}${e.unit ?: ''}"
        }
        sendEvent(e)
    }
}

void push(buttonNumber = 1) {
    sendEvent(name: "pushed", value: 1, isStateChange: true, type: "digital")
    parent?.hueSceneCommand(device, "on", settings.dynamic == true)
}
void on() { parent?.hueSceneCommand(device, "on", settings.dynamic == true) }
void off() { parent?.hueSceneCommand(device, "off") }
void refresh() { parent?.hueSceneCommand(device, "refresh") }
/** How fast a dynamic scene plays its palette (0-100 %). Only scenes with a palette have one. */
void setSpeed(speed) { parent?.hueSceneSpeed(device, speed) }
