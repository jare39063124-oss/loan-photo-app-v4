package com.banktool.loanphoto.data.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.3,
    @Json(name = "max_tokens") val maxTokens: Int = 4096,
    val stream: Boolean = false,
    /** OpenRouter provider 限制；为 null 时 Moshi 默认不序列化该字段，兼容旧调用 */
    @Json(name = "provider") val provider: ProviderLimit? = null
)

/** OpenRouter provider 限制：allow_fallbacks=false 时禁止回退到付费模型 */
@JsonClass(generateAdapter = true)
data class ProviderLimit(
    @Json(name = "allow_fallbacks") val allowFallbacks: Boolean = false
)

@JsonClass(generateAdapter = true)
data class ChatMessage(
    val role: String,
    val content: String
)
