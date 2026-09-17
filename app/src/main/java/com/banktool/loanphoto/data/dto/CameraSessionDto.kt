package com.banktool.loanphoto.data.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * camera_session.json 的 DTO。
 *
 * 字段名通过 @Json 映射到 snake_case；Kotlin 侧保留 camelCase。
 */
@JsonClass(generateAdapter = false)
data class CameraSessionDto(
    @Json(name = "camera_launched") val cameraLaunched: Boolean = false,
    @Json(name = "request_code") val requestCode: Int = 0,
    @Json(name = "row_index") val rowIndex: Int = 0,
    @Json(name = "photo_type") val photoType: String = "",
    @Json(name = "multi_select_keys") val multiSelectKeys: List<String> = emptyList(),
    @Json(name = "multi_select_rows") val multiSelectRows: List<Int> = emptyList(),
    @Json(name = "key") val key: String = "",
    @Json(name = "photo_path") val photoPath: String? = null,
    @Json(name = "media_uri") val mediaUri: String? = null,
    @Json(name = "photo_launch_time") val photoLaunchTime: Double = 0.0,
    @Json(name = "excel_uri_md5") val excelUriMd5: String = "",
    @Json(name = "timestamp") val timestamp: String = "",
)
