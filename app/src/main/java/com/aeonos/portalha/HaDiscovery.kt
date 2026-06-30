package com.aeonos.portalha

object HaDiscovery {

    // ── Screen switch ─────────────────────────────────────────────────────────

    fun discoveryTopic(deviceId: String) =
        "homeassistant/switch/${deviceId}_screen/config"

    fun stateTopic(deviceId: String) = "portal/$deviceId/screen/state"
    fun commandTopic(deviceId: String) = "portal/$deviceId/screen/command"

    fun configPayload(deviceId: String, deviceName: String): String {
        val name = deviceName.escape()
        return """{"name":"Screen","unique_id":"${deviceId}_screen","device":${device(deviceId, name)},"state_topic":"${stateTopic(deviceId)}","command_topic":"${commandTopic(deviceId)}","payload_on":"ON","payload_off":"OFF","state_on":"ON","state_off":"OFF"}"""
    }

    // ── Light sensor ──────────────────────────────────────────────────────────

    fun lightDiscoveryTopic(deviceId: String) =
        "homeassistant/sensor/${deviceId}_light/config"

    fun lightStateTopic(deviceId: String) = "portal/$deviceId/sensor/light"

    fun lightConfigPayload(deviceId: String, deviceName: String): String {
        val name = deviceName.escape()
        return """{"name":"Ambient Light","unique_id":"${deviceId}_light","device":${device(deviceId, name)},"state_topic":"${lightStateTopic(deviceId)}","device_class":"illuminance","unit_of_measurement":"lx","state_class":"measurement"}"""
    }

    // ── Ambient temperature sensor (Portal+ only; absent on Portal/Mini) ──────

    fun tempDiscoveryTopic(deviceId: String) =
        "homeassistant/sensor/${deviceId}_temperature/config"

    fun tempStateTopic(deviceId: String) = "portal/$deviceId/sensor/temperature"

    fun tempConfigPayload(deviceId: String, deviceName: String): String {
        val name = deviceName.escape()
        return """{"name":"Temperature","unique_id":"${deviceId}_temperature","device":${device(deviceId, name)},"state_topic":"${tempStateTopic(deviceId)}","device_class":"temperature","unit_of_measurement":"°C","state_class":"measurement"}"""
    }

    // Calibration offset for the temperature sensor (HA number).
    fun tempOffsetDiscoveryTopic(deviceId: String) =
        "homeassistant/number/${deviceId}_temp_offset/config"

    fun tempOffsetStateTopic(deviceId: String) = "portal/$deviceId/sensor/temp_offset/state"
    fun tempOffsetCommandTopic(deviceId: String) = "portal/$deviceId/sensor/temp_offset/set"

    fun tempOffsetConfigPayload(deviceId: String, deviceName: String): String {
        val name = deviceName.escape()
        return """{"name":"Temperature Offset","unique_id":"${deviceId}_temp_offset","device":${device(deviceId, name)},"state_topic":"${tempOffsetStateTopic(deviceId)}","command_topic":"${tempOffsetCommandTopic(deviceId)}","min":-20,"max":20,"step":0.5,"mode":"box","unit_of_measurement":"°C","icon":"mdi:thermometer-plus","entity_category":"config"}"""
    }

    // HA long-lived token, settable FROM Home Assistant (so it never has to be typed
    // on the Portal). A `text` entity in password mode; NO state_topic, so the token
    // is optimistic-only and never echoed back / retained on the broker. The app
    // stores whatever HA publishes to the command topic into Prefs.haToken.
    fun haTokenDiscoveryTopic(deviceId: String) =
        "homeassistant/text/${deviceId}_hatoken/config"

    fun haTokenCommandTopic(deviceId: String) = "portal/$deviceId/config/hatoken/set"

