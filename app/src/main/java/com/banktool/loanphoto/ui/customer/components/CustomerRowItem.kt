package com.banktool.loanphoto.ui.customer.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.banktool.loanphoto.domain.entity.CustomerRow
import com.banktool.loanphoto.ui.theme.Accent
import com.banktool.loanphoto.ui.theme.Card as CardColor
import com.banktool.loanphoto.ui.theme.Divider
import com.banktool.loanphoto.ui.theme.HighlightBg
import com.banktool.loanphoto.ui.theme.Text as TextColor
import com.banktool.loanphoto.ui.theme.TextOnLight
import com.banktool.loanphoto.ui.theme.TextSecondary
import com.banktool.loanphoto.ui.theme.Warning

/**
 * 客户清单中的单行卡片。
 *
 * 布局：
 * - 左侧：[Checkbox] 单行选择
 * - 中间：三行文本（序号+客户名 / 地址 / 性质+备注）
 * - 右侧：照片数 Badge + 拍照按钮 + 查看照片按钮（仅 photoCount>0 时显示）
 *
 * 选中时背景为 [HighlightBg]，未选中为 [CardColor]（白色）。
 * 被标记为同类型代表性户型时显示星标。
 * 长按弹出菜单（[RowLongPressMenu]）。
 *
 * @param row 客户行数据
 * @param photoCount 该行已拍照片数
 * @param isSelected 是否被选中
 * @param isBatchMarked 是否被标记为同类型代表性户型
 * @param onSelectionToggle 切换选中状态
 * @param onTakePhoto 点击拍照按钮
 * @param onViewPhotos 点击查看照片按钮
 * @param onEditRemark 点击编辑备注按钮
 * @param onLongClick 长按行回调
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CustomerRowItem(
    row: CustomerRow,
    photoCount: Int,
    isSelected: Boolean,
    isBatchMarked: Boolean = false,
    onSelectionToggle: () -> Unit,
    onTakePhoto: () -> Unit,
    onViewPhotos: () -> Unit,
    onEditRemark: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val containerColor = if (isSelected) HighlightBg else CardColor
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = {},
                onLongClick = { onLongClick() },
            ),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onSelectionToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = Accent,
                    uncheckedColor = TextSecondary,
                ),
            )

            Spacer(modifier = Modifier.width(4.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            ) {
                // 第一行：[序号] 客户名 + 同类型标记星标
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (row.serial.isNotBlank()) {
                        Text(
                            text = "[${row.serial}]",
                            color = Accent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = row.borrower.ifBlank { "未命名客户" },
                        color = TextColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isBatchMarked) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = "同类型代表性户型",
                            tint = Warning,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.size(2.dp))

                // 第二行：地址概 + 地址详
                val address = listOf(row.addrGeneral, row.addrDetail)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                if (address.isNotBlank()) {
                    Text(
                        text = address,
                        color = TextSecondary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.size(2.dp))
                }

                // 第三行：性质 + 备注
                val meta = listOf(row.propertyType, row.remark)
                    .filter { it.isNotBlank() }
                    .joinToString("  |  ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 照片数 Badge
            PhotoCountBadge(photoCount = photoCount)

            // 拍照按钮
            IconButton(onClick = onTakePhoto) {
                Icon(
                    imageVector = Icons.Filled.CameraAlt,
                    contentDescription = "拍照",
                    tint = Accent,
                )
            }

            // 查看照片按钮（仅 photoCount>0 时显示）
            if (photoCount > 0) {
                IconButton(onClick = onViewPhotos) {
                    Icon(
                        imageVector = Icons.Filled.Photo,
                        contentDescription = "查看照片",
                        tint = Accent,
                    )
                }
            }

            // 编辑备注按钮
            IconButton(onClick = onEditRemark) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "编辑备注",
                    tint = TextSecondary,
                )
            }
        }
    }
}

/**
 * 圆形照片数 Badge：[Accent] 背景，[TextOnLight] 文字。
 */
@Composable
private fun PhotoCountBadge(photoCount: Int) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Accent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = photoCount.toString(),
            color = TextOnLight,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
