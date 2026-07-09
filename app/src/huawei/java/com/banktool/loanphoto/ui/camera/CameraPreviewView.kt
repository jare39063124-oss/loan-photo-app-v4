package com.banktool.loanphoto.ui.camera

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.banktool.loanphoto.camera.engine.CameraEngine
import timber.log.Timber

/**
 * Camera2 预览 Composable（huawei flavor 专用，源集替换 main 的同名 Composable）。
 *
 * 与 main/trial/full 的 CameraX 版本区别：
 * - 使用 [TextureView] 而非 PreviewView，作为 [Camera2CameraEngine.bind] 的 host
 * - 双指缩放直接调用 [CameraEngine.setZoom]（引擎内部处理 Camera2 zoom ratio / crop region）
 * - onDispose 主动调用 [CameraEngine.release]，配合生命周期 DESTROYED 双重释放
 *
 * 注意：本文件与 main 的 CameraPreviewView.kt 同 FQN，必须在 trial/full 源集中各放一份
 * CameraX 版本，并从 main 中移除，否则 huawei 构建会出现重复定义编译错误。
 *
 * @param engine 由 Hilt 注入的 Camera2 相机引擎
 * @param onZoomChange 双指缩放后回调新 zoom ratio（供 UI 同步滑块）
 * @param modifier Compose 修饰符
 */
@Composable
fun CameraPreviewView(
    engine: CameraEngine,
    onZoomChange: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val textureView: TextureView = remember { TextureView(context) }

    DisposableEffect(Unit) {
        onDispose {
            Timber.d("Camera2 CameraPreviewView onDispose")
            engine.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            engine.bind(textureView, lifecycleOwner)
            textureView
        },
        modifier = modifier
            .fillMaxSize()
            .pointerInput(engine) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    val zi = engine.zoomInfo.value
                    val newZoom = (zi.zoomRatio * zoomChange).coerceIn(
                        if (zi.minZoomRatio > 0f) zi.minZoomRatio else 1f,
                        if (zi.maxZoomRatio > 0f) zi.maxZoomRatio else 1f,
                    )
                    engine.setZoom(newZoom)
                    onZoomChange(newZoom)
                }
            },
    )
}
