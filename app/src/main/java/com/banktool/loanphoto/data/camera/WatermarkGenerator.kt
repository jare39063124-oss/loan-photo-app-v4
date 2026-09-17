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
import com.banktool.loanphoto.domain.entity.CustomerRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 水印段内容来源。
 *
 * @param displayName 设置页下拉选项展示名
 */
enum class WatermarkSource(val displayName: String) {
    DATE("拍摄日期"),
    SERIAL("序号"),
    BORROWER("客户名"),
    ADDRESS("地址"),
    PROPERTY_TYPE("性质"),
    REMARK("备注"),
    LATLNG("经纬度"),
    CUSTOM("自定义"),
    OFF("关闭"),
}

/**
 * 单个水印段（槽位）的配置。
 *
 * @param source 内容来源
 * @param customText [WatermarkSource.CUSTOM] 段的自定义文本（其它来源忽略）
 */
data class WatermarkSegmentSetting(
    val source: WatermarkSource,
    val customText: String = "",
)

/** 默认段配置（4 槽 = 拍摄日期 / 序号 / 地址 / 经纬度，兼容旧 4 开关全 true 行为）。 */
fun defaultWatermarkSegmentSettings(): List<WatermarkSegmentSetting> = listOf(
    WatermarkSegmentSetting(WatermarkSource.DATE),
    WatermarkSegmentSetting(WatermarkSource.SERIAL),
    WatermarkSegmentSetting(WatermarkSource.ADDRESS),
    WatermarkSegmentSetting(WatermarkSource.LATLNG),
)

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
    /**
     * 4 槽段配置（槽位顺序即绘制顺序）。
     *
     * - 非 null：新段配置化路径，由 [WatermarkGenerator.buildSegments] 按槽位生成行内容
     * - null：旧 4 开关路径（兼容，[showDate]/[showSerial]/[showAddress]/[showLatlng] 生效）
     */
    val segmentSettings: List<WatermarkSegmentSetting>? = null,
    /**
     * 槽位行（slotIndex to line），与 [segmentSettings] 非 null 路径配套。
     *
     * 由调用方通过 [WatermarkGenerator.buildSegments] 生成后填入；会话级覆盖按
     * `slot_N` key 替换对应槽位行文本。null 时 [segments] 即最终绘制行。
     */
    val slotLines: List<Pair<Int, String>>? = null,
)

