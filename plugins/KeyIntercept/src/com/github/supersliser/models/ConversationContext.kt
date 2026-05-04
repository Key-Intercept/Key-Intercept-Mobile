package com.github.supersliser.models

data class ConversationContext(
    val channelName: String,
    val channelId: Long = -1L,
    val dmName: String,
    val dmId: Long = -1L,
    val serverName: String,
    val serverId: Long = -1L
)
