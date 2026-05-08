package com.github.supersliser

import android.content.Context
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.discord.restapi.RestAPIParams
import com.github.supersliser.discord.DiscordContextResolver
import com.github.supersliser.discord.DiscordResolver
import com.github.supersliser.models.KeyInterceptConfig
import com.github.supersliser.models.DroneConfig
import com.github.supersliser.models.Rule
import com.github.supersliser.models.ServerWhitelistItem
import com.github.supersliser.models.PetWord
import com.github.supersliser.supabase.SupabaseClient
import com.github.supersliser.supabase.SupabaseDataFetcher
import com.github.supersliser.supabase.SupabaseRealtimeClient
import com.github.supersliser.transforms.TransformEngine
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

@AliucordPlugin(requiresRestart = false)
class KeyIntercept : Plugin() {
    companion object {
        private var config: KeyInterceptConfig = KeyInterceptConfig(
            id = -1,
            createdAt = Date().time,
            updatedAt = Date().time,
            rulesEnd = 0,
            gagEnd = 0,
            droneEnd = 0,
            petEnd = 0,
            petAmount = 0f,
            petType = 0,
            bimboEnd = 0,
            hornyEnd = 0,
            bimboWordLength = 5,
            uwuEnd = 0,
            debug = true
        )
        private var droneConfig: DroneConfig = DroneConfig(
            config_id = -1,
            speech_header = "Beep boop, I am a drone. Bzzt.",
            speech_footer = "Bzzt, drone out.",
            drone_health = 100f,
            action_header = "Drone performs an action: ",
            action_footer = "End of drone action.",
            whisper_header = "Drone whispers: ",
            whisper_footer = "End of drone whisper.",
            loud_header = "Drone loudly announces: ",
            loud_footer = "End of drone announcement."
        )

        private var rules: List<Rule> = emptyList()
        private var whitelist: List<ServerWhitelistItem> = emptyList()
        private var petWords: List<PetWord> = emptyList()
        private var censoredWords: List<com.github.supersliser.models.CensoredWord> = emptyList()
    }

    private var supabaseExecutor: ScheduledExecutorService? = null
    private var supabaseClient: SupabaseClient? = null
    private var supabaseRealtimeClient: SupabaseRealtimeClient? = null
    private var dataFetcher: SupabaseDataFetcher? = null
    private var discordResolver: DiscordResolver? = null
    private var contextResolver: DiscordContextResolver? = null
    private var transformEngine: TransformEngine? = null

