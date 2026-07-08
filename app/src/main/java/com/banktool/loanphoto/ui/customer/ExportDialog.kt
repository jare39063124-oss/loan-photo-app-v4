package com.banktool.loanphoto.ui.customer

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.banktool.loanphoto.data.export.ExportDimension
import com.banktool.loanphoto.data.export.ExportFormat
import com.banktool.loanphoto.ui.theme.Accent
import com.banktool.loanphoto.ui.theme.Card as CardColor
import com.banktool.loanphoto.ui.theme.Divider
import com.banktool.loanphoto.ui.theme.Error
import com.banktool.loanphoto.ui.theme.Text as TextColor
import com.banktool.loanphoto.ui.theme.TextSecondary
import java.io.File

/**
 * 照片导出弹窗。
 *
 * 三态切换（由 [exportState] 驱动）：
 * - [CustomerListViewModel.ExportState.Idle] / [CustomerListViewModel.ExportState.Error]：
 *   显示配置弹窗（维度下拉 + 关键词 + 格式 RadioButton）
 * - [CustomerListViewModel.ExportState.Exporting]：显示进度弹窗
 *   （[CircularProgressIndicator] + "正在导出 (current/total)..."）
 * - [CustomerListViewModel.ExportState.Done]：显示结果弹窗 [ExportResultDialog]，
 *   由用户选择「分享」或「保存到本地」。
 *
 * @param exportState 当前导出状态
 * @param onExport 用户点击「导出」时回调
 * @param onDismiss 关闭弹窗回调（取消 / 完成后由父级触发）
 * @param onShare 用户在结果弹窗点击「分享」时回调
 * @param onSaveToLocal 用户在结果弹窗点击「保存到本地」时回调
 */
@Composable
fun ExportDialog(
    exportState: CustomerListViewModel.ExportState,
    onExport: (ExportDimension, String, ExportFormat) -> Unit,
    onDismiss: () -> Unit,
    onShare: (File) -> Unit,
    onSaveToLocal: (File) -> Unit,
) {
    when (exportState) {
        is CustomerListViewModel.ExportState.Exporting ->
            ExportProgressDialog(current = exportState.current, total = exportState.total)

        is CustomerListViewModel.ExportState.Done -> ExportResultDialog(
            file = exportState.file,
            fileSize = exportState.fileSize,
            onShare = onShare,
            onSaveToLocal = onSaveToLocal,
            onDismiss = onDismiss,
        )

        else -> ExportConfigDialog(
            exportState = exportState,
            onExport = onExport,
            onDismiss = onDismiss,
        )
    }
}

/**
 * 配置弹窗：维度下拉 + 关键词 + 格式 RadioButton。
 *
 * Error 状态下额外展示错误提示文字。
 */
