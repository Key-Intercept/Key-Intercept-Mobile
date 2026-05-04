package com.github.supersliser.discord

import com.aliucord.patcher.PreHook
import com.discord.restapi.RestAPIParams
import org.json.JSONObject

class DiscordHook(private val onMessageConstruct: (JSONObject) -> Unit) {

    fun installRestApiMessageConstructorHook(patcher: Any) {
        runCatching {
            val constructors = RestAPIParams.Message::class.java.declaredConstructors.filter { !it.isSynthetic }

            if (constructors.isEmpty()) {
                println("[KeyIntercept] No RestAPIParams.Message constructors found")
                return@runCatching
            }

            println("[KeyIntercept] Found ${constructors.size} non-synthetic RestAPIParams.Message constructors")

            constructors.forEachIndexed { ctorIdx, ctor ->
                val params = ctor.parameterTypes
                val paramStr = params.joinToString(", ") { it.simpleName }
                
                val hookMethod = patcher.javaClass.getMethod(
                    "hookMethod",
                    java.lang.reflect.Method::class.java,
                    PreHook::class.java
                ) ?: return@forEachIndexed

                val hook = PreHook { callFrame ->
                    runCatching {
                        val args = callFrame.args
                        var msgContent: JSONObject? = null

                        // Try to find the JSONObject in args
                        for (i in args.indices) {
                            if (args[i] is JSONObject) {
                                msgContent = args[i] as JSONObject
                                break
                            }
                        }

                        if (msgContent != null && msgContent.has("content")) {
                            onMessageConstruct(msgContent)
                        }
                    }
                    callFrame
                }

                try {
                    hookMethod.invoke(patcher, ctor, hook)
                } catch (e: Exception) {
                    println("[KeyIntercept] Failed to hook constructor $ctorIdx: ${e.message}")
                }
            }

            println("[KeyIntercept] Hooked ${constructors.size} RestAPIParams.Message constructors")
        }.onFailure {
            println("[KeyIntercept] Failed to install RestAPIParams.Message constructor hook: ${it.message}")
        }
    }
}
