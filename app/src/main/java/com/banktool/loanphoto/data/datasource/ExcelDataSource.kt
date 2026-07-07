package com.banktool.loanphoto.data.datasource

import android.content.Context
import android.net.Uri
import com.banktool.loanphoto.domain.entity.CustomerRow
import com.banktool.loanphoto.util.ProgressKeyUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import timber.log.Timber
import javax.inject.Inject

/**
 * Excel 读取数据源。
 *
 * 使用 Apache POI [XSSFWorkbook] 读取 .xlsx：
 * - 通过 ContentResolver openInputStream(uri) 获取输入流
 * - 读取 6 列：A=序号 B=客户名 C=地址概 D=地址详 E=性质 F=备注
 * - 第 1 行作表头（识别列索引），无表头时默认 0-5 列
 * - 跳过 A-E 全空行
 * - progressKey = md5(borrower + "|" + addrGeneral + addrDetail)[:16]
 * - rowIndex 从 0 开始
 */
class ExcelDataSource @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {

    /**
     * 读取 Excel 文件并解析为客户行列表。
     *
     * @param uri Excel 文件的 content URI 字符串
     */
    suspend fun readExcel(uri: String): List<CustomerRow> = withContext(Dispatchers.IO) {
        val rows = mutableListOf<CustomerRow>()
        val parsed = context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
            XSSFWorkbook(input).use { workbook ->
                val sheet = workbook.getSheetAt(0)
                    ?: error("Excel 文件无工作表")
                val headerRow = sheet.getRow(0)
                val (startRow, colMap) = parseHeader(headerRow)
                val lastRow = sheet.lastRowNum
                for (i in startRow until lastRow + 1) {
                    val row = sheet.getRow(i) ?: continue
                    val serial = getCellString(row, colMap["serial"] ?: 0)
                    val borrower = getCellString(row, colMap["borrower"] ?: 1)
                    val addrGeneral = getCellString(row, colMap["addrGeneral"] ?: 2)
                    val addrDetail = getCellString(row, colMap["addrDetail"] ?: 3)
                    val propertyType = getCellString(row, colMap["propertyType"] ?: 4)
                    val remark = getCellString(row, colMap["remark"] ?: 5)
                    // 跳过 A-E 全空行
                    if (serial.isBlank() && borrower.isBlank() &&
                        addrGeneral.isBlank() && addrDetail.isBlank() &&
                        propertyType.isBlank()
                    ) {
                        continue
                    }
                    val progressKey = ProgressKeyUtil.progressKey(
                        borrower, addrGeneral + addrDetail,
                    )
                    rows.add(
                        CustomerRow(
                            rowIndex = i,
                            serial = serial,
                            borrower = borrower,
                            addrGeneral = addrGeneral,
                            addrDetail = addrDetail,
                            propertyType = propertyType,
                            remark = remark,
                            progressKey = progressKey,
                        ),
                    )
                }
            }
        }
        if (parsed == null) {
            Timber.w("ExcelDataSource: openInputStream 返回 null, uri=%s", uri)
        }
        rows
    }

    /**
     * 解析表头行：识别各列索引。
     *
     * 若第 1 行像表头（命中任一关键词）则从第 2 行开始读数据（返回 startRow=1），
     * 否则从第 1 行开始（startRow=0），列索引按默认 0-5。
     *
     * 关键词匹配优先级：addrDetail 在 addrGeneral 之前，避免 "地址详" 被通用 "地址" 抢占。
     */
    private fun parseHeader(headerRow: Row?): Pair<Int, Map<String, Int>> {
        if (headerRow == null) return Pair(0, emptyMap())
        // 顺序敏感：先匹配更具体的关键词
        val keywords: List<Pair<String, List<String>>> = listOf(
            "serial" to listOf("序号", "编号"),
            "borrower" to listOf("客户名", "客户", "姓名", "borrower"),
            "addrDetail" to listOf("地址详", "详细地址", "地址明细", "详址"),
            "addrGeneral" to listOf("地址概", "地址概要", "地址"),
            "propertyType" to listOf("性质", "property"),
            "remark" to listOf("备注", "remark", "说明"),
        )
        val colMap = mutableMapOf<String, Int>()
        var matched = false
        val lastCell = headerRow.lastCellNum.toInt()
        for (cellIndex in 0 until lastCell) {
            val text = getCellString(headerRow, cellIndex).trim()
            if (text.isBlank()) continue
            for ((key, kws) in keywords) {
                if (key in colMap) continue
                if (kws.any { text.contains(it) }) {
                    colMap[key] = cellIndex
                    matched = true
                    break
                }
            }
        }
        val startRow = if (matched) 1 else 0
        return Pair(startRow, colMap)
    }

    /**
     * 读取单元格为字符串，兼容字符串/数字/布尔/公式/空值。
     */
    private fun getCellString(row: Row, colIndex: Int): String {
        val cell = row.getCell(colIndex) ?: return ""
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue.trim()
            CellType.NUMERIC -> formatNumeric(cell.numericCellValue)
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.FORMULA -> formatFormula(cell)
            CellType.BLANK -> ""
            else -> cell.toString().trim()
        }.trim()
    }

    /** 数字格式化：整数去 .0，避免科学计数法。 */
    private fun formatNumeric(value: Double): String {
        val asLong = value.toLong()
        return if (value == asLong.toDouble()) asLong.toString() else value.toString()
    }

    /** 公式单元格：按缓存结果类型取值，避免触发 FormulaEvaluator。 */
    private fun formatFormula(cell: org.apache.poi.ss.usermodel.Cell): String {
        return when (cell.cachedFormulaResultType) {
            CellType.STRING -> cell.stringCellValue.trim()
            CellType.NUMERIC -> formatNumeric(cell.numericCellValue)
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            else -> cell.toString().trim()
        }.trim()
    }
}
