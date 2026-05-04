package com.github.supersliser.models

data class ServerWhitelistItem(
    val id: Long = 0,
    val configId: Long = 0,
    val serverName: String = "",
    val discordId: Long = -1L
)
