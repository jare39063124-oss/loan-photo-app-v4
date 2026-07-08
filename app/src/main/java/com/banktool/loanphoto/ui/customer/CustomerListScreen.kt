package com.banktool.loanphoto.ui.customer

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.banktool.loanphoto.domain.entity.CustomerRow
import com.banktool.loanphoto.domain.entity.SearchField
import com.banktool.loanphoto.ui.camera.PhotoGallerySheet
import com.banktool.loanphoto.ui.customer.components.CustomerRowItem
import com.banktool.loanphoto.ui.customer.components.RecentFilesBottomSheet
import com.banktool.loanphoto.ui.customer.components.RowLongPressMenu
import com.banktool.loanphoto.ui.progress.RemarkDialog
import com.banktool.loanphoto.ui.theme.Accent
import com.banktool.loanphoto.ui.theme.Bg
import com.banktool.loanphoto.ui.theme.Card as CardColor
import com.banktool.loanphoto.ui.theme.Divider
import com.banktool.loanphoto.ui.theme.Error
import com.banktool.loanphoto.ui.theme.Text as TextColor
import com.banktool.loanphoto.ui.theme.TextSecondary
import java.io.File

/**
 * 客户清单列表主界面。
 *
 * 布局：
 * - Scaffold + TopAppBar（标题 + 导入/最近/查看进度/AI 功能/设置按钮）
 * - 搜索栏（全选 CheckBox + 字段下拉 + 输入框）
 * - LazyColumn（itemsIndexed, key = rowIndex）
 * - FAB（批量拍照）
 *
 * SAF 导入使用 [ActivityResultContracts.OpenDocument]，并持久化 URI 读权限。
 *
 * @param viewModel 列表 ViewModel
 * @param onImportExcel 外部导入回调（Screen 内部已实现 SAF，此回调作为可选 hook）
 * @param onGenerateReport 跳转 AI 日报表
 * @param onTakePhoto 跳转拍照（传入选中行）
 * @param onViewProgress 查看进度（跳转 ProgressScreen）
 * @param onOpenAssistant 跳转 AI 拍摄手
 * @param onOpenSettings 跳转设置页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerListScreen(
    viewModel: CustomerListViewModel = hiltViewModel(),
    onImportExcel: () -> Unit = {},
    onGenerateReport: () -> Unit,
    onTakePhoto: (List<CustomerRow>) -> Unit,
    onViewProgress: () -> Unit = {},
    onOpenAssistant: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // AI 功能下拉菜单展开状态
    var showAiMenu by remember { mutableStateOf(false) }

    // 已拍照片画廊 BottomSheet 当前关联的行（null 表示不显示）
    var gallerySheetRow by remember { mutableStateOf<CustomerRow?>(null) }

    // 添加查勘条目弹窗是否显示
    var showAddEntryDialog by remember { mutableStateOf(false) }

    // 拍照返回后刷新照片计数（含各分类计数）
    LifecycleResumeEffect(Unit) {
        viewModel.refreshPhotoCounts()
        onPauseOrDispose { }
    }

    // SAF Excel 导入
    val pickExcel = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            // 持久化 URI 读写权限，避免重启后失效（写权限用于 Excel 备注 F 列回写）
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            val fileName = runCatching {
                DocumentFile.fromSingleUri(context, uri)?.name
            }.getOrNull() ?: "未知文件"
            viewModel.loadExcel(uri.toString(), fileName)
        }
    }

    fun launchExcelPicker() {
        pickExcel.launch(
            arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel",
            ),
        )
    }

    // 消息提示
    LaunchedEffect(uiState.message, uiState.error) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // 解析当前过滤后的可见行（按 rowIndex 查找）
    val visibleRows: List<CustomerRow> = remember(
        uiState.filteredIndices,
        uiState.rows,
    ) {
        if (uiState.filteredIndices.isEmpty() || uiState.rows.isEmpty()) {
            emptyList()
        } else {
            val byKey = uiState.rows.associateBy { it.rowIndex }
            uiState.filteredIndices.mapNotNull { byKey[it] }
        }
    }

    val allFilteredSelected = uiState.filteredIndices.isNotEmpty() &&
        uiState.selectedRows.containsAll(uiState.filteredIndices)

    // 最近文件 BottomSheet
    if (uiState.showRecentFilesSheet) {
        RecentFilesBottomSheet(
            recentFiles = uiState.recentFiles,
            onFileSelected = { uri ->
                viewModel.hideRecentFilesSheet()
                viewModel.loadFromRecent(uri)
            },
            onRemoveFile = { uri ->
                viewModel.removeRecentFile(uri)
            },
            onDismiss = { viewModel.hideRecentFilesSheet() },
        )
    }

    // 备注编辑弹窗
    uiState.editingRemarkRowIndex?.let { rowIndex ->
        val row = uiState.rows.firstOrNull { it.rowIndex == rowIndex }
        val rowTitle = row?.let { "[${it.serial}] ${it.borrower}" } ?: ""
        RemarkDialog(
            initialContent = viewModel.getRowRemark(rowIndex),
            rowTitle = rowTitle,
            onSave = { content ->
                viewModel.saveRowRemark(rowIndex, content)
            },
            onDismiss = { viewModel.dismissEditRemark() },
        )
    }

    // 添加查勘条目弹窗
    if (showAddEntryDialog) {
        AddEntryDialog(
            onSave = { serial, borrower, address ->
                showAddEntryDialog = false
                viewModel.addSurveyEntry(serial, borrower, address)
            },
            onDismiss = { showAddEntryDialog = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "资产盘点拍照",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = TextColor,
                        )
                        if (uiState.excelFileName.isNotBlank()) {
                            Text(
                                text = uiState.excelFileName,
                                fontSize = 12.sp,
                                color = TextSecondary,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { launchExcelPicker() }) {
                        Icon(
                            imageVector = Icons.Filled.Upload,
                            contentDescription = "导入 Excel",
                            tint = Accent,
                        )
                    }
                    IconButton(onClick = { viewModel.showRecentFilesSheet() }) {
                        Icon(
                            imageVector = Icons.Filled.History,
                            contentDescription = "最近文件",
                            tint = Accent,
                        )
                    }
                    if (uiState.rows.isNotEmpty()) {
                        IconButton(onClick = onViewProgress) {
                            Icon(
                                imageVector = Icons.Filled.Assessment,
                                contentDescription = "查看进度",
                                tint = Accent,
                            )
                        }
                    }
                    // AI 功能入口（机器人图标 + 下拉菜单）
                    Box {
                        IconButton(onClick = { showAiMenu = true }) {
                            Icon(
                                imageVector = Icons.Filled.SmartToy,
                                contentDescription = "AI 功能",
                                tint = Accent,
                            )
                        }
                        DropdownMenu(
                            expanded = showAiMenu,
                            onDismissRequest = { showAiMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("AI 日报表") },
                                onClick = {
                                    showAiMenu = false
                                    onGenerateReport()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("AI 拍摄手") },
                                onClick = {
                                    showAiMenu = false
                                    onOpenAssistant()
                                },
                            )
                        }
                    }
                    // 设置入口
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "设置",
                            tint = Accent,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CardColor,
                    titleContentColor = TextColor,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (uiState.rows.isNotEmpty()) {
                FloatingActionButton(
                    onClick = {
                        val selected = viewModel.getSelectedRows()
                        if (selected.isEmpty()) {
                            onTakePhoto(visibleRows)
                        } else {
                            onTakePhoto(selected)
                        }
                    },
                    containerColor = Accent,
                    contentColor = CardColor,
                ) {
                    Icon(
                        imageVector = Icons.Filled.CameraAlt,
                        contentDescription = "批量拍照",
                    )
                }
            }
        },
        containerColor = Bg,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 搜索栏
            SearchBarRow(
                searchField = uiState.searchField,
                searchQuery = uiState.searchQuery,
                allFilteredSelected = allFilteredSelected,
                hasFilteredItems = uiState.filteredIndices.isNotEmpty(),
                onSearchQueryChange = viewModel::onSearchQueryChange,
                onSearchFieldChange = viewModel::onSearchFieldChange,
                onToggleSelectAll = viewModel::toggleSelectAll,
            )

            // 状态条
            StatusBar(uiState = uiState)

            // 添加查勘条目按钮（仅在有数据时显示，避免无文件时点击无响应）
            if (uiState.rows.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(onClick = { showAddEntryDialog = true }) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            tint = Accent,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "添加查勘条目", color = Accent)
                    }
                }
            }

            // 列表 / 空态 / 加载态
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    uiState.isLoading -> LoadingState()
                    uiState.rows.isEmpty() -> EmptyState(
                        onImport = { launchExcelPicker() },
                        recentFiles = uiState.recentFiles.take(5),
                        onFileSelected = { uri -> viewModel.loadFromRecent(uri) },
                    )
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            items(
                                items = visibleRows,
                                key = { row -> row.rowIndex },
                            ) { row ->
                                // 长按菜单状态：每行独立
                                var menuExpanded by remember { mutableStateOf(false) }
                                Box {
                                    CustomerRowItem(
                                        row = row,
                                        photoCount = uiState.photoCounts[row.progressKey] ?: 0,
                                        photoTypeCounts = uiState.photoTypeCounts[row.progressKey] ?: emptyMap(),
                                        isSelected = row.rowIndex in uiState.selectedRows,
                                        isBatchMarked = row.progressKey in uiState.batchMarkedKeys,
                                        onSelectionToggle = { viewModel.toggleRowSelection(row.rowIndex) },
                                        onTakePhoto = { onTakePhoto(listOf(row)) },
                                        onViewPhotos = { gallerySheetRow = row },
                                        onEditRemark = {
                                            viewModel.startEditRemark(row.rowIndex)
                                        },
                                        onLongClick = { menuExpanded = true },
                                    )
                                    RowLongPressMenu(
                                        expanded = menuExpanded,
                                        isMarked = row.progressKey in uiState.batchMarkedKeys,
                                        onDismiss = { menuExpanded = false },
                                        onToggleBatchMark = {
                                            viewModel.toggleBatchMarked(row.progressKey)
                                        },
                                        onViewPhotos = { gallerySheetRow = row },
                                        onEditRemark = {
                                            viewModel.startEditRemark(row.rowIndex)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // 刷新计数指示器
                if (uiState.isRefreshingCounts) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(16.dp)
                            .align(Alignment.TopCenter)
                            .size(28.dp),
                        strokeWidth = 2.dp,
                        color = Accent,
                    )
                }
            }
        }
    }

    // 已拍照片画廊 BottomSheet
    gallerySheetRow?.let { row ->
        val galleryContext = LocalContext.current
        val thumbnails = remember(gallerySheetRow) {
            val thumbDir = File(
                galleryContext.getExternalFilesDir(null),
                "thumbnails/${row.progressKey.ifBlank { "default" }.replace(Regex("[^A-Za-z0-9._-]"), "_")}",
            )
            if (thumbDir.exists()) {
                thumbDir.listFiles { f -> f.extension.equals("jpg", true) }
                    ?.sortedBy { it.lastModified() }
                    ?.map { it.absolutePath }
                    ?: emptyList()
            } else {
                emptyList()
            }
        }
        PhotoGallerySheet(
            thumbnails = thumbnails,
            totalCount = thumbnails.size,
            onDismiss = { gallerySheetRow = null },
            customerName = row.borrower,
        )
    }
}

/**
 * 搜索栏：全选 CheckBox + 字段下拉 + 输入框。
 */
