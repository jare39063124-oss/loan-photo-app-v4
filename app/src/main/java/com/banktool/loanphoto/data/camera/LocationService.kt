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
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.OnCompleteListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
        result
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
    }

    private companion object {
        const val CACHE_TTL_MS = 5L * 60 * 1000 // 5 分钟
        const val LOCATION_TIMEOUT_MS = 15000L // 15 秒超时
    }
}
