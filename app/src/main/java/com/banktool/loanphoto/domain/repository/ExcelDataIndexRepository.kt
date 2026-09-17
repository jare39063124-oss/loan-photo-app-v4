package com.banktool.loanphoto.domain.repository

import com.banktool.loanphoto.data.datasource.ColumnField

/** Excel 数据索引仓库（excel_data_index.json）：记录每个 Excel 文件下所有 progressKey 与列映射，便于重开文件时恢复进度关联、清理失效键、按确认过的列语义解析。 */
interface ExcelDataIndexRepository {

    /** 获取某 Excel 文件下所有 progressKey。 */
    suspend fun getProgressKeys(excelUriMd5: String): List<String>

    /** 给某 Excel 文件追加一个 progressKey（去重）。 */
    suspend fun addProgressKey(excelUriMd5: String, key: String)

    /** 删除某 Excel 文件的全部索引（不影响 progress.json 内的进度）。 */
    suspend fun removeExcelData(excelUriMd5: String)

    /** 一次读取整个 excel_data_index.json，返回全部 Excel 文件的 progressKey 映射（key=excelUriMd5）。 */
    suspend fun getAllEntries(): Map<String, List<String>>

    /** 获取某 Excel 文件的列映射（按物理列索引）；null 表示尚未设置。 */
    suspend fun getColumnMap(excelUriMd5: String): List<ColumnField>?

    /**
     * 保存某 Excel 文件的列映射（覆盖写，保留已有 progressKeys）。
     *
     * @param hasHeader 该文件首行是否为表头（决定后续注入解析的起始行）
     */
    suspend fun saveColumnMap(excelUriMd5: String, map: List<ColumnField>, hasHeader: Boolean = false)

    /** 某 Excel 文件已保存的列映射是否对应带表头行（首行需跳过）。 */
    suspend fun hasHeaderRow(excelUriMd5: String): Boolean
}
