package com.github.supersliser.transforms

import com.github.supersliser.utils.wordIsLink

class UwuTransform {

    fun apply(content: String): String {
        val output = StringBuilder()
        for (word in content.split(" ")) {
            if (wordIsLink(word)) {
                output.append(word).append(' ')
                continue
            }

            var outWord = word
            outWord = outWord.replace(Regex("r|l"), "w")
            outWord = outWord.replace(Regex("R|L"), "W")
            outWord = outWord.replace(Regex("n([aeiou])"), "ny$1")
            outWord = outWord.replace(Regex("N([aeiou])"), "Ny$1")
            outWord = outWord.replace(Regex("N([AEIOU])"), "NY$1")
            outWord = outWord.replace(Regex("ove"), "uv")
            outWord = outWord.replace(Regex("OVE"), "UV")
            outWord = outWord.replace(Regex("th"), "d")
            outWord = outWord.replace(Regex("TH"), "D")
            outWord = outWord.replace(Regex("u"), "uw")
            outWord = outWord.replace(Regex("U"), "UW")

            output.append(outWord).append(' ')
        }

        return output.toString().trimEnd()
    }
}
