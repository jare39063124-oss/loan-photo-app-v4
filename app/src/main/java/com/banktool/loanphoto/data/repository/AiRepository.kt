package com.banktool.loanphoto.data.repository

import android.content.Context
import com.banktool.loanphoto.BuildConfig
import com.banktool.loanphoto.data.api.DeepSeekApi
import com.banktool.loanphoto.data.dto.ChatMessage
import com.banktool.loanphoto.data.dto.ChatRequest
import com.banktool.loanphoto.data.dto.ProviderLimit
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
 * AI 服务：调用 OpenRouter API 生成日报表和聊天
 */
@Singleton
class AiRepository @Inject constructor(
    private val deepSeekApi: DeepSeekApi,
    private val moshi: Moshi,
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "AiRepository"

        /**
         * 免费模型硬锁定：所有 AI 请求（chat + 报告生成）仅允许 :free 后缀模型，
         * 请求发送前校验，配置失误时快速失败，防止调用付费模型产生费用。
         */
        private fun requireFreeModel(): String {
            require(BuildConfig.OPENROUTER_MODEL.endsWith(":free")) {
                "仅允许免费模型（当前配置：${BuildConfig.OPENROUTER_MODEL}）"
            }
            return BuildConfig.OPENROUTER_MODEL
        }
    }

    /**
     * 生成日报表
     * @param allRecords 全量客户列表（含未拍摄，photoRecord 为 null 表示未拍摄），传入 AI 上下文
     * @param visitedRecords 已拍摄客户列表（photoCount > 0），用于统计与详情
     * @param batchMarkedCount 同类型标记数
     * @param visitNote 走访备注
     * @param specialLog AI 生成页面用户填写的额外补充说明（special_log）
     * @return 解析后的 ReportRecord 列表
     */
    suspend fun generateReport(
        allRecords: List<Pair<CustomerRow, PhotoRecord?>>,
        visitedRecords: List<Pair<CustomerRow, PhotoRecord>>,
        batchMarkedCount: Int,
        visitNote: String?,
        specialLog: String? = null
    ): List<ReportRecord> = withContext(Dispatchers.IO) {
        if (BuildConfig.OPENROUTER_API_KEY_OBF.isBlank()) {
            Timber.e("OpenRouter API key is blank")
            throw IllegalStateException("OpenRouter API key 未配置")
        }
        requireFreeModel()

        val request = ReportPromptBuilder.buildChatRequest(
            allRecords = allRecords,
            visitedRecords = visitedRecords,
            batchMarkedCount = batchMarkedCount,
            visitNote = visitNote,
            specialLog = specialLog
        )

        Timber.i("Sending report generation request: ${allRecords.size} total customers (${visitedRecords.size} visited)")

        val response = try {
            deepSeekApi.chatCompletions(request)
        } catch (e: Exception) {
            Timber.e(e, "OpenRouter API call failed")
            throw e
        }

        val choice = response.choices.firstOrNull()
        val content = choice?.message?.content
            ?: throw IllegalStateException("AI 返回内容为空")
        val finishReason = choice.finishReason
        Timber.i("AI response length: ${content.length}, finishReason: $finishReason")

        // 截断容错：若 AI 响应因 max_tokens 被截断（finishReason == "length"），尝试补全 JSON 数组后重新解析
        val contentToParse = if (finishReason == "length") {
            Timber.w("Response truncated (finishReason=length), attempting JSON array completion")
            completeJsonArray(content)
        } else {
            content
        }

        parseAiResponse(contentToParse, finishReason)
    }

    /**
     * 截断容错：当 AI 响应因 max_tokens 被截断时，尝试补全 JSON 数组。
     * 策略：在 content 末尾找到最后一个 `}` 后添加 `]` 闭合数组。
     * 若末尾已包含 `]` 或找不到 `}`，则原样返回交由 [parseAiResponse] 处理。
     */
    private fun completeJsonArray(content: String): String {
        val lastBrace = content.lastIndexOf('}')
        if (lastBrace < 0) return content
        val afterBrace = content.substring(lastBrace + 1).trimStart()
        // 末尾已闭合 `]`，无需补全
        if (afterBrace.startsWith("]")) return content
        return content.substring(0, lastBrace + 1) + "]"
    }

    /**
     * 解析 AI 响应（3 级 fallback）：先直接 JSON 解析，失败后正则提取 [.*]，
     * 再退化为逐行提取 {...}。
     *
     * @param finishReason 上游 finishReason（解析失败时写入异常 message 便于诊断，如 "length" 表示截断）
     */
    private fun parseAiResponse(content: String, finishReason: String? = null): List<ReportRecord> {
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

        // Level 2: 正则提取 AI 响应中的 JSON 数组
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

        // Level 3: 逐行提取 AI 响应中的单个 JSON 对象
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
        // 异常 message 包含 finishReason，便于诊断 AI 响应问题（如 "length" 表示被 max_tokens 截断）
        val suffix = finishReason?.takeIf { it.isNotBlank() }?.let { "（finishReason=$it）" } ?: ""
        throw IllegalStateException("AI 响应解析失败$suffix")
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
            model = requireFreeModel(),
            messages = messages,
            temperature = 0.5,
            maxTokens = 8192,
            stream = false,
            provider = ProviderLimit(allowFallbacks = false)
        )

        val response = deepSeekApi.chatCompletions(request)
        response.choices.firstOrNull()?.message?.content
            ?: throw IllegalStateException("AI 返回内容为空")
    }
}
