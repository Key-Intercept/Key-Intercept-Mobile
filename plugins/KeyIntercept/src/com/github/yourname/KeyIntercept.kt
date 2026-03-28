package com.github.supersliser

import android.content.Context
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.utilities.messagesend.MessageQueue
import de.robv.android.xposed.XC_MethodHook
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

@AliucordPlugin(requiresRestart = false)
class KeyIntercept : Plugin() {
    override fun start(context: Context) {
        val targetMethods = MessageQueue::class.java.declaredMethods.filter {
            it.name == "doSend" || it.name == "sendMessage" || it.name == "enqueue"
        }

        if (targetMethods.isEmpty()) {
            logger.warn("Could not find MessageQueue send methods to patch")
            return
        }

        logger.info("Hooking MessageQueue methods: ${targetMethods.joinToString { it.name + it.parameterTypes.joinToString(prefix = "(", postfix = ")") { p -> p.simpleName } }}")

        targetMethods.forEach { method ->
            patcher.patch(method, Hook { hookParam: XC_MethodHook.MethodHookParam ->
                var changed = false
                val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
                var consumedDirectStringMutation = false

                hookParam.args.forEachIndexed { index, arg ->
                    if (arg is String) {
                        // Only mutate one message-like String argument per send call.
                        if (!consumedDirectStringMutation && isLikelyMessageContent(arg)) {
                            val updated = appendMarker(arg)
                            if (updated != arg) {
                                hookParam.args[index] = updated
                                changed = true
                                consumedDirectStringMutation = true
                            }
                        }
                        return@forEachIndexed
                    }

                    if (arg != null && mutateMessageLikeFields(arg, visited, 0)) {
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

    private fun mutateMessageLikeFields(target: Any, visited: MutableSet<Any>, depth: Int): Boolean {
        if (depth > 5 || !visited.add(target)) return false

        if (target.javaClass.isArray) {
            var changed = false
            val size = java.lang.reflect.Array.getLength(target)
            for (i in 0 until size) {
                val element = java.lang.reflect.Array.get(target, i)
                if (element != null && mutateMessageLikeFields(element, visited, depth + 1)) {
                    changed = true
                }
            }
            return changed
        }

        if (target is Iterable<*>) {
            var changed = false
            target.forEach { element ->
                if (element != null && mutateMessageLikeFields(element, visited, depth + 1)) {
                    changed = true
                }
            }
            return changed
        }

        if (target is Map<*, *>) {
            var changed = false
            target.values.forEach { value ->
                if (value != null && mutateMessageLikeFields(value, visited, depth + 1)) {
                    changed = true
                }
            }
            return changed
        }

        var changed = false
        val fieldNameHints = setOf("content", "message", "text", "body")
        var currentClass: Class<*>? = target.javaClass

        while (currentClass != null && currentClass != Any::class.java) {
            currentClass.declaredFields.forEach { field ->
                if (Modifier.isStatic(field.modifiers)) return@forEach

                runCatching {
                    field.isAccessible = true
                    val value = field.get(target) ?: return@runCatching

                    if (value is String) {
                        val fieldName = field.name.lowercase()
                        val shouldMutate = fieldNameHints.any { fieldName.contains(it) } && isLikelyMessageContent(value)
                        if (!shouldMutate) return@runCatching
                        val updated = appendMarker(value)
                        if (updated != value) {
                            field.set(target, updated)
                            changed = true
                            logger.info("Mutated field ${target.javaClass.simpleName}.${field.name}")
                        }
                        return@runCatching
                    }

                    if (depth < 5 && !value.javaClass.isPrimitive && value !is Number && value !is Boolean && value !is Char) {
                        if (mutateMessageLikeFields(value, visited, depth + 1)) {
                            changed = true
                        }
                    }
                }
            }

            currentClass = currentClass.superclass
        }

        return changed
    }

    private fun isLikelyMessageContent(value: String): Boolean {
        if (value.isBlank()) return false
        if (value.length > 4000) return false
        if (value.length in 16..64 && value.all { it.isDigit() || (it in 'a'..'f') || (it in 'A'..'F') || it == '-' || it == '_' }) {
            return false
        }

        return value.any { it.isLetter() || it.isWhitespace() }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}