package com.github.supersliser.models

import java.util.Date

data class KeyInterceptConfig(
    val id: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val rulesEnd: Long = 0,
    val gagEnd: Long = 0,
    val petEnd: Long = 0,
    val petAmount: Float = 0f,
    val petType: Long = 0,
    val bimboEnd: Long = 0,
    val hornyEnd: Long = 0,
    val bimboWordLength: Int = 0,
    val droneEnd: Long = 0,
    val droneHeaderText: String = "",
    val droneFooterText: String = "",
    val droneHealth: Float = 0f,
    val uwuEnd: Long = 0,
    val debug: Boolean = false
)
