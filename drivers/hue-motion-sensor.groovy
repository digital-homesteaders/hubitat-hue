/**
 * Hue Motion Sensor (Hue Connect)
 *
 * A Hue motion sensor: motion, light level (lux) and temperature. Thin by design: Hue Connect maps the bridge's events; this driver
 * only shows them. Design: docs/SPEC.md.
 *
 * Copyright (c) 2026 Digital Homesteaders. MIT licence:
 * https://github.com/digital-homesteaders/hubitat-hue/blob/main/LICENSE
 */

import groovy.transform.Field

@Field static final String DRIVER_NAME = "Hue Motion Sensor"
@Field static final String DRIVER_VERSION = "0.1.0"

metadata {
    definition(name: "Hue Motion Sensor", namespace: "hue", author: "Digital Homesteaders",
               importUrl: "https://raw.githubusercontent.com/digital-homesteaders/hubitat-hue/main/drivers/hue-motion-sensor.groovy") {
        capability "Sensor"
        capability "MotionSensor"
        capability "IlluminanceMeasurement"
        capability "TemperatureMeasurement"
        capability "Battery"
        capability "Refresh"
        attribute "batteryState", "enum", ["normal", "low", "critical"]
        attribute "healthStatus", "enum", ["online", "offline"]
        attribute "motionDetection", "enum", ["enabled", "disabled"]
        attribute "sensitivity", "number"
        command "enableMotionDetection"
        command "disableMotionDetection"
        command "setSensitivity", [[name: "Sensitivity*", type: "NUMBER", description: "0 (lowest) to the sensor's maximum (usually 4)"]]
    }
    preferences {
        input "txtEnable", "bool", title: "Log changes", defaultValue: true
    }
}

void installed() { }
void updated() { }
void refresh() { parent?.hueSensorCommand(device, "refresh") }

/** Events from Hue Connect. */
void parse(List<Map> events) {
    events.each { Map e ->
        if (txtEnable && device.currentValue(e.name as String)?.toString() != e.value?.toString()) {
            log.info "${device.displayName} ${e.name} is ${e.value}${e.unit && e.unit != '%' ? ' ' + e.unit : (e.unit ?: '')}"
        }
        sendEvent(e)
    }
}

/** Motion detection on or off on the sensor itself (also stops Hue's own automations using it). */
void enableMotionDetection() { parent?.hueSensorCommand(device, "enableMotionDetection") }
void disableMotionDetection() { parent?.hueSensorCommand(device, "disableMotionDetection") }
void setSensitivity(sensitivity) { parent?.hueSensorCommand(device, "setSensitivity", [sensitivity]) }
