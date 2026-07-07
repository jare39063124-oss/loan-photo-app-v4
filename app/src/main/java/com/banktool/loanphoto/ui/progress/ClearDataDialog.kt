package com.banktool.loanphoto.ui.progress

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.banktool.loanphoto.ui.theme.Error
import com.banktool.loanphoto.ui.theme.TextSecondary

/**
 * 数据清除 2 次确认弹窗。
 *
 * 第 1 次: 说明将删除"缩略图 + 拍照进度 + 备注"
 * 第 2 次: 确认删除按钮（红色）+ 取消
 *
 * 执行清除（由调用方实现）：
 * 1. 清除 thumbnails/<progress_key>/ 目录
 * 2. 清除 progress.json 中对应 progress_keys
 * 3. 更新 excel_data_index.json (移除该 excel_uri_md5 条目)
 * 4. 清除 _row_remarks 中对应行号
 *
 * @param fileName 显示在标题中的 Excel 文件名
 * @param onConfirm 确认删除回调
 * @param onDismiss 关闭弹窗
 */
@Composable
fun ClearDataDialog(
    fileName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var stage by remember { mutableStateOf(1) }

    AlertDialog(
        onDismissRequest = {
            if (stage == 2) stage = 1 else onDismiss()
        },
        title = {
            Text(
                text = if (stage == 1) "清除数据" else "最终确认",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Error,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (fileName.isNotBlank()) {
                    Text(
                        text = fileName,
                        fontSize = 13.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Text(
                    text = if (stage == 1) {
                        "此操作将删除该 Excel 文件相关的所有数据：\n" +
                            "\n- 缩略图文件" +
                            "\n- 拍照进度记录" +
                            "\n- 行级备注" +
                            "\n- 同类型代表性户型标记" +
                            "\n\n此操作不可撤销，是否继续？"
                    } else {
                        "再次确认：所有拍照数据将被永久删除，无法恢复。\n\n确定要继续吗？"
                    },
                    fontSize = 14.sp,
                )
            }
        },
        confirmButton = {
            if (stage == 1) {
                TextButton(onClick = { stage = 2 }) {
                    Text("继续", color = Error)
                }
            } else {
                Button(
                    onClick = {
                        onConfirm()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Error),
                ) {
                    Text("确认删除", color = androidx.compose.ui.graphics.Color.White)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                if (stage == 2) stage = 1 else onDismiss()
            }) {
                Text("取消")
            }
        },
    )
}
