package com.github.supersliser.transforms

import com.github.supersliser.models.KeyInterceptConfig
import com.github.supersliser.utils.wordIsLink
import kotlin.random.Random

class DroneTransform(private var config: KeyInterceptConfig) {

    fun apply(content: String): String {
        if (config.droneHealth < 0.1f) {
            return "`This Drone haaaaas receieved bzzzzt, ppplease provide repaiirs using beep '/repair', tthank youu. Returned Error: 0x7547372482`"
        }

        val containsLink = content.split(" ").any(::wordIsLink)

        var working = content
        if (!containsLink) {
            working = working.replace(Regex("(?i)\\bMe\\b"), "This Drone")
            working = working.replace(Regex("(?i)\\bMy\\b"), "Its'")
            working = working.replace(Regex("(?i)\\bI am\\b"), "It is")
            working = working.replace(Regex("(?i)\\bI(')?m\\b"), "It is")
            working = working.replace(Regex("(?i)\\bI\\b"), "This Drone")
        }

        val phaseOne = StringBuilder()
        for (word in working.split(" ")) {
            if (!wordIsLink(word) && Random.nextFloat() > config.droneHealth) {
                phaseOne.append("bzzzzt ").append(word).append(' ')
            } else {
                phaseOne.append(word).append(' ')
            }
        }

        val phaseTwo = StringBuilder()
        var lastTriggered = 0
        for (word in phaseOne.toString().trimEnd().split(" ")) {
            if (wordIsLink(word)) {
                phaseTwo.append(word).append(' ')
                continue
            }

            val outWord = StringBuilder()
            for (ch in word) {
                outWord.append(ch)
                if (Random.nextFloat() > config.droneHealth && ch.isLetter()) {
                    outWord.append(ch)
                }
            }

            phaseTwo.append(outWord).append(' ')
        }

        return "`${config.droneHeaderText}`\n${phaseTwo.toString().trimEnd()}\n`${config.droneFooterText}`"
    }

    fun updateConfig(newConfig: KeyInterceptConfig) {
        config = newConfig
    }
}
