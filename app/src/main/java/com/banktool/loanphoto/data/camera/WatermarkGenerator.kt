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
     * @param overrides 水印文本行覆盖（key: "date"/"serial"/"address"/"latlng"），
     *        绘制前按行内容特征识别并替换对应段；空值/空 map 不替换（默认行为不变）
     * @return 输出文件路径（成功时与 outputPath 相同）
     */
    suspend fun drawAndSave(
        sourcePath: String,
        outputPath: String,
        location: LocationResult?,
        config: WatermarkConfig,
        photoQuality: PhotoQuality = PhotoQuality.HIGH,
        overrides: Map<String, String> = emptyMap(),
    ): String? = withContext(Dispatchers.IO) {
        try {
            val sourceFile = File(sourcePath)
            if (!sourceFile.exists()) {
                Timber.w("WatermarkGenerator 源文件不存在: %s", sourcePath)
                return@withContext null
            }

            // 目标最长边（HIGH=1920 / MEDIUM=1280 / LOW=640），后续降采样与精确缩放均以此为基准
            val targetLongestEdge = photoQuality.maxEdge

            // 1. 仅读取原图尺寸（inJustDecodeBounds 不分配像素内存），用于计算 inSampleSize
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(sourcePath, boundsOpts)
            val srcWidth = boundsOpts.outWidth
            val srcHeight = boundsOpts.outHeight
            if (srcWidth <= 0 || srcHeight <= 0) {
                Timber.w("WatermarkGenerator 读取原图尺寸失败: %s", sourcePath)
                return@withContext null
            }

            // 2. 计算 inSampleSize（2 的幂次），使解码后最长边 >= targetLongestEdge（约目标的 1~2 倍）
            val sampleSize = calculateInSampleSize(srcWidth, srcHeight, targetLongestEdge)

            // 3. 降采样解码：相比全分辨率（可能 48MP）大幅减少像素量，是性能优化的核心步骤
            val decodeOpts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = sampleSize
            }
            val decodedBitmap = BitmapFactory.decodeFile(sourcePath, decodeOpts) ?: run {
                Timber.w("WatermarkGenerator 解码失败: %s", sourcePath)
                return@withContext null
            }

            // 4. 按源文件 EXIF 方向旋转（烘焙进像素），确保水印正向绘制；EXIF 从源文件读取以保留原方向信息
            val orientation = runCatching {
                ExifInterface(sourcePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            Timber.d("WatermarkGen EXIF orientation=%d decoded=%dx%d sampleSize=%d sourcePath=%s",
                orientation, decodedBitmap.width, decodedBitmap.height, sampleSize, sourcePath)
            val oriented = rotateBitmapByExif(decodedBitmap, orientation)
            Timber.d("WatermarkGen after rotate oriented=%dx%d (rotated=%s)",
                oriented.width, oriented.height, oriented !== decodedBitmap)

            // 5. 精确缩放到目标尺寸（不放大）：旋转后宽高可能互换，故以 oriented 实际宽高计算
            //    在已降采样的位图上缩放，远比在全分辨率位图上缩放省时省内存
            val longestEdge = maxOf(oriented.width, oriented.height)
            val scaledBitmap = if (longestEdge > targetLongestEdge) {
                val scale = targetLongestEdge.toFloat() / longestEdge
                Bitmap.createScaledBitmap(
                    oriented,
                    (oriented.width * scale).toInt(),
                    (oriented.height * scale).toInt(),
                    true,
                )
            } else {
                oriented
            }

            // 6. 在已缩放位图上绘制水印：drawWatermark 基于 bitmap.width 自适应字号/位置，
            //    故在 1920px 上绘制的视觉效果与「全分辨率绘制再缩到 1920px」一致或更优，
            //    且 maxBlockWidthRatio（25%/20%/15%）按已缩放宽度生效，行为与 v4.1.4 保持一致
            val finalBitmap = if (config.enabled) {
                drawWatermark(
                    bitmap = scaledBitmap,
                    segments = applySegmentOverrides(config.segments, overrides),
                    position = config.position,
                    fontSize = config.fontSize,
                    opacity = config.opacity,
                )
            } else {
                scaledBitmap
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
            // 回收中间位图链：decodedBitmap → oriented → scaledBitmap → finalBitmap
            // 每个对象仅回收一次（链中相同时跳过，避免 double-recycle / 内存泄漏）
            // drawWatermark 内部 copy 出新位图，故 finalBitmap 通常 != scaledBitmap，scaledBitmap 仍需回收
            if (oriented !== decodedBitmap) decodedBitmap.recycle()
            if (scaledBitmap !== oriented) oriented.recycle()
            if (finalBitmap !== scaledBitmap) scaledBitmap.recycle()
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
     * 按行内容特征识别水印段并应用 [overrides] 覆盖（绘制前单次应用）。
     *
     * 段识别依据 [buildSegments] 的确定性输出格式：
     * - 日期段：`yyyy年MM月dd日` → key "date"
     * - 序号段：以「序号:」开头 → key "serial"（覆盖值不带前缀时自动补「序号:」）
     * - 经纬度段：`lat,lng`（各 6 位小数）→ key "latlng"
     * - 其余行视为地址段 → key "address"（buildSegments 每类只输出一行）
     *
     * override 值为空白时不覆盖；overrides 为空时原样返回（现有调用行为不变）。
     */
    private fun applySegmentOverrides(
        segments: List<String>,
        overrides: Map<String, String>,
    ): List<String> {
        if (overrides.isEmpty() || segments.isEmpty()) return segments
        fun overrideOf(key: String): String? =
            overrides[key]?.takeIf { it.isNotBlank() }
        return segments.map { line ->
            when {
                DATE_SEGMENT_REGEX.matches(line) -> overrideOf("date") ?: line
                line.startsWith(SERIAL_PREFIX) -> overrideOf("serial")?.let {
                    if (it.startsWith(SERIAL_PREFIX)) it else "$SERIAL_PREFIX$it"
                } ?: line
                LATLNG_SEGMENT_REGEX.matches(line) -> overrideOf("latlng") ?: line
                else -> overrideOf("address") ?: line
            }
        }
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
     * 根据目标最长边计算 BitmapFactory 的 inSampleSize（2 的幂次）。
     * 使解码后的图片最长边 >= targetLongestEdge，再由 createScaledBitmap 精确缩放。
     *
     * 采用标准 Android 降采样模式：每次翻倍前先判断「再翻倍是否仍不小于目标」，
     * 保证解码后的最长边 >= [targetLongestEdge]（约为目标的 1~2 倍），既避免全分辨率解码，
     * 又为 [Bitmap.createScaledBitmap] 留出向下精确缩放的空间，不损失目标分辨率。
     */
    private fun calculateInSampleSize(srcWidth: Int, srcHeight: Int, targetLongestEdge: Int): Int {
        if (targetLongestEdge <= 0) return 1
        val longestEdge = maxOf(srcWidth, srcHeight)
        if (longestEdge <= targetLongestEdge) return 1
        var sampleSize = 1
        // 再翻倍后仍 >= 目标才继续，确保退出时 longestEdge/sampleSize >= targetLongestEdge
        while (longestEdge / (sampleSize * 2) >= targetLongestEdge) {
            sampleSize *= 2
        }
        return sampleSize
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
        const val SERIAL_PREFIX = "序号:"
        val DATE_SEGMENT_REGEX = Regex("""^\d{4}年\d{1,2}月\d{1,2}日$""")
        val LATLNG_SEGMENT_REGEX = Regex("""^-?\d{1,3}\.\d+,-?\d{1,3}\.\d+$""")
    }
}