@Composable
private fun ExportConfigDialog(
    exportState: CustomerListViewModel.ExportState,
    onExport: (ExportDimension, String, ExportFormat) -> Unit,
    onDismiss: () -> Unit,
) {
    var dimension by remember { mutableStateOf(ExportDimension.DEFAULT) }
    var keyword by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(ExportFormat.PDF) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    val errorMessage = (exportState as? CustomerListViewModel.ExportState.Error)?.message

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出照片", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                // 维度下拉
                Text("匹配维度", fontSize = 13.sp, color = TextSecondary)
                Spacer(modifier = Modifier.size(4.dp))
                Box {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = CardColor,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Divider),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { dropdownExpanded = true },
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = dimension.displayName,
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
                        ExportDimension.values().forEach { dim ->
                            DropdownMenuItem(
                                text = { Text(dim.displayName) },
                                onClick = {
                                    dimension = dim
                                    dropdownExpanded = false
                                },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.size(12.dp))

                // 关键词
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    label = { Text("关键词（留空导出全部）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Divider,
                    ),
                    textStyle = TextStyle(fontSize = 14.sp, color = TextColor),
                )

                Spacer(modifier = Modifier.size(12.dp))

                // 格式 RadioButton
                Text("导出格式", fontSize = 13.sp, color = TextSecondary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ExportFormat.values().forEach { fmt ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = format == fmt,
                                onClick = { format = fmt },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = Accent,
                                    unselectedColor = TextSecondary,
                                ),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = fmt.displayName,
                                fontSize = 14.sp,
                                color = if (format == fmt) TextColor else TextSecondary,
                            )
                        }
                    }
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = errorMessage,
                        fontSize = 12.sp,
                        color = Error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onExport(dimension, keyword, format) }) {
                Text("导出", color = Accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 进度弹窗：[CircularProgressIndicator] + "正在导出 (current/total)..."。
 *
 * 不可取消（用户必须等待导出完成）。
 */
@Composable
private fun ExportProgressDialog(current: Int, total: Int) {
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
                val msg = if (total > 0) {
                    "正在导出 ($current/$total)..."
                } else {
                    "正在准备导出..."
                }
                Text(
                    text = msg,
                    color = TextColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (total > 0) {
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text = "共 $total 张照片",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

/**
 * 导出完成结果弹窗。
 *
 * 显示文件名 + 文件大小，并提供三个操作：
 * - 「分享」（[onShare]）：Accent 主色 TextButton，走 ACTION_SEND
 * - 「保存到本地」（[onSaveToLocal]）：OutlinedButton，走 SAF ACTION_CREATE_DOCUMENT
 * - 「关闭」（[onDismiss]）：普通 TextButton，仅关闭弹窗
 *
 * 文件大小格式化：>= 1MB 显示 "%.1f MB"，否则显示 "%.1f KB"。
 *
 * @param file 导出文件
 * @param fileSize 文件大小（字节）
 * @param onShare 分享回调
 * @param onSaveToLocal 保存到本地回调
 * @param onDismiss 关闭弹窗回调
 */
@Composable
private fun ExportResultDialog(
    file: File,
    fileSize: Long,
    onShare: (File) -> Unit,
    onSaveToLocal: (File) -> Unit,
    onDismiss: () -> Unit,
) {
    val sizeText = if (fileSize >= 1024L * 1024L) {
        "%.1f MB".format(fileSize / (1024.0 * 1024.0))
    } else {
        "%.1f KB".format(fileSize / 1024.0)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出完成", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    text = file.name,
                    fontSize = 14.sp,
                    color = TextColor,
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = sizeText,
                    fontSize = 12.sp,
                    color = TextSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onShare(file) }) {
                Text("分享", color = Accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { onSaveToLocal(file) }) {
                    Text("保存到本地")
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

/**
 * 在 UI 层处理分享：弹 Toast 提示文件路径，并通过 [Intent.ACTION_SEND] 分享。
 *
 * 失败时（无可用应用 / FileProvider 异常）只 Toast，不阻断流程。
 *
 * 由 [ExportResultDialog] 的 onShare 回调调用：
 * ```kotlin
 * onShare = { file ->
 *     shareExportedFile(context, file)
 *     viewModel.resetExportState()
 *     showExportDialog = false
 * }
 * ```
 */
fun shareExportedFile(context: Context, file: File) {
    Toast.makeText(
        context,
        "已导出：${file.absolutePath}",
        Toast.LENGTH_LONG,
    ).show()

    val authority = "${context.packageName}.fileprovider"
    val uri = runCatching {
        FileProvider.getUriForFile(context, authority, file)
    }.getOrNull()

    if (uri == null) {
        Toast.makeText(
            context,
            "文件已保存到：${file.absolutePath}",
            Toast.LENGTH_LONG,
        ).show()
        return
    }

    val mime = when (file.extension.lowercase()) {
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        else -> "*/*"
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(intent, "分享导出文件").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching {
        context.startActivity(chooser)
    }.onFailure {
        // 无可用应用时仅 Toast 提示路径
        Toast.makeText(
            context,
            "文件已保存到：${file.absolutePath}",
            Toast.LENGTH_LONG,
        ).show()
    }
}
