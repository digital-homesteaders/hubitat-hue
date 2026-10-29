/**
 * Hue Contact Sensor (Hue Connect)
 *
 * A Hue Secure contact sensor: contact and tamper. Thin by design: Hue Connect maps the bridge's events; this driver
 * only shows them. Design: docs/SPEC.md.
 *
 * Copyright (c) 2026 Digital Homesteaders. MIT licence:
 * https://github.com/digital-homesteaders/hubitat-hue/blob/main/LICENSE
 */

import groovy.transform.Field

@Field static final String DRIVER_NAME = "Hue Contact Sensor"
@Field static final String DRIVER_VERSION = "0.1.0"

metadata {
    definition(name: "Hue Contact Sensor", namespace: "hue", author: "Digital Homesteaders",
               importUrl: "https://raw.githubusercontent.com/digital-homesteaders/hubitat-hue/main/drivers/hue-contact-sensor.groovy") {
        capability "Sensor"
        capability "ContactSensor"
        capability "TamperAlert"
        capability "Battery"
        capability "Refresh"
        attribute "batteryState", "enum", ["normal", "low", "critical"]
        attribute "healthStatus", "enum", ["online", "offline"]
    }
    preferences {
        input "txtEnable", "bool", title: "Log changes", defaultValue: true
    }
}

void installed() { }
void updated() { }
void refresh() { parent?.requestRefresh() }

/** Events from Hue Connect. */
void parse(List<Map> events) {
    events.each { Map e ->
        if (txtEnable && device.currentValue(e.name as String)?.toString() != e.value?.toString()) {
            log.info "${device.displayName} ${e.name} is ${e.value}${e.unit && e.unit != '%' ? ' ' + e.unit : (e.unit ?: '')}"
        }
        sendEvent(e)
    }
}
