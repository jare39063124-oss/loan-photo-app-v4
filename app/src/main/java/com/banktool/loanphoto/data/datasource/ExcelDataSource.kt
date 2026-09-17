package com.banktool.loanphoto.data.datasource

import android.content.Context
import android.net.Uri
import com.banktool.loanphoto.domain.entity.CustomerRow
import com.banktool.loanphoto.util.ProgressKeyUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import timber.log.Timber
import javax.inject.Inject

/**
 * Excel 解析结果：除客户行列表外，携带表头识别信息与列映射，供上层
 * 决定是否弹出列映射确认弹窗、以及条目编辑时按映射对称写回。
 *
 * @param rows 客户行列表（已跳过全空行）
 * @param headerMatched parseHeader 是否命中表头关键词（注入 [ExcelDataSource.readExcel] 的
 *   columnMap 时恒为 false，因表头探测被跳过）
 * @param rawFirstRowSamples 首行 A-F 每列文本（供列映射对话框展示样例）
 * @param resolvedColumnMap 本次解析实际生效的列映射（按物理列索引）
 */
data class ExcelReadResult(
    val rows: List<CustomerRow>,
    val headerMatched: Boolean,
    val rawFirstRowSamples: List<String>,
    val resolvedColumnMap: List<ColumnField>,
)

/**
 * Excel 读取数据源。
 *
 * 使用 Apache POI [XSSFWorkbook] 读取 .xlsx：
 * - 通过 ContentResolver openInputStream(uri) 获取输入流
 * - 默认读取 6 列：A=序号 B=客户名 C=地址概 D=地址详 E=性质 F=备注
 * - 第 1 行作表头（识别列索引），无表头时默认 0-5 列；
 *   也可注入 [ColumnField] 列映射跳过表头探测直接解析
 * - 跳过 序号/客户名/地址概/地址详/性质 全空行
 * - progressKey = md5(borrower + "|" + addrGeneral + addrDetail)[:16]
 * - rowIndex 为 Excel 物理行号（0-based，含表头）
 */
class ExcelDataSource @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {

    /**
     * 读取 Excel 文件并解析为 [ExcelReadResult]。
     *
     * @param uri Excel 文件的 content URI 字符串
     * @param columnMap 列映射（按物理列索引，见 [ColumnField]）：
     *   非空时跳过表头探测，直接按 columnMap 解析（IGNORE 列取空串）；
     *   null 时走现有 parseHeader 表头识别逻辑
     * @param skipFirstRow 仅在 columnMap 非空时生效：true 表示首行为表头需跳过
     *   （startRow=1），false 从首行开始读数据（startRow=0）
     */
    suspend fun readExcel(
        uri: String,
        columnMap: List<ColumnField>? = null,
        skipFirstRow: Boolean = false,
    ): ExcelReadResult = withContext(Dispatchers.IO) {
        try {
            var headerMatched = false
            var samples: List<String> = emptyList()
            var resolved: List<ColumnField> = ColumnField.DEFAULT
            val rows = mutableListOf<CustomerRow>()
            val parsed = context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                XSSFWorkbook(input).use { workbook ->
                    val sheet = workbook.getSheetAt(0)
                        ?: error("Excel 文件无工作表")
                    samples = readFirstRowSamples(sheet)
                    val startRow: Int
                    val fields: List<ColumnField>
                    if (columnMap != null) {
                        // 注入列映射：跳过表头探测，按给定列语义直接解析
                        fields = columnMap
                        resolved = columnMap
                        startRow = if (skipFirstRow) 1 else 0
                        Timber.i(
                            "ExcelDataSource: 使用注入列映射 %s, startRow=%d, uri=%s",
                            columnMap, startRow, uri,
                        )
                    } else {
                        val (sr, stringMap) = parseHeader(sheet.getRow(0))
                        startRow = sr
                        headerMatched = sr == 1
                        resolved = resolveColumnFieldMap(stringMap)
                        fields = resolved
                        Timber.i(
                            "ExcelDataSource: 表头识别 matched=%s, stringMap=%s, uri=%s",
                            headerMatched, stringMap, uri,
                        )
                    }
                    val lastRow = sheet.lastRowNum
                    for (i in startRow..lastRow) {
                        val row = sheet.getRow(i) ?: continue
                        val serial = fieldText(row, fields, ColumnField.SERIAL)
                        val borrower = fieldText(row, fields, ColumnField.BORROWER)
                        val addrGeneral = fieldText(row, fields, ColumnField.ADDR_GENERAL)
                        val addrDetail = fieldText(row, fields, ColumnField.ADDR_DETAIL)
                        val propertyType = fieldText(row, fields, ColumnField.PROPERTY_TYPE)
                        val remark = fieldText(row, fields, ColumnField.REMARK)
                        // 跳过 序号/客户名/地址概/地址详/性质 全空行
                        if (serial.isBlank() && borrower.isBlank() &&
                            addrGeneral.isBlank() && addrDetail.isBlank() &&
                            propertyType.isBlank()
                        ) {
                            continue
                        }
                        rows.add(
                            CustomerRow(
                                rowIndex = i,
                                serial = serial,
                                borrower = borrower,
                                addrGeneral = addrGeneral,
                                addrDetail = addrDetail,
                                propertyType = propertyType,
                                remark = remark,
                                progressKey = ProgressKeyUtil.progressKey(
                                    borrower, addrGeneral + addrDetail,
                                ),
                            ),
                        )
                    }
                }
            }
            if (parsed == null) {
                Timber.w("ExcelDataSource: openInputStream 返回 null, uri=%s", uri)
            }
            ExcelReadResult(
                rows = rows,
                headerMatched = headerMatched,
                rawFirstRowSamples = samples,
                resolvedColumnMap = resolved,
            )
        } catch (t: Throwable) {
            // 捕获 POI 抛出的 Error（NoClassDefFoundError / ExceptionInInitializerError 等），
            // 显示 UI 错误提示而非闪退。
            Timber.e(t, "ExcelDataSource: 读取 Excel 失败, uri=%s", uri)
            throw IllegalStateException("加载 Excel 失败：${t.message ?: t.javaClass.simpleName}", t)
        }
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
     * 读取首行 A-F 每列文本（供列映射对话框展示样例；无首行返回 6 个空串）。
     */
    private fun readFirstRowSamples(sheet: Sheet): List<String> {
        val row = sheet.getRow(0) ?: return List(6) { "" }
        return (0 until 6).map { idx -> getCellString(row, idx) }
    }

    /**
     * 把 parseHeader 的 key→列号结果转为按物理列索引的列映射。
     *
     * 未被关键词命中的字段保留默认列位（与旧逻辑 `colMap[key] ?: 默认列` 一致），
     * 关键词命中的列覆盖默认值；列表长度至少 6（命中列超出 F 时按最大列号扩展）。
     */
    private fun resolveColumnFieldMap(stringMap: Map<String, Int>): List<ColumnField> {
        val size = maxOf(6, (stringMap.values.maxOrNull() ?: 5) + 1)
        val result = MutableList(size) { idx ->
            ColumnField.DEFAULT.getOrElse(idx) { ColumnField.IGNORE }
        }
        for ((key, idx) in stringMap) {
            val field = KEY_TO_FIELD[key]
            if (field != null && idx in result.indices) {
                result[idx] = field
            }
        }
        return result
    }

    /**
     * 按列映射取字段文本：无对应列（未指定或为 IGNORE）返回空串；
     * 同一字段映射到多列时取首次出现的列（先到优先）。
     */
    private fun fieldText(row: Row, fields: List<ColumnField>, field: ColumnField): String {
        val idx = fields.indexOf(field)
        return if (idx < 0) "" else getCellString(row, idx)
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

    private companion object {
        /** parseHeader 的字段 key → 列语义。 */
        val KEY_TO_FIELD = mapOf(
            "serial" to ColumnField.SERIAL,
            "borrower" to ColumnField.BORROWER,
            "addrGeneral" to ColumnField.ADDR_GENERAL,
            "addrDetail" to ColumnField.ADDR_DETAIL,
            "propertyType" to ColumnField.PROPERTY_TYPE,
            "remark" to ColumnField.REMARK,
        )
    }
}