    fun haTokenConfigPayload(deviceId: String, deviceName: String): String {
        val name = deviceName.escape()
        return """{"name":"HA Token","unique_id":"${deviceId}_hatoken","device":${device(deviceId, name)},"command_topic":"${haTokenCommandTopic(deviceId)}","mode":"password","max":255,"icon":"mdi:key","entity_category":"config"}"""
    }

    // ── Accelerometer ─────────────────────────────────────────────────────────

    fun accelDiscoveryTopic(deviceId: String, axis: String) =
        "homeassistant/sensor/${deviceId}_accel_$axis/config"

    fun accelStateTopic(deviceId: String) = "portal/$deviceId/sensor/accelerometer"

    fun accelConfigPayload(deviceId: String, deviceName: String, axis: String): String {
        val name = deviceName.escape()
        return """{"name":"Accel ${axis.uppercase()}","unique_id":"${deviceId}_accel_$axis","device":${device(deviceId, name)},"state_topic":"${accelStateTopic(deviceId)}","value_template":"{{ value_json.$axis }}","unit_of_measurement":"m/s²","state_class":"measurement"}"""
    }

    // ── RGB light sensor (Portal custom type 65537) ───────────────────────────

    fun rgbDiscoveryTopic(deviceId: String, channel: String) =
        "homeassistant/sensor/${deviceId}_rgb_$channel/config"

    fun rgbStateTopic(deviceId: String) = "portal/$deviceId/sensor/rgb"

    fun rgbConfigPayload(deviceId: String, deviceName: String, channel: String): String {
        val name = deviceName.escape()
        val label = channel.uppercase()
        return """{"name":"Light $label","unique_id":"${deviceId}_rgb_$channel","device":${device(deviceId, name)},"state_topic":"${rgbStateTopic(deviceId)}","value_template":"{{ value_json.$channel }}","unit_of_measurement":"lx","state_class":"measurement","icon":"mdi:palette"}"""
    }

    // ── Tap / slap direction ──────────────────────────────────────────────────

    // Portal+ 2nd gen ("cipher") has a screen-mounted accelerometer, so this
    // gesture reads as a tilt, not a tap — relabel the entities on that model.
    private val isTiltModel = android.os.Build.DEVICE.equals("cipher", true)
    private val tapLabel = if (isTiltModel) "Tilt" else "Tap"
    private val tapIcon = if (isTiltModel) "mdi:axis-arrow" else "mdi:gesture-tap"
    private val tapSensIcon = if (isTiltModel) "mdi:axis-arrow" else "mdi:hand-tap"

    fun tapDiscoveryTopic(deviceId: String) =
        "homeassistant/sensor/${deviceId}_tap/config"

    fun tapStateTopic(deviceId: String) = "portal/$deviceId/event/tap"

    fun tapConfigPayload(deviceId: String, deviceName: String): String {
        val name = deviceName.escape()
        return """{"name":"$tapLabel","unique_id":"${deviceId}_tap","device":${device(deviceId, name)},"state_topic":"${tapStateTopic(deviceId)}","icon":"$tapIcon"}"""
    }

    // ── Tap sensitivity number (slider) ───────────────────────────────────────

    fun sensitivityDiscoveryTopic(deviceId: String) =
        "homeassistant/number/${deviceId}_tap_sensitivity/config"

    fun sensitivityStateTopic(deviceId: String) = "portal/$deviceId/tap/sensitivity/state"
    fun sensitivityCommandTopic(deviceId: String) = "portal/$deviceId/tap/sensitivity/set"

    fun sensitivityConfigPayload(deviceId: String, deviceName: String): String {
        val name = deviceName.escape()
        return """{"name":"$tapLabel Sensitivity","unique_id":"${deviceId}_tap_sensitivity","device":${device(deviceId, name)},"state_topic":"${sensitivityStateTopic(deviceId)}","command_topic":"${sensitivityCommandTopic(deviceId)}","min":2.0,"max":15.0,"step":0.5,"mode":"slider","icon":"$tapSensIcon"}"""
    }

