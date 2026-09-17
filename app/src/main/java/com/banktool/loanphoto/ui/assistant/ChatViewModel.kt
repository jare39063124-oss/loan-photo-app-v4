package com.banktool.loanphoto.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.banktool.loanphoto.data.dto.ChatMessage
import com.banktool.loanphoto.data.repository.AiRepository
import com.banktool.loanphoto.domain.entity.CustomerRow
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
你可以查询并回答当前清单的拍摄进度统计（总户数、已拍摄/未拍摄户数、各类型张数等），回答时引用上下文中的统计数据。
请用简洁专业的中文回答，不含 emoji。"""

        /** 明细全列上限：超过则只列前 N 户，其余按区域聚合，控制上下文 token */
        private const val DETAIL_LIMIT = 60

        /** 区域聚合摘要中最多列出的区域数，超出部分合并为「其他区域」 */
        private const val REGION_LIMIT = 15
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
     * - [progressRepository.getAllProgress] 一次性批量读取全部拍照记录，内存查表（避免逐户 getProgress 的 N+1 查询）
     *
     * 结构：
     * - 全局统计头（总户数/已拍摄/未拍摄/照片总数/各类型张数），供 AI 直接引用回答进度类问题
     * - 逐户明细≤[DETAIL_LIMIT] 户全列；超出则列前 [DETAIL_LIMIT] 户，其余按地址概（区域）分组聚合，控制 token
     */
    private suspend fun buildContextSummary(): String {
        val rows = photoSessionHolder.rows
        if (rows.isEmpty()) return "\n\n（当前未加载客户清单）"

        // 批量读取全部拍照记录，一次 IO，后续内存查表
        val progressMap = runCatching { progressRepository.getAllProgress() }
            .onFailure { Timber.w(it, "getAllProgress failed") }
            .getOrDefault(emptyMap())

        fun photoCount(key: String): Int = progressMap[key]?.photos?.size ?: 0

        val visitedCount = rows.count { photoCount(it.progressKey) > 0 }
        val totalPhotos = rows.sumOf { photoCount(it.progressKey) }

        // 各拍摄类型张数：优先 photoTypes（与 photos 平行的类型列表，张数精确）；
        // 记录缺 photoTypes 时无法逐张归类，回退记入「未分类」
        val typeCounts = LinkedHashMap<String, Int>()
        for (row in rows) {
            val record = progressMap[row.progressKey] ?: continue
            if (record.photoTypes.isNotEmpty()) {
                for (type in record.photoTypes) typeCounts[type] = (typeCounts[type] ?: 0) + 1
            } else if (record.photos.isNotEmpty()) {
                typeCounts["未分类"] = (typeCounts["未分类"] ?: 0) + record.photos.size
            }
        }

        fun rowLine(index: Int, row: CustomerRow): String = buildString {
            append("$index. ${row.borrower} - ${row.addrGeneral}${row.addrDetail}")
            if (row.propertyType.isNotBlank()) append("（${row.propertyType}）")
            append(" [已拍 ${photoCount(row.progressKey)} 张]\n")
        }

        return buildString {
            append("\n\n【拍摄进度统计】\n")
            append("总户数：${rows.size} 户\n")
            append("已拍摄户数：$visitedCount 户\n")
            append("未拍摄户数：${rows.size - visitedCount} 户\n")
            append("照片总数：$totalPhotos 张\n")
            if (typeCounts.isNotEmpty()) {
                append("各类型张数：")
                append(typeCounts.entries.joinToString("、") { (type, count) -> "$type $count 张" })
                append("\n")
            }

            append("\n【客户清单明细】\n")
            if (rows.size <= DETAIL_LIMIT) {
                rows.forEachIndexed { idx, row -> append(rowLine(idx + 1, row)) }
            } else {
                rows.take(DETAIL_LIMIT).forEachIndexed { idx, row -> append(rowLine(idx + 1, row)) }
                // 其余客户按地址概（区域）分组聚合摘要；CustomerRow 无线路/区域专属字段，addrGeneral 即区域粒度
                val rest = rows.drop(DETAIL_LIMIT)
                append("（其余 ${rest.size} 户按区域汇总：\n")
                val byRegion = rest.groupBy { it.addrGeneral }
                    .entries
                    .sortedByDescending { it.value.size }
                byRegion.take(REGION_LIMIT).forEach { (region, regionRows) ->
                    val regionVisited = regionRows.count { photoCount(it.progressKey) > 0 }
                    val regionPhotos = regionRows.sumOf { photoCount(it.progressKey) }
                    append("- $region：共 ${regionRows.size} 户，已拍摄 $regionVisited 户，共 $regionPhotos 张\n")
                }
                if (byRegion.size > REGION_LIMIT) {
                    val others = byRegion.drop(REGION_LIMIT)
                    val otherRows = others.flatMap { it.value }
                    val otherVisited = otherRows.count { photoCount(it.progressKey) > 0 }
                    append("- 其他区域（${others.size} 个）：共 ${otherRows.size} 户，已拍摄 $otherVisited 户\n")
                }
                append("）\n")
            }
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
