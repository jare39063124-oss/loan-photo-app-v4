package com.banktool.loanphoto.domain.entity

/**
 * 走访备注（按 Excel 文件维度保存）。
 *
 * @param excelUriMd5 Excel 文件 URI 的 md5[:16]，作为该文件走访备注的存储键
 * @param content 走访备注文本
 */
data class VisitNote(
    val excelUriMd5: String,
    val content: String,
)