@Composable
private fun SearchBarRow(
    searchField: SearchField,
    searchQuery: String,
    allFilteredSelected: Boolean,
    hasFilteredItems: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onSearchFieldChange: (SearchField) -> Unit,
    onToggleSelectAll: () -> Unit,
) {
    var dropdownExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 全选 CheckBox（置于搜索栏最左侧）
        Checkbox(
            checked = allFilteredSelected,
            onCheckedChange = { onToggleSelectAll() },
            enabled = hasFilteredItems,
            colors = CheckboxDefaults.colors(
                checkedColor = Accent,
                uncheckedColor = TextSecondary,
            ),
        )

        Spacer(modifier = Modifier.width(8.dp))

        // 字段下拉触发器
        Box {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = CardColor,
                border = androidx.compose.foundation.BorderStroke(1.dp, Divider),
                modifier = Modifier
                    .width(112.dp)
                    .clickable { dropdownExpanded = true },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = searchField.displayName,
                        fontSize = 14.sp,
                        color = TextColor,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        tint = TextSecondary,
                    )
                }
            }
            DropdownMenu(
                expanded = dropdownExpanded,
                onDismissRequest = { dropdownExpanded = false },
            ) {
                SearchField.values().forEach { field ->
                    DropdownMenuItem(
                        text = { Text(field.displayName) },
                        onClick = {
                            onSearchFieldChange(field)
                            dropdownExpanded = false
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 搜索输入框：状态过滤字段（如"未走访"）隐藏输入框，显示提示文字
        if (searchField.isStatusFilter) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = TextSecondary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "已自动筛选未走访条目",
                    color = TextSecondary,
                    fontSize = 14.sp,
                )
            }
        } else {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = "搜索${searchField.displayName}",
                        color = TextSecondary,
                        fontSize = 14.sp,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = TextSecondary,
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Divider,
                ),
                textStyle = TextStyle(fontSize = 14.sp, color = TextColor),
            )
        }
    }
}

