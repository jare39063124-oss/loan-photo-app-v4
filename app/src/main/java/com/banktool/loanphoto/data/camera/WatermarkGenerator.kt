package com.banktool.loanphoto.data.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 水印位置。
 */
enum class WatermarkPosition {
    BOTTOM_RIGHT,
    BOTTOM_LEFT,
    TOP_RIGHT,
    TOP_LEFT,
}

/**
 * 水印字号（基准 px，按图片宽度自适应缩放）。
 *
 * - LARGE 80px，水印块最大占宽 25%
 * - MEDIUM 56px，水印块最大占宽 20%
 * - SMALL 36px，水印块最大占宽 15%
 *
 * 最小不低于 24px（缩放后）。
 *
 * @param sizePx 基准字号 px
 * @param maxBlockWidthRatio 水印块最大宽度占图片宽度的比例（超出则自动缩字号）
 */
enum class WatermarkFontSize(val sizePx: Int, val maxBlockWidthRatio: Float) {
    LARGE(80, 0.25f),
    MEDIUM(56, 0.20f),
    SMALL(36, 0.15f),
}

/**
 * 水印绘制配置。
 *
 * @param segments 多行文字（按顺序绘制）
 * @param position 位置（四角）
 * @param fontSize 字号档位
 * @param opacity 不透明度 [0,1]
 * @param enabled 是否启用水印
 */
data class WatermarkConfig(
    val segments: List<String>,
    val position: WatermarkPosition = WatermarkPosition.BOTTOM_RIGHT,
    val fontSize: WatermarkFontSize = WatermarkFontSize.MEDIUM,
    val opacity: Float = 0.7f,
    val enabled: Boolean = true,
    /** 水印内容逐项显示开关（默认全 true，向后兼容）。全 false 时 segments 为空，不绘制水印块。 */
    val showDate: Boolean = true,
    val showSerial: Boolean = true,
    val showAddress: Boolean = true,
    val showLatlng: Boolean = true,
)

/**
 * 水印生成器：在 [Bitmap] 上绘制多行文字（半透明背景框 + 白色加粗文字），
 * 并保留 GPS EXIF 信息后另存为 JPEG。
 *
 * 字号自适应：scale = bitmap.width / 1080f，最终 fontSize = max(24, baseSize * scale)。
 *
 * 3 段内容约定：拍摄时间（日期）/ 地址名 / 经纬度。
 */
@Singleton
class WatermarkGenerator @Inject constructor() {

    private val dateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy年MM月dd日", Locale.getDefault())

    /**
     * 在 [bitmap] 上绘制水印，返回新 [Bitmap]（不修改原图）。
     *
     * 当 [WatermarkConfig.enabled] 为 false 时直接返回原图副本。
     */
    fun drawWatermark(
        bitmap: Bitmap,
        segments: List<String>,
        position: WatermarkPosition,
        fontSize: WatermarkFontSize,
        opacity: Float = 0.7f,
    ): Bitmap {
        val working = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        if (segments.isEmpty()) return working

        val canvas = Canvas(working)
        val width = canvas.width
        val height = canvas.height
        if (width <= 0 || height <= 0) return working

        val scale = width / BASE_WIDTH
        val marginPx = (width * EDGE_MARGIN_RATIO).toInt().coerceAtLeast(1)
        val maxBlockWidth = (width * fontSize.maxBlockWidthRatio).toInt()
        val padPx = (PADDING_PX * scale).toInt().coerceAtLeast(1)
        val lineGapPx = (LINE_GAP_PX * scale).toInt()

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
        }

        val bgPaint = Paint().apply {
            color = Color.BLACK
        }

        // 自适应字号：初始按档位缩放，若水印块超当前档位最大占宽（fontSize.maxBlockWidthRatio）则循环缩小字号直至适配
        var fontPx = (fontSize.sizePx * scale).toInt().coerceAtLeast(MIN_FONT_PX)
        var maxTextWidth: Int
        var blockWidth: Int
        var blockHeight: Int
        var lineHeight: Int
        while (true) {
            textPaint.textSize = fontPx.toFloat()
            textPaint.alpha = (255 * opacity.coerceIn(0f, 1f)).toInt()
            bgPaint.alpha = (255 * BG_OPACITY_RATIO * opacity.coerceIn(0f, 1f)).toInt()
            lineHeight = fontPx + lineGapPx
            maxTextWidth = segments.maxOf { textPaint.measureText(it).toInt() }
            blockWidth = maxTextWidth + padPx * 2
            blockHeight = lineHeight * segments.size + padPx * 2
            if (blockWidth <= maxBlockWidth || fontPx <= MIN_FONT_PX) break
            fontPx = (fontPx * 0.9f).toInt().coerceAtLeast(MIN_FONT_PX)
        }

