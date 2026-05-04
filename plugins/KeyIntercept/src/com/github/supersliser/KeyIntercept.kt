package com.github.supersliser

import android.content.Context
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.discord.restapi.RestAPIParams
import com.github.supersliser.discord.DiscordContextResolver
import com.github.supersliser.discord.DiscordResolver
import com.github.supersliser.models.KeyInterceptConfig
import com.github.supersliser.models.Rule
import com.github.supersliser.models.ServerWhitelistItem
import com.github.supersliser.models.PetWord
import com.github.supersliser.supabase.SupabaseClient
import com.github.supersliser.supabase.SupabaseDataFetcher
import com.github.supersliser.transforms.TransformEngine
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@AliucordPlugin(requiresRestart = false)
class KeyIntercept : Plugin() {
    companion object {
        private var config: KeyInterceptConfig = KeyInterceptConfig(
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

        private var rules: List<Rule> = emptyList()
        private var whitelist: List<ServerWhitelistItem> = emptyList()
        private var petWords: List<PetWord> = emptyList()
    }

    private var supabasePollExecutor: ScheduledExecutorService? = null
    private var supabaseClient: SupabaseClient? = null
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
                "rulesEnd=${value.rulesEnd}, gagEnd=${value.gagEnd}, petEnd=${value.petEnd}, " +
                "petAmount=${value.petAmount}, petType=${value.petType}, bimboEnd=${value.bimboEnd}, " +
                "hornyEnd=${value.hornyEnd}, bimboWordLength=${value.bimboWordLength}, " +
                "droneEnd=${value.droneEnd}, droneHeaderText='${value.droneHeaderText}', " +
                "droneFooterText='${value.droneFooterText}', droneHealth=${value.droneHealth}, " +
                "uwuEnd=${value.uwuEnd}, debug=${value.debug}"
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

    private fun refreshFromSupabase(reason: String) {
        runCatching {
            val fetcher = dataFetcher ?: return@runCatching

            fetcher.fetchConfigFromSupabase(config.id)?.let {
                config = it
                transformEngine?.updateConfig(it)
                logDebug("Config refreshed from Supabase ($reason)")
            }

            val fetchedRules = fetcher.fetchRulesFromSupabase(config.id)
            if (fetchedRules.isNotEmpty()) {
                rules = fetchedRules
                transformEngine?.updateRules(fetchedRules)
                logDebug("Rules refreshed: ${fetchedRules.size} rules ($reason)")
            }

            val fetchedWhitelist = fetcher.fetchWhitelistFromSupabase(config.id)
            if (fetchedWhitelist.isNotEmpty()) {
                whitelist = fetchedWhitelist
                logDebug("Whitelist refreshed: ${fetchedWhitelist.size} items ($reason)")
            }

            val fetchedPetWords = fetcher.fetchPetWordsFromSupabase(config.petType)
            if (fetchedPetWords.isNotEmpty()) {
                petWords = fetchedPetWords
                logDebug("Pet words refreshed: ${fetchedPetWords.size} words ($reason)")
            }
        }.onFailure {
            logger.error("Supabase refresh failed ($reason)", it)
        }
    }

    override fun load(context: Context) {
        logger.info("KeyIntercept loaded")
        logDebug("Initial config: ${formatConfigDetails(config)}")
        logDebug("Initial data sizes: rules=${rules.size}, whitelist=${whitelist.map { it.serverName }}, petWords=${petWords.size}")

        supabasePollExecutor?.shutdownNow()
        supabasePollExecutor = Executors.newSingleThreadScheduledExecutor()

        supabaseClient = SupabaseClient()
        dataFetcher = SupabaseDataFetcher(supabaseClient!!)
        discordResolver = DiscordResolver(supabaseClient!!)
        contextResolver = DiscordContextResolver(discordResolver!!)
        transformEngine = TransformEngine(config, rules)

        val executor = supabasePollExecutor
        if (executor == null) {
            logger.warn("Supabase poll executor could not be created; using local/default values")
            return
        }

        var success = false
        for (i in 1..30) {
            executor.execute {
                val resolvedConfigId = discordResolver?.resolveConfigIdForCurrentUser()
                if (resolvedConfigId != null && resolvedConfigId > 0) {
                    config = config.copy(id = resolvedConfigId)
                    refreshFromSupabase("initial")
                    setupSupabasePolling()
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
                                return@PreHook
                            }

                            var changed = false
                            hookParam.args.forEachIndexed { index, arg ->
                                if (arg is String && arg.isNotEmpty()) {
                                    val transformed = transformMessage(arg)
                                    if (transformed != arg) {
                                        hookParam.args[index] = transformed
                                        lastMessageTransformTime = now
                                        changed = true
                                    }
                                }
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
        val shouldTransform = contextResolver?.shouldTransformForCurrentConversation(context, whitelist, config.debug) == true
        
        if (!shouldTransform) {
            logDebug("Skipping transforms for current conversation")
            return input
        }

        logDebug("Applying transforms to message")
        return transformEngine?.applyAllTransforms(input) ?: input
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        supabasePollExecutor?.shutdownNow()
        supabasePollExecutor = null
        lastMessageTransformTime = 0
    }
}
