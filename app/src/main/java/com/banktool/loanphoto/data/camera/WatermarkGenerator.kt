package com.banktool.loanphoto.data.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
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
 * - LARGE 80px
 * - MEDIUM 56px
 * - SMALL 36px
 *
 * 最小不低于 24px（缩放后）。
 */
enum class WatermarkFontSize(val sizePx: Int) {
    LARGE(80),
    MEDIUM(56),
    SMALL(36),
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
        val fontPx = (fontSize.sizePx * scale).toInt().coerceAtLeast(MIN_FONT_PX)
        val padPx = (PADDING_PX * scale).toInt().coerceAtLeast(1)
        val lineHeight = fontPx + LINE_GAP_PX

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = fontPx.toFloat()
            typeface = Typeface.DEFAULT_BOLD
            alpha = (255 * opacity.coerceIn(0f, 1f)).toInt()
        }

        val bgPaint = Paint().apply {
            color = Color.BLACK
            alpha = (255 * BG_OPACITY_RATIO * opacity.coerceIn(0f, 1f)).toInt()
        }

        // 测量最长行宽度
        val maxTextWidth = segments.maxOf { textPaint.measureText(it).toInt() }
        val blockWidth = maxTextWidth + padPx * 2
        val blockHeight = lineHeight * segments.size + padPx * 2

        val (left, top) = when (position) {
            WatermarkPosition.BOTTOM_RIGHT -> Pair(width - blockWidth, height - blockHeight)
            WatermarkPosition.BOTTOM_LEFT -> Pair(0, height - blockHeight)
            WatermarkPosition.TOP_RIGHT -> Pair(width - blockWidth, 0)
            WatermarkPosition.TOP_LEFT -> Pair(0, 0)
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
     * @param sourcePath 源图片路径（用于读取 EXIF）
     * @param outputPath 输出 JPEG 路径
     * @param lat 纬度（写入 EXIF）
     * @param lng 经度（写入 EXIF）
     * @param config 水印配置
     * @return 输出文件路径（成功时与 outputPath 相同）
     */
    suspend fun drawAndSave(
        sourcePath: String,
        outputPath: String,
        lat: Double,
        lng: Double,
        config: WatermarkConfig,
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

            val resultBitmap = if (config.enabled) {
                drawWatermark(
                    bitmap = srcBitmap,
                    segments = config.segments,
                    position = config.position,
                    fontSize = config.fontSize,
                    opacity = config.opacity,
                )
            } else {
                srcBitmap
            }

            // 写出 JPEG
            val outFile = File(outputPath)
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { fos ->
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
                fos.flush()
            }
            srcBitmap.recycle()
            if (resultBitmap !== srcBitmap) resultBitmap.recycle()

            // 写入 GPS EXIF（保留原 EXIF + 追加 GPS）
            writeGpsExif(outFile.absolutePath, sourcePath, lat, lng)

            outFile.absolutePath
        } catch (e: Exception) {
            Timber.e(e, "WatermarkGenerator drawAndSave 失败")
            null
        }
    }

    /**
     * 构造水印 3 段内容：拍摄日期 / 地址名 / 经纬度。
     *
     * @param captureTimeMillis 拍照时间毫秒
     * @param address 地址字符串
     * @param lat 纬度
     * @param lng 经度
     */
    fun buildSegments(
        captureTimeMillis: Long,
        address: String,
        lat: Double,
        lng: Double,
    ): List<String> {
        val dateStr = LocalDate.now().format(dateFormatter)
        val addr = address.ifBlank { "未知位置" }
        val latlngStr = "%.6f,%.6f".format(Locale.US, lat, lng)
        return listOf(dateStr, addr, latlngStr)
    }

    /**
     * 复制源文件 EXIF 并写入 GPS 信息到目标文件。
     */
    private fun writeGpsExif(
        targetPath: String,
        sourcePath: String,
        lat: Double,
        lng: Double,
    ) {
        try {
            val sourceExif = runCatching { ExifInterface(sourcePath) }.getOrNull()
            val targetExif = ExifInterface(targetPath)

            // 复制原始 EXIF（方向、设备等）
            if (sourceExif != null) {
                val tags = listOf(
                    ExifInterface.TAG_ORIENTATION,
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
            }

            // GPS 经纬度
            targetExif.setLatLong(lat, lng)
            targetExif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "0")
            targetExif.setAttribute(
                ExifInterface.TAG_GPS_TIMESTAMP,
                SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()),
            )
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
    }
}
