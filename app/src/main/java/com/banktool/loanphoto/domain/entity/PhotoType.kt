package com.banktool.loanphoto.domain.entity

/**
 * 拍照类型枚举（5 种，对应 Kivy v3.22.24 默认类型集）。
 *
 * - [displayName] 用于 UI 展示（中文短名）
 * - [description] 用于辅助提示文案
 *
 * types 集合的 key 与 [displayName] 字符串保持一致，与 progress.json
 * 中 `types` Map 的 key 完全对齐。
 */
enum class PhotoType(val displayName: String, val description: String) {
    DISTANT("远景", "抵押物外观全景"),
    CLOSE("近景", "抵押物正面近景"),
    INTERIOR("内部", "室内布局装修"),
    DEFECT("瑕疵", "损坏或瑕疵部位"),
    OTHER("其他", "其他需要记录的画面"),
    ;

    companion object {
        /** 按 displayName 反查枚举（默认值 OTHER 兜底）。 */
        fun fromDisplayName(name: String?): PhotoType =
            entries.firstOrNull { it.displayName == name } ?: OTHER

        /** 获取所有 displayName 列表（用于持久化与 UI Chip）。 */
        val displayNames: List<String> = entries.map { it.displayName }
    }
}
