package com.github.supersliser.discord

import com.discord.stores.StoreStream
import com.github.supersliser.supabase.SupabaseClient
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor

class DiscordResolver(private val supabaseClient: SupabaseClient) {

    fun getPreviouslySentMessage(channelIdOverride: Long? = null): Any? {
        return runCatching {
            val channelId = channelIdOverride ?: resolveSelectedChannelId() ?: return@runCatching null
            val messagesStore = runCatching {
                StoreStream::class.java.getMethod("getMessages").invoke(null)
            }.getOrNull() ?: return@runCatching null

            val messageContainer = invokeMethodIfExists(messagesStore, listOf("getMessages"), channelId)
                ?: invokeMethodIfExists(messagesStore, listOf("getMessageList", "get", "getForChannel"), channelId)
                ?: return@runCatching null

            extractLatestMessageFromContainer(messageContainer)
        }.onFailure {
            println("[KeyIntercept] Failed to resolve previously sent message: ${it.message}")
        }.getOrNull()
    }

    fun getAuthorOfPreviouslySentMessage(channelIdOverride: Long? = null): Any? {
        val previous = getPreviouslySentMessage(channelIdOverride) ?: return null

        val fromMethod = invokeMethodIfExists(previous, listOf("getAuthor", "author", "getUser"))
        if (fromMethod != null) return fromMethod

        val fromField = readFieldIfExists(previous, listOf("author", "user", "messageAuthor"))
        if (fromField != null) return fromField

        return null
    }

    fun getPreviouslySentMessageContent(channelIdOverride: Long? = null): String? {
        val previous = getPreviouslySentMessage(channelIdOverride) ?: return null

        val fromMethod = invokeMethodIfExists(previous, listOf("getContent", "content", "getMessage"))
        val methodContent = fromMethod?.toString()?.trim().orEmpty()
        if (methodContent.isNotEmpty()) return methodContent

        val fromField = readFieldIfExists(previous, listOf("content", "message", "body"))
        val fieldContent = fromField?.toString()?.trim().orEmpty()
        if (fieldContent.isNotEmpty()) return fieldContent

        return null
    }

    fun getAuthorNameOfPreviouslySentMessage(channelIdOverride: Long? = null): String? {
        val author = getAuthorOfPreviouslySentMessage(channelIdOverride) ?: return null
        val username = extractUsernameOnly(author).trim()
        return username.ifEmpty { null }
    }

    fun editPreviousMessage(newContent: String, channelIdOverride: Long? = null): Boolean {
        val previous = getPreviouslySentMessage(channelIdOverride) ?: return false
        val messageId = extractMessageId(previous) ?: return false
        return editMessageById(messageId, newContent, channelIdOverride)
    }

    fun editMessageById(messageId: Long, newContent: String, channelIdOverride: Long? = null): Boolean {
        return runCatching {
            val channelId = channelIdOverride ?: resolveSelectedChannelId() ?: return@runCatching false
            val restApi = resolveRestApiInstance() ?: return@runCatching false
            val payload = buildRestApiMessagePayload(newContent)

            val methodNames = listOf(
                "editMessage",
                "editChannelMessage",
                "patchChannelMessage",
                "updateMessage"
            )

            for (methodName in methodNames) {
                val methods = restApi.javaClass.methods.filter { it.name == methodName }
                for (method in methods) {
                    val args = buildArgsForMethod(method.parameterTypes, channelId, messageId, payload, newContent)
                        ?: continue
                    runCatching {
                        method.invoke(restApi, *args)
                        return@runCatching true
                    }.onSuccess {
                        return@runCatching true
                    }
                }
            }

            false
        }.onFailure {
            println("[KeyIntercept] Failed to edit message id=$messageId: ${it.message}")
        }.getOrDefault(false)
    }

