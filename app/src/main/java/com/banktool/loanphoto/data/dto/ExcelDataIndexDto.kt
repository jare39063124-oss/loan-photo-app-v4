package com.banktool.loanphoto.data.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * excel_data_index.json 的 DTO。
 *
 * JSON 形态：
 * ```json
 * { "<excel_uri_md5>": ["<progress_key_1>", "<progress_key_2>"] }
 * ```
 *
 * 由于 excelUriMd5 是动态键，实际读写按 Map<String, List<String>>，
 * 本 DTO 仅在需要时承载单个 Excel 的索引数据。
 */
@JsonClass(generateAdapter = false)
data class ExcelDataIndexDto(
    @Json(name = "excel_uri_md5") val excelUriMd5: String = "",
    @Json(name = "progress_keys") val progressKeys: List<String> = emptyList(),
)
