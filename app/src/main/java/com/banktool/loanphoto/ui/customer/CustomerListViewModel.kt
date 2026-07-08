package com.banktool.loanphoto.ui.customer

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.banktool.loanphoto.data.export.ExportDimension
import com.banktool.loanphoto.data.export.ExportFormat
import com.banktool.loanphoto.data.export.PhotoExporter
import com.banktool.loanphoto.domain.entity.CustomerRow
import com.banktool.loanphoto.domain.entity.PhotoType
import com.banktool.loanphoto.domain.entity.SearchField
import com.banktool.loanphoto.domain.repository.ExcelDataIndexRepository
import com.banktool.loanphoto.domain.repository.ExcelRepository
import com.banktool.loanphoto.domain.repository.ProgressRepository
import com.banktool.loanphoto.domain.session.PhotoSessionHolder
import com.banktool.loanphoto.ui.customer.data.RecentFilesStorage
import com.banktool.loanphoto.util.ProgressKeyUtil
import com.banktool.loanphoto.util.SortUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * 最近文件 UI 模型（含数据指示信息）。
 */
data class RecentFileItem(
    val uri: String,
    val fileName: String,
    val timestamp: Long,
    val hasData: Boolean,
    val dataCount: Int,
)

/**
 * 客户清单列表 ViewModel。
 *
 * 职责：
 * 1. 加载 Excel 并按地址排序
 * 2. 实时搜索过滤（保存匹配全集 [UiState.filteredIndices]）
 * 3. 全选作用域为 filteredIndices 全集（非仅可见行）
 * 4. 单行反选同步表头 CheckBox 状态
 * 5. 查询每个 progressKey 的照片数
 * 6. 管理最近文件列表（含数据指示器）
 * 7. 行级备注编辑（_row_remarks）
 * 8. 同类型代表性户型标记（batch_marked）
 */
