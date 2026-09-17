package com.banktool.loanphoto.data.datasource

/**
 * Excel 列语义字段。
 *
 * 列映射（colMap）表示为 [List]<[ColumnField]>，按物理列索引（0..5 对应 A-F）：
 * 每个位置声明该物理列承载的业务含义；[IGNORE] 表示该列不参与解析与写回。
 *
 * 解析与写回约定：
 * - 取某字段对应列时取其在映射中**首次出现**的列（重复指定时先到优先）
 * - 字段在映射中不存在（或为 IGNORE）时，解析取空串、写回跳过该字段
 */
enum class ColumnField(val displayName: String) {
    SERIAL("序号"), BORROWER("客户名"), ADDR_GENERAL("地址(概)"),
    ADDR_DETAIL("地址(详)"), PROPERTY_TYPE("性质"), REMARK("备注"), IGNORE("忽略");

    companion object {
        /** 默认列序 A-F：序号 / 客户名 / 地址概 / 地址详 / 性质 / 备注。 */
        val DEFAULT: List<ColumnField> = listOf(
            SERIAL, BORROWER, ADDR_GENERAL, ADDR_DETAIL, PROPERTY_TYPE, REMARK,
        )
    }
}
