package com.banktool.loanphoto.domain.entity

/**
 * Excel 一行客户数据。列对应：A 序号(serial)、B 客户名(borrower)，
 * C 地址概(addrGeneral)、D 地址详(addrDetail)、E 性质(propertyType)、F 备注(remark)。
 *
 * @param rowIndex 0-based 行号（含表头时为 Excel 物理行号减 1）
 * @param progressKey md5(borrower + "|" + addrGeneral + addrDetail)[:16]，作为唯一拍照进度键
 */
data class CustomerRow(
    val rowIndex: Int,
    val serial: String,
    val borrower: String,
    val addrGeneral: String,
    val addrDetail: String,
    val propertyType: String,
    val remark: String,
    val progressKey: String,
)
