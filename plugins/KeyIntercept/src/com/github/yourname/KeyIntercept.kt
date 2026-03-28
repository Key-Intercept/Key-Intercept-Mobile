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
    private var didLogEnqueueShape = false
    private var didLogDoSendShape = false

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
                val stringArgIndexes = mutableListOf<Int>()

                if (method.name == "enqueue" && !didLogEnqueueShape) {
                    didLogEnqueueShape = true
                    logger.info("enqueue arg shapes: ${hookParam.args.joinToString { describeValueShape(it) }}")
                }

                if (method.name == "doSend" && !didLogDoSendShape) {
                    didLogDoSendShape = true
                    logger.info("doSend arg shapes: ${hookParam.args.joinToString { describeValueShape(it) }}")
                }

                hookParam.args.forEachIndexed { index, arg ->
                    if (arg is String) {
                        stringArgIndexes.add(index)
                        return@forEachIndexed
                    }

                    if (arg != null && mutateMessageLikeFields(arg, visited, 0)) {
                        changed = true
                    }
                }

                val directIndex = pickBestStringArgIndex(hookParam.args, stringArgIndexes)
                if (directIndex != null) {
                    val original = hookParam.args[directIndex] as String
                    val updated = appendMarker(original)
                    if (updated != original) {
                        hookParam.args[directIndex] = updated
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
            val mutable = target as? MutableMap<Any?, Any?>
            target.forEach { (key, value) ->
                val keyName = (key as? String)?.lowercase()
                if (keyName != null && value is String && isMessageFieldName(keyName)) {
                    val updated = appendMarker(value)
                    if (updated != value) {
                        mutable?.set(key, updated)
                        changed = true
                        logger.info("Mutated map entry key=$key")
                    }
                    return@forEach
                }

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
                        val shouldMutate = fieldNameHints.any { fieldName.contains(it) }
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

    private fun pickBestStringArgIndex(args: Array<Any?>, indexes: List<Int>): Int? {
        if (indexes.isEmpty()) return null

        // Prefer the longest human-looking string, which is typically message content.
        val bestHuman = indexes
            .map { it to (args[it] as String) }
            .filter { (_, value) -> isLikelyMessageContent(value) }
            .maxByOrNull { (_, value) -> value.length }
            ?.first

        if (bestHuman != null) return bestHuman

        // Fallback: longest non-empty string.
        return indexes
            .map { it to (args[it] as String) }
            .filter { (_, value) -> value.isNotEmpty() }
            .maxByOrNull { (_, value) -> value.length }
            ?.first
    }

    private fun isLikelyMessageContent(value: String): Boolean {
        if (value.isBlank()) return false
        if (value.length > 4000) return false
        if (value.length in 16..64 && value.all { it.isDigit() || (it in 'a'..'f') || (it in 'A'..'F') || it == '-' || it == '_' }) {
            return false
        }

        return value.any { it.isLetter() || it.isWhitespace() }
    }

    private fun isMessageFieldName(fieldName: String): Boolean {
        return fieldName.contains("content") ||
            fieldName.contains("message") ||
            fieldName.contains("text") ||
            fieldName.contains("body")
    }

    private fun describeValueShape(value: Any?): String {
        if (value == null) return "null"
        return when (value) {
            is String -> "String(len=${value.length})"
            is Map<*, *> -> "${value.javaClass.simpleName}(size=${value.size})"
            is Iterable<*> -> "${value.javaClass.simpleName}(iterable)"
            else -> {
                val fields = value.javaClass.declaredFields
                    .filterNot { Modifier.isStatic(it.modifiers) }
                    .take(8)
                    .joinToString { it.name + ":" + it.type.simpleName }
                "${value.javaClass.simpleName}{$fields}"
            }
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}