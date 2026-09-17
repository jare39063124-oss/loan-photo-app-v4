package com.banktool.loanphoto.domain.entity

/**
 * 相机会话持久化（11 字段）。
 *
 * 当应用因拍照被系统杀死后，重启需依据此记录恢复：
 * - 用户当时在哪一行、选了什么拍照类型、是否多选等。
 */
data class CameraSession(
    val cameraLaunched: Boolean,
    val requestCode: Int,
    val rowIndex: Int,
    val photoType: String,
    val multiSelectKeys: List<String>,
    val multiSelectRows: List<Int>,
    val key: String,
    val photoPath: String?,
    val mediaUri: String?,
    val photoLaunchTime: Double,
    val excelUriMd5: String,
    val timestamp: String,
)
