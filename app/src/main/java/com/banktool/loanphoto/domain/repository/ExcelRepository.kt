package com.banktool.loanphoto.domain.repository

import com.banktool.loanphoto.data.datasource.ColumnField
import com.banktool.loanphoto.data.datasource.ExcelReadResult

/** Excel 文件读取仓库。 */
interface ExcelRepository {
    /**
     * 读取 .xlsx 文件并解析为 [ExcelReadResult]。
     *
     * @param uri Excel 文件的 content URI 字符串
     * @param columnMap 列映射（按物理列索引）；非空时跳过表头探测直接解析，null 走表头识别
     * @param skipFirstRow 仅在 columnMap 非空时生效：true 表示首行为表头需跳过
     * @return 解析结果（rows 已跳过全空行；携带表头识别信息、首行样例与生效列映射）
     */
    suspend fun readExcel(
        uri: String,
        columnMap: List<ColumnField>? = null,
        skipFirstRow: Boolean = false,
    ): ExcelReadResult

    /**
     * 在 Excel 末尾追加一行查勘条目。
     *
     * @param uri Excel 文件 content URI 字符串
     * @param serial 序号
     * @param borrower 借款人
     * @param address 地址
     * @param serialColumn 序号列号（0-indexed，默认 A=0）
     * @param borrowerColumn 借款人列号（默认 B=1）
     * @param addressColumn 地址列号（默认 C=2）
     * @return 新行的 rowIndex（0-based 物理行号）；-1 表示失败
     */
    suspend fun appendRow(
        uri: String,
        serial: String,
        borrower: String,
        address: String,
        serialColumn: Int = 0,
        borrowerColumn: Int = 1,
        addressColumn: Int = 2,
    ): Int
}
