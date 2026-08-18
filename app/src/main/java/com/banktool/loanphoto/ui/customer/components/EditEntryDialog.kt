package com.banktool.loanphoto.ui.customer.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
import com.banktool.loanphoto.domain.entity.CustomerRow
import com.banktool.loanphoto.ui.theme.Accent
import com.banktool.loanphoto.ui.theme.Error
import com.banktool.loanphoto.ui.theme.TextSecondary

/**
 * 条目全量编辑弹窗（长按菜单「编辑条目」入口）。
 *
 * 可编辑 [CustomerRow] 全部业务字段：序号 / 客户名 / 地址概 / 地址详 / 性质 / 备注。
 * rowIndex 与 progressKey 不可编辑（只读展示 progressKey）——修改客户名/地址后仍保持
 * 旧 progressKey，拍照进度关联不丢。
 *
 * 保存回调 [onSave] 传回更新后的 CustomerRow（copy 保留 rowIndex/progressKey），
 * 由 CustomerListViewModel.updateCustomerRow 负责 Excel 整行回写、_row_remarks 同步
 * 与内存刷新。
 *
 * @param initial 当前条目（remark 已由调用方合并为生效值：_row_remarks 优先于 Excel F 列）
 * @param onSave 保存回调
 * @param onDismiss 关闭弹窗
 */
@Composable
fun EditEntryDialog(
    initial: CustomerRow,
    onSave: (CustomerRow) -> Unit,
    onDismiss: () -> Unit,
) {
    var serial by remember { mutableStateOf(initial.serial) }
    var borrower by remember { mutableStateOf(initial.borrower) }
    var addrGeneral by remember { mutableStateOf(initial.addrGeneral) }
    var addrDetail by remember { mutableStateOf(initial.addrDetail) }
    var propertyType by remember { mutableStateOf(initial.propertyType) }
    var remark by remember { mutableStateOf(initial.remark) }

    val canSave = borrower.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "编辑条目",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Accent,
                )
                if (initial.progressKey.isNotBlank()) {
                    Text(
                        text = "进度标识: ${initial.progressKey}",
                        fontSize = 11.sp,
                        color = TextSecondary,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = serial,
                    onValueChange = { serial = it },
                    label = { Text("序号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.size(8.dp))
                OutlinedTextField(
                    value = borrower,
                    onValueChange = { borrower = it },
                    label = { Text("客户名 *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.size(8.dp))
                OutlinedTextField(
                    value = addrGeneral,
                    onValueChange = { addrGeneral = it },
                    label = { Text("地址概") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.size(8.dp))
                OutlinedTextField(
                    value = addrDetail,
                    onValueChange = { addrDetail = it },
                    label = { Text("地址详") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.size(8.dp))
                OutlinedTextField(
                    value = propertyType,
                    onValueChange = { propertyType = it },
                    label = { Text("性质") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.size(8.dp))
                OutlinedTextField(
                    value = remark,
                    onValueChange = { remark = it },
                    label = { Text("备注") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )
                Spacer(modifier = Modifier.size(4.dp))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        initial.copy(
                            serial = serial.trim(),
                            borrower = borrower.trim(),
                            addrGeneral = addrGeneral.trim(),
                            addrDetail = addrDetail.trim(),
                            propertyType = propertyType.trim(),
                            remark = remark.trim(),
                        ),
                    )
                },
                enabled = canSave,
            ) {
                Text(
                    text = "保存",
                    color = if (canSave) Accent else TextSecondary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", color = Error)
            }
        },
    )
}
