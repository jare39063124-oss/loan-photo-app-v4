package com.banktool.loanphoto.ui.customer.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.banktool.loanphoto.ui.customer.RecentFileItem
import com.banktool.loanphoto.ui.theme.Accent
import com.banktool.loanphoto.ui.theme.Divider
import com.banktool.loanphoto.ui.theme.Success
import com.banktool.loanphoto.ui.theme.Text as TextColor
import com.banktool.loanphoto.ui.theme.TextSecondary

/**
 * 最近文件 BottomSheet。
 *
 * 列出最近 [RecentFileItem] 最多 5 条，每条显示：
 * - 文件名
 * - 数据指示器（绿点=有数据 / 灰点=无数据）
 * - "有数据 N 项" 或 "无数据"
 * - 移除按钮（清理失效或不需要的记录）
 *
 * @param recentFiles 最近文件列表（已含数据指示信息）
 * @param onFileSelected 选中某文件，参数为 uri
 * @param onRemoveFile 移除某文件记录，参数为 uri
 * @param onDismiss 关闭 BottomSheet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentFilesBottomSheet(
    recentFiles: List<RecentFileItem>,
    onFileSelected: (String) -> Unit,
    onRemoveFile: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = "最近文件",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = TextColor,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )

            if (recentFiles.isEmpty()) {
                Text(
                    text = "暂无最近文件记录",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    color = TextSecondary,
                    fontSize = 14.sp,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(recentFiles, key = { it.uri }) { item ->
                        RecentFileRow(
                            item = item,
                            onClick = { onFileSelected(item.uri) },
                            onRemove = { onRemoveFile(item.uri) },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            thickness = 1.dp,
                            color = Divider,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentFileRow(
    item: RecentFileItem,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Description,
            contentDescription = null,
            tint = Accent,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.fileName,
                color = TextColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.size(2.dp))
            Text(
                text = if (item.hasData) "有数据 ${item.dataCount} 项" else "无数据",
                color = if (item.hasData) Success else TextSecondary,
                fontSize = 12.sp,
            )
        }
        // 数据指示器圆点
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (item.hasData) Success else Divider),
        )
        Spacer(modifier = Modifier.width(8.dp))
        // 移除按钮
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "移除",
                tint = TextSecondary,
            )
        }
    }
}
