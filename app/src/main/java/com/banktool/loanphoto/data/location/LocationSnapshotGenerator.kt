package com.banktool.loanphoto.data.location

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.banktool.loanphoto.domain.entity.CustomerRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 定位截图生成器：首拍时为指定客户行生成 3 张不同比例尺（z=15/16/17）的地图定位截图。
 *
 * 瓦片源两级回退：
 * 1. 高德免 key 瓦片（wprd01~04 轮询，style=7 矢量路网），坐标需 WGS84 → GCJ02 转换
 * 2. OSM 瓦片（User-Agent: LoanPhotoApp/4.2），使用原始 WGS84 坐标
 *
 * 每级整级任一瓦片下载失败即回退下一源；两级都失败抛异常，由调用方静默处理。
 *
 * 输出：768×768 JPEG（quality=85），文件名 `yyyyMMdd-<borrower截断>-定位截图-z{15|16|17}.jpg`，
 * 保存到各自 progressKey 的照片目录（不写 progress.json，不计入拍照进度/序号统计）。
 *
 * 所有网络与位图操作均在 [Dispatchers.IO] 执行。
 */
@Singleton
class LocationSnapshotGenerator @Inject constructor(
    baseClient: OkHttpClient,
) {

    /** 派生 client：共享连接池，仅收紧超时（瓦片请求要求 15s）。 */
    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** 高德瓦片主机轮询计数（wprd01~04）。 */
    private val amapHostCounter = AtomicInteger(0)

    /**
     * 为 [row] 生成 3 张定位截图（z=15/16/17）到 [outputDir]。
     *
     * @param row 客户行（水印块取 borrower / addrGeneral+addrDetail）
     * @param lat WGS84 纬度
     * @param lng WGS84 经度
     * @param outputDir 输出目录（通常为 photos/<progressKey>/）
     * @throws Exception 任一缩放级两级瓦片源都失败时抛出，由调用方静默记录
     */
    suspend fun generate(row: CustomerRow, lat: Double, lng: Double, outputDir: File) =
        withContext(Dispatchers.IO) {
            if (!outputDir.exists()) outputDir.mkdirs()
            for (z in ZOOM_LEVELS) {
                val bitmap = renderZoomLevel(row, lat, lng, z)
                val outFile = File(outputDir, buildFileName(row, z))
                FileOutputStream(outFile).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
                    fos.flush()
                }
                bitmap.recycle()
                Timber.i("定位截图已生成: %s", outFile.absolutePath)
            }
        }

    /**
     * 渲染单个缩放级：高德优先，任一瓦片失败整级回退 OSM；两级都失败抛异常。
     */
    private fun renderZoomLevel(row: CustomerRow, lat: Double, lng: Double, z: Int): Bitmap {
        // 高德使用 GCJ02 坐标
        val (gcjLat, gcjLng) = wgs84ToGcj02(lat, lng)
        try {
            return composeTiles(row, gcjLat, gcjLng, z, useAmap = true, wgsLat = lat, wgsLng = lng)
        } catch (e: Exception) {
            Timber.w(e, "高德瓦片获取失败 z=%d，回退 OSM", z)
        }
        // OSM 使用原始 WGS84 坐标
        return composeTiles(row, lat, lng, z, useAmap = false, wgsLat = lat, wgsLng = lng)
    }

    /**
     * 瓦片序号计算 + 3×3 下载拼接 + 中心标记 + 信息水印块。
     *
     * 瓦片序号（标准 Web Mercator）：
     * - n = 2^z
     * - x = (lon+180)/360 * n
     * - y = (1 - ln(tan(latRad) + sec(latRad)) / π) / 2 * n
     *
     * 取中心瓦片 (xt, yt)，下载 3×3（xt-1..xt+1, yt-1..yt+1），按行列拼成 768×768；
     * 拍照点精确像素位置由 x/y 的小数部分换算（中心瓦片内偏移 + 256px 中心瓦片起点）。
     */
    private fun composeTiles(
        row: CustomerRow,
        lat: Double,
        lng: Double,
        z: Int,
        useAmap: Boolean,
        wgsLat: Double,
        wgsLng: Double,
    ): Bitmap {
        val n = 2.0.pow(z)
        val latRad = Math.toRadians(lat)
        val xf = (lng + 180.0) / 360.0 * n
        val yf = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
        val xt = floor(xf).toInt()
        val yt = floor(yf).toInt()

        val bitmap = Bitmap.createBitmap(CANVAS_SIZE, CANVAS_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val maxTile = n.toInt() - 1

        // 3×3 下载拼接；y 越界（极地，仅超高纬度出现）跳过该格留白
        for (dy in -1..1) {
            for (dx in -1..1) {
                val tx = xt + dx
                val ty = yt + dy
                if (ty < 0 || ty > maxTile) continue
                val tile = downloadTile(tx, ty, z, useAmap)
                    ?: throw IllegalStateException("瓦片下载失败: z=$z x=$tx y=$ty amap=$useAmap")
                canvas.drawBitmap(tile, (dx + 1) * TILE_SIZE.toFloat(), (dy + 1) * TILE_SIZE.toFloat(), null)
                if (tile != bitmap) tile.recycle()
            }
        }

        // 中心（拍照点）精确像素：中心瓦片起点 256px + 瓦片内小数偏移
        val cx = (TILE_SIZE + (xf - xt) * TILE_SIZE).toFloat()
        val cy = (TILE_SIZE + (yf - yt) * TILE_SIZE).toFloat()
        drawCenterMarker(canvas, cx, cy)
        drawInfoBlock(canvas, row, wgsLat, wgsLng)
        return bitmap
    }

    /** 下载单个瓦片；失败（网络/解码/空数据）返回 null。 */
    private fun downloadTile(x: Int, y: Int, z: Int, useAmap: Boolean): Bitmap? {
        val url = if (useAmap) {
            val host = "wprd0${1 + (amapHostCounter.incrementAndGet() % AMAP_HOST_COUNT)}"
            "https://$host.is.autonavi.com/appmaptile?x=$x&y=$y&z=$z&lang=zh_cn&size=1&scl=1&style=7"
        } else {
            "https://tile.openstreetmap.org/$z/$x/$y.png"
        }
        val requestBuilder = Request.Builder().url(url).get()
        if (!useAmap) {
            // OSM 瓦片政策要求自定义 User-Agent
            requestBuilder.header("User-Agent", OSM_USER_AGENT)
        }
        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                if (bytes.isEmpty()) return null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (e: Exception) {
            Timber.w(e, "瓦片请求异常: %s", url)
            null
        }
    }

    /** 中心位置标记：红色实心圆（r=12）+ 白色描边 + 白色十字线。 */
    private fun drawCenterMarker(canvas: Canvas, cx: Float, cy: Float) {
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        // 十字线（超出圆圈，长度 r+18）
        val arm = MARKER_RADIUS + 18f
        canvas.drawLine(cx - arm, cy, cx - MARKER_RADIUS * 0.4f, cy, crossPaint)
        canvas.drawLine(cx + MARKER_RADIUS * 0.4f, cy, cx + arm, cy, crossPaint)
        canvas.drawLine(cx, cy - arm, cx, cy - MARKER_RADIUS * 0.4f, crossPaint)
        canvas.drawLine(cx, cy + MARKER_RADIUS * 0.4f, cx, cy + arm, crossPaint)
        // 实心圆 + 白描边
        canvas.drawCircle(cx, cy, MARKER_RADIUS, fillPaint)
        canvas.drawCircle(cx, cy, MARKER_RADIUS, strokePaint)
    }

    /**
     * 底部信息水印块：半透明黑色圆角矩形 + 白字 4 行
     * （日期时间 / 客户名 / 地址 / WGS84 经纬度 6 位小数）。
     */
    private fun drawInfoBlock(canvas: Canvas, row: CustomerRow, wgsLat: Double, wgsLng: Double) {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = INFO_TEXT_SIZE
            typeface = Typeface.DEFAULT_BOLD
        }
        val lines = buildList {
            add(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            add(row.borrower.take(BORROWER_MAX_CHARS).ifBlank { "未知客户" })
            add(ellipsize(row.addrGeneral + row.addrDetail, textPaint))
            add("%.6f,%.6f".format(Locale.US, wgsLat, wgsLng))
        }
        val blockWidth = CANVAS_SIZE - INFO_MARGIN * 2
        val blockHeight = lines.size * INFO_LINE_HEIGHT + INFO_PADDING * 2
        val left = INFO_MARGIN.toFloat()
        val top = (CANVAS_SIZE - blockHeight - INFO_BOTTOM_GAP).toFloat()

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            alpha = INFO_BG_ALPHA
        }
        canvas.drawRoundRect(
            left, top, left + blockWidth, top + blockHeight,
            INFO_CORNER_RADIUS, INFO_CORNER_RADIUS, bgPaint,
        )
        lines.forEachIndexed { index, line ->
            val textY = top + INFO_PADDING + INFO_TEXT_SIZE + index * INFO_LINE_HEIGHT
            canvas.drawText(line, left + INFO_PADDING, textY, textPaint)
        }
    }

    /** 超宽文本按 [paint] 测量截断到水印块可用宽度，追加省略号。 */
    private fun ellipsize(text: String, paint: Paint): String {
        val maxWidth = CANVAS_SIZE - INFO_MARGIN * 2 - INFO_PADDING * 2
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && paint.measureText(text.take(end) + "…") > maxWidth) {
            end--
        }
        return text.take(end) + "…"
    }

    /**
     * 文件名：`yyyyMMdd-<borrower截断>-定位截图-z{z}.jpg`
     * - 非法字符 `/ \ : * ? " < > |` 替换为 `_`（与项目 NamingRuleGenerator 一致）
     * - UTF-8 总字节数 ≤ 200，超长时逐字符缩短 borrower 段
     */
    private fun buildFileName(row: CustomerRow, z: Int): String {
        val date = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        var borrower = sanitize(row.borrower.take(BORROWER_MAX_CHARS))
        var name = "$date-${borrower}-定位截图-z$z.jpg"
        while (name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) {
            if (borrower.isEmpty()) break
            borrower = borrower.dropLast(1)
            name = "$date-${borrower}-定位截图-z$z.jpg"
        }
        return name
    }

    /** 文件名非法字符替换为 `_`（参照 NamingRuleGenerator.sanitize）。 */
    private fun sanitize(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "_")

    // ---- WGS84 → GCJ02（国测局标准算法） ----

    private fun wgs84ToGcj02(lat: Double, lng: Double): Pair<Double, Double> {
        // 中国境外无偏移，直接返回原坐标
        if (lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271) {
            return lat to lng
        }
        val dLat = transformAxis(lng - 105.0, lat - 35.0)
        val dLng = transformAxis(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - GCJ_EE * magic * magic
        val sqrtMagic = sqrt(magic)
        val adjLat = (dLat * 180.0) / ((GCJ_A * (1 - GCJ_EE)) / (magic * sqrtMagic) * PI)
        val adjLng = (dLng * 180.0) / (GCJ_A / sqrtMagic * cos(radLat) * PI)
        return (lat + adjLat) to (lng + adjLng)
    }

    /** 国测局偏移多项式（lat/lng 两方向公式相同，仅入参含义不同）。 */
    private fun transformAxis(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private companion object {
        val ZOOM_LEVELS = intArrayOf(15, 16, 17)
        const val CANVAS_SIZE = 768
        const val TILE_SIZE = 256
        const val TIMEOUT_SECONDS = 15L
        const val JPEG_QUALITY = 85
        const val AMAP_HOST_COUNT = 4
        const val OSM_USER_AGENT = "LoanPhotoApp/4.2 (Android)"
        const val MARKER_RADIUS = 12f
        const val BORROWER_MAX_CHARS = 30
        const val MAX_NAME_BYTES = 200
        const val INFO_TEXT_SIZE = 30f
        const val INFO_LINE_HEIGHT = 44
        const val INFO_PADDING = 22
        const val INFO_MARGIN = 20
        const val INFO_BOTTOM_GAP = 20
        const val INFO_CORNER_RADIUS = 18f
        const val INFO_BG_ALPHA = 150
        // GCJ02 椭球参数（克拉索夫斯基椭球 1940）
        const val GCJ_A = 6378245.0
        const val GCJ_EE = 0.00669342162296594323
    }
}
