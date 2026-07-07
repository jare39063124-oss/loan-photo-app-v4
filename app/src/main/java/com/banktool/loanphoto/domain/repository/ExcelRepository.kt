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
}
