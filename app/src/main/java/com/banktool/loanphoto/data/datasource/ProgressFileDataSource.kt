package com.banktool.loanphoto.data.datasource

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject

/**
 * progress.json 文件 IO 数据源。
 *
 * 负责原始 [Map] 读写与原子落盘，不感知业务语义：
 * - 文件路径: `context.getExternalFilesDir(null)/progress.json`
 * - 原子写: 写到 `.tmp` 后 [Files.move]([StandardCopyOption.ATOMIC_MOVE])
 * - 解析失败时返回空 Map（不抛异常，避免阻断业务）
 *
 * 顶层结构兼容 Kivy v3.22.24：
 * ```json
 * {
 *   "_row_remarks": {"0": "..."},
 *   "batch_marked": {"5": true},
 *   "<progressKey>": { "photos": [...], "types": {...}, "timestamp": "...", "remark": "..." }
 * }
 * ```
 *
 * 业务层（[com.banktool.loanphoto.data.repository.ProgressRepositoryImpl]）
 * 负责将 `Any?` 节点转换为 [com.banktool.loanphoto.data.dto.ProgressEntryDto]。
 */
class ProgressFileDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val moshi: Moshi = Moshi.Builder().build()

    @Suppress("ktlint:standard:property-naming")
    private val mapType = Types.newParameterizedType(
        Map::class.java, String::class.java, Any::class.java,
    )

    private val adapter = moshi.adapter<Map<String, Any?>>(mapType).indent("  ")

    /** progress.json 文件。 */
    val file: File
        get() = File(context.getExternalFilesDir(null), PROGRESS_FILE_NAME)

    /** APP_DIR（外存私有目录），用于路径迁移。 */
    val appDir: File
        get() = context.getExternalFilesDir(null) ?: context.filesDir

    /**
     * 加载 progress.json；文件不存在或解析失败返回空 MutableMap。
     */
    @Suppress("UNCHECKED_CAST")
    fun load(): MutableMap<String, Any?> {
        if (!file.exists()) return mutableMapOf()
        return try {
            val json = file.readText(CHARSET)
            val parsed = adapter.fromJson(json) ?: mutableMapOf()
            // Moshi 返回 LinkedHashMap，已是可变；保险起见包装一次
            parsed.toMutableMap() as MutableMap<String, Any?>
        } catch (e: Exception) {
            Timber.w(e, "progress.json 解析失败，返回空 Map")
            mutableMapOf()
        }
    }

    /**
     * 原子写：先写 `.tmp`，再 [Files.move] 覆盖目标文件。
     */
    fun save(data: Map<String, Any?>) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val tmp = File(parent, "$PROGRESS_FILE_NAME.tmp")
        try {
            tmp.writeText(adapter.toJson(data), CHARSET)
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: Exception) {
            Timber.e(e, "progress.json 原子写失败")
            // ATOMIC_MOVE 在某些文件系统不可用时退回普通重命名
            try {
                if (file.exists()) file.delete()
                tmp.renameTo(file)
            } catch (e2: Exception) {
                Timber.e(e2, "progress.json 退回重命名仍失败")
            }
        }
    }

    private companion object {
        const val PROGRESS_FILE_NAME = "progress.json"
        val CHARSET = Charsets.UTF_8
    }
}
