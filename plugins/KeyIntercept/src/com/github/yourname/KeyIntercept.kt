package com.github.supersliser

import android.content.Context
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.utilities.messagesend.MessageQueue
import de.robv.android.xposed.XC_MethodHook
import java.util.Collections
import java.util.IdentityHashMap

@AliucordPlugin(requiresRestart = false)
class KeyIntercept : Plugin() {
    private val wrappedCallbacks = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

    override fun start(context: Context) {
        val targetMethods = MessageQueue::class.java.declaredMethods.filter { it.name == "doSend" || it.name == "enqueue" }

        if (targetMethods.isEmpty()) {
            logger.warn("Could not find MessageQueue send methods to patch")
            return
        }

        logger.info("Hooking MessageQueue methods: ${targetMethods.joinToString { it.name + it.parameterTypes.joinToString(prefix = "(", postfix = ")") { p -> p.simpleName } }}")

        targetMethods.forEach { method ->
            patcher.patch(method, Hook { hookParam: XC_MethodHook.MethodHookParam ->
                var changed = false
                hookParam.args.forEach { arg ->
                    if (arg != null && mutateSendPayload(arg)) {
                        changed = true
                    }
                }

                if (changed) logger.info("Modified outgoing message in MessageQueue.${method.name}")
            })
        }
    }

    private fun appendMarker(content: String): String {
        val marker = " [Modified by KeyIntercept]"
        return if (content.endsWith(marker)) content else "$content$marker"
    }

    private fun mutateSendPayload(target: Any): Boolean {
        var changed = false

        if (wrapOnPreprocessing(target)) {
            changed = true
        }

        val nestedMessage = getFieldValue(target, "message")
        if (nestedMessage != null && mutateContentField(nestedMessage, "Message")) {
            changed = true
        }

        if (mutateContentField(target, target.javaClass.simpleName)) {
            changed = true
        }

        return changed
    }

    private fun mutateContentField(target: Any, sourceName: String): Boolean {
        val content = getFieldValue(target, "content") as? String ?: return false
        val updated = appendMarker(content)
        if (updated == content) return false
        return setFieldValue(target, "content", updated).also { success ->
            if (success) logger.info("Mutated $sourceName.content")
        }
    }

    private fun wrapOnPreprocessing(target: Any): Boolean {
        val callback = getFieldValue(target, "onPreprocessing") as? Function1<*, *> ?: return false
        if (!wrappedCallbacks.add(callback)) return false

        val original = callback as Function1<Any?, Any?>
        val wrapped: (Any?) -> Any? = { input ->
            if (input != null) {
                mutateSendPayload(input)
            }

            val result = original.invoke(input)

            if (result != null) {
                mutateSendPayload(result)
            }

            val nestedMessage = getFieldValue(target, "message")
            if (nestedMessage != null) {
                mutateContentField(nestedMessage, "Message")
            }

            result
        }

        val replaced = setFieldValue(target, "onPreprocessing", wrapped)
        if (replaced) logger.info("Wrapped Send.onPreprocessing")
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

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}