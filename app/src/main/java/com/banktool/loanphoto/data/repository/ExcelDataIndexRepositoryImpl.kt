package com.banktool.loanphoto.data.repository

import android.content.Context
import com.banktool.loanphoto.data.datasource.ColumnField
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
 * Excel 数据索引仓库实现，数据落盘于 `excel_data_index.json`。
 *
 * 新结构（每个 Excel 文件一个节点对象）：
 * ```json
 * {
 *   "<excel_uri_md5>": {
 *     "progress_keys": ["<progress_key>", ...],
 *     "column_map": ["SERIAL", "BORROWER", ...],
 *     "has_header": true
 *   }
 * }
 * ```
 * - `column_map` 为 [ColumnField] 枚举 name 列表（按物理列索引，IGNORE 保留）；
 *   未设置时省略该字段
 * - `has_header` 标记列映射对应文件的首行是否为表头（注入解析时决定起始行）
 *
 * 兼容旧结构 `{ "<excel_uri_md5>": ["<progress_key>", ...] }`：读取时归一化，
 * 下次写入统一转为新结构。写入采用原子替换并以 [Mutex] 保护并发。
 */
class ExcelDataIndexRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : ExcelDataIndexRepository {

    private val moshi: Moshi = Moshi.Builder().build()

    @Suppress("ktlint:standard:property-naming")
    private val mapType = Types.newParameterizedType(
        Map::class.java, String::class.java, Any::class.java,
    )

    private val adapter = moshi.adapter<Map<String, Any?>>(mapType).indent("  ")

    private val mutex = Mutex()

    private val file: File
        get() = File(context.getExternalFilesDir(null), INDEX_FILE_NAME)

    /** 单个 Excel 文件的索引节点（内存规范化模型）。 */
    private data class IndexNode(
        val progressKeys: List<String> = emptyList(),
        /** 列映射（枚举 name 列表，按物理列索引）；null=未设置。 */
        val columnMap: List<String>? = null,
        /** 列映射对应文件的首行是否为表头行。 */
        val hasHeader: Boolean = false,
    )

    override suspend fun getProgressKeys(excelUriMd5: String): List<String> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                load()[excelUriMd5]?.progressKeys ?: emptyList()
            }
        }

    override suspend fun getAllEntries(): Map<String, List<String>> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val all = load()
                val out = LinkedHashMap<String, List<String>>(all.size)
                for ((k, node) in all) out[k] = node.progressKeys
                out
            }
        }

    override suspend fun addProgressKey(excelUriMd5: String, key: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val nodes = load().toMutableMap()
                val node = nodes[excelUriMd5] ?: IndexNode()
                if (key !in node.progressKeys) {
                    nodes[excelUriMd5] = node.copy(progressKeys = node.progressKeys + key)
                    save(nodes)
                }
            }
        }

    override suspend fun removeExcelData(excelUriMd5: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val nodes = load().toMutableMap()
                if (nodes.remove(excelUriMd5) != null) save(nodes)
            }
        }

    override suspend fun getColumnMap(excelUriMd5: String): List<ColumnField>? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val names = load()[excelUriMd5]?.columnMap ?: return@withLock null
                names.mapNotNull { name ->
                    runCatching { ColumnField.valueOf(name) }.getOrNull()
                }
            }
        }

    override suspend fun saveColumnMap(
        excelUriMd5: String,
        map: List<ColumnField>,
        hasHeader: Boolean,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val nodes = load().toMutableMap()
            val node = nodes[excelUriMd5] ?: IndexNode()
            nodes[excelUriMd5] = node.copy(
                columnMap = map.map { it.name },
                hasHeader = hasHeader,
            )
            save(nodes)
        }
    }

    override suspend fun hasHeaderRow(excelUriMd5: String): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                load()[excelUriMd5]?.hasHeader ?: false
            }
        }

    /** 读取并归一化：旧结构 List 视为 progress_keys，新结构 Map 逐字段解析。 */
    private fun load(): Map<String, IndexNode> {
        if (!file.exists()) return emptyMap()
        return try {
            val json = file.readText(CHARSET)
            val parsed = adapter.fromJson(json) ?: emptyMap()
            val out = LinkedHashMap<String, IndexNode>(parsed.size)
            for ((k, v) in parsed) {
                out[k] = when (v) {
                    is List<*> -> IndexNode(progressKeys = v.mapNotNull { it?.toString() })
                    is Map<*, *> -> IndexNode(
                        progressKeys = (v[FIELD_PROGRESS_KEYS] as? List<*>)
                            ?.mapNotNull { it?.toString() }
                            ?: emptyList(),
                        columnMap = (v[FIELD_COLUMN_MAP] as? List<*>)
                            ?.mapNotNull { it?.toString() },
                        hasHeader = v[FIELD_HAS_HEADER] == true,
                    )
                    else -> IndexNode()
                }
            }
            out
        } catch (e: Exception) {
            Timber.w(e, "excel_data_index.json 解析失败")
            emptyMap()
        }
    }

    /** 写入统一为新结构；column_map 未设置时省略该字段。 */
    private fun save(data: Map<String, IndexNode>) {
        val raw = LinkedHashMap<String, Any?>(data.size)
        for ((k, node) in data) {
            val nodeJson = LinkedHashMap<String, Any?>()
            nodeJson[FIELD_PROGRESS_KEYS] = node.progressKeys
            if (node.columnMap != null) nodeJson[FIELD_COLUMN_MAP] = node.columnMap
            nodeJson[FIELD_HAS_HEADER] = node.hasHeader
            raw[k] = nodeJson
        }
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val tmp = File(parent, "$INDEX_FILE_NAME.tmp")
        try {
            tmp.writeText(adapter.toJson(raw), CHARSET)
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
        const val FIELD_PROGRESS_KEYS = "progress_keys"
        const val FIELD_COLUMN_MAP = "column_map"
        const val FIELD_HAS_HEADER = "has_header"
        val CHARSET = Charsets.UTF_8
    }
}
