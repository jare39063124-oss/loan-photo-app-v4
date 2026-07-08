package com.banktool.loanphoto.data.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Debug
import com.banktool.loanphoto.BuildConfig
import timber.log.Timber
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用完整性校验器。
 *
 * 在应用启动时执行多重校验，防止重打包 / 调试器附加 / Frida 注入：
 * - 签名证书 SHA-256 比对（防重打包，EXPECTED_SIGNING_HASH 为空时跳过，兼容 debug）
 * - 调试器检测（Debug.isDebuggerConnected + /proc/self/status TracerPid）
 * - Frida 端口探测（27042）
 *
 * 任一失败返回 false，由调用方决定显示 LockScreen。不抛异常、不崩溃。
 */
@Singleton
class SecurityChecker @Inject constructor() {

    @Volatile
    private var failReason: String = ""

    /**
     * 执行完整性校验。全部通过返回 true，任一失败返回 false。
     * EXPECTED_SIGNING_HASH 为空串（debug 构建 / 无 keystore）时跳过签名校验。
     */
    fun verify(context: Context): Boolean {
        failReason = ""

        // 1. 签名证书校验
        val expectedHash = BuildConfig.EXPECTED_SIGNING_HASH
        if (expectedHash.isNotEmpty()) {
            val actualHash = getSigningCertSha256(context)
            if (actualHash != expectedHash) {
                failReason = "签名校验失败"
                Timber.w("Security: signature mismatch (expected=%s, actual=%s)", expectedHash, actualHash)
                return false
            }
        }

        // 2. 调试器检测
        if (Debug.isDebuggerConnected()) {
            failReason = "检测到调试器附加"
            Timber.w("Security: debugger connected")
            return false
        }
        if (getTracerPid() != 0) {
            failReason = "检测到调试器附加"
            Timber.w("Security: TracerPid=%d", getTracerPid())
            return false
        }

        // 3. Frida 端口探测
        if (isFridaListening()) {
            failReason = "检测到注入工具"
            Timber.w("Security: frida detected on port 27042")
            return false
        }

        return true
    }

    /** 返回最近一次失败原因（verify 返回 false 后调用）。 */
    fun getFailReason(): String = failReason

    /** 获取当前 APK 签名证书的 SHA-256（小写 hex）。失败返回空串。 */
    private fun getSigningCertSha256(context: Context): String {
        return try {
            val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES,
                )
            }

            val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners ?: emptyArray()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures ?: emptyArray()
            }

            if (signatures.isEmpty()) return ""
            val digest = MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Timber.w(e, "Security: getSigningCertSha256 failed")
            ""
        }
    }

    /** 读取 /proc/self/status 的 TracerPid，非 0 表示被调试。失败返回 0。 */
    private fun getTracerPid(): Int {
        return try {
            File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("TracerPid:") }
                    ?.substringAfter(":")
                    ?.trim()
                    ?.toIntOrNull()
                    ?: 0
            }
        } catch (e: Exception) {
            0
        }
    }

    /** 探测 Frida 默认端口 27042 是否在监听。 */
    private fun isFridaListening(): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", 27042), 200)
                true  // 连接成功 = Frida 在监听
            }
        } catch (e: Exception) {
            false  // 连接失败 = 无 Frida
        }
    }
}
