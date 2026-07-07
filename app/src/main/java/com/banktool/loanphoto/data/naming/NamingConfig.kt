package com.banktool.loanphoto.data.naming

/**
 * 照片命名规则配置：4 段可自定义命名段。
 *
 * 默认全部为 [NameSegment.NONE]（即回退为 `IMG_<timestamp>.jpg`）。
 * 非 NONE 段按顺序用 `-` 连接，末尾固定追加 `${photoType.displayName}-${seq:02d}.jpg`。
 *
 * @param segment1 第 1 段
 * @param segment2 第 2 段
 * @param segment3 第 3 段
 * @param segment4 第 4 段
 */
data class NamingConfig(
    val segment1: NameSegment = NameSegment.NONE,
    val segment2: NameSegment = NameSegment.NONE,
    val segment3: NameSegment = NameSegment.NONE,
    val segment4: NameSegment = NameSegment.NONE,
) {
    /** 全部段均为 NONE（即未配置任何命名段）。 */
    val isAllNone: Boolean
        get() = listOf(segment1, segment2, segment3, segment4).all { it == NameSegment.NONE }

    /** 按顺序返回 4 段配置。 */
    val segments: List<NameSegment>
        get() = listOf(segment1, segment2, segment3, segment4)
}
