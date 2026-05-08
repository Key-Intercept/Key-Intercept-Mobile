package com.github.supersliser.supabase

import com.github.supersliser.models.Rule
import com.github.supersliser.models.KeyInterceptConfig
import com.github.supersliser.models.DroneConfig
import com.github.supersliser.models.PetWord
import com.github.supersliser.models.CensoredWord
import com.github.supersliser.models.ServerWhitelistItem
import com.github.supersliser.utils.readFloat
import com.github.supersliser.utils.readInt
import com.github.supersliser.utils.readLong
import com.github.supersliser.utils.readString
import com.github.supersliser.utils.readBoolean
import com.github.supersliser.utils.readTimestampMillis
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayList

class SupabaseDataFetcher(private val client: SupabaseClient) {

    fun fetchConfigFromSupabase(configId: Long): KeyInterceptConfig? {
        return runCatching {
            val body = client.supabaseGet("Config", mapOf("id" to configId.toString()))
            val arr = JSONArray(body)
            if (arr.length() == 0) {
                null
            } else {
                val obj = arr.getJSONObject(0)
                KeyInterceptConfig(
                    id = obj.readLong("id"),
                    createdAt = obj.readTimestampMillis("created_at"),
                    updatedAt = obj.readTimestampMillis("updated_at"),
                    rulesEnd = obj.readLong("rules_end"),
                    gagEnd = obj.readLong("gag_end"),
                    petEnd = obj.readLong("pet_end"),
                    petAmount = obj.readFloat("pet_amount"),
                    petType = obj.readLong("pet_type"),
                    bimboEnd = obj.readLong("bimbo_end"),
                    hornyEnd = obj.readLong("horny_end"),
                    bimboWordLength = obj.readInt("bimbo_word_length"),
                    uwuEnd = obj.readLong("uwu_end"),
                    debug = obj.readBoolean("debug")
                )
            }
        }.onFailure {
            println("[KeyIntercept] Failed to fetch config from Supabase: ${it.message}")
        }.getOrNull()
    }

    fun fetchDroneConfigFromSupabase(configId: Long): DroneConfig? {
        val endpoints = listOf("Drone_Config", "DroneConfig")

        for (endpoint in endpoints) {
            val value = runCatching {
                val body = client.supabaseGet(endpoint, mapOf("config_id" to configId.toString()))
                val arr = JSONArray(body)
                if (arr.length() == 0) {
                    null
                } else {
                    val obj = arr.getJSONObject(0)
                    DroneConfig(
                        config_id = obj.readLong("config_id", configId),
                        speech_header = obj.readString("speech_header", "Beep boop, I am a drone. Bzzt."),
                        speech_footer = obj.readString("speech_footer", "Bzzt, drone out."),
                        drone_health = obj.readFloat("drone_health", 1f),
                        action_header = obj.readString("action_header", "Drone performs an action:"),
                        action_footer = obj.readString("action_footer", "End of drone action."),
                        whisper_header = obj.readString("whisper_header", "Drone whispers:"),
                        whisper_footer = obj.readString("whisper_footer", "End of drone whisper."),
                        loud_header = obj.readString("loud_header", "Drone loudly announces:"),
                        loud_footer = obj.readString("loud_footer", "End of drone announcement.")
                    )
                }
            }.onFailure {
                println("[KeyIntercept] Failed to fetch drone config from $endpoint: ${it.message}")
            }.getOrNull()

            if (value != null) return value
        }

        return null
    }

    fun fetchRulesFromSupabase(configId: Long): List<Rule> {
        return runCatching {
            val body = client.supabaseGet("Rules", mapOf("config_id" to configId.toString()))
            val arr = JSONArray(body)
            val out = ArrayList<Rule>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                out.add(
                    Rule(
                        id = obj.readLong("id"),
                        createdAt = obj.readTimestampMillis("created_at"),
                        updatedAt = obj.readTimestampMillis("updated_at"),
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
            println("[KeyIntercept] Failed to fetch rules from Supabase: ${it.message}")
        }.getOrDefault(emptyList())
    }

    fun fetchWhitelistFromSupabase(configId: Long): List<ServerWhitelistItem> {
        return runCatching {
            val body = client.supabaseGet("Server_Whitelist_Items", mapOf("config_id" to configId.toString()))
            val arr = JSONArray(body)
            val out = ArrayList<ServerWhitelistItem>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                out.add(
                    ServerWhitelistItem(
                        id = obj.readLong("id"),
                        configId = obj.readLong("config_id"),
                        serverName = obj.readString("server_name"),
                        discordId = obj.readLong("discord_id", -1L)
                    )
                )
            }
            out
        }.onFailure {
            println("[KeyIntercept] Failed to fetch whitelist from Supabase: ${it.message}")
        }.getOrDefault(emptyList())
    }

    fun fetchPetWordsFromSupabase(petType: Long): List<PetWord> {
        return runCatching {
            val body = client.supabaseGet("Pet_Type_Words", mapOf("pet_type" to petType.toString()))
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
            println("[KeyIntercept] Failed to fetch pet words from Supabase: ${it.message}")
        }.getOrDefault(emptyList())
    }

    fun fetchCensoredWordsFromSupabase(configId: Long): List<CensoredWord> {
        return runCatching {
            val body = client.supabaseGet("Censored_Words", mapOf("config_id" to configId.toString()))
            val arr = JSONArray(body)
            val out = ArrayList<CensoredWord>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                out.add(
                    CensoredWord(
                        id = obj.readLong("id"),
                        configId = obj.readLong("config_id"),
                        word = obj.readString("word")
                    )
                )
            }
            out
        }.onFailure {
            println("[KeyIntercept] Failed to fetch censored words from Supabase: ${it.message}")
        }.getOrDefault(emptyList())
    }
}