/**
 * 顶部状态条：显示过滤后行数 / 选中行数 / 错误信息。
 */
@Composable
private fun StatusBar(uiState: CustomerListViewModel.UiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val total = uiState.rows.size
        val filtered = uiState.filteredIndices.size
        val selected = uiState.selectedRows.size
        val summary = buildString {
            append("共 $total 条")
            if (filtered != total) append("  匹配 $filtered")
            if (selected > 0) append("  选中 $selected")
        }
        Text(
            text = summary,
            fontSize = 12.sp,
            color = TextSecondary,
        )
        if (uiState.error != null) {
            Text(
                text = uiState.error,
                fontSize = 12.sp,
                color = Error,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun LoadingState() {
    Dialog(onDismissRequest = {}) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = CardColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(
                    color = Accent,
                    strokeWidth = 5.dp,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(modifier = Modifier.size(16.dp))
                Text(
                    text = "加载中...",
                    color = TextColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(
    onImport: () -> Unit,
    recentFiles: List<RecentFileItem> = emptyList(),
    onFileSelected: (String) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Upload,
            contentDescription = null,
            tint = Divider,
            modifier = Modifier.size(64.dp),
        )
        Spacer(modifier = Modifier.size(16.dp))
        Text(
            text = "请导入 Excel 文件",
            color = TextColor,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = "支持 .xlsx 格式，点击右上角导入按钮",
            color = TextSecondary,
            fontSize = 13.sp,
        )
        Spacer(modifier = Modifier.size(16.dp))
        IconButton(onClick = onImport) {
            Icon(
                imageVector = Icons.Filled.Upload,
                contentDescription = "导入",
                tint = Accent,
            )
        }
        // 最近打开文件
        if (recentFiles.isNotEmpty()) {
            Spacer(modifier = Modifier.size(24.dp))
            Text(
                text = "最近打开",
                color = TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.size(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            ) {
                recentFiles.forEach { file ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = CardColor,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Divider),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onFileSelected(file.uri) },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.History,
                                contentDescription = null,
                                tint = Accent,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = file.fileName,
                                color = TextColor,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 添加查勘条目弹窗。
 *
 * 三个输入字段：序号（可选）、借款人（必填）、地址（必填）。
 * 借款人或地址为空时禁用保存按钮。
 */
@Composable
private fun AddEntryDialog(
    onSave: (serial: String, borrower: String, address: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var serial by remember { mutableStateOf("") }
    var borrower by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    val canSave = borrower.isNotBlank() && address.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加查勘条目", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = serial,
                    onValueChange = { serial = it },
                    label = { Text("序号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.size(8.dp))
                OutlinedTextField(
                    value = borrower,
                    onValueChange = { borrower = it },
                    label = { Text("借款人 *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.size(8.dp))
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("地址 *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(serial, borrower, address) },
                enabled = canSave,
            ) {
                Text("保存", color = if (canSave) Accent else TextSecondary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
