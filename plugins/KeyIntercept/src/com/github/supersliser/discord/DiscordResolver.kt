package com.github.supersliser.discord

import com.discord.stores.StoreStream
import com.github.supersliser.supabase.SupabaseClient
import org.json.JSONArray
import kotlin.math.floor

class DiscordResolver(private val supabaseClient: SupabaseClient) {

    fun resolveCurrentDiscordId(): Long? {
        return runCatching {
            val usersStore = StoreStream::class.java.getMethod("getUsers").invoke(null)
            if (usersStore == null) return@runCatching null

            val me = listOf("getMe", "getCurrentUser", "getSelf")
                .mapNotNull { methodName ->
                    runCatching {
                        usersStore.javaClass.getMethod(methodName).invoke(usersStore)
                    }.getOrNull()
                }.firstOrNull() ?: return@runCatching null

            listOf("id", "userId").asSequence().mapNotNull { fieldName ->
                runCatching {
                    val field = me.javaClass.getDeclaredField(fieldName)
                    field.isAccessible = true
                    val value = field.get(me)
                    when (value) {
                        is Long -> value
                        is Number -> value.toLong()
                        is String -> value.toLongOrNull()
                        else -> null
                    }
                }.getOrNull()
            }.firstOrNull()
        }.onFailure {
            println("[KeyIntercept] Failed to resolve current Discord user id: ${it.message}")
        }.getOrNull()
    }

    fun resolveConfigIdForCurrentUser(): Long? {
        return runCatching {
            val discordId = resolveCurrentDiscordId() ?: return@runCatching null
            println("[KeyIntercept] Resolving config id for discord_id=$discordId")

            val profilesBody = supabaseClient.supabaseGet("profiles", mapOf("discord_id" to discordId.toString()))
            val profilesArray = JSONArray(profilesBody)
            if (profilesArray.length() == 0) {
                println("[KeyIntercept] No profile found for discord_id=$discordId")
                return@runCatching null
            }

            val subIdRaw = runCatching {
                profilesArray.getJSONObject(0).get("id").toString()
            }.getOrDefault("")
            val subId = subIdRaw.trim()
            if (subId.isEmpty()) {
                println("[KeyIntercept] Empty sub_id in profile")
                return@runCatching null
            }

            val accessBody = supabaseClient.supabaseGet("Sub_Config_Access", mapOf("sub_id" to subId))
            val accessArray = JSONArray(accessBody)
            if (accessArray.length() == 0) {
                println("[KeyIntercept] No Sub_Config_Access found for sub_id=$subId")
                return@runCatching null
            }

            val rawConfigId = runCatching { accessArray.getJSONObject(0).get("config_id") }.getOrNull()
            val configId = when (rawConfigId) {
                is Number -> rawConfigId.toLong()
                is String -> rawConfigId.toLongOrNull()
                else -> null
            }
            if (configId == null || configId <= 0L) {
                println("[KeyIntercept] Invalid config_id from access: $rawConfigId")
                return@runCatching null
            }

            configId
        }.onFailure {
            println("[KeyIntercept] Failed to resolve config id from profiles/Sub_Config_Access: ${it.message}")
        }.getOrNull()
    }

    fun extractLongValues(value: Any?): List<Long> {
        if (value == null) return emptyList()

        return when (value) {
            is Long -> listOf(value)
            is Int -> listOf(value.toLong())
            is Short -> listOf(value.toLong())
            is Byte -> listOf(value.toLong())
            is String -> value.toLongOrNull()?.let { listOf(it) } ?: emptyList()
            is Iterable<*> -> value.flatMap { extractLongValues(it) }
            is Array<*> -> value.flatMap { extractLongValues(it) }
            else -> {
                println("[KeyIntercept] Cannot extract long from ${value.javaClass.simpleName}")
                emptyList()
            }
        }
    }

    fun extractUsernameOnly(user: Any?): String {
        if (user == null) return ""

        val methodOrder = listOf("getUsername", "getUserName", "username", "getUserTag")
        for (methodName in methodOrder) {
            val fromMethod = runCatching {
                val method = user.javaClass.getMethod(methodName)
                method.invoke(user)?.toString()?.trim().orEmpty()
            }.getOrDefault("")

            if (fromMethod.isNotEmpty()) {
                return fromMethod
            }
        }

        val fieldOrder = listOf("username", "userName", "user_tag", "tag")
        for (fieldName in fieldOrder) {
            val fromField = runCatching {
                val field = user.javaClass.getDeclaredField(fieldName)
                field.isAccessible = true
                field.get(user)?.toString()?.trim().orEmpty()
            }.getOrDefault("")

            if (fromField.isNotEmpty()) {
                return fromField
            }
        }

        return ""
    }

