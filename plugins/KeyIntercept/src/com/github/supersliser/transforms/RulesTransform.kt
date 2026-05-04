package com.github.supersliser.transforms

import com.github.supersliser.models.Rule
import doist.x.normalize.Form
import doist.x.normalize.normalize
import kotlin.random.Random

class RulesTransform(private var rules: List<Rule>) {

    fun apply(content: String): String {
        var modified = content.normalize(Form.NFKC)
        for (rule in rules) {
            if (rule.enabled && Random.nextFloat() < rule.chanceToApply) {
                modified = modified.replace(Regex(rule.ruleRegex), rule.ruleReplacement)
            }
        }
        return modified
    }

    fun updateRules(newRules: List<Rule>) {
        rules = newRules
    }
}
