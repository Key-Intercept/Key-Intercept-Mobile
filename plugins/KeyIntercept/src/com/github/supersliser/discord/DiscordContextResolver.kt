package com.github.supersliser.discord

import com.github.supersliser.models.ConversationContext
import com.github.supersliser.models.ServerWhitelistItem

class DiscordContextResolver(private val resolver: DiscordResolver) {

    fun resolveCurrentConversationContext(): ConversationContext? {
        return runCatching {
            var channelName = ""
            var channelId = -1L
            var dmName = ""
            var dmId = -1L
            var serverName = ""
            var serverId = -1L

            // Attempt to resolve via Discord stores (simplified version)
            // Full implementation would need reflection into Discord internals

            ConversationContext(
                channelName = channelName,
                channelId = channelId,
                dmName = dmName,
                dmId = dmId,
                serverName = serverName,
                serverId = serverId
            )
        }.onFailure {
            println("[KeyIntercept] Failed to resolve current conversation context: ${it.message}")
        }.getOrNull()
    }

    fun shouldTransformForCurrentConversation(
        context: ConversationContext?,
        whitelist: List<ServerWhitelistItem>,
        debug: Boolean = false
    ): Boolean {
        if (context == null) {
            if (debug) println("[KeyIntercept] Could not resolve current conversation context; skipping transforms")
            return false
        }

        if (context.channelName.contains("sfw", ignoreCase = true) &&
            !context.channelName.contains("nsfw", ignoreCase = true)
        ) {
            if (debug) println("[KeyIntercept] Skipping transforms because channel '${context.channelName}' is SFW")
            return false
        }

        // Build list of names and IDs to check
        val names = listOf(context.channelName, context.dmName, context.serverName)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val ids = listOf(context.channelId, context.dmId, context.serverId)
            .filter { it > 0 }

        if (names.isEmpty() && ids.isEmpty()) {
            if (debug) println("[KeyIntercept] No usable channel/DM/server names or IDs for whitelist matching")
            return false
        }

        // Check both names and IDs against whitelist
        val matched = whitelist.any { item ->
            // Match by name
            (names.any { item.serverName.equals(it, ignoreCase = true) }) ||
            // Match by ID for servers and DMs
            (ids.any { item.discordId == it })
        }
        if (debug) println("[KeyIntercept] Whitelist names=$names ids=$ids matched=$matched")
        return matched
    }
}
