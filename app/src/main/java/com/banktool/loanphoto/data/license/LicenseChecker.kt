package com.banktool.loanphoto.data.license

import android.content.Context
import android.os.Build
import android.provider.Settings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 授权校验器。
 *
 * 完整版仅保留签名校验（见 SecurityChecker），授权恒通过：
 * 无设备绑定、无到期限制，所有安装均视为已授权。
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

    /** 完整版恒授权。 */
    fun isAuthorized(context: Context): Boolean = true

    /** 完整版无到期限制，恒为 false。 */
    fun isExpired(): Boolean = false

    /** 完整版无设备绑定，恒为 true。 */
    fun isDeviceMatched(context: Context): Boolean = true
}
