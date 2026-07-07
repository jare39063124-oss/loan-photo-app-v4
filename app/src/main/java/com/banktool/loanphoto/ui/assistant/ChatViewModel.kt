package com.banktool.loanphoto.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.banktool.loanphoto.data.dto.ChatMessage
import com.banktool.loanphoto.data.repository.AiRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class ChatUiMessage(
    val role: String,        // "user" or "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val aiRepository: AiRepository
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatUiMessage>>(emptyList())
    val messages: StateFlow<List<ChatUiMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    companion object {
        private const val SYSTEM_PROMPT = """你是一名专业的银行抵押物拍照助手，可以帮助客户经理解答关于抵押物拍摄、贷款流程、房产评估等方面的问题。
你可以参考以下客户数据、产品数据和销售记录来回答问题。
请用简洁专业的中文回答，不含 emoji。"""
    }

    init {
        // 添加欢迎消息
        _messages.value = listOf(
            ChatUiMessage(
                role = "assistant",
                content = "您好，我是 AI 拍摄助手。可以为您解答抵押物拍摄、贷款流程等问题，请问有什么可以帮您？"
            )
        )
    }

    fun sendMessage(content: String) {
        if (content.isBlank() || _isLoading.value) return

        val userMessage = ChatUiMessage(role = "user", content = content)
        _messages.value = _messages.value + userMessage
        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                val chatMessages = listOf(
                    ChatMessage(role = "system", content = SYSTEM_PROMPT)
                ) + _messages.value.map {
                    ChatMessage(role = it.role, content = it.content)
                }

                val response = aiRepository.chat(chatMessages)

                val assistantMessage = ChatUiMessage(
                    role = "assistant",
                    content = response
                )
                _messages.value = _messages.value + assistantMessage

            } catch (e: Exception) {
                Timber.e(e, "Chat failed")
                _error.value = e.message ?: "请求失败，请重试"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
