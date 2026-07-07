package com.banktool.loanphoto.domain.repository

/**
 * Excel 数据索引仓库（excel_data_index.json）。
 *
 * 记录每个 Excel 文件下所有 progressKey，便于重新打开同一份 Excel 时
 * 快速恢复进度关联、清理失效键。
 */
interface ExcelDataIndexRepository {

    /** 获取某 Excel 文件下所有 progressKey。 */
    suspend fun getProgressKeys(excelUriMd5: String): List<String>

    /** 给某 Excel 文件追加一个 progressKey（去重）。 */
    suspend fun addProgressKey(excelUriMd5: String, key: String)

    /** 删除某 Excel 文件的全部索引（不影响 progress.json 内的进度）。 */
    suspend fun removeExcelData(excelUriMd5: String)
}
