package com.github.supersliser.transforms

import com.github.supersliser.models.KeyInterceptConfig
import com.github.supersliser.models.PetWord
import com.github.supersliser.utils.wordIsLink
import kotlin.random.Random

class PetTransform(private var config: KeyInterceptConfig) {

    private var petWords: List<PetWord> = emptyList()

    fun setPetWords(words: List<PetWord>) {
        petWords = words
    }

    fun apply(content: String): String {
        val output = StringBuilder()
        for (word in content.split(" ")) {
            if (wordIsLink(word)) {
                output.append(word).append(' ')
                continue
            }
            if (Random.nextFloat() < config.petAmount) {
                output.append(petWords.randomOrNull()?.word ?: "pet").append(' ')
            } else {
                output.append(word).append(' ')
            }
        }
        return output.toString().trimEnd()
    }

    fun updateConfig(newConfig: KeyInterceptConfig) {
        config = newConfig
    }
}
