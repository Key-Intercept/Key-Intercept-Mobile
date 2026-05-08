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
            println("[KeyIntercept][Whitelist] Selected channel id=$channelId")

            // Fallback: try to infer channel/server from previously sent message if selection failed
            if (channelId <= 0) {
                val prev = runCatching { resolver.getPreviouslySentMessage() }.getOrNull()
                if (prev != null) {
                    val maybeChan = runCatching { resolver.invokeMethodIfExists(prev, listOf("getChannelId", "getChannel", "channelId")) }.getOrNull()
                    val chanFromPrev = runCatching { resolver.extractLongValues(maybeChan).firstOrNull() }.getOrNull() ?: -1L
                    if (chanFromPrev > 0) {
                        channelId = chanFromPrev
                        println("[KeyIntercept][Whitelist] Inferred channel id from previous message: $channelId")
                    }

                    val maybeGuild = runCatching { resolver.invokeMethodIfExists(prev, listOf("getGuildId", "guildId", "getGuild")) }.getOrNull()
                    val guildFromPrev = runCatching { resolver.extractLongValues(maybeGuild).firstOrNull() }.getOrNull() ?: -1L
                    if (guildFromPrev > 0) {
                        serverId = guildFromPrev
                        println("[KeyIntercept][Whitelist] Inferred guild id from previous message: $serverId")
                    }
                } else {
                    println("[KeyIntercept][Whitelist] No previous message available to infer channel/guild")
                }
            }
            
            if (channelId > 0) {
                // Try to get the channel object and extract its name and guild
                try {
                    val channelObj = getChannelObject(channelId)
                    if (channelObj != null) {
                        channelName = extractStringValue(channelObj, listOf("name", "getName", "getChannelName")) ?: ""
                        serverId = resolver.extractLongValues(extractValue(channelObj, listOf("guild", "getGuild", "guildId", "getGuildId"))).firstOrNull() ?: -1L
                        println("[KeyIntercept][Whitelist] Channel object=${channelObj.javaClass.name} channelName='$channelName' serverId=$serverId")
                    } else {
                        println("[KeyIntercept][Whitelist] Could not resolve channel object for id=$channelId")
                    }
                } catch (e: Exception) {
                    println("[KeyIntercept] Failed to resolve channel details for ID $channelId: ${e.message}")
                }
                
                // Try to get server/guild name if we have the guild ID (will also run later if inferred)
                if (serverId > 0) {
                    try {
                        val guildObj = getGuildObject(serverId)
                        if (guildObj != null) {
                            serverName = extractStringValue(guildObj, listOf("name", "getName")) ?: ""
                            println("[KeyIntercept][Whitelist] Guild object=${guildObj.javaClass.name} serverName='$serverName'")
                        } else {
                            println("[KeyIntercept][Whitelist] Could not resolve guild object for id=$serverId")
                        }
                    } catch (e: Exception) {
                        println("[KeyIntercept] Failed to resolve guild details for ID $serverId: ${e.message}")
                    }
                }
            }

            // If we still don't have a server name but we have a serverId (inferred earlier), try to fetch guild name
            if (serverName.isEmpty() && serverId > 0) {
                try {
                    val guildObj = getGuildObject(serverId)
                    if (guildObj != null) {
                        serverName = extractStringValue(guildObj, listOf("name", "getName")) ?: ""
                        println("[KeyIntercept][Whitelist] (Fallback) Guild object=${guildObj.javaClass.name} serverName='$serverName'")
                    } else {
                        println("[KeyIntercept][Whitelist] (Fallback) Could not resolve guild object for id=$serverId")
                    }
                } catch (e: Exception) {
                    println("[KeyIntercept] (Fallback) Failed to resolve guild details for ID $serverId: ${e.message}")
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

        val serverName = context.serverName.trim()
        val serverId = context.serverId

        if (serverName.isEmpty() && serverId <= 0) {
            if (debug) println("[KeyIntercept] No usable server name or server ID for whitelist matching")
            return false
        }

        if (debug) {
            println("[KeyIntercept][Whitelist] Checking server name='$serverName' server id=$serverId")
            println("[KeyIntercept][Whitelist] Comparing against ${whitelist.size} whitelist entries")
            if (whitelist.isEmpty()) {
                println("[KeyIntercept][Whitelist] Whitelist is empty; transforms will be skipped")
            }
            whitelist.forEachIndexed { index, item ->
                println("[KeyIntercept][Whitelist] Entry #$index discordId=${item.discordId} serverName='${item.serverName}'")
            }
        }

        // Check server name or server ID against whitelist
        val matched = whitelist.any { item ->
            val normalizedEntryName = item.serverName.trim()
            val nameMatch = serverName.isNotEmpty() && normalizedEntryName.isNotEmpty() &&
                normalizedEntryName.equals(serverName, ignoreCase = true)
            val idMatch = serverId > 0 && item.discordId > 0 && item.discordId == serverId

            if (debug) {
                println("[KeyIntercept][Whitelist] compare entry name='${item.serverName}' id=${item.discordId} => nameMatch=$nameMatch idMatch=$idMatch")
            }

            nameMatch || idMatch
        }
        if (debug) println("[KeyIntercept] Whitelist serverName='$serverName' serverId=$serverId matched=$matched")
        return matched
    }
}
