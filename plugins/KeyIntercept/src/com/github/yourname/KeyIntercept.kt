package com.github.supersliser

import android.content.Context
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.utils.ChannelUtils
import com.aliucord.wrappers.ChannelWrapper
import com.discord.stores.StoreStream
import com.discord.utilities.messagesend.MessageQueue
import de.robv.android.xposed.XC_MethodHook
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Collections
import java.util.Date
import java.util.IdentityHashMap
import java.util.ArrayList
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import kotlin.random.Random

@AliucordPlugin(requiresRestart = false)
class KeyIntercept : Plugin() {
    private data class ConversationContext(
        val channelId: Long?,
        val channelName: String,
        val dmName: String,
        val serverName: String
    )

    private companion object {
        const val CONTENT_FIELD = "content"
        const val MESSAGE_FIELD = "message"
        const val PREPROCESSING_FIELD = "onPreprocessing"
        const val SUPABASE_URL = "https://qjzgfwithyvmwctesnqs.supabase.co"
        const val SUPABASE_KEY = "sb_publishable_cxq8QZp9BDtjE4G5qiPCFA_lUZ4Cbdh"

        var config: KeyInterceptConfig = KeyInterceptConfig(
            id = 1,
            createdAt = Date().time,
            updatedAt = Date().time,
            rulesEnd = Date().time + 7 * 24 * 60 * 60 * 1000L,
            gagEnd = Date().time + 7 * 24 * 60 * 60 * 1000L,
            petEnd = Date().time + 7 * 24 * 60 * 60 * 1000L,
            petAmount = 0.5f,
            petType = 1,
            bimboEnd = Date().time + 7 * 24 * 60 * 60 * 1000L,
            hornyEnd = Date().time + 7 * 24 * 60 * 60 * 1000L,
            bimboWordLength = 5,
            droneEnd = Date().time + 7 * 24 * 60 * 60 * 1000L,
            droneHeaderText = "Drone Header",
            droneFooterText = "Drone Footer",
            droneHealth = 0.75f,
            uwuEnd = Date().time + 7 * 24 * 60 * 60 * 1000L,
            debug = true
        )

        var rules: List<Rule> = listOf(
            Rule(
                id = 1,
                createdAt = Date().time,
                updatedAt = Date().time,
                configId = 1,
                ruleRegex = "\\bhello\\b",
                ruleReplacement = "hi",
                enabled = true,
                chanceToApply = 1.0f
            )
        )

        var whitelist: List<ServerWhitelistItem> = listOf(
            ServerWhitelistItem(
                configId = 1,
                serverName = "Example Server"
            )
        )

        var petWords: List<PetWord> = listOf(
            PetWord(id = 1, petType = 1, word = "pet"),
            PetWord(id = 2, petType = 2, word = "kitty"),
            PetWord(id = 3, petType = 3, word = "puppy")
        )
    }

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

    data class Rule(
        val id: Long = 0,
        val createdAt: Long = 0,
        val updatedAt: Long = 0,
        val configId: Long = 0,
        val ruleRegex: String = "",
        val ruleReplacement: String = "",
        val enabled: Boolean = false,
        val chanceToApply: Float = 0f
    )

    data class ServerWhitelistItem(
        val id: Long = 0,
        val configId: Long = 0,
        val serverName: String = ""
    )

    data class PetWord(
        val id: Long = 0,
        val petType: Long = 0,
        val word: String = ""
    )

    private val wrappedCallbacks = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private var supabasePollExecutor: ScheduledExecutorService? = null
    private val localDebugOverride = true
    @Volatile
    private var initialSupabaseSyncComplete = false

    private fun logDebug(message: String) {
        if (localDebugOverride || config.debug) {
            logger.info("[KeyIntercept][Debug] $message")
        }
    }

    private fun formatConfigDetails(value: KeyInterceptConfig): String {
        return "id=${value.id}, createdAt=${value.createdAt}, updatedAt=${value.updatedAt}, " +
            "rulesEnd=${value.rulesEnd}, gagEnd=${value.gagEnd}, petEnd=${value.petEnd}, " +
            "petAmount=${value.petAmount}, petType=${value.petType}, bimboEnd=${value.bimboEnd}, " +
            "hornyEnd=${value.hornyEnd}, bimboWordLength=${value.bimboWordLength}, " +
            "droneEnd=${value.droneEnd}, droneHeaderText='${value.droneHeaderText}', " +
            "droneFooterText='${value.droneFooterText}', droneHealth=${value.droneHealth}, " +
            "uwuEnd=${value.uwuEnd}, debug=${value.debug}"
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
    }

