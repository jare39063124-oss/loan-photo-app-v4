package com.banktool.loanphoto.domain.entity

/** Excel 数据索引：[excelUriMd5]=Excel 文件 URI 的 md5[:16]，[progressKeys]=该 Excel 解析出的全部 progressKey，用于重开文件时恢复进度关联。 */
data class ExcelDataIndex(
    val excelUriMd5: String,
    val progressKeys: List<String>,
)
