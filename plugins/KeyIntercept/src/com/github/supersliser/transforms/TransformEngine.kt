package com.github.supersliser.transforms

import com.github.supersliser.models.KeyInterceptConfig
import com.github.supersliser.models.DroneConfig
import com.github.supersliser.models.Rule
import com.github.supersliser.models.CensoredWord
import com.github.supersliser.discord.DiscordResolver
import com.github.supersliser.transforms.CensoredTransform

class TransformEngine(
    private val config: KeyInterceptConfig,
    private val droneConfig: DroneConfig,
    private val rules: List<Rule>,
    private val censoredWords: List<CensoredWord>,
    private val discordResolver: DiscordResolver?
) {

    private val rulesTransform = RulesTransform(rules)
    private val censoredTransform = CensoredTransform(config, censoredWords)
    private val gagTransform = GagTransform()
    private val petTransform = PetTransform(config)
    private val bimboTransform = BimboTransform()
    private val hornyTransform = HornyTransform()
    private val droneTransform = DroneTransform(droneConfig, config)
    private val uwuTransform = UwuTransform()

    fun applyAllTransforms(content: String): String {
        var modified = content
        modified = rulesTransform.apply(modified)
        modified = censoredTransform.apply(modified)
        modified = uwuTransform.apply(modified)
        modified = hornyTransform.apply(modified)
        modified = petTransform.apply(modified)
        modified = bimboTransform.apply(modified)
        modified = gagTransform.apply(modified)
        modified = droneTransform.apply(modified, discordResolver)
        return modified
    }

    fun updateConfig(newConfig: KeyInterceptConfig) {
        petTransform.updateConfig(newConfig)
        censoredTransform.updateConfig(newConfig)
        droneTransform.updateKeyConfig(newConfig)
    }

    fun updateDroneConfig(newDroneConfig: DroneConfig) {
        droneTransform.updateConfig(newDroneConfig)
    }

    fun updateCensoredWords(newWords: List<CensoredWord>) {
        censoredTransform.updateWords(newWords)
    }

    fun updateRules(newRules: List<Rule>) {
        rulesTransform.updateRules(newRules)
    }
}
