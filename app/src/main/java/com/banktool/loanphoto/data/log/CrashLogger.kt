package com.banktool.loanphoto.data.log

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃捕获器。
 *
 * 在 Application 启动时调用 [install] 即可接管 [Thread.getDefaultUncaughtExceptionHandler]，
 * 将未处理异常 / Error（含 OOM、[NoClassDefFoundError] 等）写入与 [FileLoggingTree]
 * 相同的 `app.log`，再委托默认 handler 让进程正常退出（弹 FC 框）。
 *
 * 设计约束：
 * - 写入逻辑必须用 try/catch 包裹，**绝不再抛**（崩溃处理器自身崩溃会陷入死循环）。
 * - [Throwable] 已涵盖 Error，无需对 Error 特殊处理。
 * - 幂等：重复 [install] 安全；[uninstall] 后可再次 [install]。
 */
object CrashLogger {

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var installed = false

    val isInstalled: Boolean
        get() = installed

    private lateinit var appContext: Context

    /**
     * 安装全局未捕获异常处理器。重复调用安全（幂等）。
     */
    fun install(context: Context) {
        if (installed) return
        appContext = context.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler())
        installed = true
    }

    /**
     * 卸载并恢复默认 handler。未安装时安全。
     */
    fun uninstall() {
        if (!installed) return
        Thread.setDefaultUncaughtExceptionHandler(defaultHandler)
        defaultHandler = null
        installed = false
    }

    /**
     * 实际的异常处理器。
     *
     * 写入崩溃块后委托 [defaultHandler]，由系统弹 FC 框并退出进程。
     */
    private class CrashHandler : Thread.UncaughtExceptionHandler {

        override fun uncaughtException(t: Thread, e: Throwable) {
            // 同步写入 app.log —— 任何异常都吞掉，避免崩溃处理器自身再次抛出
            try {
                val file = FileLoggingTree.logFile(appContext)
                file.parentFile?.mkdirs()
                val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                    .format(Date())
                val version = readVersionInfo()
                PrintWriter(FileWriter(file, true)).use { pw ->
                    pw.println()
                    pw.println("=== CRASH $now  version=$version  thread=${t.name} ===")
                    e.printStackTrace(pw)
                    var cause: Throwable? = e.cause
                    while (cause != null) {
                        pw.println("Caused by: $cause")
                        cause.printStackTrace(pw)
                        cause = cause.cause
                    }
                    pw.println("=== END CRASH ===")
                    pw.flush()
                }
            } catch (_: Throwable) {
                // 绝不再抛
            }
            // 委托默认 handler 让进程正常退出（弹 FC 框）
            defaultHandler?.uncaughtException(t, e)
        }

        /**
         * 读取版本信息：`versionName (vc=versionCode)`。
         *
         * 项目 minSdk = 26，[android.content.pm.PackageInfo.longVersionCode] 为 API 28+，
         * 故使用 `@Suppress("DEPRECATION")` 的 [android.content.pm.PackageInfo.versionCode] 兼容。
         */
        @Suppress("DEPRECATION")
        private fun readVersionInfo(): String {
            return try {
                val info = appContext.packageManager
                    .getPackageInfo(appContext.packageName, 0)
                val name = info.versionName ?: ""
                val code = info.versionCode
                "$name(vc=$code)"
            } catch (_: Throwable) {
                // 崩溃处理器内部静默：不可再触发任何可能失败的调用
                ""
            }
        }
    }
}
