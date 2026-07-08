package com.banktool.loanphoto.ui.camera

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executor

/**
 * CameraX 预览 Composable。
 *
 * - 使用 [PreviewView] 作为 AndroidView
 * - 绑定 Preview + ImageCapture 两个 use case
 * - 选择后置摄像头 [CameraSelector.DEFAULT_BACK_CAMERA]
 * - 目标宽高比 4:3（由 Preview 默认策略决定；ImageCapture 用 MAX_QUALITY）
 *
 * @param imageCapture 由调用方持有并传递的 [ImageCapture] 实例（用于触发拍照）
 * @param onCameraReady 当相机绑定完成时回调，传递 [Camera] 对象（用于缩放控制）
 * @param onZoomChange 当双指缩放改变 zoom ratio 时回调（用于同步 UI 滑块）
 */
@Composable
fun CameraPreviewView(
    imageCapture: ImageCapture,
    modifier: Modifier = Modifier,
    onCameraReady: (Camera) -> Unit = {},
    onZoomChange: (Float) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView: PreviewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    val cameraExecutor: Executor = remember { ContextCompat.getMainExecutor(context) }

    // 绑定完成后捕获的 Camera 对象，供双指缩放手势读取 zoomState
    var camera by remember { mutableStateOf<Camera?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            // 不直接 unbind；由 ProcessCameraProvider 在生命周期结束时自动清理
            Timber.d("CameraPreviewView onDispose")
        }
    }

    AndroidView(
        factory = { ctx ->
            bindCamera(
                ctx = ctx,
                previewView = previewView,
                imageCapture = imageCapture,
                lifecycleOwner = lifecycleOwner,
                cameraExecutor = cameraExecutor,
                onCameraReady = { cam ->
                    camera = cam
                    onCameraReady(cam)
                },
            )
            previewView
        },
        modifier = modifier
            .fillMaxSize()
            .pointerInput(camera) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    camera?.let { cam ->
                        val zoomState = cam.cameraInfo.zoomState.value
                        if (zoomState != null) {
                            val currentZoom = zoomState.zoomRatio
                            val minZoom = zoomState.minZoomRatio
                            val maxZoom = zoomState.maxZoomRatio
                            val newZoom = (currentZoom * zoomChange).coerceIn(minZoom, maxZoom)
                            cam.cameraControl.setZoomRatio(newZoom)
                            onZoomChange(newZoom)
                        }
                    }
                }
            },
    )
}

/**
 * 绑定 CameraX。在主线程执行。
 *
 * 捕获 [cameraProvider.bindToLifecycle] 返回的 [Camera] 对象并通过 [onCameraReady] 回调暴露，
 * 供调用方读取 zoomState / 控制 cameraControl。
 */
private fun bindCamera(
    ctx: Context,
    previewView: PreviewView,
    imageCapture: ImageCapture,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    cameraExecutor: Executor,
    onCameraReady: (Camera) -> Unit,
) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
    cameraProviderFuture.addListener({
        try {
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            // ImageCapture 由调用方持有的实例负责配置（MAX_QUALITY + 4:3）。
            // 此处重新绑定以替换之前已绑定的旧实例（避免重复绑定报错）。
            cameraProvider.unbindAll()
            val camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture,
            )
            onCameraReady(camera)
        } catch (e: Exception) {
            Timber.e(e, "CameraPreviewView 绑定相机失败")
        }
    }, cameraExecutor)
}

/**
 * 触发拍照：把 ImageCapture 写入目标文件。
 *
 * 失败时调用 [onError]，成功时调用 [onImageSaved]。
 */
fun captureToFile(
    imageCapture: ImageCapture,
    outputFile: File,
    executor: Executor,
    onImageSaved: (File) -> Unit,
    onError: (ImageCaptureException) -> Unit,
) {
    val options = ImageCapture.OutputFileOptions
        .Builder(outputFile)
        .build()
    imageCapture.takePicture(
        options,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                onImageSaved(outputFile)
            }

            override fun onError(exception: ImageCaptureException) {
                onError(exception)
            }
        },
    )
}
