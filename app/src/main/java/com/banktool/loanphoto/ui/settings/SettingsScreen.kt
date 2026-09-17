package com.banktool.loanphoto.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.banktool.loanphoto.BuildConfig
import com.banktool.loanphoto.data.camera.PhotoQuality
import com.banktool.loanphoto.data.license.LicenseChecker
import com.banktool.loanphoto.data.naming.NameSegment
import com.banktool.loanphoto.domain.entity.PhotoTypeConfig
import com.banktool.loanphoto.ui.customer.shareExportedFile
import com.banktool.loanphoto.ui.theme.Accent
import com.banktool.loanphoto.ui.theme.Bg
import com.banktool.loanphoto.ui.theme.Card as CardColor
import com.banktool.loanphoto.ui.theme.Divider
import com.banktool.loanphoto.ui.theme.Error
import com.banktool.loanphoto.ui.theme.HighlightBg
import com.banktool.loanphoto.ui.theme.Text as TextColor
import com.banktool.loanphoto.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 设置页面。
 *
 * 卡片内容：
 * - 关于：应用名「资产盘点拍照工具」+ 版本号（[BuildConfig.VERSION_NAME]）
 * - AI 模型：OpenRouter `openrouter`（[BuildConfig.OPENROUTER_MODEL]）
 * - 照片命名规则：4 段下拉选择器（CUSTOM 段附带自定义文本输入）+ 实时预览（持久化到 DataStore）
 * - 清空缓存：删除 `getExternalFilesDir/photos` 与 `reports` 目录（带确认对话框）
 *
 * 使用 Fluent Design 浅色主题。
 *
 * @param onBack 返回上一页
 * @param viewModel 设置页 ViewModel（默认由 Hilt 提供）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    var logContent by remember { mutableStateOf("") }

    val namingConfig by viewModel.namingConfig.collectAsStateWithLifecycle()
    val watermarkConfig by viewModel.watermarkConfig.collectAsStateWithLifecycle()
    val photoQuality by viewModel.photoQuality.collectAsStateWithLifecycle()
    val guideLineConfig by viewModel.guideLineConfig.collectAsStateWithLifecycle()
    val photoTypeConfigs by viewModel.photoTypeConfigs.collectAsStateWithLifecycle()
    val logEnabled by viewModel.logEnabled.collectAsStateWithLifecycle()
    val logFileExists by viewModel.logFileExists.collectAsStateWithLifecycle()

    // 拍照类型编辑 / 新增 / 删除对话框状态
    var editingConfig by remember { mutableStateOf<PhotoTypeConfig?>(null) }
    var showAddTypeDialog by remember { mutableStateOf(false) }
    var deletingConfig by remember { mutableStateOf<PhotoTypeConfig?>(null) }

    // 设备识别码 / 设备信息（用于「关于」卡片展示，授权激活时需将识别码告知作者）
    val licenseChecker = remember { LicenseChecker() }
    val deviceId = remember { licenseChecker.getDeviceId(context) }
    val deviceInfo = remember { licenseChecker.getDeviceInfo() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "设置",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = TextColor,
                    )
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
        containerColor = Bg,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 关于
            SettingsCard(title = "关于") {
                SettingsRow(label = "应用名", value = "资产盘点拍照工具")
                SettingsDivider()
                SettingsRow(label = "版本", value = BuildConfig.VERSION_NAME)
                SettingsDivider()
                SettingsRow(label = "设备识别码", value = deviceId)
                SettingsDivider()
                SettingsRow(label = "设备信息", value = deviceInfo)
                SettingsDivider()
                SettingsRow(label = "联系方式", value = "15940454123（微信同）")
            }

            // AI 模型
            SettingsCard(title = "AI 模型") {
                SettingsRow(label = "服务商", value = "OpenRouter")
                SettingsDivider()
                SettingsRow(label = "模型", value = BuildConfig.OPENROUTER_MODEL)
            }

            // 照片命名规则
            SettingsCard(title = "照片命名规则") {
                Text(
                    text = "选择 4 段命名组成部分，按顺序以「-」拼接生成照片文件名。选「自定义文本」的段可在下方输入固定文本。",
                    fontSize = 13.sp,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.size(12.dp))
                NamingSegmentConfigRow(
                    index = 0,
                    segment = namingConfig.segment1,
                    customText = namingConfig.customText1,
                    onSelect = { viewModel.setSegment(0, it) },
                    onCustomTextChange = { viewModel.setCustomText(0, it) },
                )
                Spacer(modifier = Modifier.size(8.dp))
                NamingSegmentConfigRow(
                    index = 1,
                    segment = namingConfig.segment2,
                    customText = namingConfig.customText2,
                    onSelect = { viewModel.setSegment(1, it) },
                    onCustomTextChange = { viewModel.setCustomText(1, it) },
                )
                Spacer(modifier = Modifier.size(8.dp))
                NamingSegmentConfigRow(
                    index = 2,
                    segment = namingConfig.segment3,
                    customText = namingConfig.customText3,
                    onSelect = { viewModel.setSegment(2, it) },
                    onCustomTextChange = { viewModel.setCustomText(2, it) },
                )
                Spacer(modifier = Modifier.size(8.dp))
                NamingSegmentConfigRow(
                    index = 3,
                    segment = namingConfig.segment4,
                    customText = namingConfig.customText4,
                    onSelect = { viewModel.setSegment(3, it) },
                    onCustomTextChange = { viewModel.setCustomText(3, it) },
                )
                Spacer(modifier = Modifier.size(12.dp))
                SettingsDivider()
                Spacer(modifier = Modifier.size(12.dp))
                Text(
                    text = "示例预览",
                    fontSize = 12.sp,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = viewModel.previewFileName(namingConfig),
                    fontSize = 13.sp,
                    color = Accent,
                    fontWeight = FontWeight.Medium,
                )
            }

            // 水印设置（内容与相机页水印设置弹窗共用 WatermarkSettingsContent）
            SettingsCard(title = "水印设置") {
                WatermarkSettingsContent(
                    config = watermarkConfig,
                    onEnabledChange = { viewModel.setWatermarkEnabled(it) },
                    onFontSizeChange = { viewModel.setWatermarkFontSize(it) },
                    onPositionChange = { viewModel.setWatermarkPosition(it) },
                    onOpacityChange = { viewModel.setWatermarkOpacity(it) },
                    onSegmentChange = { index, source, customText ->
                        viewModel.setSegmentSetting(index, source, customText)
                    },
                )
            }

            // 照片质量
            SettingsCard(title = "照片质量") {
                Text(
                    text = "选择拍摄照片的分辨率，等比缩放最长边至目标像素（不放大）。",
                    fontSize = 13.sp,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.size(12.dp))
                WatermarkDropdown(
                    label = "等级",
                    selectedText = photoQuality.displayName,
                    options = PhotoQuality.entries.map { it.displayName to it },
                    onSelect = { viewModel.setPhotoQuality(it) },
                )
            }

            // 拍照类型
            SettingsCard(title = "拍照类型") {
                Text(
                    text = "编辑拍照类型名称，可新增或删除类型（至少保留 1 个）。类型名用作文件名与进度记录 key。",
                    fontSize = 13.sp,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.size(12.dp))
                photoTypeConfigs.forEachIndexed { index, config ->
                    if (index > 0) SettingsDivider()
                    PhotoTypeConfigRow(
                        config = config,
                        onEdit = { editingConfig = config },
                        onDelete = { deletingConfig = config },
                    )
                }
                Spacer(modifier = Modifier.size(8.dp))
                OutlinedButton(
                    onClick = { showAddTypeDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        tint = Accent,
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(text = "添加类型", color = Accent)
                }
            }

            // 参考线
            SettingsCard(title = "参考线") {
                Text(
                    text = "相机取景界面叠加显示的参考线，辅助构图。",
                    fontSize = 13.sp,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.size(8.dp))
                GuideLineToggle(
                    label = "黄金分割线",
                    checked = guideLineConfig.goldenRatioGrid,
                    onCheckedChange = { viewModel.onGoldenRatioGridChange(it) },
                )
                GuideLineToggle(
                    label = "中心标",
                    checked = guideLineConfig.centerMark,
                    onCheckedChange = { viewModel.onCenterMarkChange(it) },
                )
            }

            // 日志
            SettingsCard(title = "日志") {
                Text(
                    text = "开启后所有运行日志与崩溃堆栈将写入 app.log，便于定位问题。",
                    fontSize = 13.sp,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.size(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "启用日志记录", fontSize = 14.sp, color = TextSecondary)
                    Switch(checked = logEnabled, onCheckedChange = { viewModel.toggleLog(it) })
                }
                SettingsDivider()
                Spacer(modifier = Modifier.size(8.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            logContent = viewModel.getLogTail()
                            showLogDialog = true
                        }
                    },
                    enabled = logFileExists,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Description,
                        contentDescription = null,
                        tint = Accent,
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(text = "查看日志", color = Accent)
                }
                Spacer(modifier = Modifier.size(8.dp))
                OutlinedButton(
                    onClick = { viewModel.getLogFile()?.let { shareExportedFile(context, it) } },
                    enabled = logFileExists,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.IosShare,
                        contentDescription = null,
                        tint = Accent,
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(text = "分享日志", color = Accent)
                }
                Spacer(modifier = Modifier.size(8.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            viewModel.clearLogFile()
                            Toast.makeText(context, "日志已清空", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = null,
                        tint = Error,
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(text = "清空日志", color = Error)
                }
            }

            // 缓存
            SettingsCard(title = "缓存") {
                Text(
                    text = "清理已拍摄照片、缩略图、进度记录、备注与报表文件，重置命名配置与最近文件列表。",
                    fontSize = 13.sp,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.size(8.dp))
                OutlinedButton(
                    onClick = { showClearCacheDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = null,
                        tint = Error,
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = "清空缓存",
                        color = Error,
                    )
                }
            }
        }
    }

    // 清空缓存确认对话框
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("清空缓存") },
            text = {
                Text("将删除所有已拍摄照片、缩略图、进度记录、备注与报表文件，此操作不可撤销。是否继续？")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearCacheDialog = false
                        scope.launch {
                            val msg = withContext(Dispatchers.IO) {
                                clearCache(context)
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                ) {
                    Text("确认清空", color = Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    // 日志查看弹窗
    if (showLogDialog) {
        LogViewerDialog(
            content = logContent,
            onDismiss = { showLogDialog = false },
            onCopyAll = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("log", logContent))
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            },
        )
    }

    // 编辑拍照类型名称对话框
    editingConfig?.let { config ->
        var editName by remember(config.id) { mutableStateOf(config.displayName) }
        AlertDialog(
            onDismissRequest = { editingConfig = null },
            title = { Text("编辑类型") },
            text = {
                OutlinedTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    singleLine = true,
                    label = { Text("类型名称") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.onEditPhotoTypeName(config, editName)
                        editingConfig = null
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingConfig = null }) {
                    Text("取消")
                }
            },
        )
    }

    // 新增拍照类型对话框
    if (showAddTypeDialog) {
        var newName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddTypeDialog = false },
            title = { Text("添加类型") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("类型名称") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.onAddPhotoType(newName)
                        showAddTypeDialog = false
                    },
                ) {
                    Text("添加")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddTypeDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    // 删除拍照类型确认对话框
    deletingConfig?.let { config ->
        AlertDialog(
            onDismissRequest = { deletingConfig = null },
            title = { Text("删除类型") },
            text = {
                Text("确定删除「${config.displayName}」吗？已拍摄该类型的照片记录不受影响。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.onDeletePhotoType(config)
                        deletingConfig = null
                    },
                ) {
                    Text("删除", color = Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingConfig = null }) {
                    Text("取消")
                }
            },
        )
    }
}

