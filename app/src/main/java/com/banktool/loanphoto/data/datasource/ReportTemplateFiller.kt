package com.banktool.loanphoto.data.datasource

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.banktool.loanphoto.data.dto.ReportRecord
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
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
 * 读取 assets/report_template.xlsx，填充 A-E 列，生成最终报表
 */
@Singleton
class ReportTemplateFiller @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ReportTemplateFiller"
        private const val TEMPLATE_ASSET = "report_template.xlsx"

        // 列索引 (0-based)
        private const val COL_CUSTOMER = 0       // A: 客户名称
        private const val COL_COLLATERAL = 1     // B: 抵押物情况
        private const val COL_FIELD = 2          // C: 实地说明
        private const val COL_RISK = 3           // D: 风险提示
        private const val COL_SUMMARY = 4        // E: 汇总说明
    }

    /**
     * 填充模板并生成报表文件
     * @param records AI 生成的报表记录
     * @param routeName 线路名称
     * @param batchMarkedCount 同类型标记数
     * @return 生成的文件路径
     */
    suspend fun fillTemplate(
        records: List<ReportRecord>,
        routeName: String,
        batchMarkedCount: Int
    ): String = withContext(Dispatchers.IO) {
        val workbook = XSSFWorkbook(context.assets.open(TEMPLATE_ASSET))
        val sheet = workbook.getSheetAt(0)

        // 找到数据起始行（跳过表头）
        var dataStartRow = 0
        for (rowNum in 0..sheet.lastRowNum) {
            val row = sheet.getRow(rowNum)
            if (row == null) {
                dataStartRow = rowNum
                break
            }
            val cell = row.getCell(0)
            if (cell == null || cell.stringCellValue.isNullOrBlank()) {
                dataStartRow = rowNum
                break
            }
        }
        if (dataStartRow == 0) dataStartRow = 1 // 默认第1行后

        // 创建单元格样式
        val cellStyle = workbook.createCellStyle().apply {
            wrapText = true
            verticalAlignment = VerticalAlignment.TOP
            alignment = HorizontalAlignment.LEFT
            borderBottom = org.apache.poi.ss.usermodel.BorderStyle.THIN
            borderTop = org.apache.poi.ss.usermodel.BorderStyle.THIN
            borderLeft = org.apache.poi.ss.usermodel.BorderStyle.THIN
            borderRight = org.apache.poi.ss.usermodel.BorderStyle.THIN
        }

        // 填充数据
        for ((index, record) in records.withIndex()) {
            val rowNum = dataStartRow + index
            var row = sheet.getRow(rowNum)
            if (row == null) row = sheet.createRow(rowNum)

            row.createCell(COL_CUSTOMER).setCellValue(record.customerName)
            row.createCell(COL_COLLATERAL).setCellValue(record.collateralInfo)
            row.createCell(COL_FIELD).setCellValue(record.fieldDescription)
            row.createCell(COL_RISK).setCellValue(record.riskAlert)
            row.createCell(COL_SUMMARY).setCellValue(record.summary)

            // 应用样式
            for (col in 0..4) {
                row.getCell(col)?.cellStyle = cellStyle
            }

            // 设置行高
            row.heightInPoints = 60f
        }

        // 添加汇总行
        val summaryRowNum = dataStartRow + records.size + 1
        val summaryRow = sheet.createRow(summaryRowNum)
        val dateStr = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA).format(Date())
        val totalCustomers = records.size
        val summaryText = "本次报告基于%s生成，共计%d个客户，其中已外访代表性户型%d户，另有%d户为同类型".format(
            dateStr, totalCustomers, totalCustomers - batchMarkedCount, batchMarkedCount
        )

        val summaryCell = summaryRow.createCell(0)
        summaryCell.setCellValue(summaryText)
        sheet.addMergedRow(summaryRowNum, 0, 4)

        // 保存到文件
        val dateStrForFile = SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date())
        val fileName = "线路四${routeName}（${dateStr}）现场勘查日报表.xlsx"
        val outputDir = File(context.getExternalFilesDir(null), "reports")
        if (!outputDir.exists()) outputDir.mkdirs()
        val outputFile = File(outputDir, fileName)

        ByteArrayOutputStream().use { baos ->
            workbook.write(baos)
            outputFile.writeBytes(baos.toByteArray())
        }
        workbook.close()

        Timber.i("Report generated: ${outputFile.absolutePath}")
        outputFile.absolutePath
    }

    /**
     * 合并行单元格辅助扩展
     */
    private fun org.apache.poi.ss.usermodel.Sheet.addMergedRow(rowNum: Int, startCol: Int, endCol: Int) {
        addMergedRegion(CellRangeAddress(rowNum, rowNum, startCol, endCol))
    }
}
