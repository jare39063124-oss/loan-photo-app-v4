package com.banktool.loanphoto.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.banktool.loanphoto.data.datasource.ColumnField
import com.banktool.loanphoto.data.datasource.ExcelWriter
import com.banktool.loanphoto.domain.entity.CustomerRow
import com.banktool.loanphoto.domain.repository.ExcelDataIndexRepository
import com.banktool.loanphoto.domain.repository.ExcelRepository
import com.banktool.loanphoto.domain.repository.ProgressRepository
import com.banktool.loanphoto.domain.repository.VisitNoteRepository
import com.banktool.loanphoto.util.ProgressKeyUtil
import com.banktool.loanphoto.util.SortUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * 进度查看界面单项：客户 + 进度信息。
 */
data class ProgressItem(
    val row: CustomerRow,
    val photoCount: Int,
    val isBatchMarked: Boolean,
    val remark: String,
)

/**
 * 进度查看界面 ViewModel：加载当前 Excel 所有客户及拍照进度、提供顶部统计
 * （总客户数 / 已拍摄数 / 待拍摄数）、导出备注到 Excel F 列、
 * 清除数据（缩略图 + 进度 + 备注 + 索引）、保存走访备注。
 */
@HiltViewModel
class ProgressViewModel @Inject constructor(
    private val excelRepository: ExcelRepository,
    private val progressRepository: ProgressRepository,
    private val excelDataIndexRepository: ExcelDataIndexRepository,
    private val visitNoteRepository: VisitNoteRepository,
    private val excelWriter: ExcelWriter,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = false,
        val items: List<ProgressItem> = emptyList(),
        val excelUri: String = "",
        val excelFileName: String = "",
        val excelUriMd5: String = "",
        val resolvedColumnMap: List<ColumnField> = ColumnField.DEFAULT,
        val totalCount: Int = 0,
        val visitedCount: Int = 0,
        val pendingCount: Int = 0,
        val visitNote: String = "",
        val isExporting: Boolean = false,
        val isClearing: Boolean = false,
        val message: String? = null,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * 加载某 Excel 的进度数据。
     *
     * 若该文件已有持久化列映射（客户清单页确认过），注入解析保证字段语义一致；
     * 否则按表头探测/默认 A-F 解析。
     */
    fun loadProgress(excelUri: String, fileName: String = "") {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val md5 = ProgressKeyUtil.excelUriMd5(excelUri)
                val savedMap = excelDataIndexRepository.getColumnMap(md5)
                val result = if (savedMap != null) {
                    val skipFirstRow = excelDataIndexRepository.hasHeaderRow(md5)
                    Timber.i(
                        "loadProgress: 使用持久化列映射 %s, skipFirstRow=%s, uriMd5=%s",
                        savedMap, skipFirstRow, md5,
                    )
                    excelRepository.readExcel(excelUri, savedMap, skipFirstRow)
                } else {
                    excelRepository.readExcel(excelUri)
                }
                val rows = result.rows

                val allProgress = progressRepository.getAllProgress()
                val batchMarked = progressRepository.getBatchMarkedKeys()
                val remarks = progressRepository.getRowRemarks(md5)

                val items = withContext(Dispatchers.Default) {
                    rows
                        .map { row ->
                            val record = allProgress[row.progressKey]
                            ProgressItem(
                                row = row,
                                photoCount = record?.photos?.size ?: 0,
                                isBatchMarked = row.progressKey in batchMarked,
                                remark = remarks[row.rowIndex.toString()] ?: row.remark,
                            )
                        }
                        .sortedWith(
                            compareBy(
                                { ProgressItemSortKey(it, SortUtil.addressSortKey(it.row.addrGeneral + it.row.addrDetail)) },
                            ),
                        )
                }

                val visited = items.count { it.photoCount > 0 }
                val visitNote = visitNoteRepository.getVisitNote(md5)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        items = items,
                        excelUri = excelUri,
                        excelFileName = fileName.ifBlank { "未知文件" },
                        excelUriMd5 = md5,
                        resolvedColumnMap = result.resolvedColumnMap,
                        totalCount = items.size,
                        visitedCount = visited,
                        pendingCount = items.size - visited,
                        visitNote = visitNote,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "加载进度失败: %s", excelUri)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "加载进度失败",
                    )
                }
            }
        }
    }

    /**
     * 导出备注到 Excel 备注映射列（默认 F 列）。
     *
     * 收集所有有备注的行（rowIndex -> remark），调用 ExcelWriter 写回原 URI。
     * 备注列号取当前文件 resolvedColumnMap 中 REMARK 对应列；无备注列时报错提示。
     */
    fun exportRemarksToExcel() {
        val current = _uiState.value
        if (current.excelUri.isBlank()) {
            _uiState.update { it.copy(error = "未加载 Excel 文件") }
            return
        }
        val remarkColumn = current.resolvedColumnMap.indexOf(ColumnField.REMARK)
        if (remarkColumn < 0) {
            _uiState.update { it.copy(error = "当前列映射中没有备注列，无法导出") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, error = null, message = null) }
            try {
                val remarksMap = current.items
                    .filter { it.remark.isNotBlank() }
                    .associate { it.row.rowIndex to it.remark }

                if (remarksMap.isEmpty()) {
                    _uiState.update {
                        it.copy(isExporting = false, message = "没有备注可导出")
                    }
                    return@launch
                }

                val uri = android.net.Uri.parse(current.excelUri)
                val success = excelWriter.writeRemarksToExcel(uri, remarksMap, remarkColumn)
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        message = if (success) "已导出 ${remarksMap.size} 条备注到 Excel" else "导出失败",
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "导出备注失败")
                _uiState.update {
                    it.copy(isExporting = false, error = e.message ?: "导出失败")
                }
            }
        }
    }

    /**
     * 清除当前 Excel 的所有数据：缩略图与原图目录、progress.json 中对应 keys
     * （含 batch_marked 引用）、_row_remarks 对应行号，并移除 excel_data_index.json 条目。
     */
    fun clearData() {
        val current = _uiState.value
        if (current.excelUriMd5.isBlank()) {
            _uiState.update { it.copy(error = "未加载 Excel 文件") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isClearing = true, error = null, message = null) }
            try {
                val md5 = current.excelUriMd5
                var progressKeys = excelDataIndexRepository.getProgressKeys(md5)
                // 索引为空说明该文件此前从未登记或索引已丢失，
                // 此时回退为按持久化数据全量扫描，避免清除操作静默失效。
                if (progressKeys.isEmpty()) {
                    progressKeys = progressRepository.getAllProgressKeys()
                    Timber.w("excel_data_index 为空，使用 progress.json 全量 keys 兜底: %d", progressKeys.size)
                }
                val rowIndexes = current.items.map { it.row.rowIndex }

                val thumbRoot = File(context.getExternalFilesDir(null), "thumbnails")
                for (key in progressKeys) {
                    val dir = File(thumbRoot, key)
                    if (dir.exists()) {
                        dir.deleteRecursively()
                    }
                }

                val photosRoot = File(context.getExternalFilesDir(null), "photos")
                for (key in progressKeys) {
                    val dir = File(photosRoot, key)
                    if (dir.exists()) {
                        dir.deleteRecursively()
                    }
                }

                progressRepository.removeProgressKeys(progressKeys)

                progressRepository.removeRowRemarks(md5, rowIndexes)

                excelDataIndexRepository.removeExcelData(md5)

                _uiState.update {
                    it.copy(
                        isClearing = false,
                        items = emptyList(),
                        totalCount = 0,
                        visitedCount = 0,
                        pendingCount = 0,
                        visitNote = "",
                        message = "数据已清除",
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "清除数据失败")
                _uiState.update {
                    it.copy(isClearing = false, error = e.message ?: "清除数据失败")
                }
            }
        }
    }

    /**
     * 保存走访备注。
     */
    fun saveVisitNote(content: String) {
        val md5 = _uiState.value.excelUriMd5
        if (md5.isBlank()) {
            _uiState.update { it.copy(error = "未加载 Excel 文件") }
            return
        }
        viewModelScope.launch {
            try {
                visitNoteRepository.saveVisitNote(md5, content)
                _uiState.update {
                    it.copy(visitNote = content, message = "走访备注已保存")
                }
            } catch (e: Exception) {
                Timber.e(e, "保存走访备注失败")
                _uiState.update { it.copy(error = e.message ?: "保存走访备注失败") }
            }
        }
    }

    /**
     * 加载走访备注（用于弹窗显示当前内容）。
     */
    fun loadVisitNote() {
        val md5 = _uiState.value.excelUriMd5
        if (md5.isBlank()) return
        viewModelScope.launch {
            try {
                val note = visitNoteRepository.getVisitNote(md5)
                _uiState.update { it.copy(visitNote = note) }
            } catch (e: Exception) {
                Timber.w(e, "加载走访备注失败")
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null, error = null) }
    }
}

/**
 * 排序键包装器，用于 ProgressItem 排序。
 */
private data class ProgressItemSortKey(
    val item: ProgressItem,
    val key: Pair<String, Pair<Int, Int>>,
) : Comparable<ProgressItemSortKey> {
    override fun compareTo(other: ProgressItemSortKey): Int {
        return compareValuesBy(
            this,
            other,
            { it.key.first },
            { it.key.second.first },
            { it.key.second.second },
            { it.item.row.rowIndex },
        )
    }
}