/**
 * 设置卡片：[CardColor] 背景 + 标题。
 */
@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = CardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Accent,
            )
            Spacer(modifier = Modifier.size(12.dp))
            content()
        }
    }
}

/**
 * 设置行：左侧标签 + 右侧值。
 */
@Composable
private fun SettingsRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = TextSecondary,
        )
        Text(
            text = value,
            fontSize = 14.sp,
            color = TextColor,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 分割线（占位行，使用 [Divider] 色）。
 */
@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = Divider)
}

/**
 * 命名段下拉选择器。
 *
 * 点击触发 [DropdownMenu]，选项为 [NameSegment.entries] 全集（拍摄日期/客户名/地址+时间/空值）。
 * 选择后立即回调 [onSelect] 持久化。
 *
 * @param label 段标签（如「段 1」）
 * @param selected 当前选中的段
 * @param onSelect 选择回调
 */
@Composable
private fun NamingSegmentDropdown(
    label: String,
    selected: NameSegment,
    onSelect: (NameSegment) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .background(HighlightBg, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    color = TextSecondary,
                )
                Text(
                    text = selected.displayName,
                    fontSize = 14.sp,
                    color = TextColor,
                    fontWeight = FontWeight.Medium,
                )
            }
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = "展开选项",
                tint = Accent,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            NameSegment.entries.forEach { segment ->
                DropdownMenuItem(
                    text = { Text(segment.displayName) },
                    onClick = {
                        onSelect(segment)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * 命名段配置行：段下拉选择器 + CUSTOM 段的自定义文本输入框。
 *
 * 下拉选「自定义文本」时段下方显示 [OutlinedTextField]，输入即时持久化（[onCustomTextChange]），
 * 示例预览自动生效；切回其它段类型时文本框消失（仓储层同时清空该段 custom key）。
 *
 * @param index 段索引（0..3，仅用于标签展示）
 * @param segment 当前选中的段
 * @param customText 该段持久化的自定义文本
 * @param onSelect 下拉选择回调
 * @param onCustomTextChange 自定义文本输入回调
 */
@Composable
private fun NamingSegmentConfigRow(
    index: Int,
    segment: NameSegment,
    customText: String,
    onSelect: (NameSegment) -> Unit,
    onCustomTextChange: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        NamingSegmentDropdown(
            label = "段 ${index + 1}",
            selected = segment,
            onSelect = onSelect,
        )
        if (segment == NameSegment.CUSTOM) {
            Spacer(modifier = Modifier.size(8.dp))
            OutlinedTextField(
                value = customText,
                onValueChange = onCustomTextChange,
                label = { Text("自定义文本（段 ${index + 1}）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 参考线单项开关行（标签 + Switch，SpaceBetween 布局）。
 */
@Composable
private fun GuideLineToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, fontSize = 14.sp, color = TextSecondary)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 拍照类型配置行：左侧类型名 + 右侧编辑/删除图标按钮。
 *
 * @param config 当前类型配置
 * @param onEdit 点击编辑图标
 * @param onDelete 点击删除图标
 */
@Composable
private fun PhotoTypeConfigRow(
    config: PhotoTypeConfig,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = config.displayName,
            fontSize = 14.sp,
            color = TextColor,
            fontWeight = FontWeight.Medium,
        )
        Row {
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "编辑类型",
                    tint = Accent,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.DeleteOutline,
                    contentDescription = "删除类型",
                    tint = Error,
                )
            }
        }
    }
}

/**
 * 清空缓存：删除 photos/、reports/、thumbnails/、visit_notes/ 目录，
 * 以及 progress.json、excel_data_index.json、camera_session.json 文件，
 * 并清空 DataStore 目录（命名配置、水印配置、最近 Excel 文件等）。
 *
 * @return 结果提示文案
 */
private fun clearCache(context: android.content.Context): String {
    val external = context.getExternalFilesDir(null) ?: return "缓存目录不可用"
    var deletedCount = 0

    val dirs = listOf("photos", "thumbnails", "reports", "visit_notes")
    for (name in dirs) {
        val dir = File(external, name)
        if (dir.exists() && dir.deleteRecursively()) deletedCount++
    }

    val files = listOf("progress.json", "excel_data_index.json", "camera_session.json")
    for (name in files) {
        val file = File(external, name)
        if (file.exists() && file.delete()) deletedCount++
    }

    val datastoreDir = File(context.filesDir, "datastore")
    if (datastoreDir.exists()) {
        val datastoreFiles = listOf("naming.preferences_pb", "watermark.preferences_pb", "recent_excel_files.preferences_pb")
        for (name in datastoreFiles) {
            val file = File(datastoreDir, name)
            if (file.exists() && file.delete()) deletedCount++
        }
    }

    return if (deletedCount > 0) "已清空全部缓存与进度（$deletedCount 项）" else "无需清理"
}
