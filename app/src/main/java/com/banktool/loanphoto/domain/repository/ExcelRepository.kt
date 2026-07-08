package com.banktool.loanphoto.domain.repository

import com.banktool.loanphoto.domain.entity.CustomerRow

/**
 * Excel 文件读取仓库。
 */
interface ExcelRepository {
    /**
     * 读取 .xlsx 文件并解析为客户行列表。
     *
     * @param uri Excel 文件的 content URI 字符串
     * @return 客户行列表（已跳过表头与全空行）
     */
    suspend fun readExcel(uri: String): List<CustomerRow>

    /**
     * 在 Excel 末尾追加一行查勘条目。
     *
     * @param uri Excel 文件 content URI 字符串
     * @param serial 序号（A 列）
     * @param borrower 借款人（B 列）
     * @param address 地址（写入 C 列「地址概」，D/E/F 留空）
     * @return 新行的 rowIndex（0-based 物理行号）；-1 表示失败
     */
    suspend fun appendRow(uri: String, serial: String, borrower: String, address: String): Int
}
