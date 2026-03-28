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
    private companion object {
        const val CONTENT_FIELD = "content"
        const val MESSAGE_FIELD = "message"
        const val PREPROCESSING_FIELD = "onPreprocessing"
        const val MARKER_SUFFIX = " [Modified by KeyIntercept]"
    }

    private val wrappedCallbacks = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

    override fun start(context: Context) {
        val targetMethods = MessageQueue::class.java.declaredMethods
            .filter { it.name == "doSend" || it.name == "enqueue" }

        if (targetMethods.isEmpty()) {
            logger.warn("Could not find MessageQueue send methods to patch")
            return
        }

        logger.info("Hooking MessageQueue methods: ${targetMethods.joinToString { it.name + it.parameterTypes.joinToString(prefix = "(", postfix = ")") { p -> p.simpleName } }}")

        targetMethods.forEach { method ->
            patcher.patch(method, Hook { hookParam: XC_MethodHook.MethodHookParam ->
                val changed = hookParam.args.any { arg -> arg != null && mutateOutgoingData(arg) }

                if (changed) logger.info("Modified outgoing message in MessageQueue.${method.name}")
            })
        }
    }

    private fun mutateOutgoingData(candidate: Any): Boolean {
        var changed = false

        if (wrapPreprocessingCallback(candidate)) {
            changed = true
        }

        val nestedMessage = getFieldValue(candidate, MESSAGE_FIELD)
        if (nestedMessage != null && mutateContentField(nestedMessage, "Message")) {
            changed = true
        }

        if (mutateContentField(candidate, candidate.javaClass.simpleName)) {
            changed = true
        }

        return changed
    }

    private fun alterMessage(content: String): String {
        return if (content.endsWith(MARKER_SUFFIX)) content else "$content$MARKER_SUFFIX"
    }

    private fun mutateContentField(target: Any, sourceName: String): Boolean {
        val content = getFieldValue(target, CONTENT_FIELD) as? String ?: return false
        val updated = alterMessage(content)
        if (updated == content) return false
        return setFieldValue(target, CONTENT_FIELD, updated).also { success ->
            if (success) logger.info("Mutated $sourceName.content")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun wrapPreprocessingCallback(target: Any): Boolean {
        val callback = getFieldValue(target, PREPROCESSING_FIELD) as? Function1<*, *> ?: return false
        if (!wrappedCallbacks.add(callback)) return false

        val original = callback as Function1<Any?, Any?>
        val wrapped: (Any?) -> Any? = { input ->
            if (input != null) {
                mutateOutgoingData(input)
            }

            val result = original.invoke(input)

            if (result != null) {
                mutateOutgoingData(result)
            }

            val nestedMessage = getFieldValue(target, MESSAGE_FIELD)
            if (nestedMessage != null) {
                mutateContentField(nestedMessage, "Message")
            }

            result
        }

        val replaced = setFieldValue(target, PREPROCESSING_FIELD, wrapped)
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