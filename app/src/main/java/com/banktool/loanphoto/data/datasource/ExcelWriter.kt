package com.banktool.loanphoto.data.datasource

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Excel 写入数据源（备注回写 / 追加行 / 整行更新）。
 *
 * 使用 Apache POI [XSSFWorkbook] 打开 .xlsx：
 * - 通过 ContentResolver openInputStream 读取原文件
 * - 第 1 行作为表头（与 [ExcelDataSource] 对齐）
 * - [writeRemarksToExcel]：对每个有备注的行，写入备注映射列（默认 F 列）
 * - [appendRowToExcel]：在末尾追加查勘条目（默认 A/B/C 列）
 * - [updateRowToExcel]：按列号 Map 更新指定行的任意列（编辑条目整行回写）
 *
 * @param uri Excel 文件 content URI
 * @param remarks key=行号（0-based，含表头时为 Excel 物理行号），value=备注内容
 */
@Singleton
class ExcelWriter @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * 将备注回写到 Excel 指定列（默认 F 列）。
     *
     * @param remarkColumn 备注列号（0-indexed），按当前文件列映射传入（无映射默认 F=5）
     * @return true 写入成功；false 失败
     */
    suspend fun writeRemarksToExcel(
        uri: Uri,
        remarks: Map<Int, String>,
        remarkColumn: Int = COL_REMARK,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (remarks.isEmpty()) {
                Timber.w("ExcelWriter: remarks 为空，跳过写入")
                return@withContext false
            }
            try {
                val resolver = context.contentResolver

                val workbook = resolver.openInputStream(uri)?.use { input ->
                    XSSFWorkbook(input)
                } ?: run {
                    Timber.w("ExcelWriter: openInputStream 返回 null, uri=%s", uri)
                    return@withContext false
                }

                workbook.use { wb ->
                    val sheet = wb.getSheetAt(0) ?: run {
                        Timber.w("ExcelWriter: 工作簿无工作表")
                        return@withContext false
                    }

                    val startRow = detectDataStartRow(sheet)

                    var written = 0
                    for ((rowIndex, content) in remarks) {
                        if (content.isBlank()) continue
                        val row = sheet.getRow(rowIndex) ?: sheet.createRow(rowIndex)
                        val cell = row.getCell(remarkColumn) ?: row.createCell(remarkColumn)
                        cell.setCellValue(content)
                        written++
                    }
                    Timber.i(
                        "ExcelWriter: 写入 %d 条备注到第 %d 列, startRow=%d",
                        written, remarkColumn, startRow,
                    )

                    val bytes = ByteArrayOutputStream().use { baos ->
                        wb.write(baos)
                        baos.toByteArray()
                    }
                    resolver.openOutputStream(uri, "wt")?.use { output ->
                        output.write(bytes)
                        output.flush()
                    } ?: run {
                        Timber.w("ExcelWriter: openOutputStream 返回 null")
                        return@withContext false
                    }
                }
                true
            } catch (t: Throwable) {
                // 捕获 Error（如 NoClassDefFoundError）避免闪退，降级为写入失败提示
                Timber.e(t, "ExcelWriter: 写入备注失败, uri=%s", uri)
                false
            }
        }

    /**
     * 在 Excel 末尾追加一行查勘条目（不覆盖原有内容）。
     *
     * 默认列映射：A=serial, B=borrower, C=address，D/E/F 留空；
     * 也可按当前文件列映射传入目标列号。
     *
     * @param uri Excel 文件 content URI
     * @param serial 序号
     * @param borrower 借款人
     * @param address 地址
     * @param serialColumn 序号列号（0-indexed，默认 A=0）
     * @param borrowerColumn 借款人列号（默认 B=1）
     * @param addressColumn 地址列号（默认 C=2）
     * @return 新行的物理行号（0-based，sheet 行号）；-1 表示失败
     */
    suspend fun appendRowToExcel(
        uri: Uri,
        serial: String,
        borrower: String,
        address: String,
        serialColumn: Int = 0,
        borrowerColumn: Int = 1,
        addressColumn: Int = 2,
    ): Int =
        withContext(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                val workbook = resolver.openInputStream(uri)?.use { input ->
                    XSSFWorkbook(input)
                } ?: run {
                    Timber.w("ExcelWriter appendRow: openInputStream 返回 null, uri=%s", uri)
                    return@withContext -1
                }

                workbook.use { wb ->
                    val sheet = wb.getSheetAt(0) ?: run {
                        Timber.w("ExcelWriter appendRow: 工作簿无工作表")
                        return@withContext -1
                    }

                    val newRowNum = sheet.lastRowNum + 1
                    val newRow = sheet.createRow(newRowNum)
                    newRow.createCell(serialColumn).setCellValue(serial) // 序号
                    newRow.createCell(borrowerColumn).setCellValue(borrower) // 借款人
                    newRow.createCell(addressColumn).setCellValue(address) // 地址概
                    // 其余列不创建（留空）

                    Timber.i(
                        "ExcelWriter appendRow: 追加行 #%d, serial@%d=%s, borrower@%d=%s",
                        newRowNum, serialColumn, serial, borrowerColumn, borrower,
                    )

                    // 原子写
                    val bytes = ByteArrayOutputStream().use { baos ->
                        wb.write(baos)
                        baos.toByteArray()
                    }
                    resolver.openOutputStream(uri, "wt")?.use { output ->
                        output.write(bytes)
                        output.flush()
                    } ?: run {
                        Timber.w("ExcelWriter appendRow: openOutputStream 返回 null")
                        return@withContext -1
                    }

                    newRowNum
                }
            } catch (t: Throwable) {
                Timber.e(t, "ExcelWriter appendRow: 追加失败, uri=%s", uri)
                -1
            }
        }

    /**
     * 更新指定行的指定列（条目全量编辑回写，A-F 列）。
     *
     * 与 [writeRemarksToExcel] 共用同一套读取/原子写流程，但支持写任意列：
     * 列映射 0=A 序号, 1=B 客户名, 2=C 地址概, 3=D 地址详, 4=E 性质, 5=F 备注。
     *
     * @param uri Excel 文件 content URI
     * @param rowIndex Excel 物理行号（0-based，含表头，与 CustomerRow.rowIndex 对齐）
     * @param values key=列号（0-indexed），value=写入内容（空串也会写入以覆盖旧值）
     * @return true 写入成功；false 失败
     */
    suspend fun updateRowToExcel(uri: Uri, rowIndex: Int, values: Map<Int, String>): Boolean =
        withContext(Dispatchers.IO) {
            if (values.isEmpty()) {
                Timber.w("ExcelWriter updateRow: values 为空，跳过写入")
                return@withContext false
            }
            try {
                val resolver = context.contentResolver

                val workbook = resolver.openInputStream(uri)?.use { input ->
                    XSSFWorkbook(input)
                } ?: run {
                    Timber.w("ExcelWriter updateRow: openInputStream 返回 null, uri=%s", uri)
                    return@withContext false
                }

                workbook.use { wb ->
                    val sheet = wb.getSheetAt(0) ?: run {
                        Timber.w("ExcelWriter updateRow: 工作簿无工作表")
                        return@withContext false
                    }

                    val row = sheet.getRow(rowIndex) ?: sheet.createRow(rowIndex)
                    for ((col, value) in values) {
                        val cell = row.getCell(col) ?: row.createCell(col)
                        cell.setCellValue(value)
                    }
                    Timber.i("ExcelWriter updateRow: 更新行 #%d, %d 列", rowIndex, values.size)

                    val bytes = ByteArrayOutputStream().use { baos ->
                        wb.write(baos)
                        baos.toByteArray()
                    }
                    resolver.openOutputStream(uri, "wt")?.use { output ->
                        output.write(bytes)
                        output.flush()
                    } ?: run {
                        Timber.w("ExcelWriter updateRow: openOutputStream 返回 null")
                        return@withContext false
                    }
                }
                true
            } catch (t: Throwable) {
                // 捕获 Error（如 NoClassDefFoundError）避免闪退，降级为写入失败提示
                Timber.e(t, "ExcelWriter updateRow: 更新行失败, uri=%s", uri)
                false
            }
        }

    /**
     * 检测数据起始行：若第 1 行像表头则返回 1，否则返回 0。
     * 与 [ExcelDataSource.parseHeader] 逻辑对齐，但仅判断是否为表头。
     */
    private fun detectDataStartRow(sheet: Sheet): Int {
        val headerRow: Row = sheet.getRow(0) ?: return 0
        val keywords = listOf("序号", "编号", "客户", "姓名", "地址", "性质", "备注", "remark")
        val lastCell = headerRow.lastCellNum.toInt()
        for (cellIndex in 0 until lastCell) {
            val cell = headerRow.getCell(cellIndex) ?: continue
            val text = cell.toString().trim()
            if (text.isBlank()) continue
            if (keywords.any { text.contains(it) }) return 1
        }
        return 0
    }

    private companion object {
        // F 列（备注）0-indexed
        const val COL_REMARK = 5
    }
}
