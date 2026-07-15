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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DeleteOutline
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import com.banktool.loanphoto.data.camera.WatermarkConfig
import com.banktool.loanphoto.data.camera.WatermarkFontSize
import com.banktool.loanphoto.data.camera.WatermarkPosition
import com.banktool.loanphoto.data.license.LicenseChecker
import com.banktool.loanphoto.data.naming.NameSegment
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
 * - AI 模型：DeepSeek `deepseek-v4-flash`（[BuildConfig.DEEPSEEK_MODEL]）
 * - 照片命名规则：4 段下拉选择器 + 实时预览（持久化到 DataStore）
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

    val namingConfig by viewModel.namingConfig.collectAsStateWithLifecycle()
    val watermarkConfig by viewModel.watermarkConfig.collectAsStateWithLifecycle()
    val photoQuality by viewModel.photoQuality.collectAsStateWithLifecycle()

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
                // 体验版额外展示有效期
                if (BuildConfig.IS_TRIAL) {
                    SettingsDivider()
                    SettingsRow(label = "有效期至", value = BuildConfig.EXPIRY_DATE)
                }
                SettingsDivider()
                SettingsRow(label = "联系方式", value = "15940454123（微信同）")
                if (BuildConfig.IS_TRIAL) {
                    SettingsDivider()
                    Text(
                        text = "重要说明：手机恢复出厂设置后，设备识别码将改变，重新安装将导致 App 不可用。在使用有效期内联系作者，仅可获得一次重新激活机会。",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }

            // AI 模型
            SettingsCard(title = "AI 模型") {
                SettingsRow(label = "服务商", value = "DeepSeek")
                SettingsDivider()
                SettingsRow(label = "模型", value = BuildConfig.DEEPSEEK_MODEL)
            }

            // 照片命名规则
            SettingsCard(title = "照片命名规则") {
                Text(
                    text = "选择 4 段命名组成部分，按顺序以「-」拼接生成照片文件名。",
                    fontSize = 13.sp,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.size(12.dp))
                NamingSegmentDropdown(
                    label = "段 1",
                    selected = namingConfig.segment1,
                    onSelect = { viewModel.setSegment(0, it) },
                )
                Spacer(modifier = Modifier.size(8.dp))
                NamingSegmentDropdown(
                    label = "段 2",
                    selected = namingConfig.segment2,
                    onSelect = { viewModel.setSegment(1, it) },
                )
                Spacer(modifier = Modifier.size(8.dp))
                NamingSegmentDropdown(
                    label = "段 3",
                    selected = namingConfig.segment3,
                    onSelect = { viewModel.setSegment(2, it) },
                )
                Spacer(modifier = Modifier.size(8.dp))
                NamingSegmentDropdown(
                    label = "段 4",
                    selected = namingConfig.segment4,
                    onSelect = { viewModel.setSegment(3, it) },
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

            // 水印设置
            SettingsCard(title = "水印设置") {
                // 启用开关
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "启用水印", fontSize = 14.sp, color = TextSecondary)
                    Switch(checked = watermarkConfig.enabled, onCheckedChange = { viewModel.setWatermarkEnabled(it) })
                }
                SettingsDivider()
                // 字号下拉
                WatermarkDropdown(
                    label = "字号",
                    selectedText = when (watermarkConfig.fontSize) {
                        WatermarkFontSize.LARGE -> "大"
                        WatermarkFontSize.MEDIUM -> "中"
                        WatermarkFontSize.SMALL -> "小"
                    },
                    options = listOf("大" to WatermarkFontSize.LARGE, "中" to WatermarkFontSize.MEDIUM, "小" to WatermarkFontSize.SMALL),
                    onSelect = { viewModel.setWatermarkFontSize(it) },
                )
                Spacer(modifier = Modifier.size(8.dp))
                // 位置下拉
                WatermarkDropdown(
                    label = "位置",
                    selectedText = when (watermarkConfig.position) {
                        WatermarkPosition.BOTTOM_RIGHT -> "右下"
                        WatermarkPosition.BOTTOM_LEFT -> "左下"
                        WatermarkPosition.TOP_RIGHT -> "右上"
                        WatermarkPosition.TOP_LEFT -> "左上"
                    },
                    options = listOf("右下" to WatermarkPosition.BOTTOM_RIGHT, "左下" to WatermarkPosition.BOTTOM_LEFT, "右上" to WatermarkPosition.TOP_RIGHT, "左上" to WatermarkPosition.TOP_LEFT),
                    onSelect = { viewModel.setWatermarkPosition(it) },
                )
                Spacer(modifier = Modifier.size(8.dp))
                // 不透明度滑块
                Text(text = "不透明度", fontSize = 12.sp, color = TextSecondary)
                Slider(
                    value = watermarkConfig.opacity,
                    onValueChange = { viewModel.setWatermarkOpacity(it) },
                    valueRange = 0.3f..1.0f,
                    steps = 6,
                )
                Text(
                    text = "%.0f%%".format(watermarkConfig.opacity * 100),
                    fontSize = 12.sp,
                    color = Accent,
                )
                Spacer(modifier = Modifier.size(8.dp))
                SettingsDivider()
                Spacer(modifier = Modifier.size(8.dp))
                // 水印内容逐项显示开关（4 项）
                Text(
                    text = "水印内容",
                    fontSize = 12.sp,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.size(4.dp))
                WatermarkContentToggle(
                    label = "拍摄日期",
                    checked = watermarkConfig.showDate,
                    onCheckedChange = { viewModel.setShowDate(it) },
                )
                WatermarkContentToggle(
                    label = "序号",
                    checked = watermarkConfig.showSerial,
                    onCheckedChange = { viewModel.setShowSerial(it) },
                )
                WatermarkContentToggle(
                    label = "地址",
                    checked = watermarkConfig.showAddress,
                    onCheckedChange = { viewModel.setShowAddress(it) },
                )
                WatermarkContentToggle(
                    label = "经纬度",
                    checked = watermarkConfig.showLatlng,
                    onCheckedChange = { viewModel.setShowLatlng(it) },
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
 * 水印选项下拉选择器（泛型化，支持字号 / 位置等任意枚举）。
 *
 * 点击触发 [DropdownMenu]，选项由 [options] 提供（显示文本 + 任意值）。
 * 选择后立即回调 [onSelect] 持久化。
 *
 * @param label 选项标签（如「字号」「位置」）
 * @param selectedText 当前选中项的显示文本
 * @param options 可选项列表（显示文本 to 值）
 * @param onSelect 选择回调
 */
@Composable
private fun <T> WatermarkDropdown(
    label: String,
    selectedText: String,
    options: List<Pair<String, T>>,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = true }
                .background(HighlightBg, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(text = label, fontSize = 12.sp, color = TextSecondary)
                Text(text = selectedText, fontSize = 14.sp, color = TextColor, fontWeight = FontWeight.Medium)
            }
            Icon(imageVector = Icons.Filled.ArrowDropDown, contentDescription = "展开选项", tint = Accent)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (text, value) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); expanded = false })
            }
        }
    }
}

/**
 * 水印内容单项开关行（标签 + Switch，SpaceBetween 布局）。
 */
@Composable
private fun WatermarkContentToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, fontSize = 14.sp, color = TextSecondary)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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

    // 1. 清理目录（递归删除）
    val dirs = listOf("photos", "thumbnails", "reports", "visit_notes")
    for (name in dirs) {
        val dir = File(external, name)
        if (dir.exists() && dir.deleteRecursively()) deletedCount++
    }

    // 2. 清理文件
    val files = listOf("progress.json", "excel_data_index.json", "camera_session.json")
    for (name in files) {
        val file = File(external, name)
        if (file.exists() && file.delete()) deletedCount++
    }

    // 3. 清理 DataStore（命名配置 + 水印配置 + 最近文件）
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
