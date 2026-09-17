package com.banktool.loanphoto.domain.entity

/**
 * 可配置的拍照类型（用户可自定义名称和数量）。
 *
 * - [id] 稳定标识（内置类型用 "distant"/"close" 等；用户新增类型用 "custom_<timestamp>"），不可变，
 *   用于配置持久化与编辑/删除定位
 * - [displayName] 可自定义显示名（如 "远景"），同时用作文件名片段与 progress.json
 *   中 `types` Map 的 key
 *
 * 旧 progress.json 中以 displayName 字符串作为 key 的数据仍可正常读取（新代码同样以 displayName 作为 key）。
 */
data class PhotoTypeConfig(
    val id: String,
    val displayName: String,
)

/**
 * 拍照类型枚举（5 种默认类型）。
 *
 * 保留作为默认值来源与稳定 ID 来源；用户自定义类型使用 [PhotoTypeConfig]。
 *
 * - [id] 稳定标识（不可变，用于配置持久化）
 * - [displayName] 用于 UI 展示（中文短名），同时用作文件名片段与 progress.json
 *   中 `types` Map 的 key，types 集合的 key 与该字符串保持一致
 * - [description] 用于辅助提示文案
 */
enum class PhotoType(val id: String, val displayName: String, val description: String) {
    DISTANT("distant", "远景", "抵押物外观全景"),
    CLOSE("close", "近景", "抵押物正面近景"),
    INTERIOR("interior", "内部", "室内布局装修"),
    DEFECT("defect", "瑕疵", "损坏或瑕疵部位"),
    OTHER("other", "其他", "其他需要记录的画面"),
    ;

    companion object {
        /** 默认配置列表（5 种内置类型，id 与 displayName 对齐枚举）。 */
        val DEFAULT_CONFIGS: List<PhotoTypeConfig> = entries.map { PhotoTypeConfig(it.id, it.displayName) }

        /** 按 displayName 反查枚举（默认值 OTHER 兜底）。 */
        fun fromDisplayName(name: String?): PhotoType =
            entries.firstOrNull { it.displayName == name } ?: OTHER

        /** 获取所有 displayName 列表（用于持久化与 UI Chip）。 */
        val displayNames: List<String> = entries.map { it.displayName }
    }
}
