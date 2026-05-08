package com.github.supersliser.transforms

import com.github.supersliser.models.CensoredWord
import com.github.supersliser.models.KeyInterceptConfig
import java.util.regex.Pattern

class CensoredTransform(
    private var config: KeyInterceptConfig,
    private var censoredWords: List<CensoredWord>
) {

    fun apply(content: String): String {
        // If the mode has expired, do nothing
        val now = System.currentTimeMillis()
        if (config.censoredEnd <= now) return content

        var out = content
        for (cw in censoredWords) {
            val word = cw.word.trim()
            if (word.isEmpty()) continue
            val escaped = Pattern.quote(word)
            val pattern = Regex("(?i)\\b$escaped\\b")
            val mask = "*".repeat(word.length)
            out = out.replace(pattern, mask)
        }
        return out
    }

    fun updateConfig(newConfig: KeyInterceptConfig) {
        config = newConfig
    }

    fun updateWords(newWords: List<CensoredWord>) {
        censoredWords = newWords
    }
}
