package com.banktool.loanphoto.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.banktool.loanphoto.ui.theme.Accent
import com.banktool.loanphoto.ui.theme.Text as TextColor
import com.banktool.loanphoto.ui.theme.TextSecondary

/**
 * 日志查看弹窗。
 *
 * 以等宽字体（[FontFamily.Monospace]）展示日志文本，支持选中复制，便于查看堆栈信息。
 * [content] 为空时显示「暂无日志」占位。
 *
 * @param content 日志文本
 * @param onDismiss 关闭弹窗
 * @param onCopyAll 复制全部日志
 */
@Composable
fun LogViewerDialog(
    content: String,
    onDismiss: () -> Unit,
    onCopyAll: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "日志查看",
                fontWeight = FontWeight.Bold,
                color = TextColor,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (content.isEmpty()) {
                    Text(
                        text = "暂无日志",
                        color = TextSecondary,
                    )
                } else {
                    SelectionContainer {
                        Text(
                            text = content,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = TextColor,
                        )
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onCopyAll) {
                Text(text = "复制全部", color = Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}
