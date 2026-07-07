package com.banktool.loanphoto.domain.entity

/**
 * 搜索字段枚举。
 *
 * 用于客户清单实时过滤，指定在哪个字段上匹配 [searchQuery]。
 *
 * - [SERIAL]: 序号列 (A)
 * - [BORROWER]: 客户名列 (B)
 * - [ADDR_GENERAL]: 地址概列 (C)
 * - [ADDR_DETAIL]: 地址详列 (D)
 * - [REMARK]: 备注（查 progress.json 的 `_row_remarks[行号]`）
 */
enum class SearchField(val displayName: String) {
    SERIAL("序号"),
    BORROWER("客户名"),
    ADDR_GENERAL("地址概"),
    ADDR_DETAIL("地址详"),
    REMARK("备注"),
}
