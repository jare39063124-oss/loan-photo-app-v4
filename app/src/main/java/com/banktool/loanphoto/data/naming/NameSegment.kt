package com.banktool.loanphoto.data.naming

/**
 * 照片文件名可用的命名段类型。
 *
 * - [DATE] 拍摄日期（yyyyMMdd）
 * - [BORROWER] 客户名
 * - [SERIAL] 序号（取自 [com.banktool.loanphoto.domain.entity.CustomerRow.serial]）
 * - [ADDRESS] 地址 + 时间（addrGeneral + addrDetail 拼接）
 * - [NONE] 空值（该段省略）
 *
 * [displayName] 用于设置页下拉选项展示。
 */
enum class NameSegment(val displayName: String) {
    DATE("拍摄日期"),
    BORROWER("客户名"),
    SERIAL("序号"),
    ADDRESS("地址+时间"),
    NONE("空值"),
    ;

    companion object {
        /** 按枚举 name 反查（无法匹配时回退 [NONE]）。 */
        fun fromName(name: String?): NameSegment =
            entries.firstOrNull { it.name == name } ?: NONE
    }
}
