package com.github.supersliser.models

data class DroneConfig(
    val config_id: Long = 0,
    val speech_header: String = "",
    val speech_footer: String = "",
    val drone_health: Float = 0f,
    val action_header: String = "",
    val action_footer: String = "",
    val whisper_header: String = "",
    val whisper_footer: String = "",
    val loud_header: String = "",
    val loud_footer: String = ""
)