/**
 * 水印生成器：在 [Bitmap] 上绘制多行文字（半透明背景框 + 白色加粗文字），
 * 并保留 GPS EXIF 信息后另存为 JPEG。
 *
 * 字号自适应：scale = bitmap.width / 1080f，最终 fontSize = max(24, baseSize * scale)。
 *
 * 水印内容：由 [WatermarkConfig.segmentSettings] 段配置（4 槽，见 [buildSegments]）在拍照时
 * 实时构建；[WatermarkConfig.segmentSettings] 为 null 时回退旧 4 开关路径。
 * 会话级文本覆盖按 `slot_N` key 替换对应槽位行。
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
     * @param overrides 水印文本行覆盖（key: `"slot_N"`，N 为槽位索引 0..3，value 为替换行文本），
     *        仅在 [WatermarkConfig.slotLines] 非 null（新段配置化路径）时生效；空白值不替换
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

            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(sourcePath, boundsOpts)
            val srcWidth = boundsOpts.outWidth
            val srcHeight = boundsOpts.outHeight
            if (srcWidth <= 0 || srcHeight <= 0) {
                Timber.w("WatermarkGenerator 读取原图尺寸失败: %s", sourcePath)
                return@withContext null
            }

            val sampleSize = calculateInSampleSize(srcWidth, srcHeight, targetLongestEdge)

            val decodeOpts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = sampleSize
            }
            val decodedBitmap = BitmapFactory.decodeFile(sourcePath, decodeOpts) ?: run {
                Timber.w("WatermarkGenerator 解码失败: %s", sourcePath)
                return@withContext null
            }

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

            val finalBitmap = if (config.enabled) {
                drawWatermark(
                    bitmap = scaledBitmap,
                    segments = applySlotOverrides(config, overrides),
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
            // drawWatermark 内部 copy 出新位图，故 finalBitmap 通常 != scaledBitmap，scaledBitmap 仍需回收
            if (oriented !== decodedBitmap) decodedBitmap.recycle()
            if (scaledBitmap !== oriented) oriented.recycle()
            if (finalBitmap !== scaledBitmap) scaledBitmap.recycle()
            finalBitmap.recycle()

            writeGpsExif(outFile.absolutePath, sourcePath, location)

            outFile.absolutePath
        } catch (e: Exception) {
            Timber.e(e, "WatermarkGenerator drawAndSave 失败")
            null
        }
    }

    /**
     * 构造水印段内容（段配置化主路径）。
     *
     * - [WatermarkConfig.segmentSettings] 非 null：按槽位顺序生成 `slotIndex to line`，
     *   OFF / 空数据段（序号空白、客户名空白、自定义文本空白等）跳过不生成
     * - 为 null：回退旧 4 开关逻辑（见带开关重载），行按生成顺序以 0..n-1 占位索引返回
     *   （回退路径无真实槽位语义，会话级覆盖不生效）
     *
     * 段取值规则（settings 非空时）：
     * - DATE → `yyyy年MM月dd日`（按 [captureTimeMillis]）
     * - SERIAL → 序号原值（无前缀；空白跳过）
     * - BORROWER → row.borrower（空白跳过）
     * - ADDRESS → 定位成功显示位置地址（空白回退「未知位置」），失败显示「定位失败」
     * - PROPERTY_TYPE / REMARK → 对应字段（空白跳过）
     * - LATLNG → `%.6f,%.6f` 或「定位失败」
     * - CUSTOM → customText（空白跳过）
     * - OFF → 跳过
     *
     * @param config 水印配置（segmentSettings 决定走新段配置路径还是旧开关路径）
     * @param captureTimeMillis 拍照时间毫秒（DATE 段取值来源）
     * @param location 位置结果；为 null 时 ADDRESS/LATLNG 段显示「定位失败」
     * @param row 当前主客户行（SERIAL/BORROWER/PROPERTY_TYPE/REMARK 段取值来源，可为 null）
     * @return 槽位索引 to 行文本；全部段跳过时返回 emptyList()（调用方据此不绘制水印块）
     */
    fun buildSegments(
        config: WatermarkConfig,
        captureTimeMillis: Long,
        location: LocationResult?,
        row: CustomerRow?,
    ): List<Pair<Int, String>> {
        val settings = config.segmentSettings ?: return buildSegments(
            captureTimeMillis,
            location,
            row?.serial?.takeIf { it.isNotBlank() },
            config.showDate,
            config.showSerial,
            config.showAddress,
            config.showLatlng,
        ).mapIndexed { index, line -> index to line }

        val dateText = Instant.ofEpochMilli(captureTimeMillis)
            .atZone(ZoneId.systemDefault())
            .format(dateFormatter)
        val lines = mutableListOf<Pair<Int, String>>()
        settings.forEachIndexed { index, setting ->
            val line = when (setting.source) {
                WatermarkSource.DATE -> dateText
                WatermarkSource.SERIAL -> row?.serial?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
                WatermarkSource.BORROWER -> row?.borrower?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
                WatermarkSource.ADDRESS -> location?.address?.ifBlank { "未知位置" } ?: "定位失败"
                WatermarkSource.PROPERTY_TYPE -> row?.propertyType?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
                WatermarkSource.REMARK -> row?.remark?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
                WatermarkSource.LATLNG -> if (location != null) {
                    "%.6f,%.6f".format(Locale.US, location.lat, location.lng)
                } else {
                    "定位失败"
                }
                WatermarkSource.CUSTOM -> setting.customText.trim().takeIf { it.isNotEmpty() } ?: return@forEachIndexed
                WatermarkSource.OFF -> return@forEachIndexed
            }
            lines += index to line
        }
        return lines
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
     * 按槽位 key 应用会话级覆盖（绘制前单次应用）。
     *
     * [WatermarkConfig.slotLines] 非 null（新段配置化路径）时，`overrides["slot_N"]`
     * （N 为槽位索引，值非空白）替换对应槽位行文本；为 null（旧 4 开关路径）时
     * 忽略 overrides，直接返回 [WatermarkConfig.segments]。
     */
    private fun applySlotOverrides(
        config: WatermarkConfig,
        overrides: Map<String, String>,
    ): List<String> {
        val slotLines = config.slotLines ?: return config.segments
        if (overrides.isEmpty()) return slotLines.map { it.second }
        return slotLines.map { (slot, line) ->
            overrides["slot_$slot"]?.takeIf { it.isNotBlank() } ?: line
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
                targetExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            }

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
        const val SERIAL_PREFIX = "序号:" // 旧 4 开关路径序号段前缀
    }
}
