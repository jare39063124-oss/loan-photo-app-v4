package com.banktool.loanphoto.data.repository

import android.net.Uri
import com.banktool.loanphoto.data.datasource.ExcelDataSource
import com.banktool.loanphoto.data.datasource.ExcelWriter
import com.banktool.loanphoto.domain.entity.CustomerRow
import com.banktool.loanphoto.domain.repository.ExcelRepository
import javax.inject.Inject

/**
 * Excel 仓库实现：读取委托给 [ExcelDataSource]，写入委托给 [ExcelWriter]。
 */
class ExcelRepositoryImpl @Inject constructor(
    private val dataSource: ExcelDataSource,
    private val excelWriter: ExcelWriter,
) : ExcelRepository {

    override suspend fun readExcel(uri: String): List<CustomerRow> =
        dataSource.readExcel(uri)

    override suspend fun appendRow(uri: String, serial: String, borrower: String, address: String): Int =
        excelWriter.appendRowToExcel(Uri.parse(uri), serial, borrower, address)
}
