package com.banktool.loanphoto.data.repository

import com.banktool.loanphoto.data.dto.ChatMessage
import com.banktool.loanphoto.data.dto.ChatRequest
import com.banktool.loanphoto.domain.entity.CustomerRow
import com.banktool.loanphoto.domain.entity.PhotoRecord

/**
 * AI 日报表系统提示词构建器
 *
 * 精简版 4 段结构：
 * 1. 字段定义（collateral_info 抵押物清单 / field_description 现状描述 / risk_alert / summary）
 * 2. 信息参考优先级（visit_note 最高 / remark 逐条 / special_log）+ 禁止「未知」模板化
 * 3. 多房间号合并（4+ 合并描述）
 * 4. 禁止事项（「等」字/编造/省略）+ 输出格式（纯 JSON 数组）
 */
object ReportPromptBuilder {

    const val REPORT_SYSTEM_PROMPT = """你是银行抵押物现场勘查报告撰写助手。根据拍摄记录和走访备注，生成结构化现场勘查日报表。

【字段定义】
输出纯 JSON 数组，每个客户 1 行（同客户多抵押物合并为 1 行）：
- collateral_info：抵押物清单，逐条列出地址（如「1. 沈阳市XX区XX路XX号；2. 沈阳市YY区YY路YY号」）
- field_description：现状描述，参考条目备注(remark)/走访备注(visit_note)/特殊日志(special_log)中实际提到的使用情况、户型、现状（如「抵押物作为仓库使用，户型三室两厅」）
- risk_alert：风险提示，无明显风险填「暂未发现明显风险」，有风险需具体描述
- summary：基础内容固定为「经实地查勘，抵押物暂未发现明显异常，建议关注企业经营情况，维护我行资金安全」；有条目备注/风险/特殊日志额外信息时追加其后，无则只填这一句

【信息参考优先级】
visit_note（走访备注）最高优先级，必须逐字逐句参考；remark（条目备注）逐条阅读，户型/使用情况融入 field_description，地址融入 collateral_info；special_log 同等重要。未提及的信息不输出，禁止「装修未知」「使用情况未知」等模板化表述，信息不足填「暂无」。

【多房间号合并】
同一地址 4 个以上房间号合并描述（如「101、102、201、202室均为相同户型」）。

【禁止事项】
- 严禁使用「等」字概括，必须逐一列出
- 严禁编造照片中不存在的信息
- 严禁省略任何已拍摄客户记录

【输出格式】
纯 JSON 数组，不要 markdown 代码块标记，不要解释文字：
{
  "customer_name": "客户名称",
  "collateral_info": "抵押物清单，逐条列出地址（如：1. 沈阳市XX区XX路XX号；2. 沈阳市YY区YY路YY号）",
  "field_description": "现状描述（参考备注，如：抵押物作为仓库使用，户型三室两厅）",
  "risk_alert": "风险提示",
  "summary": "汇总说明"
}"""

    /**
     * 构建用户 prompt
     * @param visitedRecords 已拍摄客户列表（photoCount > 0）
     * @param batchMarkedCount 同类型代表性户型标记数
     * @param visitNote 走访备注内容
     * @param specialLog AI 生成页面用户填写的额外补充说明（special_log）
     */
    fun buildUserPrompt(
        visitedRecords: List<Pair<CustomerRow, PhotoRecord>>,
        batchMarkedCount: Int,
        visitNote: String?,
        specialLog: String? = null
    ): String {
        val sb = StringBuilder()

        sb.appendLine("请根据以下拍摄记录生成现场勘查日报表。")
        sb.appendLine()

        sb.appendLine("【拍摄记录】")
        sb.appendLine("已拍摄客户共 ${visitedRecords.size} 个，其中同类型代表性户型标记 ${batchMarkedCount} 户。")
        sb.appendLine()

        // 按 borrower 分组（同客户多抵押物合并）
        val groupedByBorrower = visitedRecords.groupBy { it.first.borrower }
        sb.appendLine("【客户详情】")
        for ((borrower, records) in groupedByBorrower) {
            sb.appendLine("客户：$borrower")
            for ((row, photoRecord) in records) {
                sb.appendLine("  地址：${row.addrGeneral} ${row.addrDetail}")
                sb.appendLine("  性质：${row.propertyType}")
                sb.appendLine("  照片数：${photoRecord.photos.size}")
                sb.appendLine("  照片类型：${photoRecord.types.joinToString("、")}")
                if (photoRecord.remark.isNotEmpty()) {
                    sb.appendLine("  备注：${photoRecord.remark}")
                }
            }
            sb.appendLine()
        }

        // visit_note 注入
        if (!visitNote.isNullOrBlank()) {
            sb.appendLine("【走访备注（visit_note）- 最高优先级，必须逐字逐句参考】")
            sb.appendLine(visitNote)
            sb.appendLine()
        }

        // special_log 注入（AI 生成页面用户填写的额外说明）
        if (!specialLog.isNullOrBlank()) {
            sb.appendLine("【AI生成页面补充说明（special_log）- 重要参考】")
            sb.appendLine(specialLog)
            sb.appendLine()
        }

        sb.appendLine("请生成纯 JSON 数组格式的日报表，每个客户1条记录。")

        return sb.toString()
    }

    /**
     * 构建 ChatRequest
     */
    fun buildChatRequest(
        visitedRecords: List<Pair<CustomerRow, PhotoRecord>>,
        batchMarkedCount: Int,
        visitNote: String?,
        specialLog: String? = null,
        model: String = "deepseek-chat"
    ): ChatRequest {
        val userPrompt = buildUserPrompt(visitedRecords, batchMarkedCount, visitNote, specialLog)

        return ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = REPORT_SYSTEM_PROMPT),
                ChatMessage(role = "user", content = userPrompt)
            ),
            temperature = 0.3,
            maxTokens = 4096,
            stream = false
        )
    }
}