    fun resolveSelectedChannelId(): Long? {
        return runCatching {
            val selected = runCatching {
                StoreStream::class.java.getMethod("getChannelsSelected").invoke(null)
            }.getOrNull() ?: return@runCatching null

            // Introspection: log class, available methods and fields to help diagnose API changes
            try {
                val cls = selected.javaClass
                println("[KeyIntercept][Inspect] Selected object class=${cls.name}")
                val methodNames = cls.methods.map { it.name }.distinct().take(50)
                val fieldNames = cls.declaredFields.map { it.name }.distinct().take(50)
                println("[KeyIntercept][Inspect] Selected methods=${methodNames.joinToString(", ")}")
                println("[KeyIntercept][Inspect] Selected fields=${fieldNames.joinToString(", ")}")
            } catch (e: Exception) {
                println("[KeyIntercept][Inspect] Failed to introspect selected object: ${e.message}")
            }

            val fromMethod = invokeMethodIfExists(
                selected,
                listOf("getId", "getChannelId", "getSelectedChannelId", "id", "channelId")
            )
            val methodId = extractLongValues(fromMethod).firstOrNull()
            if (methodId != null && methodId > 0L) return@runCatching methodId

            val fromField = readFieldIfExists(
                selected,
                listOf("id", "channelId", "selectedChannelId", "selected_channel_id")
            )
            val fieldId = extractLongValues(fromField).firstOrNull()
            if (fieldId != null && fieldId > 0L) return@runCatching fieldId

            null
        }.getOrNull()
    }

    private fun extractLatestMessageFromContainer(container: Any?): Any? {
        if (container == null) return null

        val directMethod = invokeMethodIfExists(
            container,
            listOf("getLatestMessage", "getNewestMessage", "latest", "peek", "last")
        )
        if (directMethod != null) return directMethod

        val valuesMethod = invokeMethodIfExists(container, listOf("getMessages", "values", "toList", "all"))
        val sequence = toIterable(valuesMethod ?: container)
        if (sequence.isNotEmpty()) {
            return sequence
                .mapNotNull { item ->
                    val id = extractMessageId(item)
                    if (id == null || id <= 0L) null else id to item
                }
                .maxByOrNull { it.first }
                ?.second
        }

        return null
    }

    private fun resolveRestApiInstance(): Any? {
        val classNames = listOf(
            "com.discord.restapi.RestAPI",
            "com.discord.utilities.rest.RestAPI"
        )

        for (className in classNames) {
            val clazz = runCatching { Class.forName(className) }.getOrNull() ?: continue

            val staticGetters = listOf("getApi", "api", "getInstance", "instance")
            for (getter in staticGetters) {
                val value = runCatching {
                    val method = clazz.methods.firstOrNull { it.name == getter && it.parameterCount == 0 }
                    method?.invoke(null)
                }.getOrNull()
                if (value != null) return value
            }

            val staticFields = listOf("api", "INSTANCE", "instance")
            for (fieldName in staticFields) {
                val value = runCatching {
                    val field = clazz.getDeclaredField(fieldName)
                    field.isAccessible = true
                    field.get(null)
                }.getOrNull()
                if (value != null) return value
            }

            val companion = runCatching {
                val companionField = clazz.getDeclaredField("Companion")
                companionField.isAccessible = true
                companionField.get(null)
            }.getOrNull()

            if (companion != null) {
                for (getter in staticGetters) {
                    val value = runCatching {
                        val method = companion.javaClass.methods.firstOrNull { it.name == getter && it.parameterCount == 0 }
                        method?.invoke(companion)
                    }.getOrNull()
                    if (value != null) return value
                }
            }
        }

        return null
    }

