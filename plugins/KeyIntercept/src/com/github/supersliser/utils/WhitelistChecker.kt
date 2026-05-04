package com.github.supersliser.utils

import com.github.supersliser.models.ServerWhitelistItem

fun checkWhitelist(
    whitelist: List<ServerWhitelistItem>,
    serverName: String,
    serverId: Long = -1L,
    debug: Boolean = false
): Boolean {
    if (whitelist.isEmpty() || whitelist.all { it.serverName == "Example Server" }) {
        return false
    }

    // Check both serverName and serverId
    val allowed = whitelist.any { item ->
        (serverName.isNotEmpty() && item.serverName.equals(serverName, ignoreCase = true)) ||
        (serverId > 0 && item.discordId == serverId)
    }
    if (debug) {
        println("[KeyIntercept] Whitelist check for serverName='$serverName' serverId=$serverId => $allowed")
    }
    return allowed
}
