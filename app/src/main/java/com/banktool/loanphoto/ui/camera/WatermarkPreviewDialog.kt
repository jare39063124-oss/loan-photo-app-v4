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
import androidx.compose.runtime.MutableState
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
import com.banktool.loanphoto.data.camera.WatermarkSegmentSetting
import com.banktool.loanphoto.data.camera.WatermarkSource
import com.banktool.loanphoto.data.camera.defaultWatermarkSegmentSettings
import com.banktool.loanphoto.domain.entity.CustomerRow
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 预览卡片底色（浅灰）。 */
private val PreviewCardColor = Color(0xFFE0E0E0)

/** 预览水印块背景（参照实际水印：半透明黑）。 */
private val PreviewBlockColor = Color(0x8C000000)

/**
 * 编辑区单个激活槽位的数据。
 *
 * @param index 槽位索引（0..3，对应 overrides 的 `slot_N` key）
 * @param source 段内容来源
 * @param label 编辑框标签（含槽位号与来源名）
 * @param default 默认生成文本（CUSTOM 段为配置的 customText）
 * @param editable 是否可编辑（经纬度段只读，拍照时自动定位）
 */
private data class SlotUi(
    val index: Int,
    val source: WatermarkSource,
    val label: String,
    val default: String,
    val editable: Boolean,
)

/**
 * 水印预览与编辑对话框（相机页入口，会话级生效）。
 *
 * - 预览区：浅灰卡片上按当前 [WatermarkConfig] 的段配置/字号/位置等比缩小绘制水印块示意
 *   （半透明黑底 + 白色粗体文本，与 WatermarkGenerator 实际绘制样式一致）
 * - 编辑区：每个激活槽位（非 OFF）一个 OutlinedTextField，预填当前生效值
 *   （已有 `slot_N` 会话覆盖优先，否则为默认生成值：日期=今天、序号/客户名/性质/备注=当前
 *   primary [CustomerRow] 字段、地址=addrGeneral+addrDetail、经纬度=只读展示拍照时自动定位）
 * - 确定：收集非空白且与默认值不同的槽位为 overrides（key: `"slot_N"`），
 *   由 [onConfirm] 写回 ViewModel；取消/返回不改动
 *
 * @param config 当前持久化水印配置（段配置经 [WatermarkConfig.segmentSettings] 传入，
 *        缺失时按默认 4 段渲染）
 * @param primaryRow 当前主客户行（预填默认值）
 * @param currentOverrides 当前已生效的会话级覆盖（再次打开时回显）
 * @param latLngText 经纬度文本（最近已知定位或占位提示，只读展示）
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
    val settings: List<WatermarkSegmentSetting> =
        config.segmentSettings ?: defaultWatermarkSegmentSettings()
    val dateDefault = remember {
        LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日", Locale.getDefault()))
    }

    // 激活槽位（OFF 不渲染、不参与覆盖收集），默认值按段来源计算
    val slots = remember(settings, dateDefault, latLngText) {
        settings.mapIndexed { index, setting ->
            SlotUi(
                index = index,
                source = setting.source,
                label = "段 ${index + 1}（${setting.source.displayName}）",
                default = when (setting.source) {
                    WatermarkSource.DATE -> dateDefault
                    WatermarkSource.SERIAL -> primaryRow?.serial.orEmpty()
                    WatermarkSource.BORROWER -> primaryRow?.borrower.orEmpty()
                    WatermarkSource.ADDRESS ->
                        primaryRow?.addrGeneral.orEmpty() + primaryRow?.addrDetail.orEmpty()
                    WatermarkSource.PROPERTY_TYPE -> primaryRow?.propertyType.orEmpty()
                    WatermarkSource.REMARK -> primaryRow?.remark.orEmpty()
                    WatermarkSource.LATLNG -> latLngText
                    WatermarkSource.CUSTOM -> setting.customText
                    WatermarkSource.OFF -> ""
                },
                editable = setting.source != WatermarkSource.LATLNG,
            )
        }.filter { it.source != WatermarkSource.OFF }
    }

    // 每个激活槽位的编辑状态（预填已有会话覆盖，否则默认值）
    val slotStates = remember(slots, currentOverrides) {
        slots.map { slot ->
            mutableStateOf(currentOverrides["slot_${slot.index}"]?.takeIf { it.isNotBlank() } ?: slot.default)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("水印预览与编辑") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 预览区为浅灰卡片，内嵌按当前配置样式绘制的缩小水印块
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
                                lines = buildPreviewLines(slots, slotStates),
                                fontSize = config.fontSize,
                                modifier = Modifier.align(previewAlignment(config.position)),
                            )
                        }
                    }
                }

                // 编辑区为每个激活槽位一个输入框，经纬度只读
                slots.forEachIndexed { i, slot ->
                    OutlinedTextField(
                        value = slotStates[i].value,
                        onValueChange = { slotStates[i].value = it },
                        label = { Text(slot.label) },
                        readOnly = !slot.editable,
                        singleLine = !slot.editable,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (slots.isEmpty()) {
                    Text(
                        text = "全部水印段均已关闭，水印块无文本段",
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
                        slots.forEachIndexed { i, slot ->
                            val text = slotStates[i].value.trim()
                            if (text.isNotEmpty() && text != slot.default.trim()) {
                                put("slot_${slot.index}", text)
                            }
                        }
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
 *
 * 空数据段（序号/客户名/性质/备注/自定义文本空白）不显示，与实际绘制一致；
 * 日期/地址段空白时显示占位便于编辑预览；经纬度段始终展示只读文本。
 */
private fun buildPreviewLines(
    slots: List<SlotUi>,
    slotStates: List<MutableState<String>>,
): List<String> = slots.mapIndexed { i, slot -> slot to slotStates[i].value.trim() }
    .mapNotNull { (slot, text) ->
        when (slot.source) {
            WatermarkSource.SERIAL,
            WatermarkSource.BORROWER,
            WatermarkSource.PROPERTY_TYPE,
            WatermarkSource.REMARK,
            WatermarkSource.CUSTOM,
            -> text.takeIf { it.isNotEmpty() }
            WatermarkSource.DATE -> text.ifBlank { "日期" }
            WatermarkSource.ADDRESS -> text.ifBlank { "地址" }
            WatermarkSource.LATLNG -> text
            WatermarkSource.OFF -> null
        }
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