@HiltViewModel
class CustomerListViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val excelRepository: ExcelRepository,
    private val progressRepository: ProgressRepository,
    private val excelDataIndexRepository: ExcelDataIndexRepository,
    private val recentFilesStorage: RecentFilesStorage,
    private val photoSessionHolder: PhotoSessionHolder,
    private val photoExporter: PhotoExporter,
) : ViewModel() {

    /**
     * UI 状态。
     *
     * - [filteredIndices] 保存匹配搜索的 rowIndex 全集（不是列表位置，而是 [CustomerRow.rowIndex]）
     * - [selectedRows] 保存被选中的 rowIndex 集合（与 filteredIndices 同维度，确保跨排序稳定）
     * - [rowRemarks] 行级备注 Map（key=行号字符串），来自 progress.json 的 _row_remarks
     * - [batchMarkedKeys] 已标记为同类型代表性户型的 progressKey 集合
     */
    data class UiState(
        val isLoading: Boolean = false,
        val rows: List<CustomerRow> = emptyList(),
        val filteredIndices: List<Int> = emptyList(),
        val selectedRows: Set<Int> = emptySet(),
        val photoCounts: Map<String, Int> = emptyMap(),
        val photoTypeCounts: Map<String, Map<PhotoType, Int>> = emptyMap(),
        val searchField: SearchField = SearchField.BORROWER,
        val searchQuery: String = "",
        val excelUri: String = "",
        val excelFileName: String = "",
        val error: String? = null,
        val recentFiles: List<RecentFileItem> = emptyList(),
        val showRecentFilesSheet: Boolean = false,
        val isRefreshingCounts: Boolean = false,
        val rowRemarks: Map<String, String> = emptyMap(),
        val batchMarkedKeys: Set<String> = emptySet(),
        val editingRemarkRowIndex: Int? = null,
        val message: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * 照片导出状态机。
     *
     * - [Idle]：未开始 / 已重置，可触发新导出
     * - [Exporting]：进行中，(current, total) 用于进度显示
     * - [Done]：成功，附带输出 [File]
     * - [Error]：失败，附带错误消息
     */
    sealed class ExportState {
        data object Idle : ExportState()
        data class Exporting(val current: Int, val total: Int) : ExportState()
        data class Done(val file: File, val fileSize: Long) : ExportState()
        data class Error(val message: String) : ExportState()
    }

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    init {
        loadRecentFiles()
    }

    /**
     * 加载 Excel 文件。
     *
     * 步骤：
     * 1. 调用 [ExcelRepository.readExcel] 解析
     * 2. 按 [SortUtil.addressSortKey] 排序（addrGeneral + addrDetail）
     * 3. 查询每个 progressKey 的照片数
     * 4. 加载行级备注和同类型标记
     * 5. 重置搜索/选中状态，filteredIndices 默认为全集
     * 6. 写入最近文件
     */
    fun loadExcel(uri: String, fileName: String = "") {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val rawRows = excelRepository.readExcel(uri)
                val sorted = withContext(Dispatchers.Default) {
                    // 预计算排序键，避免重复调用 addressSortKey
                    rawRows
                        .map { it to SortUtil.addressSortKey(it.addrGeneral + it.addrDetail) }
                        .sortedWith(
                            compareBy(
                                { pair -> pair.second.first },
                                { pair -> pair.second.second.first },
                                { pair -> pair.second.second.second },
                                { pair -> pair.first.rowIndex },
                            ),
                        )
                        .map { it.first }
                }
                val photoStats = loadPhotoStats(sorted)
                val rowRemarks = progressRepository.getRowRemarks()
                val batchMarkedKeys = progressRepository.getBatchMarkedKeys()
                val resolvedName = fileName.ifBlank {
                    recentFilesStorage.getRecentFiles().firstOrNull { it.uri == uri }?.fileName ?: "未知文件"
                }
                val md5 = ProgressKeyUtil.excelUriMd5(uri)
                photoSessionHolder.setExcelInfo(uri, resolvedName, md5)
                // 同步全量客户行到 PhotoSessionHolder，供 ReportScreen 等非相机路径使用。
                // 否则用户直接点「AI 日报表」时 sharedVm.rows 为空（仅相机导航路径会 setPhotoRows）。
                photoSessionHolder.setPhotoRows(sorted)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        rows = sorted,
                        filteredIndices = sorted.map { row -> row.rowIndex },
                        selectedRows = emptySet(),
                        photoCounts = photoStats.counts,
                        photoTypeCounts = photoStats.typeCounts,
                        excelUri = uri,
                        excelFileName = resolvedName,
                        searchQuery = "",
                        error = null,
                        rowRemarks = rowRemarks,
                        batchMarkedKeys = batchMarkedKeys,
                    )
                }
                recentFilesStorage.addRecent(uri, resolvedName)
                loadRecentFiles()
            } catch (e: Exception) {
                Timber.e(e, "加载 Excel 失败: %s", uri)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "加载 Excel 失败",
                    )
                }
            }
        }
    }

    /**
     * 从最近文件直接加载（uri 已知，文件名从最近列表取）。
     *
     * 加载前校验 SAF 持久化 URI 读权限是否仍然有效，失效时移除最近记录并提示用户重新导入。
     */
    fun loadFromRecent(uri: String) {
        // 检查 URI 权限是否有效
        val hasPermission = runCatching {
            val parsed = android.net.Uri.parse(uri)
            context.contentResolver.persistedUriPermissions.any {
                it.uri == parsed && it.isReadPermission
            }
        }.getOrDefault(false)

        if (!hasPermission) {
            viewModelScope.launch {
                recentFilesStorage.removeRecent(uri)
                _uiState.update {
                    it.copy(message = "文件权限已失效，请重新导入Excel文件")
                }
                loadRecentFiles()
            }
            return
        }

        val fileName = _uiState.value.recentFiles.firstOrNull { it.uri == uri }?.fileName ?: ""
        loadExcel(uri, fileName)
    }

    /**
     * 移除一条最近文件记录（用于用户主动清理或权限失效场景）。
     */
    fun removeRecentFile(uri: String) {
        viewModelScope.launch {
            recentFilesStorage.removeRecent(uri)
            loadRecentFiles()
        }
    }

    /**
     * 实时搜索：输入即过滤。
     *
     * filteredIndices 保存匹配全集（所有满足条件的 rowIndex），
     * 不是 LazyColumn 当前可见的子集。
     *
     * REMARK 字段搜索时查 progress.json 的 _row_remarks[行号] 是否 contains(query)。
     */
    fun onSearchQueryChange(query: String) {
        _uiState.update { current ->
            val filtered = filterRows(current.rows, query, current.searchField, current.rowRemarks, current.photoCounts)
            current.copy(searchQuery = query, filteredIndices = filtered)
        }
    }

    /**
     * 切换搜索字段后重新过滤。
     */
    fun onSearchFieldChange(field: SearchField) {
        _uiState.update { current ->
            val filtered = filterRows(current.rows, current.searchQuery, field, current.rowRemarks, current.photoCounts)
            current.copy(searchField = field, filteredIndices = filtered)
        }
    }

    /**
     * 全选/反选：作用域为 [UiState.filteredIndices] 全集（非仅可见行）。
     *
     * - 若当前已选中全部 filteredIndices，则取消选中这些项（保留未在过滤集内的其他选中项）
     * - 否则把 filteredIndices 全部加入选中
     */
    fun toggleSelectAll() {
        _uiState.update { current ->
            if (current.filteredIndices.isEmpty()) return@update current
            val allSelected = current.selectedRows.containsAll(current.filteredIndices)
            val newSelection = if (allSelected) {
                current.selectedRows - current.filteredIndices.toSet()
            } else {
                current.selectedRows + current.filteredIndices
            }
            current.copy(selectedRows = newSelection)
        }
    }

    /**
     * 单行选择切换。选中状态用 rowIndex 标识（跨排序稳定）。
     */
    fun toggleRowSelection(rowIndex: Int) {
        _uiState.update { current ->
            val newSelection = if (rowIndex in current.selectedRows) {
                current.selectedRows - rowIndex
            } else {
                current.selectedRows + rowIndex
            }
            current.copy(selectedRows = newSelection)
        }
    }

    /**
     * 刷新照片计数（拍照返回后调用）。
     */
    fun refreshPhotoCounts() {
        val current = _uiState.value
        if (current.rows.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshingCounts = true) }
            try {
                val stats = loadPhotoStats(current.rows)
                val rowRemarks = progressRepository.getRowRemarks()
                val batchMarkedKeys = progressRepository.getBatchMarkedKeys()
                _uiState.update {
                    val filtered = filterRows(it.rows, it.searchQuery, it.searchField, rowRemarks, stats.counts)
                    it.copy(
                        photoCounts = stats.counts,
                        photoTypeCounts = stats.typeCounts,
                        rowRemarks = rowRemarks,
                        batchMarkedKeys = batchMarkedKeys,
                        isRefreshingCounts = false,
                        filteredIndices = filtered,
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "刷新照片计数失败")
                _uiState.update { it.copy(isRefreshingCounts = false) }
            }
        }
    }

    /**
     * 显示最近文件 BottomSheet。
     */
    fun showRecentFilesSheet() {
        loadRecentFiles()
        _uiState.update { it.copy(showRecentFilesSheet = true) }
    }

    /**
     * 隐藏最近文件 BottomSheet。
     */
    fun hideRecentFilesSheet() {
        _uiState.update { it.copy(showRecentFilesSheet = false) }
    }

    /**
     * 清除错误状态。
     */
    fun clearError() {
        _uiState.update { it.copy(error = null, message = null) }
    }

    /**
     * 获取当前选中的客户行列表（用于批量拍照）。
     */
    fun getSelectedRows(): List<CustomerRow> {
        val current = _uiState.value
        val selected = current.selectedRows
        return if (selected.isEmpty()) {
            emptyList()
        } else {
            current.rows.filter { it.rowIndex in selected }
        }
    }

    // ---- 行级备注 ----

    /**
     * 打开备注编辑弹窗。
     */
    fun startEditRemark(rowIndex: Int) {
        _uiState.update { it.copy(editingRemarkRowIndex = rowIndex) }
    }

    /**
     * 关闭备注编辑弹窗。
     */
    fun dismissEditRemark() {
        _uiState.update { it.copy(editingRemarkRowIndex = null) }
    }

    /**
     * 保存行级备注到 progress.json 的 _row_remarks。
     */
    fun saveRowRemark(rowIndex: Int, content: String) {
        viewModelScope.launch {
            try {
                progressRepository.saveRowRemark(rowIndex, content)
                _uiState.update { current ->
                    val newRemarks = current.rowRemarks.toMutableMap()
                    if (content.isBlank()) {
                        newRemarks.remove(rowIndex.toString())
                    } else {
                        newRemarks[rowIndex.toString()] = content
                    }
                    current.copy(
                        rowRemarks = newRemarks,
                        editingRemarkRowIndex = null,
                        message = "备注已保存",
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "保存备注失败")
                _uiState.update {
                    it.copy(error = e.message ?: "保存备注失败", editingRemarkRowIndex = null)
                }
            }
        }
    }

    /**
     * 获取某行备注内容。
     */
    fun getRowRemark(rowIndex: Int): String =
        _uiState.value.rowRemarks[rowIndex.toString()] ?: ""

    // ---- 同类型代表性户型标记 ----

    /**
     * 切换某行的同类型代表性户型标记。
     */
    fun toggleBatchMarked(progressKey: String) {
        viewModelScope.launch {
            try {
                val current = _uiState.value
                val isMarked = progressKey in current.batchMarkedKeys
                progressRepository.setBatchMarked(progressKey, !isMarked)
                _uiState.update { state ->
                    val newKeys = state.batchMarkedKeys.toMutableSet()
                    if (isMarked) {
                        newKeys.remove(progressKey)
                    } else {
                        newKeys.add(progressKey)
                    }
                    state.copy(
                        batchMarkedKeys = newKeys,
                        message = if (isMarked) "已取消标记" else "已标记为同类型代表性户型",
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "切换批量标记失败")
                _uiState.update { it.copy(error = e.message ?: "切换标记失败") }
            }
        }
    }

    // ---- 添加查勘条目 ----

    /**
     * 在当前 Excel 末尾追加一行查勘条目，并刷新列表。
     *
     * 列映射：A=serial, B=borrower, C=address。
     *
     * - 成功后重新调用 [loadExcel] 拉取最新数据
     * - 失败时通过 [UiState.error] 提示用户确认文件可写
     */
    fun addSurveyEntry(serial: String, borrower: String, address: String) {
        val currentUri = uiState.value.excelUri
        if (currentUri.isBlank()) return
        viewModelScope.launch {
            try {
                val newRowIndex = excelRepository.appendRow(currentUri, serial, borrower, address)
                if (newRowIndex >= 0) {
                    // 重新加载 Excel 刷新列表
                    loadExcel(currentUri, uiState.value.excelFileName)
                    _uiState.update { it.copy(message = "已添加条目") }
                } else {
                    _uiState.update { it.copy(error = "添加失败：请确认文件可写") }
                }
            } catch (e: Exception) {
                Timber.w(e, "添加查勘条目失败")
                _uiState.update { it.copy(error = "添加失败：请确认文件可写") }
            }
        }
    }

    // ---- 照片导出 ----

    /**
     * 触发照片导出。
     *
     * 流程：
     * 1. 状态切到 [ExportState.Exporting]，UI 显示进度弹窗
     * 2. 调用 [PhotoExporter.exportPhotos] 在 IO 线程执行
     * 3. 成功后状态切到 [ExportState.Done]，UI 负责弹 Toast + 分享
     * 4. 失败时状态切到 [ExportState.Error]
     *
     * 调用前需保证 [uiState] 中已有客户行（rows 非空），否则直接报错。
     *
     * @param dimension 匹配维度（序号 / 地址 / 借款人）
     * @param keyword 关键词（空串匹配全部）
     * @param format 输出格式（PDF / ZIP）
     */
    fun exportPhotos(dimension: ExportDimension, keyword: String, format: ExportFormat) {
        val rows = uiState.value.rows
        if (rows.isEmpty()) {
            _exportState.value = ExportState.Error("请先导入客户清单")
            return
        }
        viewModelScope.launch {
            _exportState.value = ExportState.Exporting(current = 0, total = 0)
            try {
                val file = photoExporter.exportPhotos(
                    context = context,
                    rows = rows,
                    dimension = dimension,
                    keyword = keyword,
                    format = format,
                    onProgress = { current, total ->
                        _exportState.value = ExportState.Exporting(current, total)
                    },
                )
                if (file != null) {
                    _exportState.value = ExportState.Done(file, file.length())
                } else {
                    _exportState.value = ExportState.Error("未找到匹配的照片")
                }
            } catch (e: Exception) {
                Timber.e(e, "导出照片失败")
                _exportState.value = ExportState.Error(e.message ?: "导出失败")
            }
        }
    }

    /**
     * 重置导出状态到 [ExportState.Idle]，便于下一次导出。
     */
    fun resetExportState() {
        _exportState.value = ExportState.Idle
    }

    // ---- 内部辅助 ----

    /**
     * 加载最近文件并附带数据指示器（通过 [ExcelDataIndexRepository] 查询每个文件的 progressKey 数量）。
     */
    private fun loadRecentFiles() {
        viewModelScope.launch {
            try {
                val raw = recentFilesStorage.getRecentFiles()
                val items = withContext(Dispatchers.IO) {
                    raw.map { file ->
                        val md5 = ProgressKeyUtil.excelUriMd5(file.uri)
                        val keys = runCatching {
                            excelDataIndexRepository.getProgressKeys(md5)
                        }.getOrDefault(emptyList())
                        RecentFileItem(
                            uri = file.uri,
                            fileName = file.fileName,
                            timestamp = file.timestamp,
                            hasData = keys.isNotEmpty(),
                            dataCount = keys.size,
                        )
                    }
                }
                _uiState.update { it.copy(recentFiles = items) }
            } catch (e: Exception) {
                Timber.w(e, "加载最近文件失败")
            }
        }
    }

    /**
     * 拍照统计：每行总照片数 + 各分类计数。
     *
     * - [counts] 总照片数（key=progressKey）
     * - [typeCounts] 各分类计数（key=progressKey，value=PhotoType→数量）
     *
     * 优先使用 [PhotoRecord.photoTypes]（v4.0.1 新增，与 photos 平行的类型列表）
     * 精确计算各分类张数；旧数据（v4.0.0）无 photoTypes 时回退到 [PhotoRecord.types]
     * presence（每类计数为 1）。
     *
     * 在 IO 线程上顺序执行，因 [ProgressRepository.getProgress] 内部已加 Mutex，
     * 并发亦会被串行化。
     */
    private data class PhotoStats(
        val counts: Map<String, Int>,
        val typeCounts: Map<String, Map<PhotoType, Int>>,
    )

    private suspend fun loadPhotoStats(rows: List<CustomerRow>): PhotoStats =
        withContext(Dispatchers.IO) {
            val counts = HashMap<String, Int>(rows.size)
            val typeCounts = HashMap<String, Map<PhotoType, Int>>(rows.size)
            for (row in rows) {
                val record = runCatching {
                    progressRepository.getProgress(row.progressKey)
                }.getOrNull()
                val photos = record?.photos ?: emptyList()
                counts[row.progressKey] = photos.size
                // 优先使用 photoTypes（精确张数），旧数据回退到 types presence（每类 1）
                val typeMap: Map<PhotoType, Int> = if (!record?.photoTypes.isNullOrEmpty()) {
                    record!!.photoTypes
                        .mapNotNull { name -> name.takeIf { it.isNotBlank() } }
                        .groupingBy { PhotoType.fromDisplayName(it) }
                        .eachCount()
                } else {
                    record?.types
                        ?.mapNotNull { name -> name.takeIf { it.isNotBlank() } }
                        ?.associate { PhotoType.fromDisplayName(it) to 1 }
                        ?: emptyMap()
                }
                typeCounts[row.progressKey] = typeMap
            }
            PhotoStats(counts, typeCounts)
        }

    /**
     * 按 [field] 和 [query] 过滤行，返回匹配的 rowIndex 列表。
     * query 为空时返回全集（UNVISITED 状态过滤除外，它忽略 query）。
     *
     * - REMARK 字段查 [rowRemarks] (progress.json 的 _row_remarks[行号])
     * - UNVISITED 字段忽略 query，直接返回 photoCount==0 的行
     */
    private fun filterRows(
        rows: List<CustomerRow>,
        query: String,
        field: SearchField,
        rowRemarks: Map<String, String>,
        photoCounts: Map<String, Int>,
    ): List<Int> {
        if (field == SearchField.UNVISITED) {
            return rows.filter { row ->
                (photoCounts[row.progressKey] ?: 0) == 0
            }.map { it.rowIndex }
        }
        if (query.isBlank()) return rows.map { it.rowIndex }
        val keyword = query.trim()
        return rows.asSequence()
            .filter { row -> matchField(row, keyword, field, rowRemarks, photoCounts) }
            .map { it.rowIndex }
            .toList()
    }

    private fun matchField(
        row: CustomerRow,
        keyword: String,
        field: SearchField,
        rowRemarks: Map<String, String>,
        photoCounts: Map<String, Int>,
    ): Boolean {
        return when (field) {
            SearchField.SERIAL -> row.serial.contains(keyword, ignoreCase = true)
            SearchField.BORROWER -> row.borrower.contains(keyword, ignoreCase = true)
            SearchField.ADDR -> row.addrGeneral.contains(keyword, ignoreCase = true) ||
                row.addrDetail.contains(keyword, ignoreCase = true)
            SearchField.REMARK -> {
                // 查 progress.json 的 _row_remarks[行号] 是否 contains(query)
                val remark = rowRemarks[row.rowIndex.toString()] ?: row.remark
                remark.contains(keyword, ignoreCase = true)
            }
            SearchField.UNVISITED -> {
                (photoCounts[row.progressKey] ?: 0) == 0
            }
        }
    }
}
