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

            // Resolve the current selected channel ID
            channelId = resolver.resolveSelectedChannelId() ?: -1L
            
            if (channelId > 0) {
                // Try to get the channel object and extract its name and guild
                try {
                    val channelObj = getChannelObject(channelId)
                    if (channelObj != null) {
                        channelName = extractStringValue(channelObj, listOf("name", "getName", "getChannelName")) ?: ""
                        serverId = extractLongValues(extractValue(channelObj, listOf("guild", "getGuild", "guildId", "getGuildId"))).firstOrNull() ?: -1L
                    }
                } catch (e: Exception) {
                    println("[KeyIntercept] Failed to resolve channel details for ID $channelId: ${e.message}")
                }
                
                // Try to get server/guild name if we have the guild ID
                if (serverId > 0) {
                    try {
                        val guildObj = getGuildObject(serverId)
                        if (guildObj != null) {
                            serverName = extractStringValue(guildObj, listOf("name", "getName")) ?: ""
                        }
                    } catch (e: Exception) {
                        println("[KeyIntercept] Failed to resolve guild details for ID $serverId: ${e.message}")
                    }
                }
            }

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

    private fun getChannelObject(channelId: Long): Any? {
        return runCatching {
            val channelsStore = com.discord.stores.StoreStream::class.java
                .getMethod("getChannels")
                .invoke(null) ?: return@runCatching null
            
            val methodNames = listOf("getChannel", "get")
            for (methodName in methodNames) {
                val method = runCatching {
                    channelsStore.javaClass.getMethod(methodName, Long::class.javaPrimitiveType)
                }.getOrNull() ?: continue
                
                val result = runCatching { method.invoke(channelsStore, channelId) }.getOrNull()
                if (result != null) return@runCatching result
            }
            
            null
        }.getOrNull()
    }

    private fun getGuildObject(guildId: Long): Any? {
        return runCatching {
            val guildsStore = com.discord.stores.StoreStream::class.java
                .getMethod("getGuilds")
                .invoke(null) ?: return@runCatching null
            
            val methodNames = listOf("getGuild", "get")
            for (methodName in methodNames) {
                val method = runCatching {
                    guildsStore.javaClass.getMethod(methodName, Long::class.javaPrimitiveType)
                }.getOrNull() ?: continue
                
                val result = runCatching { method.invoke(guildsStore, guildId) }.getOrNull()
                if (result != null) return@runCatching result
            }
            
            null
        }.getOrNull()
    }

    private fun extractValue(obj: Any?, methodNames: List<String>): Any? {
        if (obj == null) return null
        
        for (methodName in methodNames) {
            val method = runCatching {
                obj.javaClass.getMethod(methodName)
            }.getOrNull() ?: continue
            
            val result = runCatching { method.invoke(obj) }.getOrNull()
            if (result != null) return result
        }
        
        return null
    }

    private fun extractStringValue(obj: Any?, methodNames: List<String>): String? {
        val value = extractValue(obj, methodNames) ?: return null
        return value.toString().takeIf { it.isNotEmpty() }
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
