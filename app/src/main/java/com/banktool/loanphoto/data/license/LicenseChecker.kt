package com.banktool.loanphoto.data.license

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.banktool.loanphoto.BuildConfig
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 授权校验器。
 *
 * 基于 BuildConfig 中的 flavor 配置（trial / full）判断授权状态：
 * - full 版：始终授权，无设备/到期限制。
 * - trial 版：需校验「SHA-256(设备 Android ID) == [BuildConfig.LICENSED_DEVICE_ID_HASH]」且未过期
 *   （[BuildConfig.EXPIRY_DATE]）。LICENSED_DEVICE_ID_HASH 为空串时视为未配置授权设备，
 *   即所有 trial 安装均判定为「未授权」，引导用户将设备识别码告知作者激活。
 *
 * 设备识别码使用 [Settings.Secure.ANDROID_ID]：应用签名不变时同设备返回值稳定，
 * 且无需申请运行时权限，可在主线程同步调用。设备 ID 在比较前做 SHA-256，避免明文写入 BuildConfig。
 */
@Singleton
class LicenseChecker @Inject constructor() {

    /**
     * 获取设备识别码（Android ID）。
     *
     * 应用未卸载重装、签名不变时返回值稳定；恢复出厂设置会改变。
     * 极少数设备可能返回 null，此时降级为 "unknown"。
     */
    fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        ) ?: "unknown"
    }

    /**
     * 获取设备信息（品牌 + 型号），用于在锁定页与设置页展示。
     */
    fun getDeviceInfo(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL}"
    }

    /** 是否为体验版（trial flavor）。 */
    fun isTrial(): Boolean = BuildConfig.IS_TRIAL

    /**
     * 是否已过期。full 版恒为 false；trial 版按 [BuildConfig.EXPIRY_DATE] 比较。
     * 日期解析失败时返回 false（容错，避免阻塞用户）。
     */
    fun isExpired(): Boolean {
        if (!BuildConfig.IS_TRIAL) return false
        return try {
            val expiry = LocalDate.parse(
                BuildConfig.EXPIRY_DATE,
                DateTimeFormatter.ISO_LOCAL_DATE,
            )
            LocalDate.now() >= expiry
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 当前设备是否为已授权设备。full 版恒为 true；trial 版需
     * SHA-256(设备 Android ID) 匹配 [BuildConfig.LICENSED_DEVICE_ID_HASH]，未配置（空串）时返回 false。
     */
    fun isDeviceMatched(context: Context): Boolean {
        if (!BuildConfig.IS_TRIAL) return true
        val licensedHash = BuildConfig.LICENSED_DEVICE_ID_HASH
        if (licensedHash.isBlank()) return false  // 未配置授权设备
        return sha256(getDeviceId(context)) == licensedHash
    }

    /**
     * 综合授权判断：full 版直接通过；trial 版需「设备匹配 && 未过期」。
     */
    fun isAuthorized(context: Context): Boolean {
        if (!BuildConfig.IS_TRIAL) return true
        return isDeviceMatched(context) && !isExpired()
    }

    /** 返回体验版到期日期字符串（ISO 格式，如 2026-12-31）。 */
    fun getExpiryDate(): String = BuildConfig.EXPIRY_DATE
}

/** SHA-256 哈希，返回小写 hex 字符串。空输入返回空串。 */
private fun sha256(input: String): String {
    if (input.isEmpty()) return ""
    val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}
