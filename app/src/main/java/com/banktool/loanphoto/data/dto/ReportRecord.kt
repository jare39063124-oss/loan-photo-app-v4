package com.banktool.loanphoto.data.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * AI 生成的日报表单条记录
 * 对应 report_template.xlsx 的 A-E 列
 */
@JsonClass(generateAdapter = true)
data class ReportRecord(
    @Json(name = "customer_name") val customerName: String = "",
    @Json(name = "collateral_info") val collateralInfo: String = "",
    @Json(name = "field_description") val fieldDescription: String = "",
    @Json(name = "risk_alert") val riskAlert: String = "",
    @Json(name = "summary") val summary: String = ""
)

/**
 * AI 响应解析后的报表数据
 */
@JsonClass(generateAdapter = true)
data class ReportData(
    val records: List<ReportRecord> = emptyList(),
    @Json(name = "batch_marked_count") val batchMarkedCount: Int = 0,
    val quality: String = "ok"
)
