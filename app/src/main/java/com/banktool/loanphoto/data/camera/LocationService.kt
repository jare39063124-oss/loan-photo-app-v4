package com.banktool.loanphoto.data.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.OnCompleteListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 位置信息结果。
 *
 * - [lat] / [lng] 经纬度（WGS84）
 * - [address] 反向地理编码得到的地址字符串；失败时回退为 "纬度,经度" 格式
 * - [timestamp] 获取位置时的时间戳（毫秒）
 */
data class LocationResult(
    val lat: Double,
    val lng: Double,
    val address: String,
    val timestamp: Long,
)

private val Context.locationDataStore by preferencesDataStore(name = "location_cache")

private val KEY_LAT = floatPreferencesKey("lat")
private val KEY_LNG = floatPreferencesKey("lng")
private val KEY_ADDRESS = stringPreferencesKey("address")
private val KEY_TIMESTAMP = longPreferencesKey("timestamp")

/**
 * 位置服务封装：优先 [FusedLocationProviderClient]（GMS 可用时），否则回退 [LocationManager]。
 *
 * - 调用方需先确保已授予 ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION 权限
 * - 5 分钟位置缓存，避免每张照片都触发系统定位
 * - Geocoder 反向地理编码失败时回退为 "lat,lng" 字符串
 * - 定位失败时返回 null（不返回伪造坐标）；null 不缓存，下次调用会重试
 *
 * 注意：[getCurrentLocation] 标注 [SuppressLint] 是因为调用方已负责权限校验，
 * 此处仅做兜底检查。
 */