    private val localDebugOverride = true

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
                "rulesEnd=${value.rulesEnd}, gagEnd=${value.gagEnd}, droneEnd=${value.droneEnd}, petEnd=${value.petEnd}, " +
                "petAmount=${value.petAmount}, petType=${value.petType}, bimboEnd=${value.bimboEnd}, " +
                "hornyEnd=${value.hornyEnd}, bimboWordLength=${value.bimboWordLength}, " +
                "uwuEnd=${value.uwuEnd}, censoredEnd=${value.censoredEnd}, debug=${value.debug}"
    }

    private fun formatDroneConfigDetails(value: DroneConfig): String {
        return "config_id=${value.config_id}, drone_health=${value.drone_health}, " +
                "speech_header='${value.speech_header}', speech_footer='${value.speech_footer}', " +
                "action_header='${value.action_header}', action_footer='${value.action_footer}', " +
                "whisper_header='${value.whisper_header}', whisper_footer='${value.whisper_footer}', " +
                "loud_header='${value.loud_header}', loud_footer='${value.loud_footer}'"
    }

    private fun setupSupabaseRealtime() {
        val executor = supabaseExecutor
        if (executor == null) {
            logger.warn("Supabase executor missing; realtime not started")
            return
        }

        supabaseRealtimeClient?.close()
        val realtime = SupabaseRealtimeClient()
        supabaseRealtimeClient = realtime

        val subscriptions = listOf(
            SupabaseRealtimeClient.RealtimeSubscription(
                channel = "public:config",
                schema = "public",
                table = "Config"
            ) {
                executor.execute {
                    refreshConfigFromSupabase("realtime:config")
                }
            },
            SupabaseRealtimeClient.RealtimeSubscription(
                channel = "public:rules",
                schema = "public",
                table = "Rules"
            ) {
                executor.execute {
                    refreshRulesFromSupabase("realtime:rules")
                }
            },
            SupabaseRealtimeClient.RealtimeSubscription(
                channel = "public:server_whitelist_items",
                schema = "public",
                table = "Server_Whitelist_Items"
            ) {
                executor.execute {
                    refreshWhitelistFromSupabase("realtime:whitelist")
                }
            },
            SupabaseRealtimeClient.RealtimeSubscription(
                channel = "public:pet_type_words",
                schema = "public",
                table = "Config"
            ) {
                executor.execute {
                    refreshConfigFromSupabase("realtime:pet_type_words-config")
                    refreshPetWordsFromSupabase("realtime:pet_type_words")
                }
            },
            SupabaseRealtimeClient.RealtimeSubscription(
                channel = "public:censored_words",
                schema = "public",
                table = "Censored_Words"
            ) {
                executor.execute {
                    refreshCensoredWordsFromSupabase("realtime:censored_words")
                }
            },
            SupabaseRealtimeClient.RealtimeSubscription(
                channel = "public:drone_config",
                schema = "public",
                table = "Drone_Config"
            ) {
                executor.execute {
                    refreshDroneConfigFromSupabase("realtime:drone_config")
                }
            }
        )

        realtime.connectAndSubscribe(
            subscriptions = subscriptions,
            onConnected = {
                logDebug("Supabase realtime subscriptions connected")
            },
            onError = {
                logger.error("Supabase realtime connection error", it)
            }
        )
    }

    private fun refreshFromSupabase(reason: String) {
        refreshConfigFromSupabase(reason)
        refreshDroneConfigFromSupabase(reason)
        refreshRulesFromSupabase(reason)
        refreshWhitelistFromSupabase(reason)
        refreshPetWordsFromSupabase(reason)
        refreshCensoredWordsFromSupabase(reason)
    }

    private fun refreshCensoredWordsFromSupabase(reason: String) {
        runCatching {
            val fetcher = dataFetcher ?: return@runCatching

            val fetched = fetcher.fetchCensoredWordsFromSupabase(config.id)
            if (fetched.isNotEmpty()) {
                censoredWords = fetched
                transformEngine?.updateCensoredWords(fetched)
                logDebug("Censored words refreshed: ${fetched.size} words ($reason)")
            }
        }.onFailure {
            logger.error("Censored words refresh failed ($reason)", it)
        }
    }

    private fun refreshConfigFromSupabase(reason: String) {
        runCatching {
            val fetcher = dataFetcher ?: return@runCatching

            fetcher.fetchConfigFromSupabase(config.id)?.let {
                config = it
                transformEngine?.updateConfig(it)
                logDebug("Config refreshed from Supabase ($reason)")
            }
        }.onFailure {
            logger.error("Config refresh failed ($reason)", it)
        }
    }

    private fun refreshDroneConfigFromSupabase(reason: String) {
        runCatching {
            val fetcher = dataFetcher ?: return@runCatching

            fetcher.fetchDroneConfigFromSupabase(config.id)?.let {
                droneConfig = it
                transformEngine?.updateDroneConfig(it)
                logDebug("Drone config refreshed from Supabase ($reason)")
            }
        }.onFailure {
            logger.error("Drone config refresh failed ($reason)", it)
        }
    }

    private fun refreshRulesFromSupabase(reason: String) {
        runCatching {
            val fetcher = dataFetcher ?: return@runCatching

            val fetchedRules = fetcher.fetchRulesFromSupabase(config.id)
            if (fetchedRules.isNotEmpty()) {
                rules = fetchedRules
                transformEngine?.updateRules(fetchedRules)
                logDebug("Rules refreshed: ${fetchedRules.size} rules ($reason)")
            }
        }.onFailure {
            logger.error("Rules refresh failed ($reason)", it)
        }
    }

    private fun refreshWhitelistFromSupabase(reason: String) {
        runCatching {
            val fetcher = dataFetcher ?: return@runCatching

            val fetchedWhitelist = fetcher.fetchWhitelistFromSupabase(config.id)
            logDebug("Whitelist fetch completed: ${fetchedWhitelist.size} items ($reason)")
            if (fetchedWhitelist.isNotEmpty()) {
                val preview = fetchedWhitelist.take(5).joinToString { "${it.serverName}:${it.discordId}" }
                logDebug("Whitelist preview (first ${minOf(5, fetchedWhitelist.size)}): $preview")
            }
            if (fetchedWhitelist.isNotEmpty()) {
                whitelist = fetchedWhitelist
                logDebug("Whitelist refreshed: ${fetchedWhitelist.size} items ($reason)")
            } else {
                logDebug("Whitelist refresh returned empty set; keeping previous whitelist of size ${whitelist.size}")
            }
        }.onFailure {
            logger.error("Whitelist refresh failed ($reason)", it)
        }
    }

    private fun refreshPetWordsFromSupabase(reason: String) {
        runCatching {
            val fetcher = dataFetcher ?: return@runCatching

            val fetchedPetWords = fetcher.fetchPetWordsFromSupabase(config.petType)
            if (fetchedPetWords.isNotEmpty()) {
                petWords = fetchedPetWords
                logDebug("Pet words refreshed: ${fetchedPetWords.size} words ($reason)")
            }
        }.onFailure {
            logger.error("Pet words refresh failed ($reason)", it)
        }
    }

    override fun load(context: Context) {
        logger.info("KeyIntercept loaded")
        logDebug("Initial config: ${formatConfigDetails(config)}")
        logDebug("Initial drone config: ${formatDroneConfigDetails(droneConfig)}")
        logDebug("Initial data sizes: rules=${rules.size}, whitelist=${whitelist.map { it.serverName }}, petWords=${petWords.size}")

        supabaseExecutor?.shutdownNow()
        supabaseExecutor = Executors.newSingleThreadScheduledExecutor()

        supabaseClient = SupabaseClient()
        supabaseRealtimeClient?.close()
        supabaseRealtimeClient = null
        dataFetcher = SupabaseDataFetcher(supabaseClient!!)
        discordResolver = DiscordResolver(supabaseClient!!)
        contextResolver = DiscordContextResolver(discordResolver!!)
        transformEngine = TransformEngine(config, droneConfig, rules, censoredWords, discordResolver)

        val executor = supabaseExecutor
        if (executor == null) {
            logger.warn("Supabase executor could not be created; using local/default values")
            return
        }

        var success = false
        for (i in 1..30) {
            executor.execute {
                val resolvedConfigId = discordResolver?.resolveConfigIdForCurrentUser()
                if (resolvedConfigId != null && resolvedConfigId > 0) {
                    config = config.copy(id = resolvedConfigId)
                    refreshFromSupabase("initial")
                    setupSupabaseRealtime()
                    logDebug("Initial Supabase sync complete")
                    success = true
                }
            }
            if (success) break else Thread.sleep(1000L)
        }

        if (!success) {
            logger.warn("Could not resolve config id from Supabase; using fallback defaults")
        }
    }

    override fun start(context: Context) {
        // Hook into Discord's RestAPIParams.Message constructor to intercept outgoing messages
        runCatching {
            val constructors = RestAPIParams.Message::class.java.declaredConstructors.filter { !it.isSynthetic }
            if (constructors.isEmpty()) {
                logger.warn("No RestAPIParams.Message constructors found")
                return@runCatching
            }

            logDebug("Found ${constructors.size} non-synthetic RestAPIParams.Message constructors")

            constructors.forEachIndexed { ctorIdx, ctor ->
                try {
                    patcher.patch(ctor, com.aliucord.patcher.PreHook { hookParam ->
                        try {
                            val now = System.currentTimeMillis()
                            
                            // Deduplication: skip if we just transformed a message
                            if ((now - lastMessageTransformTime) < DEDUP_WINDOW_MS) {
                                logDebug("Skipping transform due to dedup window")
                                return@PreHook
                            }

                            var changed = false
                            val firstStringIndex = hookParam.args.indexOfFirst { it is String && it.isNotEmpty() }
                            if (firstStringIndex >= 0) {
                                val arg = hookParam.args[firstStringIndex] as String
                                logDebug("Transforming first string argument $firstStringIndex: ${arg.take(50)}...")
                                val transformed = transformMessage(arg)
                                if (transformed != arg) {
                                    logDebug("Transform changed first argument (length: ${arg.length} -> ${transformed.length})")
                                    hookParam.args[firstStringIndex] = transformed
                                    lastMessageTransformTime = now
                                    changed = true
                                } else {
                                    logDebug("Transform did not change first argument")
                                }
                            } else {
                                logDebug("No string argument found to transform")
                            }
                            if (!changed) {
                                logDebug("Message completed with no content mutation")
                            }
                        } catch (e: Exception) {
                            logger.error("Error in message transform hook", e)
                        }
                    })
                } catch (e: Exception) {
                    logger.error("Failed to hook constructor $ctorIdx", e)
                }
            }

            logDebug("Hooked ${constructors.size} RestAPIParams.Message constructors")
        }.onFailure {
            logger.error("Failed to install RestAPIParams.Message hook", it)
        }
    }

    private fun transformMessage(input: String): String {
        if (input.isEmpty()) return input

        val context = contextResolver?.resolveCurrentConversationContext()
        logDebug(
            "Transform gate context: channel='${context?.channelName.orEmpty()}' channelId=${context?.channelId ?: -1} " +
                    "server='${context?.serverName.orEmpty()}' serverId=${context?.serverId ?: -1} whitelistSize=${whitelist.size}"
        )
        val shouldTransform = contextResolver?.shouldTransformForCurrentConversation(context, whitelist, config.debug) == true
        logDebug("Transform gate decision for first argument: shouldTransform=$shouldTransform")
        
        if (!shouldTransform) {
            logDebug("Skipping transforms for current conversation")
            return input
        }

        logDebug("Applying transforms to message")
        return transformEngine?.applyAllTransforms(input) ?: input
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        supabaseRealtimeClient?.close()
        supabaseRealtimeClient = null
        supabaseExecutor?.shutdownNow()
        supabaseExecutor = null
        lastMessageTransformTime = 0
    }
}
