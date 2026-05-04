package com.github.supersliser.transforms

import com.github.supersliser.utils.wordIsLink
import kotlin.random.Random

class BimboTransform {

    fun apply(content: String): String {
        val pronouns = setOf("i", "is", "you", "he", "she", "we", "they", "it")
        val gargleWords = listOf("like", "hehe", "uhh", "totally", "so dumbb")
        val output = StringBuilder()

        for (word in content.split(" ")) {
            var changed = false
            if (!wordIsLink(word)) {
                val lower = word.lowercase()
                if (lower in pronouns) {
                    output.append("like ").append(word).append(' ')
                    changed = true
                }
            }

            if (!changed) {
                output.append(word).append(' ')
            }

            if (Random.nextFloat() < 0.1f) {
                output.append(gargleWords.random()).append(' ')
            }
        }

        return output.toString().trimEnd()
    }
}
