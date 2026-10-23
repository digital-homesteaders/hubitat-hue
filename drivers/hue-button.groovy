/**
 * Hue Button (Hue Connect)
 *
 * A Hue remote: Dimmer switch, Smart button, Tap Dial, wall switch module or Friends of Hue switch. Buttons are numbered as on the device; a Tap Dial's ring is buttons 5 (clockwise) and 6 (anticlockwise), with the degrees turned in rotation. Thin by design: Hue Connect maps the bridge's events; this driver
 * only shows them. Design: docs/SPEC.md.
 *
 * Copyright (c) 2026 Digital Homesteaders. MIT licence:
 * https://github.com/digital-homesteaders/hubitat-hue/blob/main/LICENSE
 */

import groovy.transform.Field

@Field static final String DRIVER_NAME = "Hue Button"
@Field static final String DRIVER_VERSION = "0.1.0"

metadata {
    definition(name: "Hue Button", namespace: "hue", author: "Digital Homesteaders",
               importUrl: "https://raw.githubusercontent.com/digital-homesteaders/hubitat-hue/main/drivers/hue-button.groovy") {
        capability "Sensor"
        capability "PushableButton"
        capability "HoldableButton"
        capability "ReleasableButton"
        capability "DoubleTapableButton"
        capability "Battery"
        capability "Refresh"
        attribute "batteryState", "enum", ["normal", "low", "critical"]
        attribute "healthStatus", "enum", ["online", "offline"]
        attribute "rotation", "number"
    }
    preferences {
        input "repeatHeld", "bool", title: "Repeat \"held\" while a button is held (about every 0.8 s)", defaultValue: false
        input "txtEnable", "bool", title: "Log changes", defaultValue: true
    }
}

void installed() { }
void updated() { }
void refresh() { parent?.refreshAll() }

/** Events from Hue Connect. Button events are always state changes; "held" repeats while held only if asked. */
void parse(List<Map> events) {
    events.each { Map e ->
        if (e.repeat) {
            if (settings.repeatHeld != true) return
            e = e.findAll { k, v -> k != "repeat" }
        }
        if (txtEnable && e.name in ["pushed", "held", "released", "doubleTapped"]) {
            log.info "${device.displayName} button ${e.value} was ${e.name == 'doubleTapped' ? 'double-tapped' : e.name}"
        } else if (txtEnable && e.name != "rotation" && device.currentValue(e.name as String)?.toString() != e.value?.toString()) {
            log.info "${device.displayName} ${e.name} is ${e.value}"
        }
        sendEvent(e)
    }
}

// Pressing a button from Hubitat (dashboards, rules): records the event like Hubitat's own button devices.
void push(buttonNumber) { sendEvent(name: "pushed", value: buttonNumber as int, isStateChange: true, type: "digital") }
void hold(buttonNumber) { sendEvent(name: "held", value: buttonNumber as int, isStateChange: true, type: "digital") }
void release(buttonNumber) { sendEvent(name: "released", value: buttonNumber as int, isStateChange: true, type: "digital") }
void doubleTap(buttonNumber) { sendEvent(name: "doubleTapped", value: buttonNumber as int, isStateChange: true, type: "digital") }
