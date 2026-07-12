package com.banktool.loanphoto.camera.engine

import android.widget.FrameLayout
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.Executor

/**
 * 相机缩放信息。
 *
 * - [minZoomRatio] / [maxZoomRatio]：当前后端支持的缩放倍率范围
 * - [hasWideAngle]：设备是否支持真实广角。CameraX 后端 = minZoomRatio < 1.0；
 *   Camera2 后端 = 后置逻辑相机存在可切换的广角物理子相机
 * - [isWideAngleActive]：当前是否处于广角态（zoom < 1.0 或已切换到广角物理传感器）
 */
data class ZoomInfo(
    val minZoomRatio: Float = 1f,
    val maxZoomRatio: Float = 1f,
    val zoomRatio: Float = 1f,
    val hasWideAngle: Boolean = false,
    val isWideAngleActive: Boolean = false,
)

/**
 * 相机引擎抽象层：解耦 ViewModel/UI 与具体相机 API（CameraX / Camera2）。
 *
 * - [bind]：由调用方传入 [FrameLayout] 容器，实现自建预览 Surface 并 addView
 *   （CameraX 后端创建 PreviewView；Camera2 后端创建 TextureView）
 * - [captureToFile]：触发拍照落盘（实现负责按已设置 flashMode 选用正确的闪光策略）
 * - 伸缩/广角/闪光/torch/生命周期均由实现承担
 */
interface CameraEngine {
    val zoomInfo: StateFlow<ZoomInfo>
    val cameraReady: StateFlow<Boolean>
    fun bind(container: FrameLayout, lifecycleOwner: LifecycleOwner)
    fun captureToFile(
        outputFile: File,
        executor: Executor,
        onSaved: (File) -> Unit,
        onError: (Throwable) -> Unit,
    )
    fun setZoom(ratio: Float)
    fun toggleWideAngle()
    fun setFlashMode(mode: Int)
    fun enableTorch(enable: Boolean)
    fun release()
}
