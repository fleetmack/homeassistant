/**
 * Tuya ZG-303Z / TS0601 Soil Moisture Sensor
 * Author  : George Gilman
 * Version : 1.0
 *
 * Confirmed DP map (from live device logs):
 *   DP  5  (0x05) = Soil Moisture  — raw / 10 = %
 *   DP  3  (0x03) = Battery Voltage — raw × 10 = mV  (approx, for reference only)
 *   DP 15  (0x0F) = Battery %
 *   DP 109 (0x6D) = Air Humidity   — raw = %
 *
 * Standard Zigbee clusters (little-endian int16):
 *   0x0402 = Air Temperature  — raw / 100 = °C
 *   0x0405 = Air Humidity     — raw / 100 = %  (used when available)
 */

metadata {
    definition(name: "Tuya ZG-303Z Soil Sensor", namespace: "ggilman", author: "George Gilman") {
        capability "Battery"
        capability "Refresh"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "Configuration"
        capability "HealthCheck"

        attribute "healthStatus",   "enum", ["unknown", "offline", "online"]
        attribute "soilMoisture",   "number"
        attribute "airHumidity",    "number"
        attribute "batteryVoltage", "number"

        fingerprint profileId: "0104", endpointId: "01",
                    inClusters:  "0004,0005,EF00,0000,ED00",
                    outClusters: "0019,000A",
                    model: "TS0601", manufacturer: "_TZE284_sgabhwa6"
    }

    preferences {
        input name: "temperatureOffset",  type: "decimal", title: "Temperature offset (°)",  defaultValue: 0.0, range: "-100.0..100.0"
        input name: "soilMoistureOffset", type: "decimal", title: "Soil Moisture offset (%)", defaultValue: 0.0, range: "-100.0..100.0"
        input name: "airHumidityOffset",  type: "decimal", title: "Air Humidity offset (%)",  defaultValue: 0.0, range: "-100.0..100.0"
        input name: "logEnable",          type: "bool",    title: "Enable debug logging",     defaultValue: false
        input name: "txtEnable",          type: "bool",    title: "Enable info logging",      defaultValue: true
    }
}

// ── Lifecycle ────────────────────────────────────────────────────────────────

void installed() {
    log.info "ZG-303Z installed"
    initializeVars()
}

void updated() {
    log.info "ZG-303Z preferences saved"
}

void configure() {
    if (logEnable) log.debug "ZG-303Z configuring..."
    List<String> cmds = []
    cmds += zigbee.readAttribute(0x0000, [0x0001, 0x0004, 0x0005])
    cmds += zigbee.readAttribute(0x0402, 0x0000)
    cmds += zigbee.readAttribute(0x0405, 0x0000)
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
    scheduleDeviceHealthCheck()
    log.info "Configuration commands sent."
}

void refresh() {
    if (logEnable) log.debug "ZG-303Z refreshing..."
    List<String> cmds = []
    cmds += zigbee.readAttribute(0x0402, 0x0000)
    cmds += zigbee.readAttribute(0x0405, 0x0000)
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
    log.info "Refresh command sent."
}

void ping() {
    if (logEnable) log.debug "ping → refresh"
    refresh()
}

// ── Parser ───────────────────────────────────────────────────────────────────

void parse(String description) {
    if (logEnable) log.debug "PARSE: ${description}"
    setPresent()

    try {
        Map msg = zigbee.parseDescriptionAsMap(description)
        String cluster = (msg?.cluster ?: msg?.clusterId ?: "")

        switch (cluster) {
            case "EF00":
                if (msg?.data) processTuyaEF00(msg.data)
                break
            case "0402":
                if (msg?.value) handleTemp(msg.value)
                break
            case "0405":
                if (msg?.value) handleHumidity(msg.value)
                break
            case "0001":
                // Battery percentage remaining (attrId 0x0021), Zigbee reports 0–200 scale
                if (msg?.attrId == "0021" && msg?.value) {
                    int pct = Math.min((int)(hexToInt(msg.value) / 2), 100)
                    sendEvent(name: "battery", value: pct, unit: "%")
                    if (txtEnable) log.info "Battery (cluster 0001): ${pct}%"
                }
                break
            default:
                if (logEnable) log.debug "Unhandled cluster: ${cluster}"
        }
    } catch (Exception e) {
        log.error "parse() exception: ${e.message}"
    }
}

// ── Tuya EF00 ────────────────────────────────────────────────────────────────

