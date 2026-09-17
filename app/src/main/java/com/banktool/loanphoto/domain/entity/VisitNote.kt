package com.banktool.loanphoto.domain.entity

/** 走访备注（按 Excel 文件维度保存）：[excelUriMd5] 为存储键，[content] 为备注文本。 */
data class VisitNote(
    val excelUriMd5: String,
    val content: String,
)
