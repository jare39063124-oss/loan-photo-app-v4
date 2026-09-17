package com.banktool.loanphoto.ui.settings

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.banktool.loanphoto.data.camera.WatermarkConfig
import com.banktool.loanphoto.data.camera.WatermarkFontSize
import com.banktool.loanphoto.data.camera.WatermarkPosition
import com.banktool.loanphoto.data.camera.WatermarkSegmentSetting
import com.banktool.loanphoto.data.camera.WatermarkSource
import com.banktool.loanphoto.data.camera.defaultWatermarkSegmentSettings
import com.banktool.loanphoto.ui.theme.Accent
import com.banktool.loanphoto.ui.theme.Divider
import com.banktool.loanphoto.ui.theme.HighlightBg
import com.banktool.loanphoto.ui.theme.Text as TextColor
import com.banktool.loanphoto.ui.theme.TextSecondary

/**
 * 水印设置共享内容（设置页水印卡与相机页水印设置弹窗共用）。
 *
 * 内容：启用水印 Switch、字号下拉、位置下拉、不透明度 Slider、5 个水印段下拉
 * （CUSTOM 段附带自定义文本输入框）。
 *
 * 数据与持久化分离：本组件为纯受控 UI，所有值来自 [config]（由调用方从
 * `WatermarkConfigRepository.configFlow()` 收集），所有变更经回调交给调用方写入
 * 同一 DataStore——设置页与相机弹窗编辑即时互相同步。
 *
 * @param config 当前水印配置
 * @param onEnabledChange 启用/关闭水印
 * @param onFontSizeChange 字号变更
 * @param onPositionChange 位置变更
 * @param onOpacityChange 不透明度变更
 * @param onSegmentChange 水印段变更（index=槽位索引 0..4；source 为新来源；
 *        customText 在 source=CUSTOM 时生效，其余来源忽略）
 */
@Composable
internal fun WatermarkSettingsContent(
    config: WatermarkConfig,
    onEnabledChange: (Boolean) -> Unit,
    onFontSizeChange: (WatermarkFontSize) -> Unit,
    onPositionChange: (WatermarkPosition) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onSegmentChange: (index: Int, source: WatermarkSource, customText: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // 启用开关
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "启用水印", fontSize = 14.sp, color = TextSecondary)
            Switch(checked = config.enabled, onCheckedChange = onEnabledChange)
        }
        WatermarkSettingsDivider()
        // 字号下拉
        WatermarkDropdown(
            label = "字号",
            selectedText = when (config.fontSize) {
                WatermarkFontSize.LARGE -> "大"
                WatermarkFontSize.MEDIUM -> "中"
                WatermarkFontSize.SMALL -> "小"
            },
            options = listOf(
                "大" to WatermarkFontSize.LARGE,
                "中" to WatermarkFontSize.MEDIUM,
                "小" to WatermarkFontSize.SMALL,
            ),
            onSelect = onFontSizeChange,
        )
        Spacer(modifier = Modifier.size(8.dp))
        // 位置下拉
        WatermarkDropdown(
            label = "位置",
            selectedText = when (config.position) {
                WatermarkPosition.BOTTOM_RIGHT -> "右下"
                WatermarkPosition.BOTTOM_LEFT -> "左下"
                WatermarkPosition.TOP_RIGHT -> "右上"
                WatermarkPosition.TOP_LEFT -> "左上"
            },
            options = listOf(
                "右下" to WatermarkPosition.BOTTOM_RIGHT,
                "左下" to WatermarkPosition.BOTTOM_LEFT,
                "右上" to WatermarkPosition.TOP_RIGHT,
                "左上" to WatermarkPosition.TOP_LEFT,
            ),
            onSelect = onPositionChange,
        )
        Spacer(modifier = Modifier.size(8.dp))
        // 不透明度滑块
        Text(text = "不透明度", fontSize = 12.sp, color = TextSecondary)
        Slider(
            value = config.opacity,
            onValueChange = onOpacityChange,
            valueRange = 0.3f..1.0f,
            steps = 6,
        )
        Text(
            text = "%.0f%%".format(config.opacity * 100),
            fontSize = 12.sp,
            color = Accent,
        )
        Spacer(modifier = Modifier.size(8.dp))
        WatermarkSettingsDivider()
        Spacer(modifier = Modifier.size(8.dp))
        // 水印段配置（5 槽下拉，CUSTOM 段附带文本输入）
        Text(
            text = "水印内容",
            fontSize = 12.sp,
            color = TextSecondary,
        )
        Spacer(modifier = Modifier.size(4.dp))
        val segmentSettings = config.segmentSettings ?: defaultWatermarkSegmentSettings()
        segmentSettings.forEachIndexed { index, setting ->
            if (index > 0) Spacer(modifier = Modifier.size(8.dp))
            WatermarkSegmentRow(
                index = index,
                setting = setting,
                onSelect = { onSegmentChange(index, it, setting.customText) },
                onCustomTextChange = { onSegmentChange(index, WatermarkSource.CUSTOM, it) },
            )
        }
    }
}

/**
 * 水印内容分割线（与设置卡分割线样式一致）。
 */
@Composable
private fun WatermarkSettingsDivider() {
    HorizontalDivider(color = Divider)
}

/**
 * 水印选项下拉选择器（泛型化，支持字号 / 位置等任意枚举）。
 *
 * 点击触发 [DropdownMenu]，选项由 [options] 提供（显示文本 + 任意值）。
 * 选择后立即回调 [onSelect] 持久化。
 *
 * @param label 选项标签（如「字号」「位置」）
 * @param selectedText 当前选中项的显示文本
 * @param options 可选项列表（显示文本 to 值）
 * @param onSelect 选择回调
 */
@Composable
internal fun <T> WatermarkDropdown(
    label: String,
    selectedText: String,
    options: List<Pair<String, T>>,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = true }
                .background(HighlightBg, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(text = label, fontSize = 12.sp, color = TextSecondary)
                Text(text = selectedText, fontSize = 14.sp, color = TextColor, fontWeight = FontWeight.Medium)
            }
            Icon(imageVector = Icons.Filled.ArrowDropDown, contentDescription = "展开选项", tint = Accent)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (text, value) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); expanded = false })
            }
        }
    }
}

/**
 * 水印段配置行：段来源下拉（[WatermarkSource.entries] 9 项）+ CUSTOM 段的自定义文本输入框。
 *
 * @param index 槽位索引（0..4，仅用于标签展示）
 * @param setting 当前槽位配置
 * @param onSelect 下拉选择回调
 * @param onCustomTextChange CUSTOM 段自定义文本输入回调
 */
@Composable
private fun WatermarkSegmentRow(
    index: Int,
    setting: WatermarkSegmentSetting,
    onSelect: (WatermarkSource) -> Unit,
    onCustomTextChange: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        WatermarkDropdown(
            label = "段 ${index + 1}",
            selectedText = setting.source.displayName,
            options = WatermarkSource.entries.map { it.displayName to it },
            onSelect = onSelect,
        )
        if (setting.source == WatermarkSource.CUSTOM) {
            Spacer(modifier = Modifier.size(8.dp))
            OutlinedTextField(
                value = setting.customText,
                onValueChange = onCustomTextChange,
                label = { Text("自定义文本（段 ${index + 1}）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