        // 位置计算：距边缘留 3% 空隙，并钳制不低于 marginPx（防止负坐标导致裁切）
        val (left, top) = when (position) {
            WatermarkPosition.BOTTOM_RIGHT -> Pair(
                (width - blockWidth - marginPx).coerceAtLeast(marginPx),
                (height - blockHeight - marginPx).coerceAtLeast(marginPx),
            )
            WatermarkPosition.BOTTOM_LEFT -> Pair(
                marginPx,
                (height - blockHeight - marginPx).coerceAtLeast(marginPx),
            )
            WatermarkPosition.TOP_RIGHT -> Pair(
                (width - blockWidth - marginPx).coerceAtLeast(marginPx),
                marginPx,
            )
            WatermarkPosition.TOP_LEFT -> Pair(
                marginPx,
                marginPx,
            )
        }

        // 背景半透明框
        canvas.drawRect(
            Rect(left, top, left + blockWidth, top + blockHeight),
            bgPaint,
        )

        // 文字
        segments.forEachIndexed { index, line ->
            val textY = top + padPx + fontPx + index * lineHeight
            val textX = (left + padPx).toFloat()
            canvas.drawText(line, textX, textY.toFloat(), textPaint)
        }

        return working
    }

    /**
     * 便利方法：基于 [WatermarkConfig] 绘制并另存为 JPEG（quality=92），
     * 同时把 GPS EXIF 从源文件复制到目标文件。
     *
     * 水印绘制完成后，按 [photoQuality] 等比缩放最长边至 [PhotoQuality.maxEdge]（不放大），
     * 再压缩写出 JPEG。
     *
     * @param sourcePath 源图片路径（用于读取 EXIF）
     * @param outputPath 输出 JPEG 路径
     * @param location 位置结果；为 null 时跳过 GPS EXIF 写入（仅复制原 EXIF）
     * @param config 水印配置
     * @param photoQuality 照片质量等级（缩放最长边上限，默认 [PhotoQuality.HIGH]）
     * @return 输出文件路径（成功时与 outputPath 相同）
     */
    suspend fun drawAndSave(
        sourcePath: String,
        outputPath: String,
        location: LocationResult?,
        config: WatermarkConfig,
        photoQuality: PhotoQuality = PhotoQuality.HIGH,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val sourceFile = File(sourcePath)
            if (!sourceFile.exists()) {
                Timber.w("WatermarkGenerator 源文件不存在: %s", sourcePath)
                return@withContext null
            }

            // 解码为 Bitmap（必须为 mutable config 才能画）
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val srcBitmap = BitmapFactory.decodeFile(sourcePath, opts) ?: run {
                Timber.w("WatermarkGenerator 解码失败: %s", sourcePath)
                return@withContext null
            }

            // 按源文件 EXIF 方向旋转到观看方向，确保水印正向绘制
            val orientation = runCatching {
                ExifInterface(sourcePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            Timber.d("WatermarkGen EXIF orientation=%d srcBitmap=%dx%d sourcePath=%s",
                orientation, srcBitmap.width, srcBitmap.height, sourcePath)
            val oriented = rotateBitmapByExif(srcBitmap, orientation)
            Timber.d("WatermarkGen after rotate oriented=%dx%d (rotated=%s)",
                oriented.width, oriented.height, oriented !== srcBitmap)

            val resultBitmap = if (config.enabled) {
                drawWatermark(
                    bitmap = oriented,
                    segments = config.segments,
                    position = config.position,
                    fontSize = config.fontSize,
                    opacity = config.opacity,
                )
            } else {
                oriented
            }

            // 按照片质量等比缩放（不放大）：最长边超过 maxEdge 时缩小
            val maxEdge = photoQuality.maxEdge
            val longestEdge = maxOf(resultBitmap.width, resultBitmap.height)
            val finalBitmap = if (longestEdge > maxEdge) {
                val scale = maxEdge.toFloat() / longestEdge
                Bitmap.createScaledBitmap(
                    resultBitmap,
                    (resultBitmap.width * scale).toInt(),
                    (resultBitmap.height * scale).toInt(),
                    true,
                )
            } else {
                resultBitmap
            }
            Timber.d("WatermarkGen final=%dx%d (landscape=%s) -> %s",
                finalBitmap.width, finalBitmap.height,
                finalBitmap.width > finalBitmap.height, outputPath)

            // 写出 JPEG
            val outFile = File(outputPath)
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { fos ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
                fos.flush()
            }
            // 回收位图链：srcBitmap → oriented → resultBitmap → finalBitmap
            // 每个对象仅回收一次（链中相同时跳过，避免 double-recycle / 内存泄漏）
            if (oriented !== srcBitmap) srcBitmap.recycle()
            if (resultBitmap !== oriented) oriented.recycle()
            if (finalBitmap !== resultBitmap) resultBitmap.recycle()
            finalBitmap.recycle()

            // 写入 GPS EXIF（保留原 EXIF + 追加 GPS；location=null 时跳过 GPS）
            writeGpsExif(outFile.absolutePath, sourcePath, location)

            outFile.absolutePath
        } catch (e: Exception) {
            Timber.e(e, "WatermarkGenerator drawAndSave 失败")
            null
        }
    }

    /**
     * 构造水印段内容（向后兼容重载，等价于 4 开关全 true）。
     *
     * @see [buildSegments] 的带开关重载
     */
    fun buildSegments(
        captureTimeMillis: Long,
        location: LocationResult?,
        serial: String? = null,
    ): List<String> = buildSegments(captureTimeMillis, location, serial, true, true, true, true)

    /**
     * 构造水印段内容：拍摄日期 / （可选）序号 / 地址名 / 经纬度，按 4 个开关过滤。
     *
     * - location 为 null 时（定位失败），地址段和经纬度段显示「定位失败」（受 showAddress/showLatlng 控制）
     * - 拍摄日期段始终受 showDate 控制
     * - serial 为空或 showSerial=false 时不插入序号段
     * - 全部开关关闭时返回 emptyList()（调用方据此不绘制水印块）
     *
     * @param captureTimeMillis 拍照时间毫秒
     * @param location 位置结果；为 null 时水印显示「定位失败」
     * @param serial 客户序号；非空且 showSerial=true 时插入「序号:xxx」段
     * @param showDate 是否显示拍摄日期段
     * @param showSerial 是否显示序号段
     * @param showAddress 是否显示地址段
     * @param showLatlng 是否显示经纬度段
     */
    fun buildSegments(
        captureTimeMillis: Long,
        location: LocationResult?,
        serial: String?,
        showDate: Boolean,
        showSerial: Boolean,
        showAddress: Boolean,
        showLatlng: Boolean,
    ): List<String> {
        val segments = mutableListOf<String>()
        if (showDate) {
            segments += LocalDate.now().format(dateFormatter)
        }
        if (showSerial) {
            serial?.takeIf { it.isNotBlank() }?.let { segments += "序号:$it" }
        }
        if (showAddress) {
            segments += if (location != null) location.address.ifBlank { "未知位置" } else "定位失败"
        }
        if (showLatlng) {
            segments += if (location != null) {
                "%.6f,%.6f".format(Locale.US, location.lat, location.lng)
            } else {
                "定位失败"
            }
        }
        return segments
    }

    /**
     * 按源文件 EXIF 方向标签将 bitmap 旋转到正确观看方向。
     *
     * BitmapFactory.decodeFile 不应用 EXIF 旋转，直接在原始像素上画水印会导致
     * 带方向标签的照片（如横版 ROTATE_90）水印呈竖排。本方法把旋转烘焙进像素，
     * 配合输出时 ORIENTATION_NORMAL，确保水印始终正向。
     *
     * NORMAL/UNDEFINED 或无需变换时返回原 bitmap（不拷贝）。
     */
    private fun rotateBitmapByExif(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * 复制源文件 EXIF 并写入 GPS 信息到目标文件。
     *
     * location 为 null 时仅复制原始 EXIF，跳过 GPS 写入。
     */
    private fun writeGpsExif(
        targetPath: String,
        sourcePath: String,
        location: LocationResult?,
    ) {
        try {
            val sourceExif = runCatching { ExifInterface(sourcePath) }.getOrNull()
            val targetExif = ExifInterface(targetPath)

            // 复制原始 EXIF（设备等；方向不复制，已烘焙进像素）
            if (sourceExif != null) {
                val tags = listOf(
                    ExifInterface.TAG_MAKE,
                    ExifInterface.TAG_MODEL,
                    ExifInterface.TAG_DATETIME,
                    ExifInterface.TAG_DATETIME_ORIGINAL,
                )
                for (tag in tags) {
                    val value = sourceExif.getAttribute(tag)
                    if (!value.isNullOrBlank()) {
                        targetExif.setAttribute(tag, value)
                    }
                }
                // 旋转已烘焙进像素，输出方向置 NORMAL（不复制源方向标签）
                targetExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            }

            // GPS 经纬度（location 为 null 时跳过 GPS 写入）
            if (location != null) {
                targetExif.setLatLong(location.lat, location.lng)
                targetExif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "0")
                targetExif.setAttribute(
                    ExifInterface.TAG_GPS_TIMESTAMP,
                    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()),
                )
            } else {
                Timber.d("WatermarkGenerator location=null，跳过 GPS EXIF 写入")
            }
            targetExif.saveAttributes()
        } catch (e: Exception) {
            Timber.w(e, "WatermarkGenerator EXIF 写入失败")
        }
    }

    private companion object {
        const val BASE_WIDTH = 1080f
        const val MIN_FONT_PX = 24
        const val PADDING_PX = 24
        const val LINE_GAP_PX = 12
        const val BG_OPACITY_RATIO = 0.55f
        const val JPEG_QUALITY = 92
        const val EDGE_MARGIN_RATIO = 0.03f // 水印距图片边缘 3% 空隙
    }
}
