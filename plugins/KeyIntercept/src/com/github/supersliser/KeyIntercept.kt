package com.github.supersliser

import android.content.Context
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.PreHook
import com.aliucord.utils.ChannelUtils
import com.aliucord.wrappers.ChannelWrapper
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Date
import java.util.ArrayList
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import kotlin.random.Random
import doist.x.normalize.Form
import doist.x.normalize.normalize

@AliucordPlugin(requiresRestart = false)
class KeyIntercept : Plugin() {
    private data class ConversationContext(
        val channelName: String,
        val dmName: String,
        val serverName: String
    )

    private companion object {
        const val SUPABASE_URL = "https://qjzgfwithyvmwctesnqs.supabase.co"
        const val SUPABASE_KEY = "sb_publishable_cxq8QZp9BDtjE4G5qiPCFA_lUZ4Cbdh"

        var config: KeyInterceptConfig = KeyInterceptConfig(
            id = -1,
            createdAt = Date().time,
            updatedAt = Date().time,
            rulesEnd = 0,
            gagEnd = 0,
            petEnd = 0,
            petAmount = 0f,
            petType = 0,
            bimboEnd = 0,
            hornyEnd = 0,
            bimboWordLength = 5,
            droneEnd = 0,
            droneHeaderText = "Drone Header",
            droneFooterText = "Drone Footer",
            droneHealth = 0.75f,
            uwuEnd = 0,
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

    private var supabasePollExecutor: ScheduledExecutorService? = null
    private val localDebugOverride = true

    // Deduplication: track when the last message transform happened globally
    @Volatile
    private var lastMessageTransformTime: Long = 0
    private val DEDUP_WINDOW_MS = 150L

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
                normalized =
                    normalized.substring(0, fracStart + 1) + frac.substring(0, 3) + normalized.substring(fracEnd)
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

            listOf("id", "userId").asSequence().mapNotNull { fieldName ->
                val value = runCatching {
                    var currentClass: Class<*>? = me.javaClass
                    while (currentClass != null && currentClass != Any::class.java) {
                        val field = currentClass.declaredFields.firstOrNull { it.name == fieldName }
                        if (field != null) {
                            field.isAccessible = true
                            return@runCatching field.get(me)
                        }
                        currentClass = currentClass.superclass
                    }
                    null
                }.getOrNull()

                when (value) {
                    is Long -> value
                    is Int -> value.toLong()
                    is Short -> value.toLong()
                    is String -> value.toLongOrNull()
                    else -> null
                }
            }.firstOrNull()
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

    override fun load(context: Context) {
        logger.info("KeyIntercept loaded")
        logDebug("Initial config: ${formatConfigDetails(config)}")
        logDebug("Initial data sizes: rules=${rules.size}, whitelist=${whitelist.map { it.serverName }}, petWords=${petWords.size}")

        supabasePollExecutor?.shutdownNow()
        supabasePollExecutor = Executors.newSingleThreadScheduledExecutor()

        val executor = supabasePollExecutor
        if (executor == null) {
            logger.warn("Supabase poll executor could not be created; using local/default values")
            return
        }

        var success = false;
        for (i in 1..30) {
            executor.execute {
                success = true;
                val resolvedConfigId = resolveConfigIdForCurrentUser()
                if (resolvedConfigId != null && resolvedConfigId != config.id) {
                    logDebug("Resolved config id from profiles/Sub_Config_Access: $resolvedConfigId")
                    config = config.copy(id = resolvedConfigId)
                } else if (resolvedConfigId == null) {
                    logger.warn("Could not resolve config id from Supabase access mapping; using fallback config id=${config.id}")
                    success = false;
                }

                refreshFromSupabase("initial")
                setupSupabasePolling()
                logDebug("Initial Supabase sync complete")
            }
            if (success) break else Thread.sleep(1000L)
        }
    }

    override fun start(context: Context) {
        installRestApiMessageConstructorHook()
    }

    private fun installRestApiMessageConstructorHook() {
        runCatching {
            val constructors = RestAPIParams.Message::class.java.declaredConstructors.filter { !it.isSynthetic }

            if (constructors.isEmpty()) {
                throw IllegalStateException("Didn't find any non-synthetic RestAPIParams.Message constructors")
            }

            logDebug("Found ${constructors.size} non-synthetic RestAPIParams.Message constructors")

            constructors.forEachIndexed { ctorIdx, ctor ->
                patcher.patch(ctor, PreHook { hookParam ->
                    try {
                        val now = System.currentTimeMillis()

                        logDebug("RestAPIParams.Message constructor[$ctorIdx] called at $now")

                        // Global dedup: if we just transformed a message in the last DEDUP_WINDOW_MS, skip this one
                        if ((now - lastMessageTransformTime) < DEDUP_WINDOW_MS) {
                            logDebug("  [ctor$ctorIdx] DEDUP: Last transform was only ${now - lastMessageTransformTime}ms ago, SKIPPING entire constructor")
                            return@PreHook
                        }

                        var contentArgIndex = -1
                        var changed = false

                        // Find the first string argument that looks like message content (not empty)
                        hookParam.args.forEachIndexed { index, arg ->
                            if (contentArgIndex < 0 && arg is String && arg.isNotEmpty()) {
                                contentArgIndex = index

                                logDebug("  [ctor$ctorIdx] arg[$index] NEW content: ${arg.take(80)}")

                                try {
                                    val updated = transformOutgoingString(
                                        arg,
                                        "RestAPIParams.Message[$ctorIdx]::<init> arg[$index]"
                                    )
                                    if (updated != arg) {
                                        hookParam.args[index] = updated
                                        lastMessageTransformTime = now
                                        changed = true
                                        logDebug("  [ctor$ctorIdx] arg[$index] MUTATED: ${updated.take(80)}")
                                    } else {
                                        logDebug("  [ctor$ctorIdx] arg[$index] transform returned same string")
                                    }
                                } catch (e: Exception) {
                                    logger.error(
                                        "Exception transforming RestAPIParams.Message[$ctorIdx] content arg",
                                        e
                                    )
                                }
                            }
                        }

                        if (!changed) {
                            logDebug("  [ctor$ctorIdx] No mutations made")
                        }
                    } catch (e: Exception) {
                        logger.error("Unexpected error in RestAPIParams.Message[$ctorIdx] constructor hook", e)
                    }
                })
            }

            logDebug("Hooked ${constructors.size} RestAPIParams.Message constructors")
        }.onFailure {
            logger.error("Failed to install RestAPIParams.Message constructor hook", it)
        }
    }

    private fun transformOutgoingString(input: String, origin: String): String {
        if (input.isEmpty()) return input

        if (!shouldTransformForCurrentConversation()) {
            logDebug("Skipping transforms at $origin because current conversation is not whitelisted")
            return input
        }

        logDebug("Applying fallback transforms for $origin")
        return applyTransformsWithoutContext(input)
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()

        supabasePollExecutor?.shutdownNow()
        supabasePollExecutor = null
        lastMessageTransformTime = 0
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
        var modified = content.normalize(Form.NFKC)
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

    private fun shouldTransformForCurrentConversation(): Boolean {
        val context = resolveCurrentConversationContext()
        if (context == null) {
            logDebug("Could not resolve current conversation context; skipping transforms")
            return false
        }

        if (context.channelName.contains("sfw", ignoreCase = true) &&
            !context.channelName.contains("nsfw", ignoreCase = true)
        ) {
            logDebug("Skipping transforms because channel '${context.channelName}' is SFW")
            return false
        }

        val names = listOf(context.channelName, context.dmName, context.serverName)
            .map { it.trim() }
            .filter {
                it.isNotEmpty() && !it.equals("Unknown Channel", ignoreCase = true) && !it.equals(
                    "Unknown DM",
                    ignoreCase = true
                ) && !it.equals("Unknown Server", ignoreCase = true)
            }

        if (names.isEmpty()) {
            logDebug("No usable channel/DM/server names for whitelist matching")
            return false
        }

        val matched = names.any(::checkWhitelist)
        logDebug("Whitelist names considered=$names matched=$matched")
        return matched
    }

    private fun extractLongValues(value: Any?): List<Long> {
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
                if (value.javaClass.isArray) {
                    runCatching {
                        val size = java.lang.reflect.Array.getLength(value)
                        (0 until size).flatMap { idx ->
                            extractLongValues(java.lang.reflect.Array.get(value, idx))
                        }
                    }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
            }
        }
    }

    private fun extractUserName(user: Any?): String {
        if (user == null) return ""

        logger.warn("[KeyIntercept] === USER OBJECT DUMP ===")
        logger.warn("[KeyIntercept] User class: ${user.javaClass.simpleName} (${user.javaClass.name})")

        // Dump all String fields for debugging
        val allStringFields = user.javaClass.declaredFields.filter { it.type == String::class.java }
        for (field in allStringFields) {
            field.isAccessible = true
            val value = field.get(user) as? String
            logger.warn("[KeyIntercept]   String field [${field.name}] = [$value]")
        }

        // Also try methods that return String
        val stringMethods = user.javaClass.methods.filter {
            it.parameterCount == 0 && it.returnType == String::class.java
        }
        for (method in stringMethods) {
            try {
                val value = method.invoke(user) as? String
                logger.warn("[KeyIntercept]   String method [${method.name}] = [$value]")
            } catch (e: Exception) {
                // Skip methods that error
            }
        }
        logger.warn("[KeyIntercept] === END DUMP ===")

        // Try common field names for username - prioritize "username" and check for variations
        val fieldOrder = listOf(
            "username",
            "userName",
            "tag",  // Discord sometimes uses tag for username
            "name",
            "discriminator",
            "globalName",
            "displayName"
        )
        for (fieldName in fieldOrder) {
            val fromField = runCatching {
                val field = user.javaClass.declaredFields.firstOrNull { it.name == fieldName }
                    ?: return@runCatching ""
                field.isAccessible = true
                field.get(user)?.toString()?.trim().orEmpty()
            }.getOrDefault("")

            if (fromField.isNotEmpty()) {
                logger.warn("[KeyIntercept] Username resolved from field '$fieldName': '$fromField'")
                return fromField
            }
        }

        logger.warn("[KeyIntercept] No username field found")
        return ""
    }

    private fun hasRecipientIds(channel: Any?): Boolean {
        if (channel == null) return false

        return runCatching {
            val recipientMethodNames = listOf(
                "getRecipientId",
                "getRecipientIds",
                "getRecipients",
                "getRawRecipients"
            )

            for (methodName in recipientMethodNames) {
                val hasMethod = runCatching {
                    val method = channel.javaClass.methods.firstOrNull {
                        it.name == methodName && it.parameterCount == 0
                    } ?: return@runCatching false
                    val result = method.invoke(channel)
                    extractLongValues(result).isNotEmpty()
                }.getOrDefault(false)

                if (hasMethod) return@runCatching true
            }

            val fieldNames = listOf("recipientId", "recipientIds", "recipients")
            for (fieldName in fieldNames) {
                val hasField = runCatching {
                    val field = channel.javaClass.declaredFields.firstOrNull { it.name == fieldName }
                        ?: return@runCatching false
                    field.isAccessible = true
                    extractLongValues(field.get(channel)).isNotEmpty()
                }.getOrDefault(false)

                if (hasField) return@runCatching true
            }

            false
        }.getOrDefault(false)
    }

    private fun resolveDmRecipientName(channel: Any?, fallbackName: String): String {
        if (channel == null) return fallbackName

        logger.warn("[KeyIntercept] [resolveDmRecipientName] Starting DM recipient resolution (fallback='$fallbackName')")

        return runCatching {
            val currentUserId = resolveCurrentDiscordId()

            val recipientIds = LinkedHashSet<Long>()
            val recipientMethodNames = listOf(
                "getRecipientId",
                "getRecipientIds",
                "getRecipients",
                "getRawRecipients"
            )

            for (methodName in recipientMethodNames) {
                val methodIds = runCatching {
                    val method = channel.javaClass.methods.firstOrNull {
                        it.name == methodName && it.parameterCount == 0
                    } ?: return@runCatching emptyList<Long>()
                    extractLongValues(method.invoke(channel))
                }.getOrDefault(emptyList())

                recipientIds.addAll(methodIds)
            }

            if (recipientIds.isEmpty()) {
                val fieldNames = listOf("recipientId", "recipientIds", "recipients")
                for (fieldName in fieldNames) {
                    val fieldIds = runCatching {
                        val field = channel.javaClass.declaredFields.firstOrNull { it.name == fieldName }
                            ?: return@runCatching emptyList<Long>()
                        field.isAccessible = true
                        extractLongValues(field.get(channel))
                    }.getOrDefault(emptyList())
                    recipientIds.addAll(fieldIds)
                }
            }

            logDebug("DM recipient resolution: found ${recipientIds.size} recipient IDs")

            if (currentUserId != null) {
                recipientIds.remove(currentUserId)
            }

            if (recipientIds.isEmpty()) {
                logDebug("DM recipient resolution: no valid recipient IDs after filtering")
                return@runCatching fallbackName
            }

            val usersStore = StoreStream.getUsers()
            val getUserMethod = usersStore.javaClass.methods.firstOrNull {
                it.name == "getUser" && it.parameterCount == 1
            }

            val recipientName = recipientIds.asSequence().mapNotNull { recipientId ->
                val user = runCatching {
                    if (getUserMethod == null) {
                        null
                    } else {
                        val paramType = getUserMethod.parameterTypes.firstOrNull()
                        if (paramType == java.lang.Long.TYPE || paramType == java.lang.Long::class.java) {
                            getUserMethod.invoke(usersStore, recipientId)
                        } else if (paramType == java.lang.Integer.TYPE || paramType == java.lang.Integer::class.java) {
                            getUserMethod.invoke(usersStore, recipientId.toInt())
                        } else if (paramType == String::class.java) {
                            getUserMethod.invoke(usersStore, recipientId.toString())
                        } else {
                            null
                        }
                    }
                }.getOrNull()

                val name = extractUserName(user)
                logDebug("DM recipient name extraction: extracted '$name' from user object")
                name.takeIf { it.isNotEmpty() }
            }.firstOrNull()

            logDebug("DM recipient resolution: resolved name='$recipientName' (fallback='$fallbackName')")
            recipientName ?: fallbackName
        }.getOrDefault(fallbackName)
    }

    private fun resolveCurrentConversationContext(): ConversationContext? {
        return runCatching {
            val selectedStore = StoreStream::class.java.getMethod("getChannelsSelected").invoke(null)
                ?: return@runCatching null

            val channelId = listOf("getId", "getChannelId", "getSelectedChannelId")
                .asSequence()
                .mapNotNull { methodName ->
                    runCatching {
                        val value = selectedStore.javaClass.getMethod(methodName).invoke(selectedStore)
                        when (value) {
                            is Long -> value
                            is Int -> value.toLong()
                            is String -> value.toLongOrNull()
                            else -> null
                        }
                    }.getOrNull()
                }
                .firstOrNull()
                ?: return@runCatching null

            val channel = StoreStream.getChannels().getChannel(channelId)
                ?: return@runCatching null

            val channelWrapper = ChannelWrapper(channel)
            val channelName = runCatching {
                ChannelUtils.getDisplayName(channel)?.toString().orEmpty().trim()
            }.getOrDefault("").ifEmpty { "Unknown Channel" }

            // Try isDM() first, but also fall back to checking for recipient IDs
            val isDmViaMethod = runCatching { channelWrapper.isDM() }.getOrDefault(false)
            val isDmViaRecipients = hasRecipientIds(channel)
            val isDm = isDmViaMethod || isDmViaRecipients

            logDebug("DM detection: isDM()=$isDmViaMethod, hasRecipients=$isDmViaRecipients, final isDm=$isDm")

            val dmName = if (isDm) {
                val resolved = resolveDmRecipientName(channel, channelName)
                logDebug("Resolved DM name: '$resolved' (fallback was: '$channelName')")
                resolved.ifEmpty { channelName }
            } else {
                "Unknown DM"
            }

            // Only resolve server info if it's NOT a DM
            val serverName = if (!isDm) {
                val guildId = runCatching { channelWrapper.guildId }.getOrNull()
                if (guildId != null && guildId != 0L) {
                    val guild = StoreStream.getGuilds().getGuild(guildId)
                    runCatching {
                        val nameMethod = guild?.javaClass?.methods?.firstOrNull { it.name == "getName" && it.parameterCount == 0 }
                        val name = nameMethod?.invoke(guild)?.toString()?.trim().orEmpty()
                        if (name.isEmpty()) {
                            val nameField = guild?.javaClass?.declaredFields?.firstOrNull { it.name == "name" }
                            if (nameField != null) {
                                nameField.isAccessible = true
                                nameField.get(guild)?.toString()?.trim().orEmpty()
                            } else {
                                ""
                            }
                        } else {
                            name
                        }
                    }.getOrDefault("").ifEmpty { "Unknown Server" }
                } else {
                    "Unknown Server"
                }
            } else {
                "Unknown Server"
            }

            logDebug("Context resolved: isDm=$isDm, channelName='$channelName', dmName='$dmName', serverName='$serverName'")
            ConversationContext(channelName = channelName, dmName = dmName, serverName = serverName)
        }.onFailure {
            logger.error("Failed to resolve current conversation context", it)
        }.getOrNull()
    }

    private fun checkWhitelist(serverName: String): Boolean {
        if (whitelist.isEmpty() || whitelist.all { it.serverName == "Example Server" }) {
            logDebug("Whitelist empty/default; allowing all servers")
            return true
        }

        val allowed = whitelist.any { it.serverName.equals(serverName, ignoreCase = true) }
        logDebug("Whitelist check for '$serverName' => $allowed")
        return allowed
    }

    private fun applyTransformsWithoutContext(content: String): String {
        var modified = content
        modified = applyRules(modified)
        modified = applyUwu(modified)
        modified = applyHorny(modified)
        modified = applyPet(modified)
        modified = applyBimbo(modified)
        modified = applyGag(modified)
        modified = applyDrone(modified)
        return modified
    }

}
