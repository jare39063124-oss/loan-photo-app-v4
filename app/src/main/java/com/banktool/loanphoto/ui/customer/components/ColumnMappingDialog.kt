package com.banktool.loanphoto.ui.customer.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.banktool.loanphoto.data.datasource.ColumnField
import com.banktool.loanphoto.ui.theme.Accent
import com.banktool.loanphoto.ui.theme.Card as CardColor
import com.banktool.loanphoto.ui.theme.Divider
import com.banktool.loanphoto.ui.theme.Error
import com.banktool.loanphoto.ui.theme.Text as TextColor
import com.banktool.loanphoto.ui.theme.TextSecondary

/**
 * 列映射确认弹窗：Excel 未识别到表头时，让用户为每列（A-F）指定业务含义。
 *
 * - 每列一行：列字母 + 首行样例文本（单行省略，超 8 字符截断）+ 语义下拉（[ColumnField] 7 项）
 * - 预填默认 A-F：序号 / 客户名 / 地址概 / 地址详 / 性质 / 备注
 * - 校验：「客户名」与「地址(概)」必须各有一列，否则确认按钮禁用；其余字段可忽略或重复
 *   （重复时解析/写回均以靠前的列为准）
 * - 确认回调传回完整列映射（按物理列索引）；取消则中断导入
 *
 * @param columnSamples 每列首行样例文本（物理列 0..n，来自 ExcelReadResult.rawFirstRowSamples）
 * @param onConfirm 确认回调，传回 List<ColumnField>
 * @param onCancel 取消回调（中断导入）
 */
@Composable
fun ColumnMappingDialog(
    columnSamples: List<String>,
    onConfirm: (List<ColumnField>) -> Unit,
    onCancel: () -> Unit,
) {
    var mapping by remember(columnSamples) { mutableStateOf(defaultMappingFor(columnSamples)) }
    var expandedIndex by remember { mutableIntStateOf(-1) }

    val valid = mapping.contains(ColumnField.BORROWER) && mapping.contains(ColumnField.ADDR_GENERAL)

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                text = "列映射确认",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Accent,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "未识别到表头，请确认每列内容。（也可自行在 Excel 首行加表头实现自动识别）",
                    fontSize = 13.sp,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.height(12.dp))
                columnSamples.forEachIndexed { index, sample ->
                    MappingRow(
                        letter = ('A' + index).toString(),
                        sample = sample,
                        selected = mapping.getOrElse(index) { ColumnField.IGNORE },
                        expanded = expandedIndex == index,
                        onExpandedChange = { expanded -> expandedIndex = if (expanded) index else -1 },
                        onSelect = { field ->
                            mapping = mapping.toMutableList().also { list ->
                                if (index < list.size) list[index] = field
                            }
                            expandedIndex = -1
                        },
                    )
                    if (index < columnSamples.lastIndex) {
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (valid) "同一含义重复指定时以靠前的列为准" else "「客户名」与「地址(概)」必须各指定一列",
                    fontSize = 12.sp,
                    color = if (valid) TextSecondary else Error,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "更改列映射会改变进度关联，确认后将自动迁移已拍进度",
                    fontSize = 12.sp,
                    color = TextSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(mapping) },
                enabled = valid,
            ) {
                Text(
                    text = "确认",
                    color = if (valid) Accent else TextSecondary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(text = "取消", color = Error)
            }
        },
    )
}

/** 单列映射行：列字母 + 样例文本 + 语义下拉。 */
@Composable
private fun MappingRow(
    letter: String,
    sample: String,
    selected: ColumnField,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (ColumnField) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = letter,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = TextSecondary,
            modifier = Modifier.width(20.dp),
        )
        Text(
            text = sample.truncate(8).ifBlank { "（空）" },
            fontSize = 13.sp,
            color = TextColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Box {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = CardColor,
                border = BorderStroke(1.dp, Divider),
                modifier = Modifier.clickable { onExpandedChange(true) },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = selected.displayName,
                        fontSize = 13.sp,
                        color = TextColor,
                    )
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        tint = TextSecondary,
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
            ) {
                ColumnField.values().forEach { field ->
                    DropdownMenuItem(
                        text = { Text(field.displayName) },
                        onClick = { onSelect(field) },
                    )
                }
            }
        }
    }
}

/** 预填映射：默认 A-F（序号/客户名/地址概/地址详/性质/备注），不足补 IGNORE、超出截断。 */
private fun defaultMappingFor(samples: List<String>): List<ColumnField> {
    return List(samples.size) { index ->
        ColumnField.DEFAULT.getOrElse(index) { ColumnField.IGNORE }
    }
}

/** 样例文本截断：超过 [max] 字符取前 [max] 字符加省略号。 */
private fun String.truncate(max: Int): String =
    if (length > max) take(max) + "…" else this