    // ── Sound level sensor ────────────────────────────────────────────────────

    fun soundDiscoveryTopic(deviceId: String) =
        "homeassistant/sensor/${deviceId}_sound/config"

    fun soundStateTopic(deviceId: String) = "portal/$deviceId/sensor/sound"

    fun soundConfigPayload(deviceId: String, deviceName: String): String {
        val name = deviceName.escape()
        return """{"name":"Sound Level","unique_id":"${deviceId}_sound","device":${device(deviceId, name)},"state_topic":"${soundStateTopic(deviceId)}","unit_of_measurement":"%","state_class":"measurement","icon":"mdi:microphone"}"""
    }

    // ── Mic mute switch ───────────────────────────────────────────────────────

    fun micMuteDiscoveryTopic(deviceId: String) =
        "homeassistant/switch/${deviceId}_mic_mute/config"

    fun micMuteStateTopic(deviceId: String) = "portal/$deviceId/mic/mute/state"
    fun micMuteCommandTopic(deviceId: String) = "portal/$deviceId/mic/mute/set"

    fun micMuteConfigPayload(deviceId: String, deviceName: String): String {
        val name = deviceName.escape()
        return """{"name":"Mic Mute","unique_id":"${deviceId}_mic_mute","device":${device(deviceId, name)},"state_topic":"${micMuteStateTopic(deviceId)}","command_topic":"${micMuteCommandTopic(deviceId)}","payload_on":"ON","payload_off":"OFF","state_on":"ON","state_off":"OFF","icon":"mdi:microphone-off"}"""
    }

    // ── Volume number (slider) ────────────────────────────────────────────────

    fun volumeDiscoveryTopic(deviceId: String) =
        "homeassistant/number/${deviceId}_volume/config"

    fun volumeStateTopic(deviceId: String) = "portal/$deviceId/audio/volume/state"
    fun volumeCommandTopic(deviceId: String) = "portal/$deviceId/audio/volume/set"

    fun volumeConfigPayload(deviceId: String, deviceName: String): String {
        val name = deviceName.escape()
        return """{"name":"Volume","unique_id":"${deviceId}_volume","device":${device(deviceId, name)},"state_topic":"${volumeStateTopic(deviceId)}","command_topic":"${volumeCommandTopic(deviceId)}","min":0,"max":100,"step":1,"mode":"slider","icon":"mdi:volume-high"}"""
    }

    // ── Sound buttons (doorbell / alert tones) ────────────────────────────────

    fun soundCommandTopic(deviceId: String) = "portal/$deviceId/sound/play"

    fun doorbellDiscoveryTopic(deviceId: String) =
        "homeassistant/button/${deviceId}_doorbell/config"

    fun alertDiscoveryTopic(deviceId: String) =
        "homeassistant/button/${deviceId}_alert/config"

    fun doorbellConfigPayload(deviceId: String, deviceName: String): String {
        val name = deviceName.escape()
        return """{"name":"Doorbell","unique_id":"${deviceId}_doorbell","device":${device(deviceId, name)},"command_topic":"${soundCommandTopic(deviceId)}","payload_press":"doorbell","icon":"mdi:bell-ring"}"""
    }

    fun alertConfigPayload(deviceId: String, deviceName: String): String {
        val name = deviceName.escape()
        return """{"name":"Alert","unique_id":"${deviceId}_alert","device":${device(deviceId, name)},"command_topic":"${soundCommandTopic(deviceId)}","payload_press":"alert","icon":"mdi:alert"}"""
    }

    // ── Volume mute switch ────────────────────────────────────────────────────

    fun volumeMuteDiscoveryTopic(deviceId: String) =
        "homeassistant/switch/${deviceId}_volume_mute/config"

    fun volumeMuteStateTopic(deviceId: String) = "portal/$deviceId/audio/mute/state"
    fun volumeMuteCommandTopic(deviceId: String) = "portal/$deviceId/audio/mute/set"