    fun getChannelMemberObject(channel: Any?): Any? {
        if (channel == null) return null

        val viaMethod = runCatching {
            val method = channel.javaClass.getMethod("getThreadMember")
            method.invoke(channel)
        }.getOrNull()

        if (viaMethod != null) return viaMethod

        val viaField = runCatching {
            val field = channel.javaClass.getDeclaredField("threadMember")
            field.isAccessible = true
            field.get(channel)
        }.getOrNull()

        return viaField
    }

    fun extractRecipientIdsFromMember(channel: Any?): List<Long> {
        val member = getChannelMemberObject(channel) ?: return emptyList()

        val out = LinkedHashSet<Long>()
        val memberMethodNames = listOf("getUserId", "getMemberId", "getId", "userId", "id")
        for (methodName in memberMethodNames) {
            val value = runCatching {
                val method = member.javaClass.getMethod(methodName)
                method.invoke(member)
            }.getOrNull()
            out.addAll(extractLongValues(value))
        }

        val memberFieldNames = listOf("userId", "memberId", "id")
        for (fieldName in memberFieldNames) {
            val value = runCatching {
                val field = member.javaClass.getDeclaredField(fieldName)
                field.isAccessible = true
                field.get(member)
            }.getOrNull()
            out.addAll(extractLongValues(value))
        }

        return out.toList()
    }

    fun resolveUserNameFromMember(channel: Any?): String {
        val member = getChannelMemberObject(channel) ?: return ""

        println("[KeyIntercept] [resolveDmRecipientName] Inspecting ThreadMember class: ${member.javaClass.name}")

        // First try the nested user object
        val nestedUser = runCatching {
            val userField = member.javaClass.getDeclaredField("user")
            userField.isAccessible = true
            userField.get(member)
        }.getOrNull()

        val nestedUsername = extractUsernameOnly(nestedUser)
        if (nestedUsername.isNotEmpty()) {
            return nestedUsername
        }

        // Fall back to username-like properties directly on ThreadMember
        val methodCandidates = listOf("getUsername", "getUserName", "username", "getUserTag")
        for (methodName in methodCandidates) {
            val value = runCatching {
                val method = member.javaClass.getMethod(methodName)
                method.invoke(member)?.toString()?.trim() ?: ""
            }.getOrDefault("")
            if (value.isNotEmpty()) return value
        }

        val fieldCandidates = listOf("username", "userName", "user_tag", "tag")
        for (fieldName in fieldCandidates) {
            val value = runCatching {
                val field = member.javaClass.getDeclaredField(fieldName)
                field.isAccessible = true
                field.get(member)?.toString()?.trim() ?: ""
            }.getOrDefault("")
            if (value.isNotEmpty()) return value
        }

        return extractUsernameOnly(member).ifEmpty { "" }
    }

    fun hasRecipientIds(channel: Any?): Boolean {
        if (channel == null) return false

        return runCatching {
            // Try to access recipientIds field or method
            val fieldNames = listOf("recipientIds", "recipient_ids", "members", "recipients")
            for (fieldName in fieldNames) {
                val field = channel.javaClass.getDeclaredField(fieldName)
                field.isAccessible = true
                val value = field.get(channel)
                if (value is Collection<*> && value.isNotEmpty()) {
                    return@runCatching true
                }
            }
            false
        }.getOrDefault(false)
    }

    fun resolveDmRecipientName(channel: Any?, fallbackName: String): String {
        if (channel == null) return fallbackName

        println("[KeyIntercept] [resolveDmRecipientName] Starting DM recipient resolution (fallback='$fallbackName')")
        println("[KeyIntercept] [resolveDmRecipientName] Channel class: ${channel.javaClass.simpleName}")

        return runCatching {
            val recipientIds = extractRecipientIdsFromMember(channel)
            val recipientName = if (recipientIds.isNotEmpty()) {
                resolveUserNameFromMember(channel)
            } else {
                ""
            }
            recipientName.ifEmpty { fallbackName }
        }.getOrDefault(fallbackName)
    }
}
