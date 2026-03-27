package com.github.supersliser

import android.content.Context
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.utilities.messagesend.MessageQueue
import de.robv.android.xposed.XC_MethodHook

@AliucordPlugin(requiresRestart = false)
class KeyIntercept : Plugin() {
    override fun start(context: Context) {
        // Patch the first available doSend overload to avoid hard-coding unstable internal classes.
        val doSendMethod = MessageQueue::class.java.declaredMethods.firstOrNull { it.name == "doSend" }

        if (doSendMethod == null) {
            logger.warn("Could not find MessageQueue.doSend to patch")
            return
        }

        patcher.patch(doSendMethod, Hook { hookParam: XC_MethodHook.MethodHookParam ->
            val payload = hookParam.args.firstOrNull()
            val message = payload?.let {
                runCatching {
                    val messageField = it.javaClass.getDeclaredField("message")
                    messageField.isAccessible = true
                    messageField.get(it)
                }.getOrNull()
            }

            logger.info("Outgoing message payload: ${message ?: payload}")

            message?.let {
                // Example: Modify the message content before sending
                val contentField = it.javaClass.getDeclaredField("content")
                contentField.isAccessible = true
                val originalContent = contentField.get(it) as? String ?: ""
                val modifiedContent = "$originalContent [Modified by KeyIntercept]"
                contentField.set(it, modifiedContent)
            }
        })
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}