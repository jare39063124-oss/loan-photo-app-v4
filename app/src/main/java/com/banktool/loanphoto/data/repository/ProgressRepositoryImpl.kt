package com.banktool.loanphoto.data.repository

import com.banktool.loanphoto.data.datasource.ProgressFileDataSource
import com.banktool.loanphoto.data.dto.ProgressEntryDto
import com.banktool.loanphoto.domain.entity.PhotoRecord
import com.banktool.loanphoto.domain.repository.ProgressRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 拍照进度仓库实现。
 *
 * - 文件: `progress.json`（兼容 Kivy v3.22.24）
 * - 并发: [Mutex] 保护所有读改写操作
 * - 原子写: 由 [ProgressFileDataSource] 负责（`.tmp` -> ATOMIC_MOVE）
 * - markPhotoBatch: 单次锁内更新所有 keys，仅 1 次 save
 *
 * batch_marked 存储格式（Kivy 兼容）：
 * ```json
 * "batch_marked": ["abc123def456abcd", "xyz789uvw012efgh"]
 * ```
 * 即 progressKey 的 JSON 数组。读取时兼容旧版 Map<String,Boolean> 格式。
 */
class ProgressRepositoryImpl @Inject constructor(
    private val dataSource: ProgressFileDataSource,
) : ProgressRepository {

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val entryAdapter = moshi.adapter(ProgressEntryDto::class.java)

    private val mutex = Mutex()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override suspend fun getProgress(key: String): PhotoRecord? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val fileMap = dataSource.load()
                val node = fileMap[key] ?: return@withLock null
                val dto = entryAdapter.fromJsonValue(node) as? ProgressEntryDto
                dto?.toPhotoRecord(key)
            }
        }

    override suspend fun saveProgress(key: String, record: PhotoRecord) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val fileMap = dataSource.load()
                fileMap[key] = entryAdapter.toJsonValue(record.toEntryDto())
                dataSource.save(fileMap)
            }
        }

    override suspend fun markPhoto(key: String, photoPath: String, photoType: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val fileMap = dataSource.load()
                val dto = readEntry(fileMap, key).copyWithPhoto(photoPath, photoType, now())
                fileMap[key] = entryAdapter.toJsonValue(dto)
                dataSource.save(fileMap)
            }
        }

    override suspend fun markPhotoBatch(
        keys: List<String>,
        photoPath: String,
        photoType: String,
    ) = withContext(Dispatchers.IO) {
        if (keys.isEmpty()) return@withContext
        mutex.withLock {
            val fileMap = dataSource.load()
            val timestamp = now()
            for (key in keys) {
                val dto = readEntry(fileMap, key).copyWithPhoto(photoPath, photoType, timestamp)
                fileMap[key] = entryAdapter.toJsonValue(dto)
            }
            // 单次 save
            dataSource.save(fileMap)
        }
    }

    override suspend fun getAllProgress(): Map<String, PhotoRecord> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val fileMap = dataSource.load()
                val result = HashMap<String, PhotoRecord>(fileMap.size)
                for ((key, node) in fileMap) {
                    if (key == KEY_ROW_REMARKS || key == KEY_BATCH_MARKED) continue
                    val dto = entryAdapter.fromJsonValue(node) as? ProgressEntryDto ?: continue
                    result[key] = dto.toPhotoRecord(key)
                }
                result
            }
        }

    // ---- 行级备注 ----

    override suspend fun getRowRemarks(): Map<String, String> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                readRowRemarks(dataSource.load())
            }
        }

    override suspend fun getRowRemark(rowIndex: Int): String? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                readRowRemarks(dataSource.load())[rowIndex.toString()]
            }
        }

    override suspend fun saveRowRemark(rowIndex: Int, content: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val fileMap = dataSource.load()
                val remarks = readRowRemarks(fileMap)
                remarks[rowIndex.toString()] = content
                fileMap[KEY_ROW_REMARKS] = remarks
                dataSource.save(fileMap)
            }
        }

    override suspend fun removeRowRemarks(rowIndexes: List<Int>) =
        withContext(Dispatchers.IO) {
            if (rowIndexes.isEmpty()) return@withContext
            mutex.withLock {
                val fileMap = dataSource.load()
                val remarks = readRowRemarks(fileMap)
                var changed = false
                for (idx in rowIndexes) {
                    if (remarks.remove(idx.toString()) != null) changed = true
                }
                if (changed) {
                    fileMap[KEY_ROW_REMARKS] = remarks
                    dataSource.save(fileMap)
                }
            }
        }

    // ---- 同类型代表性户型标记（Kivy 兼容 Set<String>） ----

    override suspend fun getBatchMarkedKeys(): Set<String> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                readBatchMarkedKeys(dataSource.load())
            }
        }

    override suspend fun setBatchMarked(progressKey: String, marked: Boolean) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val fileMap = dataSource.load()
                val keys = readBatchMarkedKeys(fileMap).toMutableSet()
                val changed = if (marked) keys.add(progressKey) else keys.remove(progressKey)
                if (changed) {
                    fileMap[KEY_BATCH_MARKED] = keys.toList()
                    dataSource.save(fileMap)
                }
            }
        }

    override suspend fun isBatchMarked(progressKey: String): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                progressKey in readBatchMarkedKeys(dataSource.load())
            }
        }

    // ---- 维护 ----

    override suspend fun removeProgressKeys(keys: List<String>) =
        withContext(Dispatchers.IO) {
            if (keys.isEmpty()) return@withContext
            mutex.withLock {
                val fileMap = dataSource.load()
                var changed = false
                for (k in keys) {
                    if (fileMap.remove(k) != null) changed = true
                }
                // 同时从 batch_marked 中移除
                val marked = readBatchMarkedKeys(fileMap).toMutableSet()
                var markedChanged = false
                for (k in keys) {
                    if (marked.remove(k)) markedChanged = true
                }
                if (markedChanged) {
                    fileMap[KEY_BATCH_MARKED] = marked.toList()
                    changed = true
                }
                if (changed) dataSource.save(fileMap)
            }
        }

    /**
     * v3.22.5 兼容：迁移 photos 列表中失效路径到 APP_DIR 同名文件。
     *
     * 旧版本可能将照片存于应用私有目录外的失效路径；这里尝试在 [ProgressFileDataSource.appDir]
     * 下按同名文件找回并替换。
     */
    override suspend fun migratePhotoPaths() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val fileMap = dataSource.load()
            var changed = false
            val appDir = dataSource.appDir
            val keysToCheck = fileMap.keys.filter { it != KEY_ROW_REMARKS && it != KEY_BATCH_MARKED }
            for (key in keysToCheck) {
                val node = fileMap[key] ?: continue
                val dto = entryAdapter.fromJsonValue(node) as? ProgressEntryDto ?: continue
                if (dto.photos.isEmpty()) continue
                val migrated = dto.photos.map { path -> migrateOne(path, appDir) }
                if (migrated != dto.photos) {
                    fileMap[key] = entryAdapter.toJsonValue(dto.copy(photos = migrated))
                    changed = true
                    Timber.i("迁移 photos: %s (%d 项)", key, migrated.size)
                }
            }
            if (changed) dataSource.save(fileMap)
        }
    }

    /** 单个路径迁移：原文件存在则保留，否则在 appDir 下找同名文件。 */
    private fun migrateOne(path: String, appDir: File): String {
        val f = File(path)
        if (f.exists()) return path
        val name = f.name
        if (name.isBlank()) return path
        val candidate = File(appDir, name)
        return if (candidate.exists()) candidate.absolutePath else path
    }

    // ---- 读取辅助 ----

    private fun readEntry(fileMap: Map<String, Any?>, key: String): ProgressEntryDto {
        val node = fileMap[key] ?: return ProgressEntryDto()
        return (entryAdapter.fromJsonValue(node) as? ProgressEntryDto) ?: ProgressEntryDto()
    }

    @Suppress("UNCHECKED_CAST")
    private fun readRowRemarks(fileMap: Map<String, Any?>): MutableMap<String, String> {
        val node = fileMap[KEY_ROW_REMARKS] ?: return mutableMapOf()
        val raw = node as? Map<*, *> ?: return mutableMapOf()
        return raw.entries.associate { (k, v) ->
            (k?.toString() ?: "") to (v?.toString() ?: "")
        }.toMutableMap()
    }

    /**
     * 读取 batch_marked：兼容两种格式。
     * - Kivy 格式: List<String>（progressKey 数组）
     * - 旧版格式: Map<String, Boolean>（key=rowIndex 或 progressKey）
     */
    private fun readBatchMarkedKeys(fileMap: Map<String, Any?>): Set<String> {
        val node = fileMap[KEY_BATCH_MARKED] ?: return emptySet()
        return when (node) {
            is List<*> -> node.mapNotNull { it?.toString() }.filter { it.isNotBlank() }.toSet()
            is Map<*, *> -> {
                // 旧版 Map<String,Boolean>：取 value=true 的 key
                node.entries
                    .filter { it.value == true }
                    .mapNotNull { it.key?.toString() }
                    .filter { it.isNotBlank() }
                    .toSet()
            }
            else -> emptySet()
        }
    }

    private fun now(): String = dateFormat.format(Date())

    private companion object {
        const val KEY_ROW_REMARKS = "_row_remarks"
        const val KEY_BATCH_MARKED = "batch_marked"
    }
}

