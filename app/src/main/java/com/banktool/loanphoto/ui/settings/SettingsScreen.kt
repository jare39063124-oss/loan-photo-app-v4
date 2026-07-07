package com.banktool.loanphoto.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import com.banktool.loanphoto.BuildConfig
import com.banktool.loanphoto.ui.theme.Accent
import com.banktool.loanphoto.ui.theme.Bg
import com.banktool.loanphoto.ui.theme.Card as CardColor
import com.banktool.loanphoto.ui.theme.Divider
import com.banktool.loanphoto.ui.theme.Error
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
 * - 清空缓存：删除 `getExternalFilesDir/photos` 与 `reports` 目录（带确认对话框）
 *
 * 使用 Fluent Design 浅色主题。
 *
 * @param onBack 返回上一页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showClearCacheDialog by remember { mutableStateOf(false) }

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
            }

            // AI 模型
            SettingsCard(title = "AI 模型") {
                SettingsRow(label = "服务商", value = "DeepSeek")
                SettingsDivider()
                SettingsRow(label = "模型", value = BuildConfig.DEEPSEEK_MODEL)
            }

            // 缓存
            SettingsCard(title = "缓存") {
                Text(
                    text = "清理已拍摄照片与生成的报表文件，不影响 Excel 与进度记录。",
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
                Text("将删除所有已拍摄照片与报表文件，此操作不可撤销。是否继续？")
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
 * 清空缓存：删除 `getExternalFilesDir/photos` 与 `reports` 目录。
 *
 * @return 结果提示文案
 */
private fun clearCache(context: android.content.Context): String {
    val external = context.getExternalFilesDir(null) ?: return "缓存目录不可用"
    val targets = listOf(File(external, "photos"), File(external, "reports"))
    var deleted = 0
    for (dir in targets) {
        if (dir.exists()) {
            if (dir.deleteRecursively()) deleted++
        }
    }
    return if (deleted > 0) "已清空缓存" else "无需清理"
}
