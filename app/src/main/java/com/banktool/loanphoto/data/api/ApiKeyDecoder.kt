package com.banktool.loanphoto.data.api

/**
 * OpenRouter API key 混淆解码器。
 *
 * key 以 Base64(XOR(明文, PATTERN)) 形式存储于 local.properties（openrouter.api.key.obf），
 * 构建时经 BuildConfig.OPENROUTER_API_KEY_OBF 注入，运行时在此还原为明文，
 * 避免明文 key 直接出现在 local.properties / 反编译的 BuildConfig 中。
 */
object ApiKeyDecoder {
    private val PATTERN = "BTLP2026".toByteArray(Charsets.UTF_8)

    fun decode(obfB64: String): String {
        if (obfB64.isEmpty()) return ""
        val bytes = android.util.Base64.decode(obfB64, android.util.Base64.NO_WRAP)
        return String(ByteArray(bytes.size) { i -> (bytes[i].toInt() xor PATTERN[i % PATTERN.size].toInt()).toByte() }, Charsets.UTF_8)
    }
}
