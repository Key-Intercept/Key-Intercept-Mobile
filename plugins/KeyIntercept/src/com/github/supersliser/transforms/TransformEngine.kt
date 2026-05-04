package com.github.supersliser.transforms

import com.github.supersliser.models.KeyInterceptConfig
import com.github.supersliser.models.Rule

class TransformEngine(
    private val config: KeyInterceptConfig,
    private val rules: List<Rule>
) {

    private val rulesTransform = RulesTransform(rules)
    private val gagTransform = GagTransform()
    private val petTransform = PetTransform(config)
    private val bimboTransform = BimboTransform()
    private val hornyTransform = HornyTransform()
    private val droneTransform = DroneTransform(config)
    private val uwuTransform = UwuTransform()

    fun applyAllTransforms(content: String): String {
        var modified = content
        modified = rulesTransform.apply(modified)
        modified = uwuTransform.apply(modified)
        modified = hornyTransform.apply(modified)
        modified = petTransform.apply(modified)
        modified = bimboTransform.apply(modified)
        modified = gagTransform.apply(modified)
        modified = droneTransform.apply(modified, config.droneHealth)
        return modified
    }

    fun updateConfig(newConfig: KeyInterceptConfig) {
        petTransform.updateConfig(newConfig)
        droneTransform.updateConfig(newConfig)
    }

    fun updateRules(newRules: List<Rule>) {
        rulesTransform.updateRules(newRules)
    }
}
