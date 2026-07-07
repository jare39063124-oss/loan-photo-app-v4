package com.banktool.loanphoto.domain.entity

/**
 * 一个 progressKey 对应的拍照记录。
 *
 * @param photos 照片绝对路径列表
 * @param types 拍照类型集合（远景/近景/内部/瑕疵/其他）
 * @param photoTypes 与 [photos] 平行的类型列表（同索引对应同照片的类型），用于精确计算各分类张数；
 *                   旧数据（v4.0.0）无此字段时为空列表，调用方应回退到 [types] presence。
 * @param timestamp 最后更新时间 "yyyy-MM-dd HH:mm:ss"
 * @param remark 该客户备注
 */
data class PhotoRecord(
    val progressKey: String,
    val photos: List<String>,
    val types: Set<String>,
    val photoTypes: List<String> = emptyList(),
    val timestamp: String,
    val remark: String,
)
