package com.github.supersliser.transforms

import com.github.supersliser.discord.DiscordResolver
import com.github.supersliser.models.DroneConfig
import com.github.supersliser.utils.wordIsLink
import kotlin.random.Random

class DroneTransform(private var config: DroneConfig) {

    private enum class DroneMode {
        SPEECH,
        ACTION,
        WHISPER,
        LOUD
    }

    private data class HeaderFooter(
        val header: String,
        val footer: String
    )

    fun apply(content: String, discordResolver: DiscordResolver?): String {
        if (config.drone_health < 0.1f) {
            return "`This Drone haaaaas receieved bzzzzt, ppplease provide repaiirs using beep '/repair', tthank youu. Returned Error: 0x7547372482`"
        }

        val mode = detectMode(content)
        val (headerText, footerText) = headerFooterForMode(mode)
        val strippedInput = stripModePrefix(content, mode)

        val containsLink = strippedInput.split(" ").any(::wordIsLink)

        var working = strippedInput
        if (!containsLink) {
            working = working.replace(Regex("(?i)\\bMe\\b"), "This Drone")
            working = working.replace(Regex("(?i)\\bMy\\b"), "Its'")
            working = working.replace(Regex("(?i)\\bI am\\b"), "It is")
            working = working.replace(Regex("(?i)\\bI(')?m\\b"), "It is")
            working = working.replace(Regex("(?i)\\bI\\b"), "This Drone")
        }

        val phaseOne = StringBuilder()
        for (word in working.split(" ")) {
            if (!wordIsLink(word) && Random.nextFloat() > config.drone_health) {
                phaseOne.append("bzzzzt ").append(word).append(' ')
            } else {
                phaseOne.append(word).append(' ')
            }
        }

        val phaseTwo = StringBuilder()
        for (word in phaseOne.toString().trimEnd().split(" ")) {
            if (wordIsLink(word)) {
                phaseTwo.append(word).append(' ')
                continue
            }

            val outWord = StringBuilder()
            for (ch in word) {
                outWord.append(ch)
                if (Random.nextFloat() > config.drone_health && ch.isLetter()) {
                    outWord.append(ch)
                }
            }

            phaseTwo.append(outWord).append(' ')
        }

        val body = phaseTwo.toString().trimEnd()
        val shouldCarryFooter = tryCarryFooterForward(discordResolver, footerText)
        return if (shouldCarryFooter) {
            "$body\n`${footerText}`"
        } else {
            "`${headerText}`\n$body\n`${footerText}`"
        }
    }

    private fun detectMode(content: String): DroneMode {
        val trimmed = content.trimStart()
        return when {
            trimmed.startsWith("**") -> DroneMode.LOUD
            trimmed.startsWith("-#") -> DroneMode.WHISPER
            trimmed.startsWith("*") -> DroneMode.ACTION
            else -> DroneMode.SPEECH
        }
    }

    private fun stripModePrefix(content: String, mode: DroneMode): String {
        val trimmedStart = content.trimStart()
        val stripped = when (mode) {
            DroneMode.LOUD -> trimmedStart.removePrefix("**")
            DroneMode.WHISPER -> trimmedStart.removePrefix("-#")
            DroneMode.ACTION -> trimmedStart.removePrefix("*")
            DroneMode.SPEECH -> trimmedStart
        }
        return stripped.trimStart()
    }

    private fun headerFooterForMode(mode: DroneMode): HeaderFooter {
        return when (mode) {
            DroneMode.SPEECH -> HeaderFooter(
                header = config.speech_header,
                footer = config.speech_footer
            )

            DroneMode.ACTION -> HeaderFooter(
                header = config.action_header,
                footer = config.action_footer
            )

            DroneMode.WHISPER -> HeaderFooter(
                header = config.whisper_header,
                footer = config.whisper_footer
            )

            DroneMode.LOUD -> HeaderFooter(
                header = config.loud_header,
                footer = config.loud_footer
            )
        }
    }

    private fun tryCarryFooterForward(discordResolver: DiscordResolver?, currentFooter: String): Boolean {
        if (discordResolver == null) return false

        discordResolver.getPreviouslySentMessage() ?: return false
        val previousAuthor = discordResolver.getAuthorOfPreviouslySentMessage() ?: return false
        val currentUserId = discordResolver.resolveCurrentDiscordId() ?: return false
        val previousAuthorId = extractUserId(previousAuthor) ?: return false

        if (currentUserId != previousAuthorId) return false

        val previousContent = discordResolver.getPreviouslySentMessageContent() ?: return false
        val previousFooter = extractFooter(previousContent) ?: return false
        if (!isSameFooter(previousFooter, currentFooter)) return false

        val editedPrevious = removeTrailingFooter(previousContent, currentFooter)
        if (editedPrevious == previousContent) return false

        return discordResolver.editPreviousMessage(editedPrevious)
    }

    private fun extractUserId(author: Any?): Long? {
        if (author == null) return null

        val methodNames = listOf("getId", "getUserId", "id", "userId")
        for (name in methodNames) {
            val value = runCatching {
                val method = author.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                method?.invoke(author)
            }.getOrNull()

            val id = when (value) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull()
                else -> null
            }
            if (id != null && id > 0L) return id
        }

        val fieldNames = listOf("id", "userId", "user_id")
        for (name in fieldNames) {
            val value = runCatching {
                val field = author.javaClass.getDeclaredField(name)
                field.isAccessible = true
                field.get(author)
            }.getOrNull()

            val id = when (value) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull()
                else -> null
            }
            if (id != null && id > 0L) return id
        }

        return null
    }

    private fun extractFooter(content: String): String? {
        val lines = content.lines().filter { it.trim().isNotEmpty() }
        if (lines.isEmpty()) return null
        return normalizeFooter(lines.last())
    }

    private fun isSameFooter(previousFooter: String, currentFooter: String): Boolean {
        return normalizeFooter(previousFooter) == normalizeFooter(currentFooter)
    }

    private fun normalizeFooter(raw: String): String {
        return raw.trim().removePrefix("`").removeSuffix("`").trim()
    }

    private fun removeTrailingFooter(content: String, footer: String): String {
        val lines = content.lines().toMutableList()
        while (lines.isNotEmpty() && lines.last().trim().isEmpty()) {
            lines.removeAt(lines.lastIndex)
        }

        if (lines.isEmpty()) return content

        val lastLineFooter = normalizeFooter(lines.last())
        if (lastLineFooter != normalizeFooter(footer)) return content

        lines.removeAt(lines.lastIndex)
        while (lines.isNotEmpty() && lines.last().trim().isEmpty()) {
            lines.removeAt(lines.lastIndex)
        }

        return lines.joinToString("\n")
    }

    fun updateConfig(newConfig: DroneConfig) {
        config = newConfig
    }
}
