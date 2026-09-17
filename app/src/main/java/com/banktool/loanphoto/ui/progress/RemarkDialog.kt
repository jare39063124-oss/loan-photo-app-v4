package com.banktool.loanphoto.ui.progress

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.banktool.loanphoto.ui.theme.Accent
import com.banktool.loanphoto.ui.theme.Error
import com.banktool.loanphoto.ui.theme.TextSecondary

/**
 * 行备注编辑弹窗。
 *
 * 备注 存储到 progress.json 的 `_row_remarks` 字段 (key=行号字符串)，
 * 由 ProgressRepository.saveRowRemark 负责。
 *
 * @param initialContent 当前备注内容（空串表示无备注）
 * @param rowTitle 显示在标题中的行标识（如 "[1] 张三"）
 * @param onSave 保存回调，参数为备注内容
 * @param onDismiss 关闭弹窗
 */
@Composable
fun RemarkDialog(
    initialContent: String,
    rowTitle: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var content by remember { mutableStateOf(initialContent) }

    // 初次进入时同步一次外部内容（如重新打开同一行）
    LaunchedEffect(initialContent) {
        content = initialContent
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "编辑备注",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Accent,
                )
                if (rowTitle.isNotBlank()) {
                    Text(
                        text = rowTitle,
                        fontSize = 13.sp,
                        color = TextSecondary,
                    )
                }
            }
        },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                placeholder = {
                    Text(
                        text = "请输入备注内容",
                        color = TextSecondary,
                        fontSize = 14.sp,
                    )
                },
                minLines = 3,
                maxLines = 6,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(content) }) {
                Text("保存", color = Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = Error)
            }
        },
    )
}
