package com.github.supersliser.models

data class Rule(
    val id: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val configId: Long = 0,
    val ruleRegex: String = "",
    val ruleReplacement: String = "",
    val enabled: Boolean = false,
    val chanceToApply: Float = 0f
)
