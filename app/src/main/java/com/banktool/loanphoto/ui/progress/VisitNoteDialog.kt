package com.banktool.loanphoto.ui.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
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
import com.banktool.loanphoto.ui.theme.Success
import com.banktool.loanphoto.ui.theme.TextSecondary

/**
 * 走访备注弹窗。
 *
 * 大号多行 TextField，3 个按钮：「保存」仅持久化到 visit_notes/<md5>.txt、不生成报表，
 * 「生成报表」持久化并触发 AI 报表生成，「取消」直接关闭弹窗。
 *
 * @param initialContent 当前已保存的走访备注内容
 * @param fileName 显示在标题中的 Excel 文件名
 * @param onSave 仅保存回调
 * @param onGenerateReport 保存并生成报表回调
 * @param onDismiss 关闭弹窗
 */
@Composable
fun VisitNoteDialog(
    initialContent: String,
    fileName: String,
    onSave: (String) -> Unit,
    onGenerateReport: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var content by remember { mutableStateOf(initialContent) }

    LaunchedEffect(initialContent) {
        content = initialContent
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "走访备注",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Accent,
                )
                if (fileName.isNotBlank()) {
                    Text(
                        text = fileName,
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
                    .heightIn(min = 200.dp)
                    .padding(top = 4.dp),
                placeholder = {
                    Text(
                        text = "请输入走访备注（现场情况、特殊说明等）",
                        color = TextSecondary,
                        fontSize = 14.sp,
                    )
                },
                minLines = 8,
                maxLines = 15,
            )
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { onSave(content) }) {
                    Text("保存", color = Accent)
                }
                Button(
                    onClick = { onGenerateReport(content) },
                    colors = ButtonDefaults.buttonColors(containerColor = Success),
                ) {
                    Text("生成报表", color = androidx.compose.ui.graphics.Color.White)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = Error)
            }
        },
    )
}
