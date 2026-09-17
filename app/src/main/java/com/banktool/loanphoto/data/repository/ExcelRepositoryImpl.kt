package com.banktool.loanphoto.data.repository

import android.net.Uri
import com.banktool.loanphoto.data.datasource.ColumnField
import com.banktool.loanphoto.data.datasource.ExcelDataSource
import com.banktool.loanphoto.data.datasource.ExcelReadResult
import com.banktool.loanphoto.data.datasource.ExcelWriter
import com.banktool.loanphoto.domain.repository.ExcelRepository
import javax.inject.Inject

/**
 * Excel 仓库实现：读取委托给 [ExcelDataSource]，写入委托给 [ExcelWriter]。
 */
class ExcelRepositoryImpl @Inject constructor(
    private val dataSource: ExcelDataSource,
    private val excelWriter: ExcelWriter,
) : ExcelRepository {

    override suspend fun readExcel(
        uri: String,
        columnMap: List<ColumnField>?,
        skipFirstRow: Boolean,
    ): ExcelReadResult = dataSource.readExcel(uri, columnMap, skipFirstRow)

    override suspend fun appendRow(
        uri: String,
        serial: String,
        borrower: String,
        address: String,
        serialColumn: Int,
        borrowerColumn: Int,
        addressColumn: Int,
    ): Int = excelWriter.appendRowToExcel(
        Uri.parse(uri),
        serial,
        borrower,
        address,
        serialColumn,
        borrowerColumn,
        addressColumn,
    )
}
