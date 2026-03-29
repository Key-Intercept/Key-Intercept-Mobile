package com.github.supersliser

import android.content.Context
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.utils.ChannelUtils
import com.aliucord.wrappers.ChannelWrapper
import com.discord.stores.StoreStream
import com.discord.utilities.messagesend.MessageQueue
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import de.robv.android.xposed.XC_MethodHook
import java.util.Collections
import java.util.Date
import java.util.IdentityHashMap
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
                id = 1,
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
    private var supabaseScope: CoroutineScope? = null
    private val supabaseJobs = Collections.synchronizedList(mutableListOf<Job>())
    private val realtimeChannels = Collections.synchronizedList(mutableListOf<RealtimeChannel>())
    private var supabaseClient: SupabaseClient? = null
    @Volatile
    private var initialSupabaseSyncComplete = false

    private fun logDebug(message: String) {
        if (config.debug) logger.info("[KeyIntercept][Debug] $message")
    }

    private fun launchSupabaseJob(block: suspend CoroutineScope.() -> Unit) {
        val scope = supabaseScope
        if (scope == null) {
            logger.warn("Supabase scope is not initialized; skipping async job")
            return
        }

        val job = scope.launch(block = block)
        supabaseJobs += job
    }

    @Serializable
    private data class SupabaseKeyInterceptConfig(
        val id: Long = 0,
        @SerialName("created_at") val createdAt: Long = 0,
        @SerialName("updated_at") val updatedAt: Long = 0,
        @SerialName("rules_end") val rulesEnd: Long = 0,
        @SerialName("gag_end") val gagEnd: Long = 0,
        @SerialName("pet_end") val petEnd: Long = 0,
        @SerialName("pet_amount") val petAmount: Float = 0f,
        @SerialName("pet_type") val petType: Long = 0,
        @SerialName("bimbo_end") val bimboEnd: Long = 0,
        @SerialName("horny_end") val hornyEnd: Long = 0,
        @SerialName("bimbo_word_length") val bimboWordLength: Int = 0,
        @SerialName("drone_end") val droneEnd: Long = 0,
        @SerialName("drone_header_text") val droneHeaderText: String = "",
        @SerialName("drone_footer_text") val droneFooterText: String = "",
        @SerialName("drone_health") val droneHealth: Float = 0f,
        @SerialName("uwu_end") val uwuEnd: Long = 0,
        val debug: Boolean = false
    )

    @Serializable
    private data class SupabaseRule(
        val id: Long = 0,
        @SerialName("created_at") val createdAt: Long = 0,
        @SerialName("updated_at") val updatedAt: Long = 0,
        @SerialName("config_id") val configId: Long = 0,
        @SerialName("rule_regex") val ruleRegex: String = "",
        @SerialName("rule_replacement") val ruleReplacement: String = "",
        val enabled: Boolean = false,
        @SerialName("chance_to_apply") val chanceToApply: Float = 0f
    )

    @Serializable
    private data class SupabaseServerWhitelistItem(
        val id: Long = 0,
        @SerialName("config_id") val configId: Long = 0,
        @SerialName("server_name") val serverName: String = ""
    )

    @Serializable
    private data class SupabasePetWord(
        val id: Long = 0,
        @SerialName("pet_type") val petType: Long = 0,
        val word: String = ""
    )

    private fun SupabaseKeyInterceptConfig.toModel(): KeyInterceptConfig {
        return KeyInterceptConfig(
            id = id,
            createdAt = createdAt,
            updatedAt = updatedAt,
            rulesEnd = rulesEnd,
            gagEnd = gagEnd,
            petEnd = petEnd,
            petAmount = petAmount,
            petType = petType,
            bimboEnd = bimboEnd,
            hornyEnd = hornyEnd,
            bimboWordLength = bimboWordLength,
            droneEnd = droneEnd,
            droneHeaderText = droneHeaderText,
            droneFooterText = droneFooterText,
            droneHealth = droneHealth,
            uwuEnd = uwuEnd,
            debug = debug
        )
    }

    private fun SupabaseRule.toModel(): Rule {
        return Rule(
            id = id,
            createdAt = createdAt,
            updatedAt = updatedAt,
            configId = configId,
            ruleRegex = ruleRegex,
            ruleReplacement = ruleReplacement,
            enabled = enabled,
            chanceToApply = chanceToApply
        )
    }

    private fun SupabaseServerWhitelistItem.toModel(): ServerWhitelistItem {
        return ServerWhitelistItem(
            id = id,
            configId = configId,
            serverName = serverName
        )
    }

    private fun SupabasePetWord.toModel(): PetWord {
        return PetWord(
            id = id,
            petType = petType,
            word = word
        )
    }

    private suspend fun fetchConfigFromSupabase(client: SupabaseClient): KeyInterceptConfig? {
        return runCatching {
            client.from("Config").select {
                filter {
                    eq("id", config.id)
                }
            }.decodeSingleOrNull<SupabaseKeyInterceptConfig>()?.toModel()
        }.onFailure {
            logger.error("Failed to fetch config from Supabase", it)
        }.getOrNull()
    }

    private suspend fun fetchRulesFromSupabase(client: SupabaseClient): List<Rule> {
        return runCatching {
            client.from("Rule").select {
                filter {
                    eq("config_id", config.id)
                }
            }.decodeList<SupabaseRule>().map { it.toModel() }
        }.onFailure {
            logger.error("Failed to fetch rules from Supabase", it)
        }.getOrDefault(emptyList())
    }

    private suspend fun fetchWhitelistFromSupabase(client: SupabaseClient): List<ServerWhitelistItem> {
        return runCatching {
            client.from("Server_Whitelist_Items").select {
                filter {
                    eq("config_id", config.id)
                }
            }.decodeList<SupabaseServerWhitelistItem>().map { it.toModel() }
        }.onFailure {
            logger.error("Failed to fetch whitelist from Supabase", it)
        }.getOrDefault(emptyList())
    }

    private suspend fun fetchPetWordsFromSupabase(client: SupabaseClient): List<PetWord> {
        return runCatching {
            client.from("Pet_Words").select {
                filter {
                    eq("pet_type", config.petType)
                }
            }.decodeList<SupabasePetWord>().map { it.toModel() }
        }.onFailure {
            logger.error("Failed to fetch pet words from Supabase", it)
        }.getOrDefault(emptyList())
    }

    private fun setupRealtimeSync(client: SupabaseClient) {
        val configChannel = client.channel("public:Config")
        val rulesChannel = client.channel("public:Rule")
        val whitelistChannel = client.channel("public:Server_Whitelist_Items")
        val petWordsChannel = client.channel("public:Pet_Words")

        realtimeChannels += configChannel
        realtimeChannels += rulesChannel
        realtimeChannels += whitelistChannel
        realtimeChannels += petWordsChannel

        val scope = supabaseScope
        if (scope == null) {
            logger.warn("Supabase scope is not initialized; realtime sync will not start")
            return
        }

        supabaseJobs += configChannel.postgresChangeFlow<PostgresAction>(schema = "public")
            .onEach { action ->
                logDebug("Supabase config change received: ${action::class.simpleName}")
                val newConfig = fetchConfigFromSupabase(client)
                if (newConfig != null) {
                    config = newConfig
                    logDebug("Supabase config updated: configId=${config.id}, petType=${config.petType}, debug=${config.debug}")

                    val refreshedRules = fetchRulesFromSupabase(client)
                    if (refreshedRules.isNotEmpty()) {
                        rules = refreshedRules
                        logDebug("Supabase rules refreshed after config change: ${rules.size}")
                    }

                    val refreshedWhitelist = fetchWhitelistFromSupabase(client)
                    if (refreshedWhitelist.isNotEmpty()) {
                        whitelist = refreshedWhitelist
                        logDebug("Supabase whitelist refreshed after config change: ${whitelist.size}")
                    }

                    val refreshedPetWords = fetchPetWordsFromSupabase(client)
                    if (refreshedPetWords.isNotEmpty()) {
                        petWords = refreshedPetWords
                        logDebug("Supabase pet words refreshed after config change: ${petWords.size}")
                    }
                }
            }
            .launchIn(scope)

        supabaseJobs += rulesChannel.postgresChangeFlow<PostgresAction>(schema = "public")
            .onEach { action ->
                when (action) {
                    is PostgresAction.Insert,
                    is PostgresAction.Update,
                    is PostgresAction.Delete -> {
                        val newRules = fetchRulesFromSupabase(client)
                        if (newRules.isNotEmpty()) {
                            rules = newRules
                            logDebug("Supabase rules updated via realtime: ${rules.size}")
                        }
                    }

                    else -> {}
                }
            }
            .launchIn(scope)

        supabaseJobs += whitelistChannel.postgresChangeFlow<PostgresAction>(schema = "public")
            .onEach { action ->
                when (action) {
                    is PostgresAction.Insert,
                    is PostgresAction.Update,
                    is PostgresAction.Delete -> {
                        val newWhitelist = fetchWhitelistFromSupabase(client)
                        if (newWhitelist.isNotEmpty()) {
                            whitelist = newWhitelist
                            logDebug("Supabase whitelist updated via realtime: ${whitelist.size}")
                        }
                    }

                    else -> {}
                }
            }
            .launchIn(scope)

        supabaseJobs += petWordsChannel.postgresChangeFlow<PostgresAction>(schema = "public")
            .onEach { action ->
                when (action) {
                    is PostgresAction.Insert,
                    is PostgresAction.Update,
                    is PostgresAction.Delete -> {
                        val newPetWords = fetchPetWordsFromSupabase(client)
                        if (newPetWords.isNotEmpty()) {
                            petWords = newPetWords
                            logDebug("Supabase pet words updated via realtime: ${petWords.size}")
                        }
                    }

                    else -> {}
                }
            }
            .launchIn(scope)

        launchSupabaseJob {
            runCatching { configChannel.subscribe() }.onFailure { logger.error("Failed to subscribe to config channel", it) }
            runCatching { rulesChannel.subscribe() }.onFailure { logger.error("Failed to subscribe to rules channel", it) }
            runCatching { whitelistChannel.subscribe() }.onFailure { logger.error("Failed to subscribe to whitelist channel", it) }
            runCatching { petWordsChannel.subscribe() }.onFailure { logger.error("Failed to subscribe to pet words channel", it) }
            logDebug("Supabase realtime channels subscribed")
        }
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
        if (supabaseScope == null) {
            supabaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        logDebug(
            "Initial config: debug=${config.debug}, rules=${rules.size}, whitelist=${whitelist.map { it.serverName }}, petWords=${petWords.size}"
        )

        val client = createSupabaseClient(
            supabaseUrl = "https://qjzgfwithyvmwctesnqs.supabase.co",
            supabaseKey = "sb_publishable_cxq8QZp9BDtjE4G5qiPCFA_lUZ4Cbdh"
        ) {
            install(Postgrest)
            install(Realtime)
        }

        supabaseClient = client
        logDebug("Supabase client initialized")

        launchSupabaseJob {
            fetchConfigFromSupabase(client)?.let {
                config = it
                logDebug("Initial config fetched from Supabase: configId=${config.id}, petType=${config.petType}, debug=${config.debug}")
            }

            val fetchedRules = fetchRulesFromSupabase(client)
            if (fetchedRules.isNotEmpty()) {
                rules = fetchedRules
                logDebug("Initial rules fetched from Supabase: ${rules.size}")
            }

            val fetchedWhitelist = fetchWhitelistFromSupabase(client)
            if (fetchedWhitelist.isNotEmpty()) {
                whitelist = fetchedWhitelist
                logDebug("Initial whitelist fetched from Supabase: ${whitelist.size}")
            }

            val fetchedPetWords = fetchPetWordsFromSupabase(client)
            if (fetchedPetWords.isNotEmpty()) {
                petWords = fetchedPetWords
                logDebug("Initial pet words fetched from Supabase: ${petWords.size}")
            }

            initialSupabaseSyncComplete = true
            logDebug("Initial Supabase sync complete")

            setupRealtimeSync(client)
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
                val changed = hookParam.args.any { arg -> arg != null && mutateOutgoingData(arg) }
                if (changed) {
                    logger.info("Modified outgoing message in MessageQueue.${method.name}")
                } else {
                    logDebug("No mutation performed in MessageQueue.${method.name}")
                }
            })
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()

        val channelsToClose = realtimeChannels.toList()
        realtimeChannels.clear()

        launchSupabaseJob {
            channelsToClose.forEach { channel ->
                runCatching { channel.unsubscribe() }
                    .onFailure { logger.error("Failed to unsubscribe realtime channel ${channel.topic}", it) }
            }
            logDebug("Supabase realtime channels unsubscribed")
        }

        supabaseJobs.toList().forEach { it.cancel() }
        supabaseJobs.clear()
        supabaseClient = null
        supabaseScope?.coroutineContext?.cancelChildren()
        supabaseScope = null
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
            if (input != null) mutateOutgoingData(input)

            val result = original.invoke(input)

            if (result != null) mutateOutgoingData(result)

            val nestedMessage = getFieldValue(target, MESSAGE_FIELD)
            if (nestedMessage != null) {
                mutateContentField(nestedMessage, "Message")
            }

            result
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
