package com.github.supersliser.transforms

import kotlin.random.Random

class HornyTransform {

    fun apply(content: String): String {
        val hornyWords = listOf(
            "hmmph", "nngh", "ahhh", "ooh", "oohh", "mmm", "hehe", "hehehe", "heheh",
            "eheh", "ehehe", "eheheh", "guhh", "pleasee", "need to cumm", "oh goshh",
            "ohhh", "ahhh", "cummm", "gggg"
        )

        val output = StringBuilder()
        for (word in content.split(" ")) {
            output.append(word).append(' ')
            if (Random.nextFloat() < 0.75f) {
                output.append(hornyWords.random()).append(' ')
            }
        }
        return output.toString().trimEnd()
    }
}
