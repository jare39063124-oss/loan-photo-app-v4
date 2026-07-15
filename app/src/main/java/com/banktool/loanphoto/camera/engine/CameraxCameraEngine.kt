package com.banktool.loanphoto.camera.engine

import android.content.Context
import android.view.Surface
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CameraX 后端实现：对原 CameraPreviewView + CameraViewModel 中 CameraX 逻辑的 1:1 忠实包装。
 *
 * 行为与 v4.1.0 完全一致：
 * - 预览：PreviewView(PERFORMANCE + FILL_CENTER) + Preview + ImageCapture 绑定到后置摄像头
 * - 拍照：ImageCapture MAXIMIZE_QUALITY + 4:3，按 flashMode 选择闪光策略
 * - 缩放：读取 zoomState 范围并 setZoomRatio；minZoom < 1.0 视为广角
 * - 闪光：flashMode==2 维持 torch 常亮，其余交由 ImageCapture 拍照瞬间处理
 */
@Singleton
class CameraxCameraEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : CameraEngine {

    /** ImageCapture use case（与原 CameraViewModel 配置一致）。 */
    private val imageCapture: ImageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
        .setTargetRotation(currentDisplayRotation())
        .build()

    /** 绑定后持有，供缩放/torch 控制。 */
    private var camera: Camera? = null

    private val _zoomInfo = MutableStateFlow(ZoomInfo())
    override val zoomInfo: StateFlow<ZoomInfo> = _zoomInfo.asStateFlow()

    private val _cameraReady = MutableStateFlow(false)
    override val cameraReady: StateFlow<Boolean> = _cameraReady.asStateFlow()

    /**
     * 当前闪光灯模式：0=OFF, 1=AUTO, 2=TORCH 常亮, 3=ON。
     * 绑定后若为 2 则自动恢复 torch 常亮（与原 onCameraReady 行为一致）。
     */
    private var flashMode: Int = 0

    /**
     * 绑定预览到生命周期。由调用方传入 [FrameLayout] 容器，本实现自建 [PreviewView] 并 addView。
     *
     * 复刻原 bindCamera + onCameraReady + updateZoomInfo：
     * - unbindAll 后重新绑定 Preview + ImageCapture
     * - 读取 zoomState 初始化缩放范围与广角判定
     * - flashMode==2 时打开 torch
     */
    override fun bind(container: FrameLayout, lifecycleOwner: LifecycleOwner) {
        val previewView = PreviewView(container.context)
        container.removeAllViews()
        container.addView(previewView)
        val cameraExecutor = ContextCompat.getMainExecutor(context)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                // 重新绑定以替换之前已绑定的旧实例（避免重复绑定报错）
                cameraProvider.unbindAll()
                val cam = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                )
                camera = cam

                // 读取 zoomState 初始化缩放范围；minZoom < 1.0 视为广角
                val zoomState = cam.cameraInfo.zoomState.value
                if (zoomState != null) {
                    val minZoom = zoomState.minZoomRatio
                    val maxZoom = zoomState.maxZoomRatio
                    val zoom = zoomState.zoomRatio
                    _zoomInfo.value = ZoomInfo(
                        minZoomRatio = minZoom,
                        maxZoomRatio = maxZoom,
                        zoomRatio = zoom,
                        hasWideAngle = minZoom < 1.0f,
                        isWideAngleActive = zoom < 1.0f,
                    )
                }

                // 若当前处于常亮模式（flashMode==2），相机绑定后立即打开补光灯
                if (flashMode == 2) {
                    cam.cameraControl.enableTorch(true)
                }

                _cameraReady.value = true
            } catch (e: Exception) {
                Timber.e(e, "CameraxCameraEngine 绑定相机失败")
            }
        }, cameraExecutor)
    }

    /**
     * 触发拍照落盘。拍照前根据 flashMode 设置 ImageCapture 闪光模式：
     * 0→OFF, 1→AUTO, 2→TORCH 常亮（torch 已开，拍照用 ON 确保补光），3→ON。
     */
    override fun captureToFile(
        outputFile: File,
        executor: Executor,
        onSaved: (File) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        imageCapture.flashMode = when (flashMode) {
            1 -> ImageCapture.FLASH_MODE_AUTO
            2 -> ImageCapture.FLASH_MODE_ON // 常亮：torch 已开，拍照用 ON
            3 -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }

        // 更新 targetRotation 为当前显示屏旋转，处理 bind 后用户旋转设备的情况
        imageCapture.targetRotation = currentDisplayRotation()

        val options = ImageCapture.OutputFileOptions
            .Builder(outputFile)
            .build()
        imageCapture.takePicture(
            options,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onSaved(outputFile)
                }

                override fun onError(exception: ImageCaptureException) {
                    onError(exception)
                }
            },
        )
    }

    /**
     * 设置缩放倍率。ratio 会被 clamp 到 [minZoomRatio, maxZoomRatio] 范围内。
     * 同步更新 _zoomInfo.zoomRatio 与 isWideAngleActive（与原 setZoom 行为一致）。
     */
    override fun setZoom(ratio: Float) {
        val cam = camera ?: return
        val zoomState = cam.cameraInfo.zoomState.value
        val minZoom = zoomState?.minZoomRatio ?: 1f
        val maxZoom = zoomState?.maxZoomRatio ?: 1f
        val clamped = ratio.coerceIn(minZoom, maxZoom)
        cam.cameraControl.setZoomRatio(clamped)
        _zoomInfo.value = _zoomInfo.value.copy(
            zoomRatio = clamped,
            isWideAngleActive = clamped < 1.0f,
        )
    }

    /**
     * 切换广角 / 1x：
     * - 当前 zoomRatio < 1.0（广角态）→ 切回 1.0x
     * - 当前 zoomRatio >= 1.0 → 切到 minZoomRatio（如 0.5x 广角）
     */
    override fun toggleWideAngle() {
        val current = _zoomInfo.value.zoomRatio
        val minZoom = _zoomInfo.value.minZoomRatio
        val target = if (current < 1.0f) 1.0f else minZoom
        setZoom(target)
    }

    /**
     * 设置闪光灯模式（0=OFF, 1=AUTO, 2=TORCH 常亮, 3=ON）。
     * - 模式 2（TORCH 常亮）立即打开补光灯
     * - 其它模式关闭补光灯，交由 ImageCapture 在拍照瞬间处理
     */
    override fun setFlashMode(mode: Int) {
        flashMode = mode
        if (mode == 2) {
            enableTorch(true)
        } else {
            enableTorch(false)
        }
    }

    /** 打开/关闭补光灯。 */
    override fun enableTorch(enable: Boolean) {
        camera?.cameraControl?.enableTorch(enable)
    }

    /**
     * 释放资源。CameraX 后端无需主动 unbind——ProcessCameraProvider 在 Lifecycle destroy
     * 时会自动 unbind。此处保留为 no-op 以满足接口契约。
     */
    override fun release() {
        // CameraX 由生命周期自动管理，无需手动释放
    }

    /** 获取当前显示屏旋转角度（用于 ImageCapture targetRotation）。 */
    private fun currentDisplayRotation(): Int =
        try {
            val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
            display.rotation
        } catch (e: Exception) {
            Timber.w(e, "获取显示屏旋转失败，回退 ROTATION_0")
            Surface.ROTATION_0
        }
}
