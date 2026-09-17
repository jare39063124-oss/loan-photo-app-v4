package com.banktool.loanphoto.data.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * progress.json 中单个 progressKey 对应的条目。
 *
 * JSON 形态：
 * ```json
 * "<progress_key>": {
 *   "photos": ["/abs/path/photo1.jpg"],
 *   "types": {"远景": true, "近景": true},
 *   "photo_types": ["远景", "近景", "远景"],
 *   "timestamp": "2026-07-07 10:00:00",
 *   "remark": "客户备注"
 * }
 * ```
 *
 * - `types`: 已拍摄分类的集合（presence 形式）
 * - `photo_types`: 与 `photos` 平行的类型列表（同索引对应同照片的类型），
 *   用于精确计算各分类张数。字段缺失时默认空列表，回退到 types presence。
 *
 * 顶层文件还包含两个特殊键：
 * - `_row_remarks`: Map<String, String>（key=行号字符串）
 * - `batch_marked`: Map<String, Boolean>（key=行号字符串）
 *
 * 由于 progressKey 是动态键，整个文件按 Map<String, Any?> 读写，
 * 本 DTO 仅描述单个条目的结构，由 [com.banktool.loanphoto.data.repository.ProgressRepositoryImpl]
 * 负责与 Map 的互转。
 */
@JsonClass(generateAdapter = false)
data class ProgressEntryDto(
    @Json(name = "photos") val photos: List<String> = emptyList(),
    @Json(name = "types") val types: Map<String, Boolean> = emptyMap(),
    @Json(name = "photo_types") val photoTypes: List<String> = emptyList(),
    @Json(name = "timestamp") val timestamp: String = "",
    @Json(name = "remark") val remark: String = "",
)

/**
 * progress.json 顶层文件结构（逻辑视图，非直接序列化）。
 *
 * [entries] 的 key 为 progressKey（16 位 hex）。
 * 实际读写时由 Repository 拆分为扁平 Map：
 * { "_row_remarks": {...}, "batch_marked": {...}, "<key>": ProgressEntryDto, ... }
 */
data class ProgressFileDto(
    val rowRemarks: Map<String, String> = emptyMap(),
    val batchMarked: Map<String, Boolean> = emptyMap(),
    val entries: Map<String, ProgressEntryDto> = emptyMap(),
)