    fun volumeMuteConfigPayload(deviceId: String, deviceName: String): String {
        val name = deviceName.escape()
        return """{"name":"Volume Mute","unique_id":"${deviceId}_volume_mute","device":${device(deviceId, name)},"state_topic":"${volumeMuteStateTopic(deviceId)}","command_topic":"${volumeMuteCommandTopic(deviceId)}","payload_on":"ON","payload_off":"OFF","state_on":"ON","state_off":"OFF","icon":"mdi:volume-off"}"""
    }

    // ── Screen brightness number (slider) ─────────────────────────────────────

    fun brightnessDiscoveryTopic(deviceId: String) =
        "homeassistant/number/${deviceId}_brightness/config"

    fun brightnessStateTopic(deviceId: String) = "portal/$deviceId/display/brightness/state"
    fun brightnessCommandTopic(deviceId: String) = "portal/$deviceId/display/brightness/set"

    fun brightnessConfigPayload(deviceId: String, deviceName: String): String {
        val name = deviceName.escape()
        return """{"name":"Brightness","unique_id":"${deviceId}_brightness","device":${device(deviceId, name)},"state_topic":"${brightnessStateTopic(deviceId)}","command_topic":"${brightnessCommandTopic(deviceId)}","min":0,"max":100,"step":1,"mode":"slider","icon":"mdi:brightness-6"}"""
    }

    // ── Camera switch ─────────────────────────────────────────────────────────

    fun cameraDiscoveryTopic(deviceId: String) =
        "homeassistant/switch/${deviceId}_camera/config"

    fun cameraStateTopic(deviceId: String) = "portal/$deviceId/camera/state"
    fun cameraCommandTopic(deviceId: String) = "portal/$deviceId/camera/set"

    fun cameraConfigPayload(deviceId: String, deviceName: String): String {
        val name = deviceName.escape()
        return """{"name":"Camera","unique_id":"${deviceId}_camera","device":${device(deviceId, name)},"state_topic":"${cameraStateTopic(deviceId)}","command_topic":"${cameraCommandTopic(deviceId)}","payload_on":"ON","payload_off":"OFF","state_on":"ON","state_off":"OFF","icon":"mdi:camera"}"""
    }

    // ── Motion detection enable switch ────────────────────────────────────────

    fun motionEnableDiscoveryTopic(deviceId: String) =
        "homeassistant/switch/${deviceId}_motion_enable/config"

    fun motionEnableStateTopic(deviceId: String) = "portal/$deviceId/motion_enable/state"
    fun motionEnableCommandTopic(deviceId: String) = "portal/$deviceId/motion_enable/set"

    fun motionEnableConfigPayload(deviceId: String, deviceName: String): String {
        val name = deviceName.escape()
        return """{"name":"Motion Detection","unique_id":"${deviceId}_motion_enable","device":${device(deviceId, name)},"state_topic":"${motionEnableStateTopic(deviceId)}","command_topic":"${motionEnableCommandTopic(deviceId)}","payload_on":"ON","payload_off":"OFF","state_on":"ON","state_off":"OFF","icon":"mdi:motion-sensor"}"""
    }

    // ── Camera streaming enable switch ────────────────────────────────────────

    fun streamEnableDiscoveryTopic(deviceId: String) =
        "homeassistant/switch/${deviceId}_stream_enable/config"

    fun streamEnableStateTopic(deviceId: String) = "portal/$deviceId/stream_enable/state"
    fun streamEnableCommandTopic(deviceId: String) = "portal/$deviceId/stream_enable/set"

    fun streamEnableConfigPayload(deviceId: String, deviceName: String): String {
        val name = deviceName.escape()
        return """{"name":"Camera Streaming","unique_id":"${deviceId}_stream_enable","device":${device(deviceId, name)},"state_topic":"${streamEnableStateTopic(deviceId)}","command_topic":"${streamEnableCommandTopic(deviceId)}","payload_on":"ON","payload_off":"OFF","state_on":"ON","state_off":"OFF","icon":"mdi:video"}"""
    }

