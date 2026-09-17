package com.banktool.loanphoto.data.log

import android.content.Context
import android.util.Log
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件日志树。
 *
 * 将所有 Timber 日志同步写入应用内部存储 `filesDir/logs/app.log`，
 * 供问题诊断与崩溃复现分析使用。日志单文件超过 5MB 时自动滚动
 * （删除后重建），避免无限增长。
 *
 * 写入失败一律静默吞掉，绝不能让日志逻辑反向影响业务。
 *
 * 使用方式：在 Application 中 `Timber.plant(FileLoggingTree(this))`。
 */
class FileLoggingTree(private val context: Context) : Timber.Tree() {

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val file = logFile(context)
        // 单文件超 5MB 直接滚动重建，防止无限增长撑爆存储
        if (file.exists() && file.length() > MAX_LOG_FILE_BYTES) {
            file.delete()
        }
        val now = dateFormatter.format(Date())
        val level = levelName(priority)
        val thread = Thread.currentThread().name
        val line = buildString {
            append(now).append(SEPARATOR)
            append(level).append(SEPARATOR)
            append('[').append(thread).append(']').append(SEPARATOR)
            append(tag ?: "").append(": ").append(message)
            if (t != null) {
                append('\n').append(Log.getStackTraceString(t))
            }
        }
        writeSafely(file, line)
    }

    @Synchronized
    private fun writeSafely(file: File, line: String) {
        var writer: FileWriter? = null
        try {
            writer = FileWriter(file, true)
            writer.write(line)
            writer.write("\n")
            writer.flush()
        } catch (_: Throwable) {
        } finally {
            try {
                writer?.close()
            } catch (_: Throwable) {
                // 同样静默
            }
        }
    }

    /** 将 [Log] 的 priority 常量映射为可读字符串。未知级别回退为数字。 */
    private fun levelName(priority: Int): String = when (priority) {
        Log.VERBOSE -> "VERBOSE"
        Log.DEBUG -> "DEBUG"
        Log.INFO -> "INFO"
        Log.WARN -> "WARN"
        Log.ERROR -> "ERROR"
        Log.ASSERT -> "ASSERT"
        else -> priority.toString()
    }

    companion object {
        /** 单日志文件大小上限（5MB），超过即滚动重建。 */
        private const val MAX_LOG_FILE_BYTES = 5L * 1024 * 1024

        private const val SEPARATOR = "  "

        private val dateFormatter =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

        /**
         * 返回日志文件路径：`filesDir/logs/app.log`。
         *
         * 调用即保证父目录存在（`mkdirs()`），供 [CrashLogger] 等其它模块复用。
         */
        fun logFile(context: Context): File {
            val dir = File(context.filesDir, "logs").apply { mkdirs() }
            return File(dir, "app.log")
        }
    }
}