@Singleton
class LocationService @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val fusedClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    @Volatile
    private var cached: LocationResult? = null

    /**
     * 有界位置历史（最新在尾）。用于 getCurrentLocation 失败时兜底。
     *
     * - 容量上限 [MAX_HISTORY]，每次写入后 trimToSize
     * - 写入时同步清理年龄超过 [HISTORY_MAX_AGE_MS] 的过期条目
     * - 纯内存结构，不持久化，重启后为空
     */
    private val history: ArrayDeque<LocationResult> = ArrayDeque()

    /**
     * 服务级 CoroutineScope（用于 DataStore fire-and-forget 写入与 init 异步加载）。
     * SupervisorJob 保证单次写失败不会取消整个 scope。
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 从 DataStore 加载的最近一次持久化定位（init 时异步加载）。
     * 用于内存 history 为空（如进程重启）时的兜底。
     */
    @Volatile
    private var persistedLastLocation: LocationResult? = null

    init {
        // 异步加载持久化定位到内存（不阻塞构造，失败仅记录日志）
        serviceScope.launch {
            runCatching {
                val prefs = context.locationDataStore.data.first()
                val lat = prefs[KEY_LAT]
                val lng = prefs[KEY_LNG]
                if (lat != null && lng != null) {
                    val address = prefs[KEY_ADDRESS] ?: ""
                    val timestamp = prefs[KEY_TIMESTAMP] ?: 0L
                    persistedLastLocation = LocationResult(
                        lat = lat.toDouble(),
                        lng = lng.toDouble(),
                        address = address,
                        timestamp = timestamp,
                    )
                }
            }.onFailure { Timber.w(it, "加载持久化位置失败") }
        }
    }

    /**
     * 获取当前位置。优先返回缓存（5 分钟内）。
     *
     * - GMS 可用时走 [FusedLocationProviderClient]
     * - GMS 不可用时走 [LocationManager] 兜底
     * - 无权限/超时/异常时返回 null（不返回伪造坐标）
     * - null 不缓存，下次调用会重试
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): LocationResult? = withContext(Dispatchers.IO) {
        cached?.let { c ->
            val age = System.currentTimeMillis() - c.timestamp
            if (age in 0..CACHE_TTL_MS) {
                Timber.d("LocationService 使用缓存位置 age=%dms", age)
                return@withContext c
            }
        }

        if (!hasLocationPermission()) {
            Timber.w("LocationService 无位置权限，返回 null")
            return@withContext null
        }

        val location: Location? = try {
            withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
                if (isGmsAvailable()) {
                    Timber.d("LocationService 使用 GMS FusedLocation")
                    awaitLocation()
                } else {
                    Timber.d("LocationService GMS 不可用，使用 LocationManager 兜底")
                    awaitLocationViaLocationManager()
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "LocationService 获取位置失败")
            null
        }

        if (location == null) {
            Timber.w("LocationService 位置获取为 null（超时或无可用 provider），不缓存")
            return@withContext null
        }

        val addr = reverseGeocode(location.latitude, location.longitude)
        val result = LocationResult(
            lat = location.latitude,
            lng = location.longitude,
            address = addr,
            timestamp = System.currentTimeMillis(),
        )
        cached = result
        recordHistory(result)
        result
    }

    /**
     * 记录一条定位到历史，并清理过期/超量条目。
     * 必须在 synchronized(history) 块内调用或由单一调用方调用（getCurrentLocation 在 Dispatchers.IO）。
     */
    private fun recordHistory(result: LocationResult) {
        val now = System.currentTimeMillis()
        synchronized(history) {
            history.removeAll { now - it.timestamp > HISTORY_MAX_AGE_MS }
            history.addLast(result)
            while (history.size > MAX_HISTORY) {
                history.removeFirst()
            }
        }
        // 同步写入 DataStore（fire-and-forget），供进程重启后兜底
        serviceScope.launch {
            runCatching {
                context.locationDataStore.edit { prefs ->
                    prefs[KEY_LAT] = result.lat.toFloat()
                    prefs[KEY_LNG] = result.lng.toFloat()
                    prefs[KEY_ADDRESS] = result.address
                    prefs[KEY_TIMESTAMP] = result.timestamp
                }
            }.onFailure { Timber.w(it, "持久化位置到 DataStore 失败") }
        }
    }

    /**
     * 返回历史中最新一条且年龄 ≤ [maxAgeMs] 的定位；无则返回 null。
     *
     * 用于 [getCurrentLocation] 失败（如地下无信号）时的兜底：调用方可用最近一次有效定位
     * 代替 null，避免水印显示「定位失败」、GPS EXIF 缺失。
     *
     * @param maxAgeMs 最大可接受年龄（毫秒），拍照兜底建议 10_000（10 秒）
     */
    suspend fun getRecentWithin(maxAgeMs: Long): LocationResult? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val memHit: LocationResult? = synchronized(history) {
            var found: LocationResult? = null
            for (i in history.size - 1 downTo 0) {
                val r = history[i]
                val age = now - r.timestamp
                if (age in 0..maxAgeMs) {
                    found = r
                    break
                }
            }
            found
        }
        if (memHit != null) return@withContext memHit
        persistedLastLocation?.let { p ->
            val age = now - p.timestamp
            if (age in 0..maxAgeMs) {
                Timber.d("LocationService 使用持久化定位兜底 age=%dms", age)
                return@withContext p
            }
        }
        null
    }

    /**
     * 返回最近一次已知定位（不限年龄），依次尝试：
     * 1. [cached]：5 分钟内（[CACHE_TTL_MS]）的内存缓存
     * 2. [history]：30 秒内（[HISTORY_MAX_AGE_MS]）的内存历史（复用 [getRecentWithin]）
     * 3. [persistedLastLocation]：DataStore 持久化的最近一次定位（不限年龄，进程重启后兜底）
     *
     * 与 [getRecentWithin] 的区别：本方法不限制持久化定位的年龄，用于"宁可返回旧定位也不返回 null"
     * 的最终兜底场景（如拍照水印必须有一份定位数据）。
     *
     * @return 最近一次已知定位；三者均无时返回 null
     */
    suspend fun getLastKnownLocation(): LocationResult? = withContext(Dispatchers.IO) {
        cached?.let { c ->
            val age = System.currentTimeMillis() - c.timestamp
            if (age in 0..CACHE_TTL_MS) {
                Timber.d("LocationService.getLastKnownLocation 使用缓存 age=%dms", age)
                return@withContext c
            }
        }
        getRecentWithin(HISTORY_MAX_AGE_MS)?.let { return@withContext it }
        persistedLastLocation?.let { p ->
            val age = System.currentTimeMillis() - p.timestamp
            Timber.d("LocationService.getLastKnownLocation 使用持久化定位兜底 age=%dms", age)
            return@withContext p
        }
        null
    }

    /**
     * 反向地理编码：lat/lng -> 地址字符串。
     *
     * Android 13+ 推荐使用 [Geocoder.getFromLocation] 的异步回调；旧版本使用同步阻塞。
     * 任何异常都回退为 "lat,lng" 字符串。
     */
    @Suppress("DEPRECATION")
    private suspend fun reverseGeocode(lat: Double, lng: Double): String =
        withContext(Dispatchers.IO) {
            if (!Geocoder.isPresent()) {
                return@withContext formatLatlng(lat, lng)
            }
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(lat, lng, 1) { list ->
                        if (cont.isActive) cont.resume(list)
                    }
                }
            } else {
                runCatching { geocoder.getFromLocation(lat, lng, 1) }.getOrDefault(emptyList())
            }
            val first = addresses?.firstOrNull()
            first?.getAddressLine(0)?.takeIf { it.isNotBlank() }
                ?: formatLatlng(lat, lng)
        }

    /** 检查 Google Play Services 是否可用。 */
    private fun isGmsAvailable(): Boolean {
        return GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }

    /**
     * 把 FusedLocation 的 Task 转为 suspend。
     *
     * 不依赖 kotlinx-coroutines-play-services，使用 [suspendCancellableCoroutine] +
     * [OnCompleteListener] 实现。
     */
    @SuppressLint("MissingPermission")
    private suspend fun awaitLocation(): Location? = suspendCancellableCoroutine { cont ->
        val task = fusedClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            null,
        )
        task.addOnCompleteListener(OnCompleteListener { t ->
            if (cont.isActive) {
                cont.resume(if (t.isSuccessful) t.result else null)
            }
        })
    }

    /**
     * LocationManager 兜底定位（GMS 不可用时使用）。
     *
     * - 优先 GPS_PROVIDER，其次 NETWORK_PROVIDER
     * - 单次更新，获取到位置后立即移除监听
     * - 无可用 provider 或 SecurityException 时返回 null
     */
    @SuppressLint("MissingPermission")
    private suspend fun awaitLocationViaLocationManager(): Location? = withContext(Dispatchers.IO) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { lm.isProviderEnabled(it) }
        if (providers.isEmpty()) return@withContext null
        suspendCancellableCoroutine { cont ->
            val provider = providers.first()
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    lm.removeUpdates(this)
                    if (cont.isActive) cont.resume(location)
                }
                override fun onProviderDisabled(provider: String) {}
                override fun onProviderEnabled(provider: String) {}
                @Suppress("DEPRECATION")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            try {
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            } catch (e: SecurityException) {
                if (cont.isActive) cont.resume(null)
            }
            cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
        }
    }

    private fun formatLatlng(lat: Double, lng: Double): String =
        "%.6f,%.6f".format(Locale.US, lat, lng)

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /** 清除位置缓存（例如用户手动刷新场景）。 */
    fun clearCache() {
        cached = null
        synchronized(history) { history.clear() }
    }

    private companion object {
        const val CACHE_TTL_MS = 5L * 60 * 1000 // 5 分钟
        const val LOCATION_TIMEOUT_MS = 15000L // 15 秒超时
        const val MAX_HISTORY = 5 // 位置历史容量上限
        const val HISTORY_MAX_AGE_MS = 30_000L // 历史最大年龄 30 秒，超过则清理（>10s 兜底窗口）
    }
}
