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
 * 拍照进度仓库实现，数据落盘于 `progress.json`。
 *
 * 所有读改写操作由 [Mutex] 串行保护，落盘由 [ProgressFileDataSource]
 * 以临时文件加原子替换完成；markPhotoBatch 在单次锁内更新所有 keys，只落盘一次。
 *
 * batch_marked 存 progressKey 的字符串数组，如 `["abc123def456abcd"]`；
 * 读取时也接受键值对形式的早期数据。
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
            dataSource.save(fileMap)
        }
    }

    override suspend fun getAllProgress(): Map<String, PhotoRecord> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val fileMap = dataSource.load()
                val result = HashMap<String, PhotoRecord>(fileMap.size)
                for ((key, node) in fileMap) {
                    if (isInternalKey(key)) continue
                    val dto = entryAdapter.fromJsonValue(node) as? ProgressEntryDto ?: continue
                    result[key] = dto.toPhotoRecord(key)
                }
                result
            }
        }

    override suspend fun getAllProgressKeys(): List<String> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                dataSource.load().keys.filterNot { isInternalKey(it) }
            }
        }

    // ---- 行级备注（按 Excel 文件隔离，key=_row_remarks_<uriMd5>）----

    override suspend fun getRowRemarks(uriMd5: String): Map<String, String> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val fileMap = dataSource.load()
                readRowRemarksForFile(fileMap, uriMd5)
            }
        }

    override suspend fun saveRowRemark(uriMd5: String, rowIndex: Int, content: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val fileMap = dataSource.load()
                val remarks = readRowRemarksForFile(fileMap, uriMd5).toMutableMap()
                remarks[rowIndex.toString()] = content
                fileMap[rowRemarksKey(uriMd5)] = remarks
                dataSource.save(fileMap)
            }
        }

    override suspend fun removeRowRemarks(uriMd5: String, rowIndexes: List<Int>) =
        withContext(Dispatchers.IO) {
            if (rowIndexes.isEmpty()) return@withContext
            mutex.withLock {
                val fileMap = dataSource.load()
                val remarks = readRowRemarksForFile(fileMap, uriMd5).toMutableMap()
                var changed = false
                for (idx in rowIndexes) {
                    if (remarks.remove(idx.toString()) != null) changed = true
                }
                if (changed) {
                    fileMap[rowRemarksKey(uriMd5)] = remarks
                    dataSource.save(fileMap)
                }
            }
        }

    // ---- 同类型代表性户型标记 ----

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

    // ---- 列映射变更时的进度迁移 ----

    override suspend fun copyProgress(oldKey: String, newKey: String) =
        withContext(Dispatchers.IO) {
            if (oldKey == newKey) return@withContext
            mutex.withLock {
                val fileMap = dataSource.load()
                val oldNode = fileMap[oldKey] ?: return@withLock
                val oldDto = entryAdapter.fromJsonValue(oldNode) as? ProgressEntryDto
                    ?: return@withLock
                if (oldDto.photos.isEmpty()) return@withLock
                // 新键已有进度（photos 非空）时不覆盖
                val newDto = fileMap[newKey]?.let { entryAdapter.fromJsonValue(it) as? ProgressEntryDto }
                if (newDto != null && newDto.photos.isNotEmpty()) return@withLock
                // 深拷贝：集合取独立副本，避免与旧条目共享引用后互相影响
                val copied = oldDto.copy(
                    photos = oldDto.photos.toList(),
                    types = oldDto.types.toMap(),
                    photoTypes = oldDto.photoTypes.toList(),
                )
                fileMap[newKey] = entryAdapter.toJsonValue(copied)
                dataSource.save(fileMap)
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
                // 同步清理对应的代表性户型标记
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
     * 将 photos 列表中的失效路径重定向到应用目录下的同名文件。
     *
     * 早期版本可能把照片存于应用私有目录外的失效路径；这里尝试在 [ProgressFileDataSource.appDir]
     * 下按同名文件找回并替换。
     */
    override suspend fun migratePhotoPaths() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val fileMap = dataSource.load()
            var changed = false
            val appDir = dataSource.appDir
            val keysToCheck = fileMap.keys.filterNot { isInternalKey(it) }
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

    /** 单个路径修复：原文件存在则保留，否则在 appDir 下找同名文件。 */
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

    /**
     * 读取指定文件的行级备注。
     *
     * 新 key（`_row_remarks_<uriMd5>`）缺失时执行一次性迁移：把旧全局 `_row_remarks`
     * 整体写入新 key 并落盘（旧键保留不删），避免升级后行备注丢失。
     */
    private fun readRowRemarksForFile(
        fileMap: MutableMap<String, Any?>,
        uriMd5: String,
    ): Map<String, String> {
        val newKey = rowRemarksKey(uriMd5)
        val node = fileMap[newKey]
        if (node is Map<*, *>) return nodeToRemarks(node)
        // 一次性迁移：旧全局备注整体写入新 key，旧键保留
        val legacy = fileMap[KEY_ROW_REMARKS_LEGACY]
        val migrated = if (legacy is Map<*, *>) nodeToRemarks(legacy) else mutableMapOf()
        fileMap[newKey] = migrated
        dataSource.save(fileMap)
        Timber.i("行级备注迁移到按文件隔离 key=%s（%d 条）", newKey, migrated.size)
        return migrated
    }

    private fun nodeToRemarks(node: Map<*, *>): MutableMap<String, String> {
        return node.entries.associate { (k, v) ->
            (k?.toString() ?: "") to (v?.toString() ?: "")
        }.toMutableMap()
    }

    /** 行级备注按文件隔离的存储 key。 */
    private fun rowRemarksKey(uriMd5: String): String = "${KEY_ROW_REMARKS_PREFIX}_$uriMd5"

    /** progress.json 内部保留键：行级备注（旧全局键与按文件键）与批量标记。 */
    private fun isInternalKey(key: String): Boolean =
        key == KEY_BATCH_MARKED || key.startsWith(KEY_ROW_REMARKS_PREFIX)

    /**
     * 读取 batch_marked，接受两种格式：progressKey 的字符串数组，
     * 或键值对形式（key=rowIndex 或 progressKey）。
     */
    private fun readBatchMarkedKeys(fileMap: Map<String, Any?>): Set<String> {
        val node = fileMap[KEY_BATCH_MARKED] ?: return emptySet()
        return when (node) {
            is List<*> -> node.mapNotNull { it?.toString() }.filter { it.isNotBlank() }.toSet()
            is Map<*, *> -> {
                // 键值对形式的存储，仅保留取值为真的键
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
        /** 行级备注 key 前缀：旧全局键 `_row_remarks`，新按文件键 `_row_remarks_<uriMd5>`。 */
        const val KEY_ROW_REMARKS_PREFIX = "_row_remarks"
        const val KEY_ROW_REMARKS_LEGACY = "_row_remarks"
        const val KEY_BATCH_MARKED = "batch_marked"
    }
}

// ---- 实体 <-> DTO 转换 ----

private fun ProgressEntryDto.toPhotoRecord(key: String): PhotoRecord =
    PhotoRecord(
        progressKey = key,
        photos = photos,
        // 类型集合以键值对形式存储，取值为真的键即为所选类型
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