private void processTuyaEF00(List data) {
    List<Integer> bytes = data.collect { toInt(it) }
    if (logEnable) log.debug "EF00 bytes: ${bytes.collect { String.format('%02x', it) }.join(' ')}"

    int i = 2   // skip 2-byte sequence number
    while (i + 4 <= bytes.size()) {
        int dp         = bytes[i]
        int dataType   = bytes[i + 1]
        int dataLength = (bytes[i + 2] << 8) | bytes[i + 3]

        if (i + 4 + dataLength > bytes.size()) {
            log.warn "EF00: not enough bytes for DP ${dp}, stopping"
            break
        }

        List<Integer> valueBytes = bytes.subList(i + 4, i + 4 + dataLength)
        long rawVal = parseTuyaLong(valueBytes)

        if (logEnable) log.debug "EF00 DP=${dp} (0x${String.format('%02x', dp)}) raw=${rawVal}"

        switch (dp) {
            case 5:    // Soil Moisture — tenths of a percent (e.g. 235 = 23.5%)
                double sm = rawVal / 10.0
                if (sm > 100) sm = rawVal / 100.0
                sm = clamp(roundTo1(sm + safeDouble(settings.soilMoistureOffset)), 0, 100)
                sendEvent(name: "soilMoisture", value: sm, unit: "%")
                sendEvent(name: "humidity",     value: sm, unit: "%")
                if (txtEnable) log.info "Soil Moisture: ${sm}%"
                break

            case 3:    // Battery voltage (raw × 10 = mV, approximate)
                double volts = roundTo2(rawVal * 10 / 1000.0)
                sendEvent(name: "batteryVoltage", value: volts, unit: "V")
                if (txtEnable) log.info "Battery Voltage: ${volts} V"
                break

            case 15:   // Battery %
                int batt = (int) Math.min(rawVal, 100)
                sendEvent(name: "battery", value: batt, unit: "%")
                if (txtEnable) log.info "Battery: ${batt}%"
                break

            case 109:  // Air Humidity — whole percent (0x6D)
                double ah = clamp(roundTo1(rawVal + safeDouble(settings.airHumidityOffset)), 0, 100)
                sendEvent(name: "airHumidity", value: ah, unit: "%")
                if (txtEnable) log.info "Air Humidity: ${ah}%"
                break

            default:
                if (logEnable) log.debug "EF00 unhandled DP=${dp} raw=${rawVal}"
        }

        i += (4 + dataLength)
    }
}

// ── Standard cluster handlers ────────────────────────────────────────────────

private void handleTemp(String hexValue) {
    try {
        String h = hexValue.replaceAll(/[^0-9a-fA-F]/, "")
        log.warn "handleTemp raw: '${h}'"
        if (h.length() < 4) { log.warn "handleTemp: too short"; return }
        // Parse as big-endian int16 (no byte swap needed it turns out)
        int raw = Integer.parseInt(h, 16)
        if (raw > 0x7FFF) raw -= 0x10000
        double tempC = raw / 100.0
        log.warn "handleTemp raw=${raw} tempC=${tempC}"
        double out = (location.temperatureScale == "F") ? (tempC * 1.8 + 32) : tempC
        out = roundTo1(out + safeDouble(settings.temperatureOffset))
        sendEvent(name: "temperature", value: out, unit: "°${location.temperatureScale}")
        if (txtEnable) log.info "Air Temperature: ${out}°${location.temperatureScale}"
    } catch (Exception e) {
        log.error "handleTemp error: ${e.message}  input: '${hexValue}'"
    }
}

private void handleHumidity(String hexValue) {
    try {
        String h = hexValue.replaceAll(/[^0-9a-fA-F]/, "")
        if (h.length() < 4) { log.warn "handleHumidity: value too short: '${hexValue}'"; return }
        int lo  = Integer.parseInt(h.substring(0, 2), 16)
        int hi  = Integer.parseInt(h.substring(2, 4), 16)
        int raw = (hi << 8) | lo
        double hum = clamp(roundTo1((raw / 100.0) + safeDouble(settings.airHumidityOffset)), 0, 100)
        sendEvent(name: "airHumidity", value: hum, unit: "%")
        if (txtEnable) log.info "Air Humidity (cluster 0405): ${hum}%"
        if (logEnable) log.debug "handleHumidity: hex=${h} raw=${raw} hum=${hum}"
    } catch (Exception e) {
        log.error "handleHumidity error: ${e.message}  input: '${hexValue}'"
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

/** Safely convert a String (hex) or numeric value to int */
private int toInt(val) {
    if (val instanceof String) return Integer.parseInt(val.trim(), 16)
    return (val as int)
}

/** Parse a list of integer bytes as a big-endian unsigned long */
private long parseTuyaLong(List<Integer> bytes) {
    long result = 0
    bytes.each { result = (result << 8) | (it & 0xFF) }
    return result
}

/** Parse a hex string as a plain integer */
private int hexToInt(String hex) {
    return Integer.parseInt(hex.replaceAll(/[^0-9a-fA-F]/, ""), 16)
}

private double roundTo1(double v) { Math.round(v * 10)  / 10.0 }
private double roundTo2(double v) { Math.round(v * 100) / 100.0 }
private double clamp(double v, double min, double max) { Math.max(min, Math.min(max, v)) }

private double safeDouble(val) {
    try { return val ? val.toDouble() : 0.0 } catch (e) { return 0.0 }
}

// ── Health check ─────────────────────────────────────────────────────────────

void initializeVars() {
    state.notPresentCounter = 0
    device.updateDataValue("temperatureUnit", location.temperatureScale)
    sendEvent(name: "healthStatus", value: "online")
}

void scheduleDeviceHealthCheck() {
    unschedule("deviceHealthCheck")
    int s = (Math.random() * 60) as int
    int m = (Math.random() * 60) as int
    schedule("${s} ${m} * * * ?", "deviceHealthCheck")
}

void deviceHealthCheck() {
    state.notPresentCounter = (state.notPresentCounter ?: 0) + 1
    if (state.notPresentCounter > 4) sendEvent(name: "healthStatus", value: "offline")
}

void setPresent() {
    state.notPresentCounter = 0
    if (device.currentValue("healthStatus") != "online")
        sendEvent(name: "healthStatus", value: "online")
}
