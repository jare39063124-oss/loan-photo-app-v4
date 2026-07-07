package com.banktool.loanphoto.data.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 缩略图生成器。
 *
 * - 最长边 480px 等比缩放
 * - JPEG quality=70
 * - 输出路径: `getExternalFilesDir()/thumbnails/<progressKey>/<basename>.jpg`
 * - 使用 [BitmapFactory.Options.inSampleSize] 控制内存占用
 * - 在 IO Dispatcher 执行
 */
@Singleton
class ThumbnailGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * 生成缩略图。
     *
     * @param sourcePath 源图片绝对路径
     * @param progressKey 客户进度键（用于归档到独立子目录）
     * @return 缩略图绝对路径；源文件不存在或解码失败时返回 null
     */
    suspend fun generateThumbnail(
        sourcePath: String,
        progressKey: String,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val sourceFile = File(sourcePath)
            if (!sourceFile.exists()) {
                Timber.w("ThumbnailGenerator 源文件不存在: %s", sourcePath)
                return@withContext null
            }

            // 1. 仅解码尺寸
            val boundsOpts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(sourcePath, boundsOpts)
            val srcW = boundsOpts.outWidth
            val srcH = boundsOpts.outHeight
            if (srcW <= 0 || srcH <= 0) {
                Timber.w("ThumbnailGenerator 解码尺寸失败: %s", sourcePath)
                return@withContext null
            }

            // 2. 计算 inSampleSize：使最长边解码后仍 >= THUMBNAIL_MAX_EDGE，
            //    避免反复降采样后再精确缩放。
            val longestEdge = maxOf(srcW, srcH)
            val sampleSize = computeInSampleSize(longestEdge, THUMBNAIL_MAX_EDGE)

            // 3. 真正解码（带采样）
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val sampled = BitmapFactory.decodeFile(sourcePath, decodeOpts) ?: run {
                Timber.w("ThumbnailGenerator 解码失败: %s", sourcePath)
                return@withContext null
            }

            // 4. 精确缩放到 480px 最长边
            val scaled = scaleToLongestEdge(sampled, THUMBNAIL_MAX_EDGE)
            if (scaled !== sampled) sampled.recycle()

            // 5. 写出
            val outDir = File(
                context.getExternalFilesDir(null),
                "${THUMBNAIL_DIR_NAME}/${sanitizeKey(progressKey)}",
            )
            if (!outDir.exists()) outDir.mkdirs()

            val baseName = sourceFile.nameWithoutExtension.ifBlank { "thumb_${System.currentTimeMillis()}" }
            val outFile = File(outDir, "$baseName.jpg")
            FileOutputStream(outFile).use { fos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
                fos.flush()
            }
            scaled.recycle()

            outFile.absolutePath
        } catch (e: Exception) {
            Timber.e(e, "ThumbnailGenerator 生成失败: %s", sourcePath)
            null
        }
    }

    /**
     * 计算 inSampleSize：使采样后最长边不小于目标边（避免过度缩小后糊）。
     */
    private fun computeInSampleSize(longestEdge: Int, targetEdge: Int): Int {
        if (longestEdge <= targetEdge) return 1
        var sample = 1
        while (longestEdge / sample > targetEdge * 2) {
            sample *= 2
        }
        return sample
    }

    /** 等比缩放使最长边 = [targetEdge]；原图已小于目标时返回原图。 */
    private fun scaleToLongestEdge(src: Bitmap, targetEdge: Int): Bitmap {
        val w = src.width
        val h = src.height
        val longest = maxOf(w, h)
        if (longest <= targetEdge) return src
        val scale = targetEdge.toFloat() / longest.toFloat()
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, newW, newH, true)
    }

    /** progressKey 用于子目录名，过滤非法字符。 */
    private fun sanitizeKey(key: String): String =
        key.ifBlank { "default" }.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private companion object {
        const val THUMBNAIL_MAX_EDGE = 480
        const val THUMBNAIL_DIR_NAME = "thumbnails"
        const val JPEG_QUALITY = 70
    }
}