    // ── Motion binary sensor ──────────────────────────────────────────────────

    fun motionDiscoveryTopic(deviceId: String) =
        "homeassistant/binary_sensor/${deviceId}_motion/config"

    fun motionStateTopic(deviceId: String) = "portal/$deviceId/camera/motion"

    fun motionConfigPayload(deviceId: String, deviceName: String): String {
        val name = deviceName.escape()
        return """{"name":"Motion","unique_id":"${deviceId}_motion","device":${device(deviceId, name)},"state_topic":"${motionStateTopic(deviceId)}","device_class":"motion","payload_on":"ON","payload_off":"OFF"}"""
    }

    // ── Motion sensitivity number (slider) ────────────────────────────────────

    fun motionSensitivityDiscoveryTopic(deviceId: String) =
        "homeassistant/number/${deviceId}_motion_sensitivity/config"

    fun motionSensitivityStateTopic(deviceId: String) =
        "portal/$deviceId/camera/motion_sensitivity/state"

    fun motionSensitivityCommandTopic(deviceId: String) =
        "portal/$deviceId/camera/motion_sensitivity/set"

    fun motionSensitivityConfigPayload(deviceId: String, deviceName: String): String {
        val name = deviceName.escape()
        return """{"name":"Motion Sensitivity","unique_id":"${deviceId}_motion_sensitivity","device":${device(deviceId, name)},"state_topic":"${motionSensitivityStateTopic(deviceId)}","command_topic":"${motionSensitivityCommandTopic(deviceId)}","min":1,"max":100,"step":1,"mode":"slider","icon":"mdi:motion-sensor"}"""
    }

    // Topics to clear when motion detection is disabled
    fun motionEntityTopics(deviceId: String) = listOf(
        motionDiscoveryTopic(deviceId),
        motionSensitivityDiscoveryTopic(deviceId)
    )

    // ── Portal presence binary sensor ─────────────────────────────────────────

    fun presenceDiscoveryTopic(deviceId: String) =
        "homeassistant/binary_sensor/${deviceId}_presence/config"

    fun presenceStateTopic(deviceId: String) = "portal/$deviceId/presence/state"

    fun presenceConfigPayload(deviceId: String, deviceName: String): String {
        val name = deviceName.escape()
        return """{"name":"Portal Presence","unique_id":"${deviceId}_presence","device":${device(deviceId, name)},"state_topic":"${presenceStateTopic(deviceId)}","device_class":"occupancy","payload_on":"ON","payload_off":"OFF"}"""
    }

    // ── Presence detection enable switch ──────────────────────────────────────

    fun presenceEnableDiscoveryTopic(deviceId: String) =
        "homeassistant/switch/${deviceId}_presence_enable/config"

    fun presenceEnableStateTopic(deviceId: String) = "portal/$deviceId/presence_enable/state"
    fun presenceEnableCommandTopic(deviceId: String) = "portal/$deviceId/presence_enable/set"

    fun presenceEnableConfigPayload(deviceId: String, deviceName: String): String {
        val name = deviceName.escape()
        return """{"name":"Presence Detection","unique_id":"${deviceId}_presence_enable","device":${device(deviceId, name)},"state_topic":"${presenceEnableStateTopic(deviceId)}","command_topic":"${presenceEnableCommandTopic(deviceId)}","payload_on":"ON","payload_off":"OFF","state_on":"ON","state_off":"OFF","icon":"mdi:account-eye"}"""
    }

    // ── Screen-off timer enable switch ────────────────────────────────────────

    fun screenTimeoutDiscoveryTopic(deviceId: String) =
        "homeassistant/switch/${deviceId}_screen_timeout/config"

    fun screenTimeoutStateTopic(deviceId: String) = "portal/$deviceId/screen_timeout/state"
    fun screenTimeoutCommandTopic(deviceId: String) = "portal/$deviceId/screen_timeout/set"

