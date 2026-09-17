package com.banktool.loanphoto.ui.customer.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.banktool.loanphoto.domain.entity.CustomerRow
import com.banktool.loanphoto.domain.entity.PhotoType
import com.banktool.loanphoto.domain.entity.PhotoTypeConfig
import com.banktool.loanphoto.ui.theme.Accent
import com.banktool.loanphoto.ui.theme.Card as CardColor
import com.banktool.loanphoto.ui.theme.HighlightBg
import com.banktool.loanphoto.ui.theme.PhotoDoneBg
import com.banktool.loanphoto.ui.theme.TextOnLight
import com.banktool.loanphoto.ui.theme.TextSecondary
import com.banktool.loanphoto.ui.theme.Warning

/**
 * 客户清单中的单行卡片。
 *
 * v4.0.3 布局：
 * - 顶部行：[Checkbox] + 文本列（[序号] 借款人名 / 地址 / 性质+备注）+ 最右侧竖向操作按钮（拍照 / 查看已拍 / 编辑备注）
 * - 底部计数行：全量分类计数（[PhotoTypeCountRow]），左边缘对齐 Checkbox
 *
 * 选中时背景为 [HighlightBg]，有照片时为 [PhotoDoneBg]（浅绿），其余为 [CardColor]（白色）。
 * 被标记为同类型代表性户型时显示星标。
 * 长按弹出菜单（[RowLongPressMenu]）。
 *
 * @param row 客户行数据
 * @param photoCount 该行已拍照片数
 * @param photoTypeCounts 各分类拍照计数（key=displayName 字符串，value=数量）
 * @param photoTypeConfigs 拍照类型配置列表（用于决定展示哪些分类 chip；默认 5 种内置类型）
 * @param isSelected 是否被选中
 * @param isBatchMarked 是否被标记为同类型代表性户型
 * @param onSelectionToggle 切换选中状态
 * @param onTakePhoto 点击拍照按钮
 * @param onViewPhotos 点击查看照片按钮（即使 photoCount=0 也会触发）
 * @param onEditRemark 点击编辑备注按钮
 * @param onAddressClick 点击地址文字回调（弹出导航应用选择）
 * @param onLongClick 长按行回调
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun CustomerRowItem(
    row: CustomerRow,
    photoCount: Int,
    photoTypeCounts: Map<String, Int> = emptyMap(),
    photoTypeConfigs: List<PhotoTypeConfig> = PhotoType.DEFAULT_CONFIGS,
    isSelected: Boolean,
    isBatchMarked: Boolean = false,
    onSelectionToggle: () -> Unit,
    onTakePhoto: () -> Unit,
    onViewPhotos: () -> Unit,
    onEditRemark: () -> Unit,
    onAddressClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    val containerColor = when {
        isSelected -> HighlightBg
        photoCount > 0 -> PhotoDoneBg
        else -> CardColor
    }
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
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Top,
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

                // 文本列：序号/借款人/地址/性质备注（不再包含 PhotoTypeCountRow）
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (row.serial.isNotBlank()) {
                            Text(
                                text = "[${row.serial}]",
                                color = Accent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = row.borrower.ifBlank { "未命名客户" },
                            color = Accent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
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

                    val address = listOf(row.addrGeneral, row.addrDetail)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                    if (address.isNotBlank()) {
                        Text(
                            text = address,
                            color = TextSecondary,
                            fontSize = 14.sp,
                            modifier = Modifier.clickable { onAddressClick() },
                        )
                        Spacer(modifier = Modifier.size(2.dp))
                    }

                    // 性质与备注合并为一行次要文本
                    val meta = listOf(row.propertyType, row.remark)
                        .filter { it.isNotBlank() }
                        .joinToString("  |  ")
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            color = TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }

                // 操作按钮竖排
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    IconButton(
                        onClick = onTakePhoto,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CameraAlt,
                            contentDescription = "拍照",
                            tint = Accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    // 查看已拍入口始终保留（0 张时也可进入查看）
                    IconButton(
                        onClick = onViewPhotos,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Photo,
                            contentDescription = "查看已拍",
                            tint = Accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    IconButton(
                        onClick = onEditRemark,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "编辑备注",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // 底部计数行，左对齐 Checkbox 左边缘（Card padding 8 + Row padding 8 = 16dp）
            PhotoTypeCountRow(
                totalCount = photoCount,
                typeCounts = photoTypeCounts,
                configs = photoTypeConfigs,
                modifier = Modifier.padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
            )
        }
    }
}

/**
 * 全量分类计数展示（[FlowRow]）。
 *
 * 遍历 [configs] 展示各分类 chip + 「总计」前缀。计数匹配基于 [PhotoTypeConfig.displayName]
 * （与 progress.json 中 types Map 的 key 对齐）。
 * 格式示例：「总计 N  远景0 近景2 内部1 瑕疵0 其他0」
 *
 * - 总计：[Accent] 背景 + [TextOnLight] 文字（强调）
 * - count>0 分类：[HighlightBg] 背景 + [Accent] 文字
 * - count=0 分类：透明背景 + [TextSecondary] 文字（弱化）
 *
 * @param typeCounts 各分类计数（key=displayName 字符串）
 * @param totalCount 总照片数（用于「总计」chip）
 * @param configs 拍照类型配置列表（决定展示哪些分类 chip）
 * @param modifier 外部传入的修饰符（用于控制对齐与 padding）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhotoTypeCountRow(
    typeCounts: Map<String, Int>,
    totalCount: Int,
    configs: List<PhotoTypeConfig>,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TotalCountChip(total = totalCount)
        // 各分类 chip（按用户配置遍历）
        configs.forEach { config ->
            val count = typeCounts[config.displayName] ?: 0
            PhotoTypeCountChip(displayName = config.displayName, count = count)
        }
    }
}

/**
 * 总计 chip：[Accent] 实心背景 + 白色加粗文字。
 */
@Composable
private fun TotalCountChip(total: Int) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Accent)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "总计 $total",
            color = TextOnLight,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * 单个分类计数 Chip。
 *
 * 格式始终为「{displayName}{count}」（如「远景0」「近景2」）。
 * - count=0：[TextSecondary] 文字 + 透明背景（弱化但可见）
 * - count>0：[Accent] 文字 + [HighlightBg] 背景（强调）
 */
@Composable
private fun PhotoTypeCountChip(displayName: String, count: Int) {
    val text = "$displayName$count"
    val bgColor = if (count > 0) HighlightBg else Color.Transparent
    val textColor = if (count > 0) Accent else TextSecondary
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 11.sp,
        )
    }
}
