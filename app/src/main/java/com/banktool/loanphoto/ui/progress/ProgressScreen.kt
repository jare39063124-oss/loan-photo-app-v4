package com.banktool.loanphoto.ui.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.banktool.loanphoto.ui.theme.Accent
import com.banktool.loanphoto.ui.theme.Bg
import com.banktool.loanphoto.ui.theme.Card as CardColor
import com.banktool.loanphoto.ui.theme.Divider
import com.banktool.loanphoto.ui.theme.Error
import com.banktool.loanphoto.ui.theme.HighlightBg
import com.banktool.loanphoto.ui.theme.Success
import com.banktool.loanphoto.ui.theme.Text as TextColor
import com.banktool.loanphoto.ui.theme.TextSecondary
import com.banktool.loanphoto.ui.theme.Warning

/**
 * 进度查看界面。
 *
 * 布局：
 * - Scaffold + TopAppBar（返回 + 标题）
 * - 顶部统计卡片（总客户数 / 已拍摄数 / 待拍摄数）
 * - LazyColumn 每行: 客户名 + 地址 + 已拍数 + 类型标签 + 备注
 * - 底部按钮: 导出备注到Excel / 清除数据 / 走访备注
 *
 * @param excelUri Excel 文件 URI
 * @param fileName Excel 文件名
 * @param onBack 返回回调
 * @param onGenerateReport 生成报表回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(
    excelUri: String,
    fileName: String,
    onBack: () -> Unit,
    onGenerateReport: () -> Unit,
    viewModel: ProgressViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 首次进入时加载数据
    LaunchedEffect(excelUri) {
        if (excelUri.isNotBlank() && uiState.excelUri != excelUri) {
            viewModel.loadProgress(excelUri, fileName)
        }
    }

    // 消息提示
    LaunchedEffect(uiState.message, uiState.error) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    var showClearDialog by remember { mutableStateOf(false) }
    var showVisitNoteDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        ClearDataDialog(
            fileName = uiState.excelFileName,
            onConfirm = {
                showClearDialog = false
                viewModel.clearData()
            },
            onDismiss = { showClearDialog = false },
        )
    }

    if (showVisitNoteDialog) {
        VisitNoteDialog(
            initialContent = uiState.visitNote,
            fileName = uiState.excelFileName,
            onSave = { content ->
                showVisitNoteDialog = false
                viewModel.saveVisitNote(content)
            },
            onGenerateReport = { content ->
                showVisitNoteDialog = false
                viewModel.saveVisitNote(content)
                onGenerateReport()
            },
            onDismiss = { showVisitNoteDialog = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "拍照进度",
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
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
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
        containerColor = Bg,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 顶部统计卡片
            StatisticsBar(uiState = uiState)

            // 列表
            Box(modifier = Modifier.weight(1f)) {
                when {
                    uiState.isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = Accent)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("正在加载...", color = TextSecondary, fontSize = 14.sp)
                            }
                        }
                    }
                    uiState.items.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "暂无进度数据",
                                color = TextSecondary,
                                fontSize = 14.sp,
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
                        ) {
                            items(
                                items = uiState.items,
                                key = { it.row.rowIndex },
                            ) { item ->
                                ProgressRowCard(item = item)
                            }
                        }
                    }
                }
            }

            // 底部按钮栏
            BottomActionBar(
                uiState = uiState,
                onExport = { viewModel.exportRemarksToExcel() },
                onClear = { showClearDialog = true },
                onVisitNote = { showVisitNoteDialog = true },
            )
        }
    }
}

/**
 * 顶部统计卡片：总客户数 / 已拍摄数 / 待拍摄数。
 */
@Composable
private fun StatisticsBar(uiState: ProgressViewModel.UiState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = CardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatItem(
                label = "总客户",
                value = uiState.totalCount,
                color = Accent,
            )
            StatItem(
                label = "已拍摄",
                value = uiState.visitedCount,
                color = Success,
            )
            StatItem(
                label = "待拍摄",
                value = uiState.pendingCount,
                color = Warning,
            )
        }
    }
}

@Composable
private fun StatItem(label: String, value: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value.toString(),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = TextSecondary,
        )
    }
}

/**
 * 单行进度卡片：客户名 + 地址 + 已拍数 + 类型标签 + 备注。
 */
@Composable
private fun ProgressRowCard(item: ProgressItem) {
    val containerColor = if (item.isBatchMarked) HighlightBg else CardColor
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // 第一行：[序号] 客户名 + 同类型标记星标
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.row.serial.isNotBlank()) {
                        Text(
                            text = "[${item.row.serial}]",
                            color = Accent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = item.row.borrower.ifBlank { "未命名客户" },
                        color = TextColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (item.isBatchMarked) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = "同类型代表性户型",
                            tint = Warning,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                // 第二行：地址
                val address = listOf(item.row.addrGeneral, item.row.addrDetail)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                if (address.isNotBlank()) {
                    Text(
                        text = address,
                        color = TextSecondary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // 第三行：性质 + 备注
                val meta = listOf(item.row.propertyType, item.remark)
                    .filter { it.isNotBlank() }
                    .joinToString("  |  ")
                if (meta.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = meta,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 已拍数 Badge
            Spacer(modifier = Modifier.width(8.dp))
            PhotoCountBadge(photoCount = item.photoCount)
        }
    }
}

/**
 * 圆形照片数 Badge。
 */
@Composable
private fun PhotoCountBadge(photoCount: Int) {
    val bgColor = if (photoCount > 0) Accent else Divider
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = photoCount.toString(),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * 底部按钮栏：导出备注 / 清除数据 / 走访备注。
 */
@Composable
private fun BottomActionBar(
    uiState: ProgressViewModel.UiState,
    onExport: () -> Unit,
    onClear: () -> Unit,
    onVisitNote: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardColor)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = onExport,
            enabled = !uiState.isExporting && uiState.items.isNotEmpty(),
            modifier = Modifier.weight(1f),
        ) {
            if (uiState.isExporting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Accent,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Save,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text("导出备注", fontSize = 13.sp)
        }

        OutlinedButton(
            onClick = onVisitNote,
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = Icons.Filled.NoteAlt,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("走访备注", fontSize = 13.sp)
        }

        Button(
            onClick = onClear,
            enabled = !uiState.isClearing && uiState.items.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(containerColor = Error),
            modifier = Modifier.weight(1f),
        ) {
            if (uiState.isClearing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Clear,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text("清除数据", fontSize = 13.sp, color = Color.White)
        }
    }
}