    fun screenTimeoutConfigPayload(deviceId: String, deviceName: String): String {
        val name = deviceName.escape()
        return """{"name":"Screen Timeout","unique_id":"${deviceId}_screen_timeout","device":${device(deviceId, name)},"state_topic":"${screenTimeoutStateTopic(deviceId)}","command_topic":"${screenTimeoutCommandTopic(deviceId)}","payload_on":"ON","payload_off":"OFF","state_on":"ON","state_off":"OFF","icon":"mdi:timer-off"}"""
    }

    // ── Screen-off timer minutes number ───────────────────────────────────────

    fun screenTimeoutMinsDiscoveryTopic(deviceId: String) =
        "homeassistant/number/${deviceId}_screen_timeout_mins/config"

    fun screenTimeoutMinsStateTopic(deviceId: String) = "portal/$deviceId/screen_timeout_mins/state"
    fun screenTimeoutMinsCommandTopic(deviceId: String) = "portal/$deviceId/screen_timeout_mins/set"

    fun screenTimeoutMinsConfigPayload(deviceId: String, deviceName: String): String {
        val name = deviceName.escape()
        return """{"name":"Screen Timeout Minutes","unique_id":"${deviceId}_screen_timeout_mins","device":${device(deviceId, name)},"state_topic":"${screenTimeoutMinsStateTopic(deviceId)}","command_topic":"${screenTimeoutMinsCommandTopic(deviceId)}","min":1,"max":240,"step":1,"mode":"box","unit_of_measurement":"min","icon":"mdi:timer-cog"}"""
    }

    // ── IP address sensor (diagnostic) ────────────────────────────────────────

    fun ipDiscoveryTopic(deviceId: String) =
        "homeassistant/sensor/${deviceId}_ip/config"

    fun ipStateTopic(deviceId: String) = "portal/$deviceId/sensor/ip"

    fun ipConfigPayload(deviceId: String, deviceName: String): String {
        val name = deviceName.escape()
        return """{"name":"IP Address","unique_id":"${deviceId}_ip","device":${device(deviceId, name)},"state_topic":"${ipStateTopic(deviceId)}","icon":"mdi:ip-network","entity_category":"diagnostic"}"""
    }

    // ── Stale entity cleanup ──────────────────────────────────────────────────

    fun staleTopics(deviceId: String) = listOf(
        "homeassistant/select/${deviceId}_rotation/config",
        "homeassistant/sensor/${deviceId}_tilt/config"
    )

    // All command topics. Old builds set "retain":true on the HA configs, so the
    // broker still holds the last command (e.g. screen OFF) and replays it at every
    // connect — locking the screen and killing the camera on every app start.
    // Cleared (empty retained publish) before subscribing.
    fun commandTopics(deviceId: String) = listOf(
        commandTopic(deviceId),
        sensitivityCommandTopic(deviceId),
        micMuteCommandTopic(deviceId),
        volumeCommandTopic(deviceId),
        volumeMuteCommandTopic(deviceId),
        brightnessCommandTopic(deviceId),
        cameraCommandTopic(deviceId),
        motionSensitivityCommandTopic(deviceId),
        motionEnableCommandTopic(deviceId),
        streamEnableCommandTopic(deviceId),
        soundCommandTopic(deviceId),
        presenceEnableCommandTopic(deviceId),
        screenTimeoutCommandTopic(deviceId),
        screenTimeoutMinsCommandTopic(deviceId),
        tempOffsetCommandTopic(deviceId)
    )

    // ── Shared helpers ────────────────────────────────────────────────────────

    private fun device(deviceId: String, escapedName: String) =
        """{"identifiers":["$deviceId"],"name":"$escapedName","model":"Meta Portal","manufacturer":"Meta"}"""

    private fun String.escape() = replace("\"", "\\\"")
}
