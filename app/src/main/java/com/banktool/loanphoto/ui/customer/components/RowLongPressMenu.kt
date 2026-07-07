package com.banktool.loanphoto.ui.customer.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.banktool.loanphoto.ui.theme.Accent
import com.banktool.loanphoto.ui.theme.Warning

/**
 * 行长按菜单。
 *
 * 三个选项：
 * - "标记为同类型代表性户型"（可切换标记/取消）
 * - "查看已拍照片"
 * - "编辑备注"
 *
 * @param expanded 是否展开
 * @param isMarked 当前行是否已被标记为同类型代表性户型
 * @param onDismiss 关闭菜单
 * @param onToggleBatchMark 切换标记状态
 * @param onViewPhotos 查看已拍照片
 * @param onEditRemark 编辑备注
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RowLongPressMenu(
    expanded: Boolean,
    isMarked: Boolean,
    onDismiss: () -> Unit,
    onToggleBatchMark: () -> Unit,
    onViewPhotos: () -> Unit,
    onEditRemark: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        DropdownMenuItem(
            text = {
                Text(
                    text = if (isMarked) "取消同类型代表性户型标记" else "标记为同类型代表性户型",
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = if (isMarked) Warning else Accent,
                )
            },
            onClick = {
                onDismiss()
                onToggleBatchMark()
            },
        )
        DropdownMenuItem(
            text = { Text("查看已拍照片") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Photo,
                    contentDescription = null,
                    tint = Accent,
                )
            },
            onClick = {
                onDismiss()
                onViewPhotos()
            },
        )
        DropdownMenuItem(
            text = { Text("编辑备注") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = null,
                    tint = Accent,
                )
            },
            onClick = {
                onDismiss()
                onEditRemark()
            },
        )
    }
}
