package com.banktool.loanphoto.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.banktool.loanphoto.data.camera.WatermarkConfig
import com.banktool.loanphoto.data.camera.WatermarkFontSize
import com.banktool.loanphoto.data.camera.WatermarkPosition
import com.banktool.loanphoto.domain.entity.CustomerRow
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 预览卡片底色（浅灰）。 */
private val PreviewCardColor = Color(0xFFE0E0E0)

/** 预览水印块背景（参照实际水印：半透明黑）。 */
private val PreviewBlockColor = Color(0x8C000000)

/**
 * 水印预览与编辑对话框（相机页入口，会话级生效）。
 *
 * - 预览区：浅灰卡片上按当前 [WatermarkConfig] 的开关/字号/位置等比缩小绘制水印块示意
 *   （半透明黑底 + 白色粗体文本，与 WatermarkGenerator 实际绘制样式一致）
 * - 编辑区：水印包含的每个文本字段一个 OutlinedTextField，预填当前默认值
 *   （日期=今天、序号/地址=当前 primary [CustomerRow]），经纬度只读展示（拍照时自动定位）
 * - 确定：仅收集非空白字段为 overrides（key: "date"/"serial"/"address"），
 *   由 [onConfirm] 写回 ViewModel；取消/返回不改动
 *
 * @param config 当前持久化水印配置（开关/字号/位置/不透明度）
 * @param primaryRow 当前主客户行（预填默认值）
 * @param currentOverrides 当前已生效的会话级覆盖（再次打开时回显）
 * @param latLngText 只读经纬度文本（最近已知定位或占位提示）
 * @param onConfirm 确定回调，参数为非空白键值的 overrides map
 * @param onDismiss 取消/关闭回调
 */
@Composable
fun WatermarkPreviewDialog(
    config: WatermarkConfig,
    primaryRow: CustomerRow?,
    currentOverrides: Map<String, String>,
    latLngText: String,
    onConfirm: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val dateDefault = remember {
        LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日", Locale.getDefault()))
    }
    var dateText by remember {
        mutableStateOf(currentOverrides["date"] ?: dateDefault)
    }
    var serialText by remember {
        mutableStateOf(currentOverrides["serial"] ?: primaryRow?.serial.orEmpty())
    }
    var addressText by remember {
        mutableStateOf(
            currentOverrides["address"]
                ?: (primaryRow?.addrGeneral.orEmpty() + primaryRow?.addrDetail.orEmpty()),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("水印预览与编辑") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // ---- 预览区：浅灰卡片 + 按当前配置样式绘制的缩小水印块 ----
                Surface(
                    color = PreviewCardColor,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    ) {
                        if (!config.enabled) {
                            Text(
                                text = "水印已关闭",
                                color = Color(0xFF9E9E9E),
                                fontSize = 12.sp,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        } else {
                            WatermarkPreviewBlock(
                                lines = buildPreviewLines(
                                    config = config,
                                    dateText = dateText,
                                    serialText = serialText,
                                    addressText = addressText,
                                    latLngText = latLngText,
                                ),
                                fontSize = config.fontSize,
                                modifier = Modifier.align(previewAlignment(config.position)),
                            )
                        }
                    }
                }

                // ---- 编辑区：每个文本字段一个输入框，经纬度只读 ----
                if (config.showDate) {
                    OutlinedTextField(
                        value = dateText,
                        onValueChange = { dateText = it },
                        label = { Text("拍摄日期") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (config.showSerial) {
                    OutlinedTextField(
                        value = serialText,
                        onValueChange = { serialText = it },
                        label = { Text("序号") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (config.showAddress) {
                    OutlinedTextField(
                        value = addressText,
                        onValueChange = { addressText = it },
                        label = { Text("地址") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (config.showLatlng) {
                    OutlinedTextField(
                        value = latLngText,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("经纬度（拍照时自动定位，不可编辑）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (!config.showDate && !config.showSerial && !config.showAddress && !config.showLatlng) {
                    Text(
                        text = "全部内容开关已关闭，水印块无文本段",
                        color = Color(0xFF9E9E9E),
                        fontSize = 12.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val overrides = buildMap {
                        dateText.trim().takeIf { it.isNotEmpty() }?.let { put("date", it) }
                        serialText.trim().takeIf { it.isNotEmpty() }?.let { put("serial", it) }
                        addressText.trim().takeIf { it.isNotEmpty() }?.let { put("address", it) }
                    }
                    onConfirm(overrides)
                },
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 构造预览文本行（与 WatermarkGenerator.buildSegments 输出格式一致，
 * 编辑中的字段实时反映到预览）。
 */
private fun buildPreviewLines(
    config: WatermarkConfig,
    dateText: String,
    serialText: String,
    addressText: String,
    latLngText: String,
): List<String> = buildList {
    if (config.showDate) add(dateText.ifBlank { "日期" })
    if (config.showSerial && serialText.isNotBlank()) add("序号:$serialText")
    if (config.showAddress) add(addressText.ifBlank { "地址" })
    if (config.showLatlng) add(latLngText)
}

/** 预览卡片内水印块对齐方式（与实际四角位置对应）。 */
private fun previewAlignment(position: WatermarkPosition): Alignment = when (position) {
    WatermarkPosition.BOTTOM_RIGHT -> Alignment.BottomEnd
    WatermarkPosition.BOTTOM_LEFT -> Alignment.BottomStart
    WatermarkPosition.TOP_RIGHT -> Alignment.TopEnd
    WatermarkPosition.TOP_LEFT -> Alignment.TopStart
}

/** 预览字号（sp），与实际档位（80/56/36px @1080px 基准）等比缩小。 */
private fun previewFontSize(fontSize: WatermarkFontSize) = when (fontSize) {
    WatermarkFontSize.LARGE -> 13.sp
    WatermarkFontSize.MEDIUM -> 10.sp
    WatermarkFontSize.SMALL -> 8.sp
}

/**
 * 等比缩小的水印块示意：半透明黑底 + 白色粗体多行文本（参照实际绘制样式）。
 */
@Composable
private fun WatermarkPreviewBlock(
    lines: List<String>,
    fontSize: WatermarkFontSize,
    modifier: Modifier = Modifier,
) {
    if (lines.isEmpty()) return
    Column(
        modifier = modifier
            .background(PreviewBlockColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        lines.forEach { line ->
            Text(
                text = line,
                color = Color.White,
                fontSize = previewFontSize(fontSize),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}
