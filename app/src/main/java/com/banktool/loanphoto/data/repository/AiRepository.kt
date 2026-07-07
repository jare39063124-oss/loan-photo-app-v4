package com.banktool.loanphoto.data.repository

import android.content.Context
import com.banktool.loanphoto.BuildConfig
import com.banktool.loanphoto.data.api.DeepSeekApi
import com.banktool.loanphoto.data.dto.ChatMessage
import com.banktool.loanphoto.data.dto.ChatRequest
import com.banktool.loanphoto.data.dto.ReportRecord
import com.banktool.loanphoto.domain.entity.CustomerRow
import com.banktool.loanphoto.domain.entity.PhotoRecord
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 服务：调用 DeepSeek API 生成日报表和聊天
 */
@Singleton
class AiRepository @Inject constructor(
    private val deepSeekApi: DeepSeekApi,
    private val moshi: Moshi,
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "AiRepository"
        private const val MODEL = "deepseek-chat"
        private const val CHAT_MODEL = "deepseek-chat"
    }

    /**
     * 生成日报表
     * @param visitedRecords 已拍摄客户列表
     * @param batchMarkedCount 同类型标记数
     * @param visitNote 走访备注
     * @return 解析后的 ReportRecord 列表
     */
    suspend fun generateReport(
        visitedRecords: List<Pair<CustomerRow, PhotoRecord>>,
        batchMarkedCount: Int,
        visitNote: String?
    ): List<ReportRecord> = withContext(Dispatchers.IO) {
        if (BuildConfig.DEEPSEEK_API_KEY.isBlank()) {
            Timber.e("DeepSeek API key is blank")
            throw IllegalStateException("DeepSeek API key 未配置")
        }

        val request = ReportPromptBuilder.buildChatRequest(
            visitedRecords = visitedRecords,
            batchMarkedCount = batchMarkedCount,
            visitNote = visitNote,
            model = MODEL
        )

        Timber.i("Sending report generation request: ${visitedRecords.size} customers")

        val response = try {
            deepSeekApi.chatCompletions(request)
        } catch (e: Exception) {
            Timber.e(e, "DeepSeek API call failed")
            throw e
        }

        val content = response.choices.firstOrNull()?.message?.content
            ?: throw IllegalStateException("AI 返回内容为空")

        Timber.i("AI response length: ${content.length}")

        parseAiResponse(content)
    }

    /**
     * 解析 AI 响应（3 级 fallback）
     * 1. 直接 JSON 解析
     * 2. 正则提取 [.*]
     * 3. 逐行提取 {...}
     */
    private fun parseAiResponse(content: String): List<ReportRecord> {
        // Level 1: 直接 JSON 解析
        try {
            val cleanContent = content.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val type = Types.newParameterizedType(
                List::class.java,
                ReportRecord::class.java
            )
            val adapter = moshi.adapter<List<ReportRecord>>(type)
            val records = adapter.fromJson(cleanContent)
            if (!records.isNullOrEmpty()) {
                Timber.i("Level 1 parse success: ${records.size} records")
                return records
            }
        } catch (e: Exception) {
            Timber.w("Level 1 parse failed: ${e.message}")
        }

        // Level 2: 正则提取 [.*]
        try {
            val regex = Regex("""\[[\s\S]*\]""")
            val match = regex.find(content)
            if (match != null) {
                val jsonStr = match.value
                val type = Types.newParameterizedType(
                    List::class.java,
                    ReportRecord::class.java
                )
                val adapter = moshi.adapter<List<ReportRecord>>(type)
                val records = adapter.fromJson(jsonStr)
                if (!records.isNullOrEmpty()) {
                    Timber.i("Level 2 parse success: ${records.size} records")
                    return records
                }
            }
        } catch (e: Exception) {
            Timber.w("Level 2 parse failed: ${e.message}")
        }

        // Level 3: 逐行提取 {...}
        try {
            val regex = Regex("""\{[^{}]+\}""")
            val matches = regex.findAll(content).map { it.value }.toList()
            if (matches.isNotEmpty()) {
                val adapter = moshi.adapter(ReportRecord::class.java)
                val records = matches.mapNotNull { jsonStr ->
                    try {
                        adapter.fromJson(jsonStr)
                    } catch (e: Exception) {
                        null
                    }
                }.filter { it.customerName.isNotBlank() }

                if (records.isNotEmpty()) {
                    Timber.i("Level 3 parse success: ${records.size} records")
                    return records
                }
            }
        } catch (e: Exception) {
            Timber.w("Level 3 parse failed: ${e.message}")
        }

        Timber.e("All parse levels failed, content preview: ${content.take(200)}")
        throw IllegalStateException("AI 响应解析失败")
    }

    /**
     * 校验 AI 是否参考了 visit_note
     * @param records AI 生成的记录
     * @param visitNote 走访备注
     * @return true 如果 >=30% 关键片段被包含
     */
    fun validateResponse(records: List<ReportRecord>, visitNote: String?): Boolean {
        if (visitNote.isNullOrBlank()) return true

        // 提取 visit_note 中含数字/号/栋/楼的关键片段（len>=4）
        val keywords = mutableListOf<String>()
        val regex = Regex("""[\w\u4e00-\u9fff]*[0-9号栋楼][\w\u4e00-\u9fff]*""")
        regex.findAll(visitNote).forEach { match ->
            if (match.value.length >= 4) {
                keywords.add(match.value)
            }
        }

        if (keywords.isEmpty()) return true

        // 检查 AI 响应中是否包含 >=30% 关键片段
        val allContent = records.joinToString(" ") { record ->
            "${record.customerName} ${record.collateralInfo} ${record.fieldDescription} ${record.riskAlert} ${record.summary}"
        }

        val matchedCount = keywords.count { keyword ->
            allContent.contains(keyword)
        }

        val ratio = matchedCount.toDouble() / keywords.size
        Timber.i("Validation: $matchedCount/${keywords.size} keywords matched (ratio=$ratio)")

        return ratio >= 0.3
    }

    /**
     * 聊天（AI 拍摄助手）
     */
    suspend fun chat(messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        val request = ChatRequest(
            model = CHAT_MODEL,
            messages = messages,
            temperature = 0.5,
            maxTokens = 2048,
            stream = false
        )

        val response = deepSeekApi.chatCompletions(request)
        response.choices.firstOrNull()?.message?.content
            ?: throw IllegalStateException("AI 返回内容为空")
    }
}
