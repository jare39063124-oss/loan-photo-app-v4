package com.banktool.loanphoto.data.datasource

import android.content.Context
import com.banktool.loanphoto.data.dto.ReportRecord
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 日报表模板填充器
 *
 * 读取 assets/report_template.xlsx，按模板列头填充 A-F 列，生成最终报表。
 *
 * 模板布局（report_template.xlsx）：
 *   第1行  : "附件2."（合并 A1:F1）
 *   第2行  : "抵押物/抵债资产现场勘查日报表"（合并 A2:F2）
 *   第3行  : 列头 A=序号 B=日期 C=勘查业务贷款人名称 D=抵押物/抵债资产具体情况
 *            E=现状描述 F=备注（是否存在发生风险的可能）
 *   第4-19行: 空白数据区（供本类填充）
 *   第20行起: 底部说明（填报人姓名/线路完成进度/评估公司确认/风险情景注释）
 */
@Singleton
class ReportTemplateFiller @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ReportTemplateFiller"
        private const val TEMPLATE_ASSET = "report_template.xlsx"

        // 列索引 (0-based)，与模板第3行列头严格对齐
        private const val COL_SERIAL = 0      // A: 序号
        private const val COL_DATE = 1         // B: 日期
        private const val COL_CUSTOMER = 2     // C: 勘查业务贷款人名称
        private const val COL_COLLATERAL = 3   // D: 抵押物/抵债资产具体情况
        private const val COL_FIELD = 4        // E: 现状描述
        private const val COL_REMARK = 5       // F: 备注（是否存在发生风险的可能）

        // 数据区行范围（0-based 行号）
        private const val DATA_START_ROW = 3        // 第4行
        private const val FOOTER_START_ROW_1BASED = 20  // 底部说明起始（第20行，1-based）
    }

    /**
     * 填充模板并生成报表文件。
     *
     * @param records AI 生成的报表记录
     * @param routeName 线路名称（用于输出文件名）
     * @param batchMarkedCount 同类型标记数（保留参数兼容调用方，模板底部已含说明，不再单独生成汇总行）
     * @return 生成的临时文件绝对路径（位于 cacheDir，供分享/保存使用）
     */
    suspend fun fillTemplate(
        records: List<ReportRecord>,
        routeName: String,
        batchMarkedCount: Int
    ): String = withContext(Dispatchers.IO) {
        val workbook = context.assets.open(TEMPLATE_ASSET).use { XSSFWorkbook(it) }
        val sheet = workbook.getSheetAt(0)

        // 清空数据区（第4行到第19行，即 0-based DATA_START_ROW..FOOTER_START_ROW_1BASED-2），
        // 防止模板被污染或残留旧数据，但保留底部说明（第20行起）不动。
        val dataEndRowExclusive = FOOTER_START_ROW_1BASED - 1  // 0-based 上界（不含），=19
        for (rowNum in DATA_START_ROW until dataEndRowExclusive) {
            sheet.getRow(rowNum)?.let { sheet.removeRow(it) }
        }

        // 创建单元格样式：边框 + 自动换行 + 左上对齐
        val cellStyle = workbook.createCellStyle().apply {
            wrapText = true
            verticalAlignment = VerticalAlignment.TOP
            alignment = HorizontalAlignment.LEFT
            borderBottom = BorderStyle.THIN
            borderTop = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
        }

        // 日期格式（B 列）
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val todayStr = dateFmt.format(Date())

        // 填充数据
        for ((index, record) in records.withIndex()) {
            val rowNum = DATA_START_ROW + index
            // 数据区预留 16 行（第4-19行），超出则不再写入（避免覆盖底部说明）
            if (rowNum >= dataEndRowExclusive) {
                Timber.w("Data area full (max %d rows), %d records truncated",
                    dataEndRowExclusive - DATA_START_ROW, records.size - index)
                break
            }
            var row = sheet.getRow(rowNum)
            if (row == null) row = sheet.createRow(rowNum)

            // A: 序号
            row.createCell(COL_SERIAL).setCellValue((index + 1).toString())
            // B: 日期
            row.createCell(COL_DATE).setCellValue(todayStr)
            // C: 勘查业务贷款人名称
            row.createCell(COL_CUSTOMER).setCellValue(record.customerName)
            // D: 抵押物/抵债资产具体情况
            row.createCell(COL_COLLATERAL).setCellValue(record.collateralInfo)
            // E: 现状描述
            row.createCell(COL_FIELD).setCellValue(record.fieldDescription)
            // F: 备注（是否存在发生风险的可能）= 风险提示 + 汇总说明
            val remark = buildString {
                if (record.riskAlert.isNotBlank()) append(record.riskAlert)
                if (record.summary.isNotBlank()) {
                    if (isNotEmpty()) append(' ')
                    append(record.summary)
                }
            }
            row.createCell(COL_REMARK).setCellValue(remark)

            // 应用样式到 A-F 全部 6 列
            for (col in COL_SERIAL..COL_REMARK) {
                row.getCell(col)?.cellStyle = cellStyle
            }

            // 设置行高以容纳多行文本
            row.heightInPoints = 60f
        }

        // 输出到 cacheDir 临时文件（供分享/保存使用，不再写入 app 私有 reports 目录）
        val dateStrForFile = SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date())
        val fileName = "现场勘查日报表_${dateStrForFile}_${routeName}.xlsx"
        val outputFile = File(context.cacheDir, fileName)

        ByteArrayOutputStream().use { baos ->
            workbook.write(baos)
            outputFile.writeBytes(baos.toByteArray())
        }
        workbook.close()

        Timber.i("Report generated: ${outputFile.absolutePath} (records=${records.size}, batchMarked=$batchMarkedCount)")
        outputFile.absolutePath
    }

    /**
     * 安全读取单元格为字符串，兼容 STRING/NUMERIC/BOOLEAN/FORMULA/BLANK。
     * 避免 NUMERIC 单元格调用 stringCellValue 抛 IllegalStateException。
     */
    private fun getCellString(cell: org.apache.poi.ss.usermodel.Cell?): String {
        if (cell == null) return ""
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

    /**
     * 合并行单元格辅助扩展（保留供未来使用）。
     */
    private fun org.apache.poi.ss.usermodel.Sheet.addMergedRow(rowNum: Int, startCol: Int, endCol: Int) {
        addMergedRegion(CellRangeAddress(rowNum, rowNum, startCol, endCol))
    }
}
