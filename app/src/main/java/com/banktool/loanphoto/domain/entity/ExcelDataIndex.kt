package com.banktool.loanphoto.domain.entity

/**
 * Excel 数据索引：记录某 Excel 文件下所有 progressKey。
 *
 * 用于切换/重新打开同一份 Excel 时，快速恢复进度关联。
 *
 * @param excelUriMd5 Excel 文件 URI 的 md5[:16]
 * @param progressKeys 该 Excel 解析出的 progressKey 列表
 */
data class ExcelDataIndex(
    val excelUriMd5: String,
    val progressKeys: List<String>,
)
