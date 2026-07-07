package com.banktool.loanphoto.data.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.3,
    @Json(name = "max_tokens") val maxTokens: Int = 4096,
    val stream: Boolean = false
)

@JsonClass(generateAdapter = true)
data class ChatMessage(
    val role: String,
    val content: String
)
