package com.banktool.loanphoto.data.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import com.banktool.loanphoto.domain.entity.CustomerRow
import com.banktool.loanphoto.domain.repository.ProgressRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * 照片导出维度。
 *
 * - [SERIAL]: 按序号匹配（[CustomerRow.serial]）
 * - [ADDRESS]: 按地址匹配（[CustomerRow.addrGeneral] + [CustomerRow.addrDetail]）
 * - [BORROWER]: 按借款人匹配（[CustomerRow.borrower]）
 */
enum class ExportDimension(val displayName: String) {
    SERIAL("序号"),
    ADDRESS("地址"),
    BORROWER("借款人"),
    ;

    companion object {
        /** 与 [com.banktool.loanphoto.domain.entity.SearchField] 顺序对齐的默认值。 */
        val DEFAULT: ExportDimension = BORROWER
    }
}

/**
 * 导出文件格式。
 *
 * - [PDF]: 每张照片一页（A4 横向 842×595 pt），用 [PdfDocument] 生成
 * - [ZIP]: 按原文件名打包，用 [ZipOutputStream] 生成
 */
enum class ExportFormat(val displayName: String, val ext: String) {
    PDF("PDF", "pdf"),
    ZIP("压缩包", "zip"),
}

/**
 * 照片导出器。
 *
 * 匹配逻辑（参见 [matchRows]）：
 * - [ExportDimension.SERIAL] → rows.filter { it.serial.contains(keyword, ignoreCase=true) }
 * - [ExportDimension.ADDRESS] → rows.filter { (it.addrGeneral + it.addrDetail).contains(keyword, ignoreCase=true) }
 * - [ExportDimension.BORROWER] → rows.filter { it.borrower.contains(keyword, ignoreCase=true) }
 *
 * 关键词为空时返回全集。
 *
 * 照片文件获取（参见 [collectPhotos]）：优先读取 [ProgressRepository.getProgress] 返回的
 * [com.banktool.loanphoto.domain.entity.PhotoRecord.photos]（progress.json 中记录的绝对路径列表）；
 * 不可用或为空时扫描 `getExternalFilesDir/photos/<progressKey>/` 子目录；
 * 再退化为按文件名匹配（包含客户 serial 或 borrower）。
 *
 * 输出路径：`getExternalFilesDir/reports/导出_<关键字>_<时间戳>.<ext>`
 */
