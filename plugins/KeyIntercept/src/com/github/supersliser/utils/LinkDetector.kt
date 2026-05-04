package com.github.supersliser.utils

fun wordIsLink(word: String): Boolean {
    return word.startsWith("http://") || word.startsWith("https://") || word.startsWith("www.")
}
