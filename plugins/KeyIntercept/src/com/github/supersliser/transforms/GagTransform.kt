package com.github.supersliser.transforms

import com.github.supersliser.utils.wordIsLink

class GagTransform {

    fun apply(content: String): String {
        val remainChars = setOf(
            'a', 'e', 'i', 'o', 'u', 'g', 'h',
            'A', 'E', 'I', 'O', 'U', 'G', 'H',
            '?', '!', '.', ',', ':', ';', '#', '*', '-', '(', ')', '~'
        )

        val output = StringBuilder()
        for (word in content.split(" ")) {
            if (wordIsLink(word)) {
                output.append(word).append(' ')
                continue
            }

            for (ch in word) {
                if (ch in remainChars) {
                    output.append(ch)
                } else {
                    output.append('*')
                }
            }
            output.append(' ')
        }

        return output.toString().trimEnd()
    }
}
