package com.banktool.loanphoto.data.repository

import android.content.Context
import com.banktool.loanphoto.domain.repository.SpecialLogRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject

/**
 * 特殊日志仓库实现。
 *
 * 内容按 excelUriMd5（16 位 hex）存为 `special_logs/<md5>.txt` 纯文本，
 * 写入先落临时文件再原子替换，[Mutex] 保护并发读写。
 *
 * 实现与 [VisitNoteRepositoryImpl] 同构，仅目录与日志前缀不同。
 */
class SpecialLogRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpecialLogRepository {

    private val mutex = Mutex()

    private val dir: File
        get() = File(context.getExternalFilesDir(null), SPECIAL_LOG_DIR).also { if (!it.exists()) it.mkdirs() }

    override suspend fun getSpecialLog(excelUriMd5: String): String =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val f = logFile(excelUriMd5)
                if (!f.exists()) return@withLock ""
                try {
                    f.readText(CHARSET)
                } catch (e: Exception) {
                    Timber.w(e, "特殊日志读取失败: %s", excelUriMd5)
                    ""
                }
            }
        }

    override suspend fun saveSpecialLog(excelUriMd5: String, content: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                writeAtomic(excelUriMd5, content)
            }
        }

    /** 原子写：先写 .tmp，再 ATOMIC_MOVE 覆盖。 */
    private fun writeAtomic(excelUriMd5: String, content: String) {
        val target = logFile(excelUriMd5)
        val parent = target.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val tmp = File(parent, "${target.name}.tmp")
        try {
            tmp.writeText(content, CHARSET)
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: Exception) {
            Timber.e(e, "特殊日志原子写失败: %s", excelUriMd5)
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        }
    }

    private fun logFile(excelUriMd5: String): File =
        File(dir, "$excelUriMd5$TXT_SUFFIX")

    private companion object {
        const val SPECIAL_LOG_DIR = "special_logs"
        const val TXT_SUFFIX = ".txt"
        val CHARSET = Charsets.UTF_8
    }
}
