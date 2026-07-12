package com.banktool.loanphoto.ui.camera

import android.widget.FrameLayout
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.banktool.loanphoto.camera.engine.CameraEngine
import timber.log.Timber

/**
 * 统一相机预览 Composable（trial/full 共用，main 源集）。
 *
 * 提供一个 [FrameLayout] 容器，由 [CameraEngine.bind] 自建预览 Surface：
 * - CameraxCameraEngine 创建 PreviewView 并 addView
 * - Camera2CameraEngine 创建 TextureView 并 addView
 *
 * 双指捏合读 engine.zoomInfo 范围调 engine.setZoom。
 */
@Composable
fun CameraPreviewView(
    engine: CameraEngine,
    onZoomChange: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(Unit) {
        onDispose {
            Timber.d("CameraPreviewView onDispose")
            engine.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            val container = FrameLayout(ctx)
            engine.bind(container, lifecycleOwner)
            container
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
