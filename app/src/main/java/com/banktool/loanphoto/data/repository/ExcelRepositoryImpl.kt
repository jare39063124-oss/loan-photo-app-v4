package com.banktool.loanphoto.data.repository

import com.banktool.loanphoto.data.datasource.ExcelDataSource
import com.banktool.loanphoto.domain.entity.CustomerRow
import com.banktool.loanphoto.domain.repository.ExcelRepository
import javax.inject.Inject

/**
 * Excel 仓库实现：委托给 [ExcelDataSource]。
 */
class ExcelRepositoryImpl @Inject constructor(
    private val dataSource: ExcelDataSource,
) : ExcelRepository {

    override suspend fun readExcel(uri: String): List<CustomerRow> =
        dataSource.readExcel(uri)
}
