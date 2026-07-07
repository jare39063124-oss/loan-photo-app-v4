package com.banktool.loanphoto.domain.repository

/**
 * 走访备注仓库（按 Excel 文件维度保存）。
 */
interface VisitNoteRepository {

    /** 读取某 Excel 文件的走访备注，无则返回空串。 */
    suspend fun getVisitNote(excelUriMd5: String): String

    /** 保存某 Excel 文件的走访备注（覆盖式原子写）。 */
    suspend fun saveVisitNote(excelUriMd5: String, content: String)
}
