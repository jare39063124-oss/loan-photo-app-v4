package com.banktool.loanphoto.data.repository

import android.content.Context
import com.banktool.loanphoto.domain.repository.ExcelDataIndexRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
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
 * Excel 数据索引仓库实现。
 *
 * - 文件: `excel_data_index.json`
 * - 结构: `{ "<excel_uri_md5>": ["<progress_key>", ...] }`
 * - 原子写 + [Mutex] 保护
 */
class ExcelDataIndexRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : ExcelDataIndexRepository {

    private val moshi: Moshi = Moshi.Builder().build()

    @Suppress("ktlint:standard:property-naming")
    private val mapType = Types.newParameterizedType(
        Map::class.java,
        String::class.java,
        Types.newParameterizedType(List::class.java, String::class.java),
    )

    private val adapter = moshi.adapter<Map<String, List<String>>>(mapType).indent("  ")

    private val mutex = Mutex()

    private val file: File
        get() = File(context.getExternalFilesDir(null), INDEX_FILE_NAME)

    override suspend fun getProgressKeys(excelUriMd5: String): List<String> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val map = load()
                map[excelUriMd5] ?: emptyList()
            }
        }

    override suspend fun addProgressKey(excelUriMd5: String, key: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val map = load().toMutableMap()
                val list = (map[excelUriMd5] ?: emptyList()).toMutableList()
                if (key !in list) list.add(key)
                map[excelUriMd5] = list
                save(map)
            }
        }

    override suspend fun removeExcelData(excelUriMd5: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val map = load().toMutableMap()
                if (map.remove(excelUriMd5) != null) save(map)
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun load(): Map<String, List<String>> {
        if (!file.exists()) return emptyMap()
        return try {
            val json = file.readText(CHARSET)
            val parsed = adapter.fromJson(json)
            (parsed ?: emptyMap())
        } catch (e: Exception) {
            Timber.w(e, "excel_data_index.json 解析失败")
            emptyMap()
        }
    }

    private fun save(data: Map<String, List<String>>) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val tmp = File(parent, "$INDEX_FILE_NAME.tmp")
        try {
            tmp.writeText(adapter.toJson(data), CHARSET)
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: Exception) {
            Timber.e(e, "excel_data_index.json 原子写失败，退回重命名")
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }

    private companion object {
        const val INDEX_FILE_NAME = "excel_data_index.json"
        val CHARSET = Charsets.UTF_8
    }
}