// ---- 实体 <-> DTO 转换 ----

private fun ProgressEntryDto.toPhotoRecord(key: String): PhotoRecord =
    PhotoRecord(
        progressKey = key,
        photos = photos,
        // Kivy 用 Map<String,Boolean> 表示类型集合；这里取 value=true 的键
        types = types.filterValues { it }.keys,
        photoTypes = photoTypes,
        timestamp = timestamp,
        remark = remark,
    )

private fun PhotoRecord.toEntryDto(): ProgressEntryDto =
    ProgressEntryDto(
        photos = photos,
        types = types.associateWith { true },
        photoTypes = photoTypes,
        timestamp = timestamp,
        remark = remark,
    )

/** 追加一张照片与类型，去重后更新时间戳。photo_types 与 photos 平行维护。 */
private fun ProgressEntryDto.copyWithPhoto(
    photoPath: String,
    photoType: String,
    timestamp: String,
): ProgressEntryDto {
    val alreadyExists = photos.contains(photoPath)
    val newPhotos = if (alreadyExists) photos else photos + photoPath
    // photo_types 与 photos 同步追加，保持索引对齐
    val newPhotoTypes = if (alreadyExists) photoTypes else photoTypes + photoType
    val newTypes = types.toMutableMap().apply {
        if (photoType.isNotBlank()) put(photoType, true)
    }
    return copy(
        photos = newPhotos,
        types = newTypes,
        photoTypes = newPhotoTypes,
        timestamp = timestamp,
    )
}
