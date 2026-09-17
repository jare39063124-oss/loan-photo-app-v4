package com.banktool.loanphoto.data.naming

/**
 * 照片命名规则配置：4 段可自定义命名段。
 *
 * 默认 `[DATE, BORROWER, ADDRESS, NONE]`，即未配置时自动生成
 * `日期-客户名-地址-类型-序号.jpg`（v4.0.3 起的默认行为）。
 * 非 NONE 段按顺序用 `-` 连接，末尾固定追加 `${photoType.displayName}-${seq:02d}.jpg`。
 *
 * @param segment1 第 1 段（默认 [NameSegment.DATE]）
 * @param segment2 第 2 段（默认 [NameSegment.BORROWER]）
 * @param segment3 第 3 段（默认 [NameSegment.ADDRESS]）
 * @param segment4 第 4 段（默认 [NameSegment.NONE]）
 * @param customText1 段 1 为 [NameSegment.CUSTOM] 时的自定义文本（其它段类型忽略，默认空）
 * @param customText2 段 2 为 [NameSegment.CUSTOM] 时的自定义文本（默认空）
 * @param customText3 段 3 为 [NameSegment.CUSTOM] 时的自定义文本（默认空）
 * @param customText4 段 4 为 [NameSegment.CUSTOM] 时的自定义文本（默认空）
 */
data class NamingConfig(
    val segment1: NameSegment = NameSegment.DATE,
    val segment2: NameSegment = NameSegment.BORROWER,
    val segment3: NameSegment = NameSegment.ADDRESS,
    val segment4: NameSegment = NameSegment.NONE,
    val customText1: String = "",
    val customText2: String = "",
    val customText3: String = "",
    val customText4: String = "",
) {
    /** 全部段均为 NONE（即未配置任何命名段）。 */
    val isAllNone: Boolean
        get() = listOf(segment1, segment2, segment3, segment4).all { it == NameSegment.NONE }

    /** 按顺序返回 4 段配置。 */
    val segments: List<NameSegment>
        get() = listOf(segment1, segment2, segment3, segment4)

    /** 按顺序返回 4 段自定义文本（与 [segments] 索引一一对应）。 */
    val customTexts: List<String>
        get() = listOf(customText1, customText2, customText3, customText4)
}
