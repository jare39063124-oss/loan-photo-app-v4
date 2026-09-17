package com.banktool.loanphoto.domain.repository

import com.banktool.loanphoto.domain.entity.PhotoRecord

/** 拍照进度仓库（progress.json 读写）。 */
interface ProgressRepository {

    /** 获取某 progressKey 的拍照记录，无则 null。 */
    suspend fun getProgress(key: String): PhotoRecord?

    /** 整条保存某 progressKey 的拍照记录。 */
    suspend fun saveProgress(key: String, record: PhotoRecord)

    /** 给某 progressKey 追加一张照片及类型，自动更新时间戳。 */
    suspend fun markPhoto(key: String, photoPath: String, photoType: String)

    /**
     * 批量标记：在单个 mutex 锁内把同一张照片/类型追加到多个 keys，
     * 仅触发一次落盘 save。
     */
    suspend fun markPhotoBatch(keys: List<String>, photoPath: String, photoType: String)

    /**
     * 获取所有 progressKey 的拍照记录。
     * 仅返回 entries（不含 `_row_remarks` 前缀（含按文件隔离键）/ batch_marked 特殊键）。
     */
    suspend fun getAllProgress(): Map<String, PhotoRecord>

    /**
     * 获取 progress.json 中全部 progressKey（用于 clearData 兜底，不依赖 excel_data_index.json）。
     *
     * 返回除 `_row_remarks`（含按文件隔离的 `_row_remarks_<uriMd5>`）和 `batch_marked`
     * 两个内部字段外的所有 key。
     */
    suspend fun getAllProgressKeys(): List<String>

    /** 行级备注 Map（key=行号字符串），按 Excel 文件隔离（uriMd5 维度）。 */
    suspend fun getRowRemarks(uriMd5: String): Map<String, String>

    /** 保存某行备注（按 Excel 文件隔离）。 */
    suspend fun saveRowRemark(uriMd5: String, rowIndex: Int, content: String)

    /** 已批量标记的 progressKey 集合。 */
    suspend fun getBatchMarkedKeys(): Set<String>

    /** 设置某 progressKey 的批量标记状态。 */
    suspend fun setBatchMarked(progressKey: String, marked: Boolean)

    /** 查询某 progressKey 是否已被批量标记。 */
    suspend fun isBatchMarked(progressKey: String): Boolean

    /** 删除给定 progressKey 的拍照记录（不删行级备注/批量标记）。 */
    suspend fun removeProgressKeys(keys: List<String>)

    /**
     * 清除给定 progressKeys 对应的行级备注（按 Excel 文件隔离）。
     * @param uriMd5 Excel 文件 URI 的 md5[:16]
     * @param rowIndexes 需要清除备注的行号列表
     */
    suspend fun removeRowRemarks(uriMd5: String, rowIndexes: List<Int>)

    /** 将 photos 列表中的失效路径修复为 APP_DIR 下同名文件。 */
    suspend fun migratePhotoPaths()
}
