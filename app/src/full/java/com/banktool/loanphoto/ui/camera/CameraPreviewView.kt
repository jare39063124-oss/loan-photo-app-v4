package com.banktool.loanphoto.ui.camera

import androidx.camera.view.PreviewView
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
 * 相机预览 Composable（CameraX 后端，trial/full flavor 使用）。
 *
 * 通过 [CameraEngine] 抽象层绑定预览宿主，解耦具体相机 API。
 *
 * - CameraX 后端：[PreviewView] 作为 AndroidView，实现模式 PERFORMANCE + FILL_CENTER
 * - 绑定 Preview + ImageCapture 两个 use case，选择后置摄像头（由 engine 负责）
 * - 双指缩放手势通过 engine 读取 zoomInfo 范围并调用 engine.setZoom
 *
 * 注意：本文件在 trial/full 源集中各放一份（FQN 与 huawei 源集的 Camera2 版本相同，
 * 通过源集拆分避免 huawei 构建的重复定义错误）。修改时请同步两份。
 *
 * @param engine 相机引擎实现（由 Hilt 按 flavor 注入，trial/full 为 CameraxCameraEngine）
 * @param onZoomChange 当双指缩放改变 zoom ratio 时回调（用于同步 UI 滑块）
 */
@Composable
fun CameraPreviewView(
    engine: CameraEngine,
    onZoomChange: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView: PreviewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // 不直接 unbind；由后端引擎在生命周期结束时自动清理
            Timber.d("CameraPreviewView onDispose")
        }
    }

    AndroidView(
        factory = {
            // engine.bind 在主线程执行（CameraX 后端的 ProcessCameraProvider.getInstance 使用 main executor）
            engine.bind(previewView, lifecycleOwner)
            previewView
        },
        modifier = modifier
            .fillMaxSize()
            .pointerInput(engine) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    val zi = engine.zoomInfo.value
                    val newZoom = (zi.zoomRatio * zoomChange)
                        .coerceIn(zi.minZoomRatio, zi.maxZoomRatio)
                    engine.setZoom(newZoom)
                    onZoomChange(newZoom)
                }
            },
    )
}