    private fun buildRestApiMessagePayload(content: String): Any {
        val payloadJson = JSONObject().put("content", content)
        val constructors = com.discord.restapi.RestAPIParams.Message::class.java.declaredConstructors

        for (ctor in constructors) {
            val args = Array<Any?>(ctor.parameterCount) { idx ->
                val type = ctor.parameterTypes[idx]
                when {
                    JSONObject::class.java.isAssignableFrom(type) -> payloadJson
                    type == String::class.java -> null
                    type == java.lang.Boolean.TYPE || type == java.lang.Boolean::class.java -> false
                    type == java.lang.Integer.TYPE || type == java.lang.Integer::class.java -> 0
                    type == java.lang.Long.TYPE || type == java.lang.Long::class.java -> 0L
                    type == java.lang.Double.TYPE || type == java.lang.Double::class.java -> 0.0
                    type == java.lang.Float.TYPE || type == java.lang.Float::class.java -> 0f
                    else -> null
                }
            }

            val hasJsonParam = ctor.parameterTypes.any { JSONObject::class.java.isAssignableFrom(it) }
            if (!hasJsonParam) continue

            runCatching {
                ctor.isAccessible = true
                return ctor.newInstance(*args)
            }
        }

        return payloadJson
    }

    private fun buildArgsForMethod(
        parameterTypes: Array<Class<*>>,
        channelId: Long,
        messageId: Long,
        payload: Any,
        newContent: String
    ): Array<Any?>? {
        val args = Array<Any?>(parameterTypes.size) { null }
        var usedChannel = false
        var usedMessage = false
        var usedPayload = false

        for (i in parameterTypes.indices) {
            val type = parameterTypes[i]
            when {
                (type == java.lang.Long.TYPE || type == java.lang.Long::class.java) && !usedChannel -> {
                    args[i] = channelId
                    usedChannel = true
                }

                (type == java.lang.Long.TYPE || type == java.lang.Long::class.java) && !usedMessage -> {
                    args[i] = messageId
                    usedMessage = true
                }

                type.isAssignableFrom(payload.javaClass) && !usedPayload -> {
                    args[i] = payload
                    usedPayload = true
                }

                JSONObject::class.java.isAssignableFrom(type) && !usedPayload -> {
                    args[i] = JSONObject().put("content", newContent)
                    usedPayload = true
                }

                type == String::class.java && !usedPayload -> {
                    args[i] = newContent
                    usedPayload = true
                }

                type == java.lang.Boolean.TYPE || type == java.lang.Boolean::class.java -> args[i] = false
                type == java.lang.Integer.TYPE || type == java.lang.Integer::class.java -> args[i] = 0
                type == java.lang.Double.TYPE || type == java.lang.Double::class.java -> args[i] = 0.0
                type == java.lang.Float.TYPE || type == java.lang.Float::class.java -> args[i] = 0f
                else -> args[i] = null
            }
        }

        if (!usedChannel || !usedMessage || !usedPayload) return null
        return args
    }

    private fun extractMessageId(message: Any?): Long? {
        if (message == null) return null

        val fromMethod = invokeMethodIfExists(message, listOf("getId", "id", "getMessageId", "messageId"))
        val methodId = extractLongValues(fromMethod).firstOrNull()
        if (methodId != null && methodId > 0L) return methodId

        val fromField = readFieldIfExists(message, listOf("id", "messageId", "message_id"))
        val fieldId = extractLongValues(fromField).firstOrNull()
        if (fieldId != null && fieldId > 0L) return fieldId

        return null
    }

    fun invokeMethodIfExists(target: Any, names: List<String>, vararg args: Any?): Any? {
        for (name in names) {
            val methods = target.javaClass.methods.filter { it.name == name && it.parameterCount == args.size }
            for (method in methods) {
                val result = runCatching { method.invoke(target, *args) }.getOrNull()
                if (result != null) return result
            }
        }
        return null
    }

    fun readFieldIfExists(target: Any, names: List<String>): Any? {
        for (name in names) {
            val value = runCatching {
                val field = target.javaClass.getDeclaredField(name)
                field.isAccessible = true
                field.get(target)
            }.getOrNull()

            if (value != null) return value
        }
        return null
    }

    private fun toIterable(value: Any?): List<Any?> {
        if (value == null) return emptyList()

        return when (value) {
            is Iterable<*> -> value.toList()
            is Array<*> -> value.toList()
            is Map<*, *> -> value.values.toList()
            else -> emptyList()
        }
    }

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
