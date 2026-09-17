package com.banktool.loanphoto.camera.engine

import android.content.Context
import android.view.OrientationEventListener
import android.view.Surface
import android.widget.FrameLayout
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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

    /**
     * 设备真实方向（0/90/180/270），由 [OrientationEventListener] 提供并量化。
     *
     * 应用锁定竖屏（AndroidManifest: screenOrientation="portrait"），[android.view.Display.getRotation]
     * 恒为 ROTATION_0，无法感知用户横持手机；OrientationEventListener 直接读取传感器方向，
     * 是横版照片水印方向修复的关键。
     */
    @Volatile
    private var deviceOrientationDegrees: Int = 0

    private val orientationEventListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(degrees: Int) {
            if (degrees == ORIENTATION_UNKNOWN) return
            val quantized = quantizeDegrees(degrees)
            deviceOrientationDegrees = quantized
            _deviceOrientation.value = quantized
        }
    }

    /** 绑定后持有，供缩放/torch 控制。 */
    private var camera: Camera? = null

    /**
     * bind() 中注册的 ON_RESUME 观察者引用，重入 bind 时先移除旧的，避免重复注册造成泄漏。
     */
    private var resumeObserver: LifecycleEventObserver? = null

    private val _zoomInfo = MutableStateFlow(ZoomInfo())
    override val zoomInfo: StateFlow<ZoomInfo> = _zoomInfo.asStateFlow()

    private val _cameraReady = MutableStateFlow(false)
    override val cameraReady: StateFlow<Boolean> = _cameraReady.asStateFlow()

    private val _deviceOrientation = MutableStateFlow(0)
    override val deviceOrientationFlow: StateFlow<Int> = _deviceOrientation.asStateFlow()

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
        // 启用方向监听，捕获用户横持手机的真实方向（应用锁竖屏，display.rotation 失效）
        if (orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable()
        }
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

                // minZoom < 1.0 视为设备具备超广角（CameraX 无法直接查询镜头焦距）
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

                if (flashMode == 2) {
                    cam.cameraControl.enableTorch(true)
                }

                _cameraReady.value = true

                // 注册 ON_RESUME 观察者：锁屏恢复后 CameraX 可能重置 zoomRatio，需重新同步并恢复广角
                // 重入 bind 时先移除旧观察者，避免重复注册
                resumeObserver?.let { lifecycleOwner.lifecycle.removeObserver(it) }
                val observer = object : LifecycleEventObserver {
                    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                        if (event == Lifecycle.Event.ON_RESUME) {
                            val cam = camera ?: return
                            val zoomState = cam.cameraInfo.zoomState.value ?: return
                            val currentRatio = zoomState.zoomRatio
                            val minRatio = zoomState.minZoomRatio
                            val isWideActive = _zoomInfo.value.isWideAngleActive
                            if (isWideActive && currentRatio >= 1.0f && minRatio < 1.0f) {
                                Timber.d("ON_RESUME 检测到广角被重置(zoomRatio=%s, minZoomRatio=%s)，恢复广角", currentRatio, minRatio)
                                cam.cameraControl.setZoomRatio(minRatio)
                                _zoomInfo.value = _zoomInfo.value.copy(zoomRatio = minRatio)
                            } else {
                                _zoomInfo.value = _zoomInfo.value.copy(
                                    zoomRatio = currentRatio,
                                    minZoomRatio = minRatio,
                                    maxZoomRatio = zoomState.maxZoomRatio,
                                    hasWideAngle = minRatio < 1.0f,
                                )
                            }
                        }
                    }
                }
                resumeObserver = observer
                lifecycleOwner.lifecycle.addObserver(observer)
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

        // bind 后设备可能已旋转，拍照前刷新旋转角度
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
        // 禁用方向监听，避免离开相机界面后仍占用传感器
        orientationEventListener.disable()
    }

    /**
     * 获取当前显示屏旋转角度（用于 ImageCapture targetRotation）。
     *
     * 应用锁定竖屏（[AndroidManifest.xml] screenOrientation="portrait"）使
     * [android.view.Display.getRotation] 恒为 ROTATION_0；此处改用 [OrientationEventListener]
     * 维护的 [deviceOrientationDegrees] 推导 Surface 旋转，从而正确感知横持。
     *
     * 方向约定（关键）：
     * - [OrientationEventListener] 回调的 degrees 为**顺时针 (CW)** 约定：
     *   0 = 自然方向（顶朝上）；90 = 顺时针旋转 90°（顶朝右，右横持）；
     *   180 = 倒置；270 = 顺时针旋转 270°（顶朝左，左横持）。
     * - [android.view.Display.getRotation] 与 Surface.ROTATION_* 为**逆时针 (CCW)** 约定：
     *   ROTATION_0 = 0°；ROTATION_90 = 逆时针 90°（顶朝左，左横持）；
     *   ROTATION_180 = 180°；ROTATION_270 = 逆时针 270°（顶朝右，右横持）。
     * - 两者方向相反，必须反向映射，不能 1:1 对应。
     *
     * 反向映射（OE CW → Surface CCW；EXIF 由 CameraX 公式 (sensorOrientation + rotationDegrees) % 360 推算，sensorOrientation=90）：
     * - OE 0   → ROTATION_0   → EXIF ROTATE_90（竖直，正常竖拍，旋转 90° 正立）
     * - OE 90  → ROTATION_270 → EXIF NORMAL（右横持，传感器已正立，无需旋转）
     * - OE 180 → ROTATION_180 → EXIF ROTATE_270（倒置，旋转 270° 正立）
     * - OE 270 → ROTATION_90  → EXIF ROTATE_180（左横持，传感器上下颠倒，旋转 180° 正立）
     *
     * 参考实现：Google Camera2Basic 官方示例
     * https://github.com/android/camera-samples/blob/main/Camera2Basic/.../CameraFragment.kt
     */
    private fun currentDisplayRotation(): Int = when (deviceOrientationDegrees) {
        90 -> Surface.ROTATION_270
        180 -> Surface.ROTATION_180
        270 -> Surface.ROTATION_90
        else -> Surface.ROTATION_0
    }

    /**
     * 将 OrientationEventListener 回调的 0-359 度数量化到最近的 0/90/180/270。
     * 未知方向（-1）由调用方提前过滤，此处不处理。
     */
    private fun quantizeDegrees(degrees: Int): Int = when (degrees) {
        in 0..44, in 315..359 -> 0
        in 45..134 -> 90
        in 135..224 -> 180
        in 225..314 -> 270
        else -> 0
    }
}
