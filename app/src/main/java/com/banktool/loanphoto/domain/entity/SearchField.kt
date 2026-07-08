package com.banktool.loanphoto.domain.entity

/**
 * 搜索字段枚举。
 *
 * 用于客户清单实时过滤，指定在哪个字段上匹配 [searchQuery]。
 *
 * - [SERIAL]: 序号列 (A)
 * - [BORROWER]: 客户名列 (B)
 * - [ADDR]: 地址（合并匹配 C 列「地址概」+ D 列「地址详」）
 * - [REMARK]: 备注（查 progress.json 的 `_row_remarks[行号]`）
 * - [UNVISITED]: 未走访（状态过滤，photoCount==0，忽略 query）
 *
 * [isStatusFilter] 为 true 的字段是状态过滤（不依赖关键词输入），
 * UI 层应隐藏搜索输入框或显示提示文字。
 */
enum class SearchField(val displayName: String, val isStatusFilter: Boolean = false) {
    SERIAL("序号"),
    BORROWER("客户名"),
    ADDR("地址"),
    REMARK("备注"),
    UNVISITED("未走访", isStatusFilter = true),
}
