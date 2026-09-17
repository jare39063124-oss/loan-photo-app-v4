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
 * - [UNVISITED]: 未走访（先按状态过滤 photoCount==0，再按文本匹配全量字段）
 * - [VISITED]: 已拍摄（先按状态过滤 photoCount>0，再按文本匹配全量字段）
 *
 * VISITED/UNVISITED 也支持文本输入：先按状态过滤，
 * 再按搜索文本匹配全量内容（序号 / 客户名 / 地址 / 备注，任一命中）。
 * [isStatusFilter] 当前恒为 false，UI 层对所有字段均显示搜索输入框。
 */
enum class SearchField(val displayName: String, val isStatusFilter: Boolean = false) {
    SERIAL("序号"),
    BORROWER("客户名"),
    ADDR("地址"),
    REMARK("备注"),
    UNVISITED("未走访"),
    VISITED("已拍摄"),
}
