package com.banktool.loanphoto.domain.repository

/**
 * 特殊日志仓库（按 Excel 文件维度保存）。
 *
 * 与 [VisitNoteRepository] 同构，用于持久化用户在 AI 日报表页面输入的
 * 「补充说明/特殊日志」内容，按 excelUriMd5 区分。
 *
 * - 文件: `special_logs/<md5>.txt`（纯文本，原子写）
 * - 进入报告页时通过 [getSpecialLog] 回显
 * - 用户点击「保存」或「生成日报表」时通过 [saveSpecialLog] 持久化
 */
interface SpecialLogRepository {

    /** 读取某 Excel 文件的特殊日志，无则返回空串。 */
    suspend fun getSpecialLog(excelUriMd5: String): String

    /** 保存某 Excel 文件的特殊日志（覆盖式原子写）。 */
    suspend fun saveSpecialLog(excelUriMd5: String, content: String)
}