    private fun supabaseGet(table: String, filters: Map<String, String> = emptyMap()): String {
        val query = buildString {
            append("select=*")
            for ((key, value) in filters) {
                append('&')
                append(urlEncode(key))
                append("=eq.")
                append(urlEncode(value))
            }
        }

        val endpoint = "$SUPABASE_URL/rest/v1/$table?$query"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("apikey", SUPABASE_KEY)
            setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
            setRequestProperty("Accept", "application/json")
        }

        return try {
            val body = runCatching {
                connection.inputStream.bufferedReader().use { it.readText() }
            }.getOrElse {
                connection.errorStream?.bufferedReader()?.use { r -> r.readText() } ?: ""
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                error("Supabase request failed for $table (HTTP $code): $body")
            }

            body
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.readLong(key: String, default: Long = 0L): Long {
        if (!has(key) || isNull(key)) return default
        val value = get(key)
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: default
            else -> default
        }
    }

    private fun JSONObject.readInt(key: String, default: Int = 0): Int {
        if (!has(key) || isNull(key)) return default
        val value = get(key)
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: default
            else -> default
        }
    }

    private fun JSONObject.readFloat(key: String, default: Float = 0f): Float {
        if (!has(key) || isNull(key)) return default
        val value = get(key)
        return when (value) {
            is Number -> value.toFloat()
            is String -> value.toFloatOrNull() ?: default
            else -> default
        }
    }

    private fun JSONObject.readBoolean(key: String, default: Boolean = false): Boolean {
        if (!has(key) || isNull(key)) return default
        val value = get(key)
        return when (value) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            is Number -> value.toInt() != 0
            else -> default
        }
    }

    private fun JSONObject.readString(key: String, default: String = ""): String {
        if (!has(key) || isNull(key)) return default
        return runCatching { getString(key) }.getOrElse { default }
    }

    private fun parseEpochLikeToMillis(value: Long): Long {
        return if (value in -99_999_999_999L..99_999_999_999L) value * 1000L else value
    }

    private fun parseTimestampStringToMillis(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val numeric = trimmed.toLongOrNull()
        if (numeric != null) return parseEpochLikeToMillis(numeric)

        var normalized = trimmed
        if (normalized.indexOf(' ') >= 0 && normalized.indexOf('T') < 0) {
            normalized = normalized.replace(' ', 'T')
        }

        // Supabase/Postgres can send microseconds; reduce to millis precision for parsing.
        val fracStart = normalized.indexOf('.')
        if (fracStart >= 0) {
            var fracEnd = normalized.indexOf('Z', fracStart)
            if (fracEnd < 0) {
                val plusPos = normalized.indexOf('+', fracStart)
                val minusPos = normalized.indexOf('-', fracStart + 1)
                fracEnd = when {
                    plusPos >= 0 && minusPos >= 0 -> minOf(plusPos, minusPos)
                    plusPos >= 0 -> plusPos
                    minusPos >= 0 -> minusPos
                    else -> normalized.length
                }
            }

            val frac = normalized.substring(fracStart + 1, fracEnd)
            if (frac.length > 3) {
                normalized = normalized.substring(0, fracStart + 1) + frac.substring(0, 3) + normalized.substring(fracEnd)
            }
        }

        val patterns = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss.SSS",
            "yyyy-MM-dd HH:mm:ss"
        )

        for (pattern in patterns) {
            val parser = SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val parsed = runCatching { parser.parse(normalized) }.getOrNull()
            if (parsed != null) return parsed.time
        }

        return null
    }

    private fun JSONObject.readTimestampMillis(key: String, default: Long = 0L): Long {
        if (!has(key) || isNull(key)) return default
        val value = get(key)
        return when (value) {
            is Number -> parseEpochLikeToMillis(value.toLong())
            is String -> parseTimestampStringToMillis(value) ?: default
            else -> default
        }
    }

    private fun fetchConfigFromSupabase(): KeyInterceptConfig? {
        return runCatching {
            val body = supabaseGet("Config", mapOf("id" to config.id.toString()))
            val arr = JSONArray(body)
            if (arr.length() == 0) {
                null
            } else {
                val obj = arr.getJSONObject(0)
                KeyInterceptConfig(
                    id = obj.readLong("id", config.id),
                    createdAt = obj.readTimestampMillis("created_at"),
                    updatedAt = obj.readTimestampMillis("updated_at"),
                    rulesEnd = obj.readTimestampMillis("rules_end"),
                    gagEnd = obj.readTimestampMillis("gag_end"),
                    petEnd = obj.readTimestampMillis("pet_end"),
                    petAmount = obj.readFloat("pet_amount"),
                    petType = obj.readLong("pet_type", config.petType),
                    bimboEnd = obj.readTimestampMillis("bimbo_end"),
                    hornyEnd = obj.readTimestampMillis("horny_end"),
                    bimboWordLength = obj.readInt("bimbo_word_length"),
                    droneEnd = obj.readTimestampMillis("drone_end"),
                    droneHeaderText = obj.readString("drone_header_text"),
                    droneFooterText = obj.readString("drone_footer_text"),
                    droneHealth = obj.readFloat("drone_health"),
                    uwuEnd = obj.readTimestampMillis("uwu_end"),
                    debug = obj.readBoolean("debug")
                )
            }
        }.onFailure {
            logger.error("Failed to fetch config from Supabase", it)
        }.getOrNull()
    }

    private fun resolveCurrentDiscordId(): Long? {
        return runCatching {
            val usersStore = StoreStream::class.java.getMethod("getUsers").invoke(null)
            if (usersStore == null) return@runCatching null

            val me = listOf("getMe", "getCurrentUser", "getSelf")
                .asSequence()
                .mapNotNull { methodName ->
                    runCatching { usersStore.javaClass.getMethod(methodName).invoke(usersStore) }.getOrNull()
                }
                .firstOrNull()
                ?: return@runCatching null

            readLongField(me, "id", "userId")
        }.onFailure {
            logger.error("Failed to resolve current Discord user id", it)
        }.getOrNull()
    }

    private fun resolveConfigIdForCurrentUser(): Long? {
        return runCatching {
            val discordId = resolveCurrentDiscordId() ?: return@runCatching null
            logDebug("Resolving config id for discord_id=$discordId")

            val profilesBody = supabaseGet("profiles", mapOf("discord_id" to discordId.toString()))
            val profilesArray = JSONArray(profilesBody)
            if (profilesArray.length() == 0) {
                logger.warn("No row in profiles for discord_id=$discordId")
                return@runCatching null
            }

            val subIdRaw = runCatching {
                profilesArray.getJSONObject(0).get("id").toString()
            }.getOrDefault("")
            val subId = subIdRaw.trim()
            if (subId.isEmpty()) {
                logger.warn("profiles row for discord_id=$discordId does not contain a valid id")
                return@runCatching null
            }

            val accessBody = supabaseGet("Sub_Config_Access", mapOf("sub_id" to subId))
            val accessArray = JSONArray(accessBody)
            if (accessArray.length() == 0) {
                logger.warn("No row in Sub_Config_Access for sub_id=$subId")
                return@runCatching null
            }

            val rawConfigId = runCatching { accessArray.getJSONObject(0).get("config_id") }.getOrNull()
            val configId = when (rawConfigId) {
                is Number -> rawConfigId.toLong()
                is String -> rawConfigId.toLongOrNull()
                else -> null
            }
            if (configId == null || configId <= 0L) {
                logger.warn("Sub_Config_Access row for sub_id=$subId has non-numeric config_id=$rawConfigId")
                return@runCatching null
            }

            configId
        }.onFailure {
            logger.error("Failed to resolve config id from profiles/Sub_Config_Access", it)
        }.getOrNull()
    }

    private fun fetchRulesFromSupabase(): List<Rule> {
        return runCatching {
            val body = supabaseGet("Rules", mapOf("config_id" to config.id.toString()))
            val arr = JSONArray(body)
            val out = ArrayList<Rule>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                out.add(
                    Rule(
                        id = obj.readLong("id"),
                        createdAt = obj.readLong("created_at"),
                        updatedAt = obj.readLong("updated_at"),
                        configId = obj.readLong("config_id"),
                        ruleRegex = obj.readString("rule_regex"),
                        ruleReplacement = obj.readString("rule_replacement"),
                        enabled = obj.readBoolean("enabled"),
                        chanceToApply = obj.readFloat("chance_to_apply")
                    )
                )
            }
            out
        }.onFailure {
            logger.error("Failed to fetch rules from Supabase", it)
        }.getOrDefault(emptyList())
    }

    private fun fetchWhitelistFromSupabase(): List<ServerWhitelistItem> {
        return runCatching {
            val body = supabaseGet("Server_Whitelist_Items", mapOf("config_id" to config.id.toString()))
            val arr = JSONArray(body)
            val out = ArrayList<ServerWhitelistItem>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                out.add(
                    ServerWhitelistItem(
                        id = obj.readLong("id"),
                        configId = obj.readLong("config_id"),
                        serverName = obj.readString("server_name")
                    )
                )
            }
            out
        }.onFailure {
            logger.error("Failed to fetch whitelist from Supabase", it)
        }.getOrDefault(emptyList())
    }

    private fun fetchPetWordsFromSupabase(): List<PetWord> {
        return runCatching {
            val body = supabaseGet("Pet_Type_Words", mapOf("pet_type" to config.petType.toString()))
            val arr = JSONArray(body)
            val out = ArrayList<PetWord>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                out.add(
                    PetWord(
                        id = obj.readLong("id"),
                        petType = obj.readLong("pet_type"),
                        word = obj.readString("word")
                    )
                )
            }
            out
        }.onFailure {
            logger.error("Failed to fetch pet words from Supabase", it)
        }.getOrDefault(emptyList())
    }

    private fun refreshFromSupabase(reason: String) {
        runCatching {
            fetchConfigFromSupabase()?.let {
                config = it
                logDebug("Supabase config updated ($reason): ${formatConfigDetails(config)}")
            }

            val fetchedRules = fetchRulesFromSupabase()
            if (fetchedRules.isNotEmpty()) {
                rules = fetchedRules
                logDebug("Supabase rules updated ($reason): ${rules.size}")
            }

            val fetchedWhitelist = fetchWhitelistFromSupabase()
            if (fetchedWhitelist.isNotEmpty()) {
                whitelist = fetchedWhitelist
                logDebug("Supabase whitelist updated ($reason): ${whitelist.size}")
            }

            val fetchedPetWords = fetchPetWordsFromSupabase()
            if (fetchedPetWords.isNotEmpty()) {
                petWords = fetchedPetWords
                logDebug("Supabase pet words updated ($reason): ${petWords.size}")
            }
        }.onFailure {
            logger.error("Supabase refresh failed ($reason)", it)
        }
    }

    private fun setupSupabasePolling() {
        val executor = supabasePollExecutor
        if (executor == null) {
            logger.warn("Supabase poll executor missing; polling not started")
            return
        }

        executor.scheduleWithFixedDelay({
            refreshFromSupabase("poll")
        }, 15L, 15L, TimeUnit.SECONDS)

        logDebug("Supabase polling started (15s interval)")
    }

    private fun preview(input: String, maxLen: Int = 120): String {
        return if (input.length <= maxLen) input else input.take(maxLen) + "..."
    }

    private fun describeArg(arg: Any?): String {
        if (arg == null) return "null"
        val klass = arg.javaClass.simpleName
        val content = getFieldValue(arg, CONTENT_FIELD) as? String
        return if (content != null) "$klass(content=\"${preview(content)}\")" else klass
    }

    override fun load(context: Context) {
        logger.info("KeyIntercept loaded")
        initialSupabaseSyncComplete = false
        logDebug("Initial config: ${formatConfigDetails(config)}")
        logDebug("Initial data sizes: rules=${rules.size}, whitelist=${whitelist.map { it.serverName }}, petWords=${petWords.size}")

        supabasePollExecutor?.shutdownNow()
        supabasePollExecutor = Executors.newSingleThreadScheduledExecutor()

        val executor = supabasePollExecutor
        if (executor == null) {
            logger.warn("Supabase poll executor could not be created; using local/default values")
            initialSupabaseSyncComplete = true
            return
        }

        executor.execute {
            val resolvedConfigId = resolveConfigIdForCurrentUser()
            if (resolvedConfigId != null && resolvedConfigId != config.id) {
                logDebug("Resolved config id from profiles/Sub_Config_Access: $resolvedConfigId")
                config = config.copy(id = resolvedConfigId)
            } else if (resolvedConfigId == null) {
                logger.warn("Could not resolve config id from Supabase access mapping; using fallback config id=${config.id}")
            }

            refreshFromSupabase("initial")
            initialSupabaseSyncComplete = true
            setupSupabasePolling()
            logDebug("Initial Supabase sync complete")
        }
    }

    override fun start(context: Context) {
        val targetMethods = MessageQueue::class.java.declaredMethods
            .filter { it.name == "doSend" || it.name == "enqueue" }

        if (targetMethods.isEmpty()) {
            logger.warn("Could not find MessageQueue send methods to patch")
            return
        }

        logger.info(
            "Hooking MessageQueue methods: ${
                targetMethods.joinToString {
                    it.name + it.parameterTypes.joinToString(
                        prefix = "(",
                        postfix = ")"
                    ) { p -> p.simpleName }
                }
            }"
        )
        logDebug("Installing hooks for ${targetMethods.size} MessageQueue method(s)")

        targetMethods.forEach { method ->
            patcher.patch(method, Hook { hookParam: XC_MethodHook.MethodHookParam ->
                logDebug(
                    "Hook hit: MessageQueue.${method.name} args=${hookParam.args.joinToString(prefix = "[", postfix = "]") { describeArg(it) }}"
                )

                val contextSource = hookParam.args.firstOrNull { it != null && it !is String }
                var changedStringArg = false
                hookParam.args.forEachIndexed { index, arg ->
                    if (arg is String && contextSource != null) {
                        val updated = alterMessage(contextSource, arg)
                        if (updated != arg) {
                            hookParam.args[index] = updated
                            changedStringArg = true
                            logDebug("Mutated String arg[$index] in MessageQueue.${method.name}")
                        }
                    }
                }

                val changed = hookParam.args.any { arg -> arg != null && mutateOutgoingData(arg) }
                if (changed || changedStringArg) {
                    logger.info("Modified outgoing message in MessageQueue.${method.name}")
                } else {
                    logDebug("No mutation performed in MessageQueue.${method.name}")
                }
            })
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()

        supabasePollExecutor?.shutdownNow()
        supabasePollExecutor = null
        initialSupabaseSyncComplete = false
    }

    private fun mutateOutgoingData(candidate: Any): Boolean {
        if (!initialSupabaseSyncComplete) {
            logDebug("Skipping mutation because initial Supabase sync is not complete yet")
            return false
        }

        logDebug("Inspecting candidate ${candidate.javaClass.name}")
        var changed = false

        if (wrapPreprocessingCallback(candidate)) {
            changed = true
        }

        val nestedMessage = getFieldValue(candidate, MESSAGE_FIELD)
        if (nestedMessage == null) {
            logDebug("No nested '$MESSAGE_FIELD' field on ${candidate.javaClass.simpleName}")
        }
        if (nestedMessage != null && mutateContentField(nestedMessage, "Message")) {
            changed = true
        }

        if (mutateContentField(candidate, candidate.javaClass.simpleName)) {
            changed = true
        }

        return changed
    }

    private fun checkWhitelist(serverName: String): Boolean {
        if (whitelist.isEmpty() || whitelist.all { it.serverName == "Example Server" }) {
            logDebug("Whitelist is empty, allowing all servers by default")
            return true
        }
        val allowed = whitelist.any { it.serverName == serverName }
        logDebug("Whitelist check for '$serverName' => $allowed (allowed=${whitelist.map { it.serverName }})")
        return allowed
    }

    private fun shouldApplyRules(): Boolean = config.rulesEnd > System.currentTimeMillis()

    private fun shouldApplyGag(): Boolean = config.gagEnd > System.currentTimeMillis()

    private fun shouldApplyPet(): Boolean = config.petEnd > System.currentTimeMillis()

    private fun shouldApplyBimbo(): Boolean = config.bimboEnd > System.currentTimeMillis()

    private fun shouldApplyHorny(): Boolean = config.hornyEnd > System.currentTimeMillis()

    private fun shouldApplyDrone(): Boolean = config.droneEnd > System.currentTimeMillis()

    private fun shouldApplyUwu(): Boolean = config.uwuEnd > System.currentTimeMillis()

    private fun applyRules(content: String): String {
        if (!shouldApplyRules()) return content
        var modified = content
        for (rule in rules) {
            if (rule.enabled && Random.nextFloat() < rule.chanceToApply) {
                modified = modified.replace(Regex(rule.ruleRegex), rule.ruleReplacement)
            }
        }
        return modified
    }

    private fun applyGag(content: String): String {
        if (!shouldApplyGag()) return content
        val remainChars = setOf(
            'a', 'e', 'i', 'o', 'u', 'g', 'h',
            'A', 'E', 'I', 'O', 'U', 'G', 'H',
            '?', '!', '.', ',', ':', ';', '#', '*', '-', '(', ')', '~'
        )

        val output = StringBuilder()
        for (word in content.split(" ")) {
            if (wordIsLink(word)) {
                output.append(word).append(' ')
                continue
            }

            for (ch in word) {
                if (remainChars.contains(ch)) {
                    output.append(ch)
                } else {
                    output.append(
                        if (ch.isUpperCase()) {
                            if (Random.nextBoolean()) 'G' else 'H'
                        } else {
                            if (Random.nextBoolean()) 'g' else 'h'
                        }
                    )
                }
            }
            output.append(' ')
        }

        return output.toString().trimEnd()
    }

    private fun applyPet(content: String): String {
        if (!shouldApplyPet()) return content
        val output = StringBuilder()
        for (word in content.split(" ")) {
            if (wordIsLink(word)) {
                output.append(word).append(' ')
                continue
            }
            if (Random.nextFloat() < config.petAmount) {
                output.append(petWords.randomOrNull()?.word ?: "pet").append(' ')
            } else {
                output.append(word).append(' ')
            }
        }
        return output.toString().trimEnd()
    }

    private fun applyBimbo(content: String): String {
        if (!shouldApplyBimbo()) return content

        val pronouns = setOf("i", "is", "you", "he", "she", "we", "they", "it")
        val gargleWords = listOf("like", "hehe", "uhh", "totally", "so dumbb")
        val output = StringBuilder()

        for (word in content.split(" ")) {
            var changed = false
            if (!wordIsLink(word)) {
                if (pronouns.contains(word.lowercase())) {
                    output.append(word).append(" like totally ")
                    changed = true
                }
                if (word.length > config.bimboWordLength && config.bimboWordLength > 2) {
                    output.append(word.substring(0, config.bimboWordLength - 2))
                    output.append("uhhhh long words hardd hehe")
                    return output.toString()
                }
            }

            if (!changed) {
                output.append(word).append(' ')
            }

            if (Random.nextFloat() < 0.1f) {
                output.append(gargleWords.random()).append(' ')
            }
        }

        return output.toString().trimEnd()
    }

    private fun applyHorny(content: String): String {
        if (!shouldApplyHorny()) return content

        val hornyWords = listOf(
            "hmmph", "nngh", "ahhh", "ooh", "oohh", "mmm", "hehe", "hehehe", "heheh",
            "eheh", "ehehe", "eheheh", "guhh", "pleasee", "need to cumm", "oh goshh",
            "ohhh", "ahhh", "cummm", "gggg"
        )

        val output = StringBuilder()
        for (word in content.split(" ")) {
            output.append(word).append(' ')
            if (Random.nextFloat() < 0.75f) {
                output.append(hornyWords.random()).append(' ')
            }
        }
        return output.toString().trimEnd()
    }

    private fun applyDrone(content: String): String {
        if (!shouldApplyDrone()) return content

        if (config.droneHealth < 0.1f) {
            return "`This Drone haaaaas receieved bzzzzt, ppplease provide repaiirs using beep '/repair', tthank youu. Returned Error: 0x7547372482`"
        }

        val containsLink = content.split(" ").any(::wordIsLink)

        var working = content
        if (!containsLink) {
            working = working.replace(Regex("(?i)\\bMe\\b"), "This Drone")
            working = working.replace(Regex("(?i)\\bMy\\b"), "Its'")
            working = working.replace(Regex("(?i)\\bI am\\b"), "It is")
            working = working.replace(Regex("(?i)\\bI(')?m\\b"), "It is")
            working = working.replace(Regex("(?i)\\bI\\b"), "This Drone")
        }

        val phaseOne = StringBuilder()
        for (word in working.split(" ")) {
            if (!wordIsLink(word) && Random.nextFloat() > config.droneHealth) {
                phaseOne.append(listOf("`beep`", "`bzzt`").random()).append(' ')
            }
            phaseOne.append(word).append(' ')
        }

        val phaseTwo = StringBuilder()
        var lastTriggered = 0
        for (word in phaseOne.toString().trimEnd().split(" ")) {
            if (wordIsLink(word)) {
                phaseTwo.append(word).append(' ')
                continue
            }

            val outWord = StringBuilder()
            for (ch in word) {
                outWord.append(ch)
                lastTriggered += 1

                val noisyThreshold = (lastTriggered / 100f) - (config.droneHealth / 100f)
                if (ch != '`' && Random.nextFloat() < noisyThreshold.coerceAtLeast(0f)) {
                    lastTriggered = 0
                    val repeatCount = floor(Random.nextDouble(0.0, 10.0)).toInt()
                    repeat(repeatCount) { outWord.append(ch) }
                }
            }

            phaseTwo.append(outWord).append(' ')
        }

        return "`${config.droneHeaderText}`\n${phaseTwo.toString().trimEnd()}\n`${config.droneFooterText}`"
    }

    private fun applyUwu(content: String): String {
        if (!shouldApplyUwu()) return content

        val output = StringBuilder()
        for (word in content.split(" ")) {
            if (wordIsLink(word)) {
                output.append(word).append(' ')
                continue
            }

            var outWord = word
            outWord = outWord.replace(Regex("r|l"), "w")
            outWord = outWord.replace(Regex("R|L"), "W")
            outWord = outWord.replace(Regex("n([aeiou])"), "ny$1")
            outWord = outWord.replace(Regex("N([aeiou])"), "Ny$1")
            outWord = outWord.replace(Regex("N([AEIOU])"), "NY$1")
            outWord = outWord.replace(Regex("ove"), "uv")
            outWord = outWord.replace(Regex("OVE"), "UV")
            outWord = outWord.replace(Regex("th"), "d")
            outWord = outWord.replace(Regex("TH"), "D")
            outWord = outWord.replace(Regex("u"), "uw")
            outWord = outWord.replace(Regex("U"), "UW")

            output.append(outWord).append(' ')
        }

        return output.toString().trimEnd()
    }

    private fun wordIsLink(word: String): Boolean {
        return word.startsWith("http://") || word.startsWith("https://") || word.startsWith("www.")
    }

    private fun parseLongLike(value: Any?): Long? {
        return when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Short -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }

    private fun asNonBlankString(value: Any?): String? {
        val text = runCatching { value?.toString() }.getOrNull() ?: return null
        val trimmed = text.trim()
        return if (trimmed.isEmpty()) null else trimmed
    }

    private fun readLongField(target: Any, vararg fieldNames: String): Long? {
        for (fieldName in fieldNames) {
            val parsed = parseLongLike(getFieldValue(target, fieldName))
            if (parsed != null) return parsed
        }
        return null
    }

    private fun resolveChannelId(source: Any): Long? {
        readLongField(source, "channelId", "channel_id")?.let { return it }

        val sourceChannel = getFieldValue(source, "channel")
        if (sourceChannel != null) {
            readLongField(sourceChannel, "id", "channelId", "channel_id")?.let { return it }
        }

        val messageObj = getFieldValue(source, MESSAGE_FIELD)
        if (messageObj != null) {
            readLongField(messageObj, "channelId", "channel_id")?.let { return it }

            val messageChannel = getFieldValue(messageObj, "channel")
            if (messageChannel != null) {
                readLongField(messageChannel, "id", "channelId", "channel_id")?.let { return it }
            }
        }

        return null
    }

    private fun resolveConversationContext(source: Any): ConversationContext {
        val channelId = resolveChannelId(source)
        if (channelId == null) {
            logDebug("Could not resolve channelId from ${source.javaClass.name}")
            return ConversationContext(null, "Unknown Channel", "Unknown DM", "Unknown Server")
        }

        val channel = StoreStream.getChannels().getChannel(channelId)
        if (channel == null) {
            logDebug("StoreStream returned null channel for channelId=$channelId")
            return ConversationContext(channelId, "Unknown Channel", "Unknown DM", "Unknown Server")
        }

        val channelWrapper = ChannelWrapper(channel)
        val channelName = asNonBlankString(
            runCatching { ChannelUtils.getDisplayName(channel) as Any? }.getOrNull()
        ) ?: "Unknown Channel"

        val isDm = runCatching { channelWrapper.isDM() }.getOrDefault(false)
        val dmName = if (isDm) {
            channelName.takeIf { it != "Unknown Channel" } ?: "Unknown DM"
        } else {
            "Unknown DM"
        }

        val guildId = runCatching { channelWrapper.guildId }.getOrNull()
        val serverName = if (guildId != null && guildId != 0L) {
            val guild = StoreStream.getGuilds().getGuild(guildId)
            (guild?.let {
                asNonBlankString(getFieldValue(it, "name"))
                    ?: asNonBlankString(getFieldValue(it, "guildName"))
            }) ?: "Unknown Server"
        } else {
            "Unknown Server"
        }

        return ConversationContext(channelId, channelName, dmName, serverName)
    }

    private fun isWhitelisted(context: ConversationContext): Boolean {
        val names = listOf(context.channelName, context.dmName, context.serverName)
            .filter { it != "Unknown Channel" && it != "Unknown DM" && it != "Unknown Server" }

        if (names.isEmpty()) {
            logDebug("No resolvable context names found for whitelist check")
            return false
        }

        val matched = names.any(::checkWhitelist)
        logDebug("Whitelist names considered=$names matched=$matched")
        return matched
    }

    private fun alterMessage(source: Any, content: String): String {
        val context = resolveConversationContext(source)
        val channelName = context.channelName
        val dmName = context.dmName
        val serverName = context.serverName

        logDebug(
            "alterMessage source=${source.javaClass.simpleName}, channelId=${context.channelId}, channel='$channelName', dm='$dmName', guild='$serverName', input='${preview(content)}'"
        )

        if (!isWhitelisted(context)) {
            logDebug("Skipping mutation because target is not whitelisted")
            return content
        }
        if (channelName.contains("sfw", ignoreCase = true) && !channelName.contains("nsfw", ignoreCase = true)) {
            logDebug("Skipping mutation because channel '$channelName' is SFW")
            return content
        }

        fun applyStage(name: String, input: String, transform: (String) -> String): String {
            val output = transform(input)
            if (output == input) {
                logDebug("Stage '$name' produced no changes")
            } else {
                logDebug("Stage '$name' changed text to '${preview(output)}'")
            }
            return output
        }

        var modified = content
        modified = applyStage("rules", modified, ::applyRules)
        modified = applyStage("uwu", modified, ::applyUwu)
        modified = applyStage("horny", modified, ::applyHorny)
        modified = applyStage("pet", modified, ::applyPet)
        modified = applyStage("bimbo", modified, ::applyBimbo)
        modified = applyStage("gag", modified, ::applyGag)
        modified = applyStage("drone", modified, ::applyDrone)
        return modified
    }

    private fun mutateContentField(target: Any, sourceName: String): Boolean {
        val content = getFieldValue(target, CONTENT_FIELD) as? String
        if (content == null) {
            logDebug("$sourceName has no '$CONTENT_FIELD' String field (class=${target.javaClass.name})")
            return false
        }

        val updated = alterMessage(target, content)
        if (updated == content) {
            logDebug("No content update for $sourceName")
            return false
        }

        return setFieldValue(target, CONTENT_FIELD, updated).also { success ->
            if (success) logger.info("Mutated $sourceName.content")
            if (!success) logDebug("Failed to write '$CONTENT_FIELD' back to ${target.javaClass.name}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun wrapPreprocessingCallback(target: Any): Boolean {
        val callback = getFieldValue(target, PREPROCESSING_FIELD) as? Function1<*, *>
        if (callback == null) {
            logDebug("No '$PREPROCESSING_FIELD' callback on ${target.javaClass.name}")
            return false
        }

        if (!wrappedCallbacks.add(callback)) {
            logDebug("Callback already wrapped for ${target.javaClass.name}")
            return false
        }

        val original = callback as Function1<Any?, Any?>
        val wrapped: (Any?) -> Any? = { input ->
            logDebug("Preprocessing callback invoked for ${target.javaClass.simpleName}")
            val transformedInput = when (input) {
                is String -> alterMessage(target, input)
                null -> null
                else -> {
                    mutateOutgoingData(input)
                    input
                }
            }

            val originalResult = original.invoke(transformedInput)
            val transformedResult = when (originalResult) {
                is String -> alterMessage(transformedInput ?: target, originalResult)
                null -> null
                else -> {
                    mutateOutgoingData(originalResult)
                    originalResult
                }
            }

            val nestedMessage = getFieldValue(target, MESSAGE_FIELD)
            if (nestedMessage != null) {
                mutateContentField(nestedMessage, "Message")
            }

            transformedResult
        }

        val replaced = setFieldValue(target, PREPROCESSING_FIELD, wrapped)
        if (replaced) logger.info("Wrapped Send.onPreprocessing")
        if (!replaced) logDebug("Failed to replace '$PREPROCESSING_FIELD' on ${target.javaClass.name}")
        return replaced
    }

    private fun getFieldValue(target: Any, fieldName: String): Any? {
        var currentClass: Class<*>? = target.javaClass
        while (currentClass != null && currentClass != Any::class.java) {
            val field = currentClass.declaredFields.firstOrNull { it.name == fieldName }
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.get(target)
                }.getOrNull()
            }
            currentClass = currentClass.superclass
        }
        return null
    }

    private fun setFieldValue(target: Any, fieldName: String, value: Any): Boolean {
        var currentClass: Class<*>? = target.javaClass
        while (currentClass != null && currentClass != Any::class.java) {
            val field = currentClass.declaredFields.firstOrNull { it.name == fieldName }
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.set(target, value)
                    true
                }.getOrDefault(false)
            }
            currentClass = currentClass.superclass
        }
        return false
    }
}