class PhotoExporter @Inject constructor(
    private val progressRepository: ProgressRepository,
) {

    /**
     * 导出照片到 PDF 或 ZIP。
     *
     * @param context 用于访问 [Context.getExternalFilesDir]
     * @param rows 全量客户行（函数内会按 [dimension] + [keyword] 过滤）
     * @param dimension 匹配维度
     * @param keyword 关键词（空串匹配全部）
     * @param format 输出格式
     * @param onProgress 进度回调 (current, total)，在 IO 线程触发
     * @return 输出文件；若匹配为空或无可用照片，返回 null
     */
    suspend fun exportPhotos(
        context: Context,
        rows: List<CustomerRow>,
        dimension: ExportDimension,
        keyword: String,
        format: ExportFormat,
        onProgress: (current: Int, total: Int) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        if (rows.isEmpty()) {
            Timber.w("导出失败：rows 为空")
            return@withContext null
        }

        val matched = matchRows(rows, dimension, keyword)
        if (matched.isEmpty()) {
            Timber.i("导出跳过：未匹配到客户行 (dim=%s, kw=%s)", dimension, keyword)
            return@withContext null
        }

        val photos = collectPhotos(context, matched)
        if (photos.isEmpty()) {
            Timber.i("导出跳过：匹配到 %d 行但无可用照片", matched.size)
            return@withContext null
        }

        val reportsDir = File(context.getExternalFilesDir(null), REPORTS_DIR).apply {
            if (!exists()) mkdirs()
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val safeKeyword = keyword.trim()
            .ifBlank { "全部" }
            .replace(Regex("[^A-Za-z0-9_\\u4e00-\\u9fa5]"), "_")
            .take(MAX_KEYWORD_LEN)
        val outputName = "导出_${safeKeyword}_$timestamp.${format.ext}"
        val outputFile = File(reportsDir, outputName)

        val ok = when (format) {
            ExportFormat.PDF -> runCatching {
                exportToPdf(photos, outputFile, onProgress)
            }.also { result ->
                result.exceptionOrNull()?.let { Timber.e(it, "PDF 导出失败") }
            }.isSuccess
            ExportFormat.ZIP -> runCatching {
                exportToZip(photos, outputFile, onProgress)
            }.also { result ->
                result.exceptionOrNull()?.let { Timber.e(it, "ZIP 导出失败") }
            }.isSuccess
        }

        if (ok && outputFile.exists() && outputFile.length() > 0) {
            Timber.i("导出成功：%s (%d bytes, %d 张照片)", outputFile.absolutePath, outputFile.length(), photos.size)
            outputFile
        } else {
            // 失败时清理半成品文件
            runCatching { if (outputFile.exists()) outputFile.delete() }
            null
        }
    }

    /**
     * 按 [dimension] 与 [keyword] 过滤客户行。
     * 关键词为空（或纯空白）时返回全集。
     */
    private fun matchRows(
        rows: List<CustomerRow>,
        dimension: ExportDimension,
        keyword: String,
    ): List<CustomerRow> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return rows
        return when (dimension) {
            ExportDimension.SERIAL ->
                rows.filter { it.serial.contains(kw, ignoreCase = true) }
            ExportDimension.ADDRESS ->
                rows.filter { (it.addrGeneral + it.addrDetail).contains(kw, ignoreCase = true) }
            ExportDimension.BORROWER ->
                rows.filter { it.borrower.contains(kw, ignoreCase = true) }
        }
    }

    /**
     * 收集匹配客户行对应的照片文件。
     *
     * 三级回退：[ProgressRepository.getProgress] 返回的 photos 绝对路径列表、
     * `photos/<progressKey>/` 目录扫描、`photos/` 全目录按文件名匹配 serial / borrower。
     *
     * 同一文件不会被重复加入（按 absolutePath 去重）。
     */
    private suspend fun collectPhotos(
        context: Context,
        rows: List<CustomerRow>,
    ): List<File> {
        val photosRoot = context.getExternalFilesDir(null)?.let { File(it, PHOTOS_DIR) }
        val seen = LinkedHashSet<String>()
        val result = ArrayList<File>()

        for (row in rows) {
            val record = runCatching { progressRepository.getProgress(row.progressKey) }.getOrNull()
            record?.photos
                ?.asSequence()
                ?.map { File(it) }
                ?.filter { it.exists() && it.isFile }
                ?.forEach { addIfNew(seen, result, it) }

            if (result.isNotEmpty() && seen.any { it.startsWith(row.progressKey) || it.contains(row.progressKey) }) {
                // 已通过 progress.json 找到该客户照片，跳过后续回退
                continue
            }

            val keyDir = photosRoot?.let { File(it, row.progressKey) }
            if (keyDir != null && keyDir.exists()) {
                keyDir.listFiles { f -> f.isFile && f.extension.isImageExt() }
                    ?.sortedBy { it.lastModified() }
                    ?.forEach { addIfNew(seen, result, it) }
            }

            if (photosRoot != null && photosRoot.exists()) {
                val serial = row.serial.trim()
                val borrower = row.borrower.trim()
                if (serial.isNotEmpty() || borrower.isNotEmpty()) {
                    photosRoot.walkTopDown()
                        .filter { f -> f.isFile && f.extension.isImageExt() }
                        .filter { f ->
                            (serial.isNotEmpty() && f.name.contains(serial, ignoreCase = true)) ||
                                (borrower.isNotEmpty() && f.name.contains(borrower, ignoreCase = true))
                        }
                        .sortedBy { it.lastModified() }
                        .forEach { addIfNew(seen, result, it) }
                }
            }
        }
        return result
    }

    private fun addIfNew(seen: MutableSet<String>, out: MutableList<File>, file: File) {
        val path = file.absolutePath
        if (seen.add(path)) {
            out.add(file)
        }
    }

    private fun String.isImageExt(): Boolean =
        lowercase() in IMAGE_EXTENSIONS

    /**
     * PDF 导出：A4 横向 842×595 pt，照片居中等比缩放适配页面。
     */
    private fun exportToPdf(
        photos: List<File>,
        outputFile: File,
        onProgress: (current: Int, total: Int) -> Unit,
    ) {
        val pdfDocument = PdfDocument()
        try {
            photos.forEachIndexed { index, photoFile ->
                val pageInfo = PdfDocument.PageInfo.Builder(PDF_PAGE_W, PDF_PAGE_H, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas
                canvas.drawColor(Color.WHITE)

                val bitmap = decodeScaledBitmap(photoFile, PDF_PAGE_W, PDF_PAGE_H)
                if (bitmap != null) {
                    val scale = minOf(
                        PDF_PAGE_W.toFloat() / bitmap.width,
                        PDF_PAGE_H.toFloat() / bitmap.height,
                    )
                    val scaledW = (bitmap.width * scale).toInt().coerceAtLeast(1)
                    val scaledH = (bitmap.height * scale).toInt().coerceAtLeast(1)
                    val left = (PDF_PAGE_W - scaledW) / 2
                    val top = (PDF_PAGE_H - scaledH) / 2
                    canvas.drawBitmap(
                        bitmap,
                        null,
                        Rect(left, top, left + scaledW, top + scaledH),
                        null,
                    )
                    bitmap.recycle()
                }
                pdfDocument.finishPage(page)
                onProgress(index + 1, photos.size)
            }
            FileOutputStream(outputFile).use { out ->
                pdfDocument.writeTo(out)
            }
        } finally {
            pdfDocument.close()
        }
    }

    /**
     * ZIP 导出：按原文件名打包，重名时追加 _1/_2 序号。
     */
    private fun exportToZip(
        photos: List<File>,
        outputFile: File,
        onProgress: (current: Int, total: Int) -> Unit,
    ) {
        val usedNames = HashSet<String>()
        ZipOutputStream(FileOutputStream(outputFile).buffered()).use { zip ->
            photos.forEachIndexed { index, photoFile ->
                val entryName = uniqueEntryName(photoFile.name, usedNames)
                usedNames.add(entryName)
                zip.putNextEntry(ZipEntry(entryName))
                photoFile.inputStream().use { input ->
                    input.copyTo(zip)
                }
                zip.closeEntry()
                onProgress(index + 1, photos.size)
            }
        }
    }

    private fun uniqueEntryName(rawName: String, used: HashSet<String>): String {
        if (rawName !in used) return rawName
        val dot = rawName.lastIndexOf('.')
        val base = if (dot > 0) rawName.substring(0, dot) else rawName
        val ext = if (dot > 0) rawName.substring(dot) else ""
        var i = 1
        while ("${base}_$i$ext" in used) i++
        return "${base}_$i$ext"
    }

    /**
     * 解码 bitmap，超过页面尺寸时按 2 的幂次降采样，避免 OOM。
     */
    private fun decodeScaledBitmap(file: File, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val opts = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (e: OutOfMemoryError) {
            Timber.w(e, "解码 bitmap OOM: %s", file.absolutePath)
            null
        } catch (e: Exception) {
            Timber.w(e, "解码 bitmap 失败: %s", file.absolutePath)
            null
        }
    }

    private fun calculateInSampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        if (srcW <= reqW && srcH <= reqH) return 1
        var sample = 1
        while (srcW / (2 * sample) >= reqW && srcH / (2 * sample) >= reqH) {
            sample *= 2
        }
        return sample
    }

    private companion object {
        const val PHOTOS_DIR = "photos"
        const val REPORTS_DIR = "reports"
        const val MAX_KEYWORD_LEN = 24
        const val PDF_PAGE_W = 842 // A4 横向 pt
        const val PDF_PAGE_H = 595
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }
}
