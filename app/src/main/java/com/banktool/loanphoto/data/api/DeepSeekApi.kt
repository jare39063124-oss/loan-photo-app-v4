package com.banktool.loanphoto.data.api

import com.banktool.loanphoto.data.dto.ChatRequest
import com.banktool.loanphoto.data.dto.ChatResponse
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * DeepSeek Chat Completions API
 * baseUrl: https://api.deepseek.com/v1/
 */
interface DeepSeekApi {

    @POST("chat/completions")
    suspend fun chatCompletions(@Body request: ChatRequest): ChatResponse
}
