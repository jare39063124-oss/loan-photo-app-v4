package com.banktool.loanphoto.data.camera

/**
 * 照片质量等级。
 * @param displayName 显示名称
 * @param maxEdge 最长边像素上限（等比缩放，不放大）
 */
enum class PhotoQuality(val displayName: String, val maxEdge: Int) {
    HIGH("高清 1080P", 1920),
    MEDIUM("标清 720P", 1280),
    LOW("低清 480P", 640);
}
