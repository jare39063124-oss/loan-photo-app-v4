package com.banktool.loanphoto.ui.customer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.banktool.loanphoto.data.datasource.ColumnField
import com.banktool.loanphoto.data.datasource.ExcelReadResult
import com.banktool.loanphoto.data.datasource.ExcelWriter
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
 * 列映射待确认状态：文件无表头且未确认过列映射时，暂存样例与文件信息，
 * 由 UI 层弹出 [com.banktool.loanphoto.ui.customer.components.ColumnMappingDialog]。
 *
 * [previousColumnMap] 记录确认前的旧列映射（取自当前会话 [UiState.resolvedColumnMap]：
 * 会话内已加载过该文件时为真实生效映射，否则为会话默认映射），
 * 供 [CustomerListViewModel.confirmColumnMapping] 迁移映射前的已拍进度。
 */
data class PendingColumnMapping(
    val uri: String,
    val fileName: String,
    val excelUriMd5: String,
    val columnSamples: List<String>,
    val previousColumnMap: List<ColumnField>? = null,
)

/**
 * 客户清单列表 ViewModel。
 *
 * 职责：
 * - 加载 Excel 并按地址排序（支持列映射：无表头文件经用户确认后按映射解析/写回）
 * - 实时搜索过滤（保存匹配全集 [UiState.filteredIndices]）
 * - 全选作用域为 filteredIndices 全集（非仅可见行）
 * - 单行反选同步表头 CheckBox 状态
 * - 查询每个 progressKey 的照片数
 * - 管理最近文件列表（含数据指示器）
 * - 行级备注编辑（_row_remarks_<uriMd5>，按文件隔离）
 * - 同类型代表性户型标记（batch_marked）
 * - 搜索历史（最近 5 条，SharedPreferences，一/二级搜索栏共用）
 * - 条目全量编辑（updateCustomerRow：Excel 整行回写 + _row_remarks 同步 + 内存刷新）
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
    private val excelWriter: ExcelWriter,
) : ViewModel() {

    /**
     * UI 状态。
     *
     * - [filteredIndices] 保存匹配搜索的 rowIndex 全集（不是列表位置，而是 [CustomerRow.rowIndex]）
     * - [selectedRows] 保存被选中的 rowIndex 集合（与 filteredIndices 同维度，确保跨排序稳定）
     * - [rowRemarks] 行级备注 Map（key=行号字符串），来自 progress.json 的
     *   `_row_remarks_<uriMd5>`（按文件隔离）
     * - [batchMarkedKeys] 已标记为同类型代表性户型的 progressKey 集合
     * - [searchHistory] 一/二级搜索栏共用的历史关键词（最新在前，最多 5 条）
     * - [resolvedColumnMap] 当前文件生效的列映射（按物理列索引），编辑/备注写回时使用
     * - [pendingColumnMapping] 非空表示文件无表头且未确认列映射，UI 层应弹映射确认弹窗
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
        // 二级搜索：在一级结果基础上二次过滤；secondSearchQuery 为空时不生效
        val secondSearchField: SearchField = SearchField.SERIAL,
        val secondSearchQuery: String = "",
        val excelUri: String = "",
        val excelFileName: String = "",
        val excelUriMd5: String = "",
        val error: String? = null,
        val recentFiles: List<RecentFileItem> = emptyList(),
        val showRecentFilesSheet: Boolean = false,
        val isRefreshingCounts: Boolean = false,
        val rowRemarks: Map<String, String> = emptyMap(),
        val batchMarkedKeys: Set<String> = emptySet(),
        val editingRemarkRowIndex: Int? = null,
        val message: String? = null,
        val searchHistory: List<String> = emptyList(),
        val resolvedColumnMap: List<ColumnField> = ColumnField.DEFAULT,
        val pendingColumnMapping: PendingColumnMapping? = null,
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

    private val searchHistoryStore = SearchHistoryStore(context)

    init {
        loadRecentFiles()
        _uiState.update { it.copy(searchHistory = searchHistoryStore.getHistory()) }
    }

    /**
     * 加载并解析 [uri] 对应的 Excel 文件（含列映射决策）。
     *
     * 流程：
     * 1. 查询持久化列映射（excel_data_index.json 的 column_map）：
     *    有 → 直接注入解析（不探测表头、不弹窗），带表头标记决定起始行
     * 2. 无持久化映射 → 先探测解析：
     *    - parseHeader 命中表头 → 照旧渲染，并把 resolvedColumnMap 持久化
     *      （编辑写回与解析对称）
     *    - 未命中 → 记录 [UiState.pendingColumnMapping]（样例+文件信息），
     *      列表暂不渲染，UI 层弹列映射确认弹窗
     * 3. 用户确认映射后走 [confirmColumnMapping]：saveColumnMap + 按映射重新解析 → 渲染列表
     *
     * 其余逻辑与原有行为一致：按 [SortUtil.addressSortKey] 排序、查询照片数/行备注/
     * 批量标记、重置搜索/选中状态、写入最近文件。
     */
    fun loadExcel(uri: String, fileName: String = "") {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, pendingColumnMapping = null) }
            try {
                val md5 = ProgressKeyUtil.excelUriMd5(uri)
                val resolvedName = fileName.ifBlank {
                    recentFilesStorage.getRecentFiles().firstOrNull { it.uri == uri }?.fileName ?: "未知文件"
                }
                val savedMap = excelDataIndexRepository.getColumnMap(md5)
                val result: ExcelReadResult
                if (savedMap != null) {
                    val skipFirstRow = excelDataIndexRepository.hasHeaderRow(md5)
                    Timber.i(
                        "loadExcel: 使用持久化列映射 %s, skipFirstRow=%s, uriMd5=%s",
                        savedMap, skipFirstRow, md5,
                    )
                    result = excelRepository.readExcel(uri, savedMap, skipFirstRow)
                } else {
                    val detected = excelRepository.readExcel(uri)
                    if (detected.headerMatched) {
                        // 表头命中也持久化，保证后续编辑写回与解析对称
                        excelDataIndexRepository.saveColumnMap(md5, detected.resolvedColumnMap, hasHeader = true)
                        Timber.i(
                            "loadExcel: 表头识别命中，持久化列映射 %s, uriMd5=%s",
                            detected.resolvedColumnMap, md5,
                        )
                        result = detected
                    } else {
                        // 无表头且无映射：记录待确认状态，列表暂不渲染
                        Timber.i("loadExcel: 未识别到表头，待用户确认列映射, uriMd5=%s", md5)
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                pendingColumnMapping = PendingColumnMapping(
                                    uri = uri,
                                    fileName = resolvedName,
                                    excelUriMd5 = md5,
                                    columnSamples = detected.rawFirstRowSamples,
                                    // 记录确认前的旧映射，供确认后迁移旧进度（见 confirmColumnMapping）
                                    previousColumnMap = it.resolvedColumnMap,
                                ),
                            )
                        }
                        return@launch
                    }
                }
                applyLoadedExcel(uri, resolvedName, md5, result)
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
     * 用户在列映射确认弹窗点击「确认」：迁移旧进度、持久化映射并按映射重新解析渲染。
     *
     * 列映射改变会改变 borrower/addr 取值 → progressKey 变化 → 映射前已拍进度失联。
     * 因此在用新映射重解析得到 rows 后、保存新 column_map 前，若存在旧映射
     * （[PendingColumnMapping.previousColumnMap]）且与新映射不同，则用旧映射对同一批
     * 物理行重算 oldProgressKey，逐行 [ProgressRepository.copyProgress] 迁移到新键
     * （旧键条目保留不删、新键已有进度不覆盖）。
     *
     * 迁移失败不阻断导入；旧映射不存在（同会话内首次加载且无历史映射可参照）时跳过迁移。
     */
    fun confirmColumnMapping(mapping: List<ColumnField>) {
        val pending = _uiState.value.pendingColumnMapping ?: return
        viewModelScope.launch {
            try {
                // 先按新映射重解析，再保存映射：解析失败时保留旧状态便于用户重试
                val result = excelRepository.readExcel(pending.uri, mapping, skipFirstRow = false)
                val oldMap = pending.previousColumnMap
                if (oldMap != null && oldMap != mapping) {
                    val migrated = migrateProgressForRemap(pending.uri, oldMap, result)
                    Timber.i(
                        "confirmColumnMapping: 进度迁移 %d 条, uriMd5=%s",
                        migrated, pending.excelUriMd5,
                    )
                }
                excelDataIndexRepository.saveColumnMap(pending.excelUriMd5, mapping, hasHeader = false)
                Timber.i(
                    "confirmColumnMapping: 保存列映射 %s, uriMd5=%s",
                    mapping, pending.excelUriMd5,
                )
                applyLoadedExcel(pending.uri, pending.fileName, pending.excelUriMd5, result)
            } catch (e: Exception) {
                Timber.e(e, "应用列映射失败")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "应用列映射失败",
                        pendingColumnMapping = null,
                    )
                }
            }
        }
    }

    /**
     * 列映射变更时的进度迁移：用 [oldMap] 重读同一文件（与新解析一致从物理首行开始），
     * 按物理行号（[CustomerRow.rowIndex]）对齐新旧行，把旧键的已拍进度复制到新键。
     *
     * 全空行跳过逻辑随映射不同而不同：某行在旧映射下被跳过时无旧键，自然不迁移。
     * 返回触发 copyProgress 的行数；旧键条目不存在时 copyProgress 内部为无操作。
     */
    private suspend fun migrateProgressForRemap(
        uri: String,
        oldMap: List<ColumnField>,
        newResult: ExcelReadResult,
    ): Int {
        return try {
            val oldResult = excelRepository.readExcel(uri, oldMap, skipFirstRow = false)
            val oldKeysByRowIndex = oldResult.rows.associate { it.rowIndex to it.progressKey }
            var migrated = 0
            for (row in newResult.rows) {
                val oldKey = oldKeysByRowIndex[row.rowIndex] ?: continue
                if (oldKey == row.progressKey) continue
                progressRepository.copyProgress(oldKey, row.progressKey)
                migrated++
            }
            migrated
        } catch (e: Exception) {
            Timber.w(e, "列映射进度迁移失败（不阻断导入）")
            0
        }
    }

    /**
     * 用户在列映射确认弹窗点击「取消」：中断导入，回到空列表状态。
     */
    fun cancelColumnMapping() {
        _uiState.update { it.copy(pendingColumnMapping = null, isLoading = false) }
    }

    /**
     * 渲染解析结果：排序、照片统计、会话信息、UI 状态与最近文件（loadExcel/confirmColumnMapping 共用）。
     */
    private suspend fun applyLoadedExcel(
        uri: String,
        fileName: String,
        md5: String,
        result: ExcelReadResult,
    ) {
        val rawRows = result.rows
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
        val rowRemarks = progressRepository.getRowRemarks(md5)
        val batchMarkedKeys = progressRepository.getBatchMarkedKeys()
        photoSessionHolder.setExcelInfo(uri, fileName, md5)
        // 用户可能不经过拍照直接进入「AI 日报表」等报表路径，
        // 此处预填共享 VM 的行集合，否则报表页拿到的数据为空。
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
                excelFileName = fileName,
                excelUriMd5 = md5,
                resolvedColumnMap = result.resolvedColumnMap,
                pendingColumnMapping = null,
                searchQuery = "",
                secondSearchQuery = "",
                secondSearchField = SearchField.SERIAL,
                error = null,
                rowRemarks = rowRemarks,
                batchMarkedKeys = batchMarkedKeys,
            )
        }
        recentFilesStorage.addRecent(uri, fileName)
        loadRecentFiles()
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
            val filtered = applyTwoLevelFilter(
                current.rows, query, current.searchField,
                current.secondSearchQuery, current.secondSearchField,
                current.rowRemarks, current.photoCounts,
            )
            current.copy(searchQuery = query, filteredIndices = filtered)
        }
    }

    /**
     * 切换搜索字段后重新过滤。
     */
    fun onSearchFieldChange(field: SearchField) {
        _uiState.update { current ->
            val filtered = applyTwoLevelFilter(
                current.rows, current.searchQuery, field,
                current.secondSearchQuery, current.secondSearchField,
                current.rowRemarks, current.photoCounts,
            )
            current.copy(searchField = field, filteredIndices = filtered)
        }
    }

    /**
     * 二级搜索：输入即过滤（在一级结果基础上二次过滤）。
     *
     * [query] 为空时二级过滤不生效，直接返回一级结果。
     */
    fun onSecondSearchQueryChange(query: String) {
        _uiState.update { current ->
            val filtered = applyTwoLevelFilter(
                current.rows, current.searchQuery, current.searchField,
                query, current.secondSearchField,
                current.rowRemarks, current.photoCounts,
            )
            current.copy(secondSearchQuery = query, filteredIndices = filtered)
        }
    }

    /**
     * 切换二级搜索字段后重新过滤。
     */
    fun onSecondSearchFieldChange(field: SearchField) {
        _uiState.update { current ->
            val filtered = applyTwoLevelFilter(
                current.rows, current.searchQuery, current.searchField,
                current.secondSearchQuery, field,
                current.rowRemarks, current.photoCounts,
            )
            current.copy(secondSearchField = field, filteredIndices = filtered)
        }
    }

    // ---- 搜索历史 ----

    /**
     * 提交搜索（键盘搜索动作触发）：非空 query 写入搜索历史（去重、最新在前、最多 5 条）。
     *
     * 一/二级搜索栏共用同一份历史；过滤本身已由 onSearchQueryChange 实时完成，
     * 此处只负责落盘与刷新 [UiState.searchHistory]。
     */
    fun onSearchSubmit(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        searchHistoryStore.add(q)
        _uiState.update { it.copy(searchHistory = searchHistoryStore.getHistory()) }
    }

    /** 清空搜索历史。 */
    fun clearSearchHistory() {
        searchHistoryStore.clear()
        _uiState.update { it.copy(searchHistory = emptyList()) }
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
                val rowRemarks = progressRepository.getRowRemarks(current.excelUriMd5)
                val batchMarkedKeys = progressRepository.getBatchMarkedKeys()
                _uiState.update {
                    val filtered = applyTwoLevelFilter(
                        it.rows, it.searchQuery, it.searchField,
                        it.secondSearchQuery, it.secondSearchField,
                        rowRemarks, stats.counts,
                    )
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
     * 保存行级备注到 progress.json 的 `_row_remarks_<uriMd5>`，并回写到 Excel 备注映射列。
     *
     * - 先写入内部存储（progress.json 的 `_row_remarks_<uriMd5>`，按文件隔离）
     * - 若 [UiState.excelUri] 非空且列映射中存在备注列，额外调用
     *   [ExcelWriter.writeRemarksToExcel] 回写到映射列（默认 F 列）
     * - 列映射无备注列时跳过 Excel 回写（仅保存在 App 内，不提示错误）
     * - Excel 写入失败时不阻断内部存储保存，通过 [UiState.error] 提示用户
     * - Excel 写入成功时不额外提示，保持原有 "备注已保存" message
     */
    fun saveRowRemark(rowIndex: Int, content: String) {
        viewModelScope.launch {
            try {
                val md5 = _uiState.value.excelUriMd5
                progressRepository.saveRowRemark(md5, rowIndex, content)

                val currentUri = uiState.value.excelUri
                val remarkColumn = uiState.value.resolvedColumnMap.indexOf(ColumnField.REMARK)
                var excelWriteOk = true
                if (currentUri.isNotBlank() && remarkColumn >= 0) {
                    try {
                        excelWriteOk = excelWriter.writeRemarksToExcel(
                            Uri.parse(currentUri),
                            mapOf(rowIndex to content),
                            remarkColumn,
                        )
                    } catch (e: Exception) {
                        Timber.e(e, "备注回写 Excel 失败, rowIndex=%d", rowIndex)
                        excelWriteOk = false
                    }
                } else if (remarkColumn < 0) {
                    Timber.w("列映射无备注列，跳过 Excel 备注回写, uriMd5=%s", md5)
                }

                _uiState.update { current ->
                    val newRemarks = current.rowRemarks.toMutableMap()
                    if (content.isBlank()) {
                        newRemarks.remove(rowIndex.toString())
                    } else {
                        newRemarks[rowIndex.toString()] = content
                    }
                    if (excelWriteOk) {
                        current.copy(
                            rowRemarks = newRemarks,
                            editingRemarkRowIndex = null,
                            message = "备注已保存",
                        )
                    } else {
                        current.copy(
                            rowRemarks = newRemarks,
                            editingRemarkRowIndex = null,
                            error = "Excel 写入失败，备注仅保存在 App 内",
                        )
                    }
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
     * 列位置按当前文件 resolvedColumnMap 取 序号/客户名/地址概 对应列（无映射时 A/B/C）。
     *
     * - 成功后重新调用 [loadExcel] 拉取最新数据
     * - 失败时通过 [UiState.error] 提示用户确认文件可写
     */
    fun addSurveyEntry(serial: String, borrower: String, address: String) {
        val currentUri = uiState.value.excelUri
        if (currentUri.isBlank()) return
        viewModelScope.launch {
            try {
                val colMap = uiState.value.resolvedColumnMap
                val serialColumn = colMap.indexOf(ColumnField.SERIAL).takeIf { it >= 0 } ?: 0
                val borrowerColumn = colMap.indexOf(ColumnField.BORROWER).takeIf { it >= 0 } ?: 1
                val addressColumn = colMap.indexOf(ColumnField.ADDR_GENERAL).takeIf { it >= 0 } ?: 2
                val newRowIndex = excelRepository.appendRow(
                    currentUri,
                    serial,
                    borrower,
                    address,
                    serialColumn,
                    borrowerColumn,
                    addressColumn,
                )
                if (newRowIndex >= 0) {
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

    // ---- 编辑条目 ----

    /**
     * 更新一条客户条目（长按菜单「编辑条目」）。
     *
     * [updated] 由 EditEntryDialog 构造（copy 保留 rowIndex/progressKey），落盘策略沿用
     * 行级备注的双写机制并扩展为整行更新：
     * - Excel 整行回写（[ExcelWriter.updateRowToExcel]），列号按当前文件
     *   [UiState.resolvedColumnMap] 构建：字段有对应物理列才写（IGNORE 列/无对应列不写）
     * - progress.json 的 `_row_remarks_<uriMd5>` 同步备注（[ProgressRepository.saveRowRemark]），
     *   与「编辑备注」功能保持一致（显示时 _row_remarks 优先于 Excel 备注列）
     * - 内存刷新 [UiState.rows]（StateFlow，列表 UI 自动刷新）与 [PhotoSessionHolder.rows]
     *   （按 rowIndex 替换）。progressKey/rowIndex 保持不变，拍照进度关联不丢。
     *
     * Excel 写入失败不阻断内存更新，通过 [UiState.error] 提示（与 saveRowRemark 行为一致）。
     */
    fun updateCustomerRow(updated: CustomerRow) {
        viewModelScope.launch {
            try {
                // 按 resolvedColumnMap 构建写回值：字段→对应物理列（IGNORE 列/无对应列不写）
                val currentUri = uiState.value.excelUri
                var excelWriteOk = true
                if (currentUri.isNotBlank()) {
                    val values = buildRowWriteValues(uiState.value.resolvedColumnMap, updated)
                    excelWriteOk = runCatching {
                        excelWriter.updateRowToExcel(
                            Uri.parse(currentUri),
                            updated.rowIndex,
                            values,
                        )
                    }.getOrDefault(false)
                    if (!excelWriteOk) {
                        Timber.w("条目整行回写 Excel 失败, rowIndex=%d", updated.rowIndex)
                    }
                }

                // 行级备注展示时优先于表格备注列，此处双写保持两处一致
                progressRepository.saveRowRemark(_uiState.value.excelUriMd5, updated.rowIndex, updated.remark)

                // 内存刷新 rows + rowRemarks（rows 为 StateFlow，列表自动刷新）
                _uiState.update { current ->
                    val newRows = current.rows.map { row ->
                        if (row.rowIndex == updated.rowIndex) updated else row
                    }
                    val newRemarks = current.rowRemarks.toMutableMap().apply {
                        if (updated.remark.isBlank()) {
                            remove(updated.rowIndex.toString())
                        } else {
                            put(updated.rowIndex.toString(), updated.remark)
                        }
                    }
                    if (excelWriteOk) {
                        current.copy(
                            rows = newRows,
                            rowRemarks = newRemarks,
                            message = "条目已更新",
                        )
                    } else {
                        current.copy(
                            rows = newRows,
                            rowRemarks = newRemarks,
                            error = "Excel 写入失败，修改仅保存在 App 内",
                        )
                    }
                }

                // 当前会话若已持有行集合，按 rowIndex 原位替换
                val holderRows = photoSessionHolder.rows
                if (holderRows.any { it.rowIndex == updated.rowIndex }) {
                    photoSessionHolder.setPhotoRows(
                        holderRows.map { row ->
                            if (row.rowIndex == updated.rowIndex) updated else row
                        },
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "更新条目失败")
                _uiState.update { it.copy(error = e.message ?: "更新条目失败") }
            }
        }
    }

    // ---- 照片导出 ----

    /**
     * 触发照片导出：调用 [PhotoExporter.exportPhotos] 在 IO 线程执行，
     * 进度经 [ExportState.Exporting] 上报；成功转 [ExportState.Done]（UI 弹 Toast + 分享），
     * 失败转 [ExportState.Error]。
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
     * 按 [colMap] 构建整行写回值（[ExcelWriter.updateRowToExcel] 的 values）：
     * 字段→其映射的物理列号；IGNORE 列不写、无对应列的字段不写。
     * 同一字段映射到多列时取首次出现的列（先到优先，与解析逻辑一致）。
     */
    private fun buildRowWriteValues(colMap: List<ColumnField>, row: CustomerRow): Map<Int, String> {
        val fieldValues = listOf(
            ColumnField.SERIAL to row.serial,
            ColumnField.BORROWER to row.borrower,
            ColumnField.ADDR_GENERAL to row.addrGeneral,
            ColumnField.ADDR_DETAIL to row.addrDetail,
            ColumnField.PROPERTY_TYPE to row.propertyType,
            ColumnField.REMARK to row.remark,
        )
        val out = LinkedHashMap<Int, String>()
        for ((field, value) in fieldValues) {
            val idx = colMap.indexOf(field)
            if (idx >= 0) out[idx] = value
        }
        return out
    }

    /**
     * 加载最近文件并附带数据指示器（通过 [ExcelDataIndexRepository] 查询每个文件的 progressKey 数量）。
     */
    private fun loadRecentFiles() {
        viewModelScope.launch {
            try {
                val raw = recentFilesStorage.getRecentFiles()
                val items = withContext(Dispatchers.IO) {
                    // 一次读取整个 excel_data_index.json，避免每个最近文件重读+重解析
                    val allEntries = excelDataIndexRepository.getAllEntries()
                    raw.map { file ->
                        val md5 = ProgressKeyUtil.excelUriMd5(file.uri)
                        val keys = allEntries[md5] ?: emptyList()
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
     * 优先使用 [PhotoRecord.photoTypes]（与 photos 平行的类型列表）精确计算各分类张数；
     * 记录缺 photoTypes 字段时回退到 [PhotoRecord.types] presence（每类计数为 1）。
     *
     * 一次调用 [ProgressRepository.getAllProgress] 读取全部记录到内存，
     * 循环内仅做 O(1) Map 查找，避免每行重读+重解析整个 progress.json。
     */
    private data class PhotoStats(
        val counts: Map<String, Int>,
        val typeCounts: Map<String, Map<PhotoType, Int>>,
    )

    private suspend fun loadPhotoStats(rows: List<CustomerRow>): PhotoStats =
        withContext(Dispatchers.IO) {
            val allProgress = progressRepository.getAllProgress()
            val counts = HashMap<String, Int>(rows.size)
            val typeCounts = HashMap<String, Map<PhotoType, Int>>(rows.size)
            for (row in rows) {
                val record = allProgress[row.progressKey]
                val photos = record?.photos ?: emptyList()
                counts[row.progressKey] = photos.size
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
     * 两级过滤：先用一级条件过滤，再用二级条件在一级结果上二次过滤。
     *
     * 二级 [secondQuery] 为空时直接返回一级结果（二级过滤不生效）。
     * 返回匹配的 rowIndex 列表。
     */
    private fun applyTwoLevelFilter(
        rows: List<CustomerRow>,
        firstQuery: String,
        firstField: SearchField,
        secondQuery: String,
        secondField: SearchField,
        rowRemarks: Map<String, String>,
        photoCounts: Map<String, Int>,
    ): List<Int> {
        // 一级过滤
        val firstFiltered = applySingleFilter(rows, firstQuery, firstField, rowRemarks, photoCounts)
        val finalFiltered = if (secondQuery.isBlank()) {
            firstFiltered
        } else {
            applySingleFilter(firstFiltered, secondQuery, secondField, rowRemarks, photoCounts)
        }
        return finalFiltered.map { it.rowIndex }
    }

    /**
     * 单级过滤：按 [field] 和 [query] 过滤行，返回匹配的 CustomerRow 列表。
     *
     * - VISITED/UNVISITED：先按状态过滤（photoCount>0 / ==0），
     *   query 非空时再按文本匹配全量字段（序号 / 客户名 / 地址 / 备注，任一命中）
     * - 其他字段：query 为空时返回全集；query 非空时按指定字段匹配
     *
     * REMARK 字段查 [rowRemarks]（progress.json 的 _row_remarks[行号]）。
     */
    private fun applySingleFilter(
        rows: List<CustomerRow>,
        query: String,
        field: SearchField,
        rowRemarks: Map<String, String>,
        photoCounts: Map<String, Int>,
    ): List<CustomerRow> {
        if (field == SearchField.UNVISITED || field == SearchField.VISITED) {
            val wantVisited = field == SearchField.VISITED
            val statusFiltered = rows.filter { row ->
                val count = photoCounts[row.progressKey] ?: 0
                if (wantVisited) count > 0 else count == 0
            }
            if (query.isBlank()) return statusFiltered
            val keyword = query.trim()
            return statusFiltered.filter { row -> matchAnyField(row, keyword, rowRemarks) }
        }
        // 普通字段
        if (query.isBlank()) return rows
        val keyword = query.trim()
        return rows.filter { row -> matchField(row, keyword, field, rowRemarks) }
    }

    /**
     * 全量字段文本匹配：序号 / 客户名 / 地址 / 备注，任一包含即命中。
     * 用于 VISITED/UNVISITED 选中后的文本搜索。
     */
    private fun matchAnyField(
        row: CustomerRow,
        keyword: String,
        rowRemarks: Map<String, String>,
    ): Boolean {
        if (row.serial.contains(keyword, ignoreCase = true)) return true
        if (row.borrower.contains(keyword, ignoreCase = true)) return true
        if (row.addrGeneral.contains(keyword, ignoreCase = true)) return true
        if (row.addrDetail.contains(keyword, ignoreCase = true)) return true
        val remark = rowRemarks[row.rowIndex.toString()] ?: row.remark
        if (remark.contains(keyword, ignoreCase = true)) return true
        return false
    }

    /**
     * 按指定 [field] 匹配单个字段（非状态字段）。
     */
    private fun matchField(
        row: CustomerRow,
        keyword: String,
        field: SearchField,
        rowRemarks: Map<String, String>,
    ): Boolean {
        return when (field) {
            SearchField.SERIAL -> row.serial.contains(keyword, ignoreCase = true)
            SearchField.BORROWER -> row.borrower.contains(keyword, ignoreCase = true)
            SearchField.ADDR -> row.addrGeneral.contains(keyword, ignoreCase = true) ||
                row.addrDetail.contains(keyword, ignoreCase = true)
            SearchField.REMARK -> {
                val remark = rowRemarks[row.rowIndex.toString()] ?: row.remark
                remark.contains(keyword, ignoreCase = true)
            }
            // 状态字段的文本匹配已在调用方的全字段匹配逻辑中处理，此分支正常不会进入
            SearchField.UNVISITED, SearchField.VISITED -> false
        }
    }
}
