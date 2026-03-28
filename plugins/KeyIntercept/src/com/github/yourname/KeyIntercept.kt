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
        val doSendMethods = MessageQueue::class.java.declaredMethods.filter { it.name == "doSend" }

        if (doSendMethods.isEmpty()) {
            logger.warn("Could not find MessageQueue.doSend to patch")
            return
        }

        doSendMethods.forEach { method ->
            patcher.patch(method, Hook { hookParam: XC_MethodHook.MethodHookParam ->
                var changed = false
                hookParam.args.forEachIndexed { index, arg ->
                    if (arg is String) {
                        val updated = appendMarker(arg)
                        if (updated != arg) {
                            hookParam.args[index] = updated
                            changed = true
                        }
                        return@forEachIndexed
                    }

                    if (arg != null && mutateContentField(arg)) {
                        changed = true
                    }

                    val nestedMessage = arg?.let { getFieldValue(it, "message") }
                    if (nestedMessage != null && mutateContentField(nestedMessage)) {
                        changed = true
                    }
                }

                if (changed) {
                    logger.info("Modified outgoing message in MessageQueue.doSend")
                }
            })
        }
    }

    private fun appendMarker(content: String): String {
        val marker = " [Modified by KeyIntercept]"
        return if (content.endsWith(marker)) content else "$content$marker"
    }

    private fun getFieldValue(target: Any, fieldName: String): Any? {
        return runCatching {
            val field = target.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(target)
        }.getOrNull()
    }

    private fun mutateContentField(target: Any): Boolean {
        return runCatching {
            val contentField = target.javaClass.getDeclaredField("content")
            contentField.isAccessible = true
            val current = contentField.get(target) as? String ?: return false
            val updated = appendMarker(current)
            if (updated == current) return false
            contentField.set(target, updated)
            true
        }.getOrDefault(false)
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}