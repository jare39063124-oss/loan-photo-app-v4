package com.banktool.loanphoto.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.banktool.loanphoto.data.dto.ChatMessage
import com.banktool.loanphoto.data.repository.AiRepository
import com.banktool.loanphoto.domain.repository.ProgressRepository
import com.banktool.loanphoto.domain.session.PhotoSessionHolder
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
    private val aiRepository: AiRepository,
    private val photoSessionHolder: PhotoSessionHolder,
    private val progressRepository: ProgressRepository,
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatUiMessage>>(emptyList())
    val messages: StateFlow<List<ChatUiMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    companion object {
        private const val SYSTEM_PROMPT = """你是一名专业的银行抵押物拍照助手，可以帮助客户经理解答关于抵押物拍摄、贷款流程、房产评估等方面的问题。
你可以参考下方注入的当前客户清单与拍摄进度来回答问题。
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

    /**
     * 构建当前客户清单与拍摄进度的上下文摘要，注入 system message。
     *
     * 数据来源：
     * - [photoSessionHolder.rows] 当前 Excel 的全量客户行（由 CustomerListViewModel.loadExcel 写入）
     * - [progressRepository.getProgress] 每个客户的照片数（异步查询，限制前 30 户避免 token 爆炸）
     */
    private suspend fun buildContextSummary(): String {
        val rows = photoSessionHolder.rows
        if (rows.isEmpty()) return "\n\n（当前未加载客户清单）"
        val sample = rows.take(30)
        val progressMap = mutableMapOf<String, Int>()
        for (row in sample) {
            val count = runCatching {
                progressRepository.getProgress(row.progressKey)?.photos?.size ?: 0
            }.getOrDefault(0)
            progressMap[row.progressKey] = count
        }
        return buildString {
            append("\n\n当前客户清单（共 ${rows.size} 户，展示前 ${sample.size} 户）：\n")
            sample.forEachIndexed { idx, row ->
                val count = progressMap[row.progressKey] ?: 0
                append("${idx + 1}. ${row.borrower} - ${row.addrGeneral}${row.addrDetail}")
                if (row.propertyType.isNotBlank()) append("（${row.propertyType}）")
                append(" [已拍 $count 张]\n")
            }
            if (rows.size > 30) append("...（及其他 ${rows.size - 30} 户）\n")
        }
    }

    fun sendMessage(content: String) {
        if (content.isBlank() || _isLoading.value) return

        val userMessage = ChatUiMessage(role = "user", content = content)
        _messages.value = _messages.value + userMessage
        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                val systemContent = SYSTEM_PROMPT + buildContextSummary()
                val chatMessages = listOf(
                    ChatMessage(role = "system", content = systemContent)
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
