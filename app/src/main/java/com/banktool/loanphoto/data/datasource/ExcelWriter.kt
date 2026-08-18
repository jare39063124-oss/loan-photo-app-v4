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
 * Excel 写入数据源（备注回写 F 列）。
 *
 * 使用 Apache POI [XSSFWorkbook] 打开 .xlsx，把行级备注写入 F 列（第 5 列，0-indexed）。
 *
 * - 通过 ContentResolver openInputStream 读取原文件
 * - 第 1 行作为表头（与 [ExcelDataSource] 对齐）
 * - 对每个有备注的行，写入 F 列 = 备注内容
 * - 原子写: 先写到内存 ByteArrayOutputStream，再通过 ContentResolver openOutputStream 覆盖原 URI
 *
 * @param uri Excel 文件 content URI
 * @param remarks key=行号（0-based，含表头时为 Excel 物理行号），value=备注内容
 */
@Singleton
class ExcelWriter @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * 将备注回写到 Excel F 列。
     *
     * 步骤：
     * 1. 通过 ContentResolver 读取原 .xlsx
     * 2. 读取第 1 行作为表头（识别 startRow）
     * 3. 对每个 (rowIndex, remark) 写入 F 列
     * 4. 通过 ContentResolver openOutputStream 原子覆盖
     *
     * @return true 写入成功；false 失败
     */
    suspend fun writeRemarksToExcel(uri: Uri, remarks: Map<Int, String>): Boolean =
        withContext(Dispatchers.IO) {
            if (remarks.isEmpty()) {
                Timber.w("ExcelWriter: remarks 为空，跳过写入")
                return@withContext false
            }
            try {
                val resolver = context.contentResolver

                // 1. 读取原工作簿
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

                    // 2. 识别表头起始行（与 ExcelDataSource.parseHeader 对齐）
                    val startRow = detectDataStartRow(sheet)

                    // 3. 写入备注到 F 列
                    var written = 0
                    for ((rowIndex, content) in remarks) {
                        if (content.isBlank()) continue
                        // rowIndex 是 Excel 物理行号（含表头），直接用作 sheet 行号
                        val row = sheet.getRow(rowIndex) ?: sheet.createRow(rowIndex)
                        val cell = row.getCell(COL_REMARK) ?: row.createCell(COL_REMARK)
                        cell.setCellValue(content)
                        written++
                    }
                    Timber.i("ExcelWriter: 写入 %d 条备注到 F 列, startRow=%d", written, startRow)

                    // 4. 原子写：先序列化到内存，再覆盖 URI
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
     * 列映射：A=serial, B=borrower, C=address, D/E/F 留空。
     *
     * @param uri Excel 文件 content URI
     * @param serial 序号
     * @param borrower 借款人
     * @param address 地址（写入 C 列）
     * @return 新行的物理行号（0-based，sheet 行号）；-1 表示失败
     */
    suspend fun appendRowToExcel(uri: Uri, serial: String, borrower: String, address: String): Int =
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

                    // 在末尾追加一行（getLastRowNum 返回最后有数据的行号，0-based；新行 = lastRowNum + 1）
                    // 注意：若 sheet 为空（lastRowNum=-1），新行=0；若有表头+数据，新行=lastRowNum+1
                    val newRowNum = sheet.lastRowNum + 1
                    val newRow = sheet.createRow(newRowNum)
                    newRow.createCell(0).setCellValue(serial) // A 列 序号
                    newRow.createCell(1).setCellValue(borrower) // B 列 借款人
                    newRow.createCell(2).setCellValue(address) // C 列 地址概
                    // D/E/F 列不创建（留空）

                    Timber.i("ExcelWriter appendRow: 追加行 #%d, serial=%s, borrower=%s", newRowNum, serial, borrower)

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

                    // 原子写：先序列化到内存，再覆盖 URI
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
