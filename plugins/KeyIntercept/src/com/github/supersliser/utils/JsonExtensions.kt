package com.github.supersliser.utils

import org.json.JSONObject

fun JSONObject.readLong(key: String, default: Long = 0L): Long {
    if (!has(key) || isNull(key)) return default
    val value = get(key)
    return when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: default
        else -> default
    }
}

fun JSONObject.readInt(key: String, default: Int = 0): Int {
    if (!has(key) || isNull(key)) return default
    val value = get(key)
    return when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: default
        else -> default
    }
}

fun JSONObject.readFloat(key: String, default: Float = 0f): Float {
    if (!has(key) || isNull(key)) return default
    val value = get(key)
    return when (value) {
        is Number -> value.toFloat()
        is String -> value.toFloatOrNull() ?: default
        else -> default
    }
}

fun JSONObject.readBoolean(key: String, default: Boolean = false): Boolean {
    if (!has(key) || isNull(key)) return default
    val value = get(key)
    return when (value) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true)
        is Number -> value.toInt() != 0
        else -> default
    }
}

fun JSONObject.readString(key: String, default: String = ""): String {
    if (!has(key) || isNull(key)) return default
    return runCatching { getString(key) }.getOrElse { default }
}

fun JSONObject.readTimestampMillis(key: String, default: Long = 0L): Long {
    if (!has(key) || isNull(key)) return default
    val value = get(key)
    return when (value) {
        is Number -> parseEpochLikeToMillis(value.toLong())
        is String -> parseTimestampStringToMillis(value) ?: default
        else -> default
    }
}
