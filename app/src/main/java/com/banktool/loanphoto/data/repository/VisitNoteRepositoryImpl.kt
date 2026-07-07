package com.banktool.loanphoto.data.repository

import android.content.Context
import com.banktool.loanphoto.domain.repository.VisitNoteRepository
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
 * 走访备注仓库实现。
 *
 * - 文件: `visit_notes/<md5>.txt`（纯文本，原子写）
 * - key 为 excelUriMd5（16 位 hex），直接用作文件名
 * - [Mutex] 保护并发读写
 */
class VisitNoteRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : VisitNoteRepository {

    private val mutex = Mutex()

    private val dir: File
        get() = File(context.getExternalFilesDir(null), VISIT_NOTE_DIR).also { if (!it.exists()) it.mkdirs() }

    override suspend fun getVisitNote(excelUriMd5: String): String =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val f = noteFile(excelUriMd5)
                if (!f.exists()) return@withLock ""
                try {
                    f.readText(CHARSET)
                } catch (e: Exception) {
                    Timber.w(e, "走访备注读取失败: %s", excelUriMd5)
                    ""
                }
            }
        }

    override suspend fun saveVisitNote(excelUriMd5: String, content: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                writeAtomic(excelUriMd5, content)
            }
        }

    /** 原子写：先写 .tmp，再 ATOMIC_MOVE 覆盖。 */
    private fun writeAtomic(excelUriMd5: String, content: String) {
        val target = noteFile(excelUriMd5)
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
            Timber.e(e, "走访备注原子写失败: %s", excelUriMd5)
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        }
    }

    private fun noteFile(excelUriMd5: String): File =
        File(dir, "$excelUriMd5$TXT_SUFFIX")

    private companion object {
        const val VISIT_NOTE_DIR = "visit_notes"
        const val TXT_SUFFIX = ".txt"
        val CHARSET = Charsets.UTF_8
    }
}
