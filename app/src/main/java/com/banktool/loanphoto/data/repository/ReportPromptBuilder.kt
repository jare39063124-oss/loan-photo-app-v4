package com.banktool.loanphoto.data.repository

import com.banktool.loanphoto.BuildConfig
import com.banktool.loanphoto.data.dto.ChatMessage
import com.banktool.loanphoto.data.dto.ChatRequest
import com.banktool.loanphoto.data.dto.ProviderLimit
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
- collateral_info：抵押物/抵债资产清单，逐条列出地址和面积（如「1. 沈阳市XX区XX路XX号，面积XX㎡；2. 沈阳市YY区YY路YY号，面积YY㎡」）。同一借款人存在多个抵押物时必须全部列出。
- field_description：现状描述，必须逐条参考每个抵押物条目的备注(remark)原文，将备注内容融入描述。走访备注(visit_note)/特殊日志(special_log)中实际提到的使用情况、户型、现状也应参考。示例：备注「土地为国有出让教育用地，原为学生宿舍目前闲置，共计7层」→ 现状描述须包含「土地为国有出让教育用地，原为学生宿舍目前闲置，共计7层」表述。多个抵押物时按地址逐条描述。
- risk_alert：风险提示，无明显风险填「暂未发现明显风险」，有风险需具体描述
- summary：基础内容固定为「经实地查勘，抵押物暂未发现明显异常，建议关注企业经营情况，维护我行资金安全」；有条目备注/风险/特殊日志额外信息时追加其后，无则只填这一句

【信息参考优先级】
visit_note（走访备注）最高优先级，必须逐字逐句参考；remark（条目备注）逐条阅读，户型/使用情况融入 field_description，地址融入 collateral_info；special_log 同等重要。未提及的信息不输出，禁止「装修未知」「使用情况未知」等模板化表述，信息不足填「暂无」。

【多房间号合并】
同一地址 4 个以上房间号合并描述（如「101、102、201、202室均为相同户型」）。

【输入说明】
以下为全量客户清单。请仅为已拍摄照片的客户生成日报条目，未拍摄的客户不需要生成。

【禁止事项】
- 严禁使用「等」字概括，必须逐一列出
- 严禁编造照片中不存在的信息
- 严禁省略任何已拍摄客户记录

【输出格式】
纯 JSON 数组，不要 markdown 代码块标记，不要解释文字：
{
  "customer_name": "客户名称",
  "collateral_info": "抵押物/抵债资产清单，逐条列出地址和面积（如：1. 沈阳市XX区XX路XX号，面积XX㎡；2. 沈阳市YY区YY路YY号，面积YY㎡）",
  "field_description": "现状描述（参考备注，如：抵押物作为仓库使用，户型三室两厅）",
  "risk_alert": "风险提示",
  "summary": "汇总说明"
}"""

    /**
     * 构建用户 prompt
     *
     * 全量客户清单传入 AI 上下文：已拍摄客户标注 `[已拍摄]` 并附带照片/备注详情，
     * 未拍摄客户仅标注 `[未拍摄]` 并给出基础信息，AI 仅应为已拍摄客户生成日报条目。
     *
     * @param allRecords 全量客户列表（photoRecord 为 null 表示未拍摄）
     * @param visitedRecords 已拍摄客户列表（photoCount > 0），用于统计与详情展示
     * @param batchMarkedCount 同类型代表性户型标记数
     * @param visitNote 走访备注内容
     * @param specialLog AI 生成页面用户填写的额外补充说明（special_log）
     */
    fun buildUserPrompt(
        allRecords: List<Pair<CustomerRow, PhotoRecord?>>,
        visitedRecords: List<Pair<CustomerRow, PhotoRecord>>,
        batchMarkedCount: Int,
        visitNote: String?,
        specialLog: String? = null
    ): String {
        val sb = StringBuilder()

        sb.appendLine("请根据以下客户清单生成现场勘查日报表（仅为标注[已拍摄]的客户生成，[未拍摄]客户不需要生成）。")
        sb.appendLine()

        sb.appendLine("【客户清单】")
        sb.appendLine("全量客户共 ${allRecords.size} 个，其中已拍摄 ${visitedRecords.size} 个，同类型代表性户型标记 ${batchMarkedCount} 户。")
        sb.appendLine()

        // 按 borrower 分组（同客户多抵押物合并）；使用全量客户，未拍摄客户也纳入上下文
        val groupedByBorrower = allRecords.groupBy { it.first.borrower }
        sb.appendLine("【客户详情】")
        // 客户数据无独立面积字段：面积信息可能存在于 remark 原文或 visit_note 中，
        // 提示 AI 如遇面积信息须一并写入 collateral_info
        sb.appendLine("（注：如以下备注或走访信息中包含面积信息，请一并写入 collateral_info 的对应条目。）")
        for ((borrower, records) in groupedByBorrower) {
            sb.appendLine("客户：$borrower")
            for ((row, photoRecord) in records) {
                // 标注是否已拍摄，让 AI 区分：仅 [已拍摄] 客户生成日报条目
                val visited = photoRecord != null && photoRecord.photos.isNotEmpty()
                sb.appendLine("  ${if (visited) "[已拍摄]" else "[未拍摄]"} 地址：${row.addrGeneral} ${row.addrDetail}")
                sb.appendLine("  性质：${row.propertyType}")
                if (photoRecord != null && visited) {
                    sb.appendLine("  照片数：${photoRecord.photos.size}")
                    sb.appendLine("  照片类型：${photoRecord.types.joinToString("、")}")
                    // Excel 原始备注(remark)原文：field_description 须逐条参考，必须输出
                    if (row.remark.isNotEmpty()) {
                        sb.appendLine("  备注(remark原文)：${row.remark}")
                    }
                    // App 内编辑的行级备注（progress.json _row_remarks），与 Excel 原文可能不同
                    if (photoRecord.remark.isNotEmpty() && photoRecord.remark != row.remark) {
                        sb.appendLine("  备注(走访编辑)：${photoRecord.remark}")
                    }
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

        sb.appendLine("请生成纯 JSON 数组格式的日报表，每个已拍摄客户1条记录，未拍摄客户不要生成。")

        return sb.toString()
    }

    /**
     * 构建 ChatRequest
     *
     * @param allRecords 全量客户列表（含未拍摄），传入 AI 上下文
     * @param visitedRecords 已拍摄客户列表，用于统计与详情
     */
    fun buildChatRequest(
        allRecords: List<Pair<CustomerRow, PhotoRecord?>>,
        visitedRecords: List<Pair<CustomerRow, PhotoRecord>>,
        batchMarkedCount: Int,
        visitNote: String?,
        specialLog: String? = null,
        model: String = "nvidia/nemotron-3-ultra-550b-a55b:free"
    ): ChatRequest {
        val userPrompt = buildUserPrompt(allRecords, visitedRecords, batchMarkedCount, visitNote, specialLog)

        return ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = REPORT_SYSTEM_PROMPT),
                ChatMessage(role = "user", content = userPrompt)
            ),
            temperature = 0.3,
            // 提升 maxTokens 至 16384，避免长客户列表导致 JSON 响应被截断（finishReason=length）
            maxTokens = 16384,
            stream = false
        )
    }
}
