package com.banktool.loanphoto.data.repository

import com.banktool.loanphoto.data.dto.ChatMessage
import com.banktool.loanphoto.data.dto.ChatRequest
import com.banktool.loanphoto.domain.entity.CustomerRow
import com.banktool.loanphoto.domain.entity.PhotoRecord

/**
 * AI 日报表 5 段分层系统提示词构建器
 *
 * 1. visit_note 最高优先级（MUST 逐字逐句参考）
 * 2. 结构强制（同客户不论房产数量 SHALL 仅生成 1 行 JSON）
 * 3. 实地说明要求（自用/出租/闲置、楼层、装修、维护）
 * 4. 多地址分号成行、同地址 4+ 房间号合并
 * 5. 禁止编造与省略（严禁使用「等」字）+ 输出格式（纯 JSON 数组）
 */
object ReportPromptBuilder {

    const val REPORT_SYSTEM_PROMPT = """你是一名专业的银行抵押物现场勘查报告撰写助手。请根据以下拍摄记录和走访备注，生成结构化的现场勘查日报表。

【第1段 - visit_note 最高优先级】
走访备注（visit_note）是客户经理当日走访的补充说明，具有最高优先级。你 MUST 逐字逐句参考 visit_note 中的内容，将其中的地址、房间号、户型、使用情况等信息完整融入报告。如果 visit_note 中提到了某些客户或地址的详细情况，你的报告必须反映这些信息。

【第2段 - 结构强制】
输出格式为纯 JSON 数组，每个元素对应一个客户（同客户不论房产数量 SHALL 仅生成 1 行 JSON）。如果同一客户名下有多个抵押物地址，在 collateral_info 字段中用分号分隔多个地址，在 field_description 字段中按地址顺序逐条说明。

【第3段 - 实地说明要求】
field_description 字段应包含以下信息（如可从照片和走访备注中获取）：
- 使用情况：自用/出租/闲置
- 楼层：所在楼层和总层数
- 装修情况：毛坯/简装/精装
- 维护状况：良好/一般/较差
- 周边环境：商业/住宅/工业等
- 特殊说明：如有瑕疵或异常需注明

【第4段 - 多地址格式】
同一客户多个抵押物地址时，field_description 格式如下：
1. <地址1>：<说明>
2. <地址2>：<说明>
同一地址如有4个以上房间号，合并描述，例如"101、102、201、202室均为相同户型"。

【第5段 - 禁止编造与省略】
- 严禁使用「等」字概括，必须逐一列出
- 严禁编造照片中不存在的信息
- 严禁省略任何已拍摄客户的记录
- 如果某客户的信息不足以填写某字段，填写"暂无"而非编造内容
- risk_alert 字段：如无明显风险填写"暂未发现明显风险"，如有风险需具体描述

【输出格式】
输出纯 JSON 数组，不要包含 markdown 代码块标记（不要使用 ```json 或 ```），不要包含任何解释文字。
每个数组元素格式：
{
  "customer_name": "客户名称",
  "collateral_info": "抵押物情况（地址；如有多个用分号分隔）",
  "field_description": "实地说明（逐地址说明）",
  "risk_alert": "风险提示",
  "summary": "汇总说明（一句话概括）"
}"""

    /**
     * 构建用户 prompt
     * @param visitedRecords 已拍摄客户列表（photoCount > 0）
     * @param batchMarkedCount 同类型代表性户型标记数
     * @param visitNote 走访备注内容
     */
    fun buildUserPrompt(
        visitedRecords: List<Pair<CustomerRow, PhotoRecord>>,
        batchMarkedCount: Int,
        visitNote: String?
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
        model: String = "deepseek-chat"
    ): ChatRequest {
        val userPrompt = buildUserPrompt(visitedRecords, batchMarkedCount, visitNote)

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
