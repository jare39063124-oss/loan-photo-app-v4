package com.banktool.loanphoto.ui.camera

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.banktool.loanphoto.data.camera.WatermarkConfig
import com.banktool.loanphoto.data.camera.WatermarkFontSize
import com.banktool.loanphoto.data.camera.WatermarkPosition
import com.banktool.loanphoto.data.camera.WatermarkSource
import com.banktool.loanphoto.ui.settings.WatermarkSettingsContent

/**
 * 相机页水印设置弹窗（取景页水印预览块点击入口）。
 *
 * 复用设置页水印卡同一份 [WatermarkSettingsContent]，数据与 setter 均来自
 * [CameraViewModel] 的水印配置代理（`watermarkConfig` StateFlow + 各 set 方法，
 * 写入全局 `watermark.preferences_pb` DataStore）——设置页与相机弹窗编辑即时互相同步，
 * 取景页实时水印预览块亦随流刷新。
 *
 * 与 [WatermarkPreviewDialog]（拍照前逐槽会话级编辑）并存，职责不同：
 * 本弹窗修改持久化全局配置，后者只影响本会话拍照。
 *
 * @param config 当前水印配置（与设置页同源）
 * @param onEnabledChange 启用/关闭水印
 * @param onFontSizeChange 字号变更
 * @param onPositionChange 位置变更
 * @param onOpacityChange 不透明度变更
 * @param onSegmentChange 水印段变更（槽位索引 0..4）
 * @param onDismiss 关闭回调
 */
@Composable
fun WatermarkSettingsDialog(
    config: WatermarkConfig,
    onEnabledChange: (Boolean) -> Unit,
    onFontSizeChange: (WatermarkFontSize) -> Unit,
    onPositionChange: (WatermarkPosition) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onSegmentChange: (index: Int, source: WatermarkSource, customText: String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "水印设置", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                WatermarkSettingsContent(
                    config = config,
                    onEnabledChange = onEnabledChange,
                    onFontSizeChange = onFontSizeChange,
                    onPositionChange = onPositionChange,
                    onOpacityChange = onOpacityChange,
                    onSegmentChange = onSegmentChange,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}
