package com.banktool.loanphoto.camera.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * 基于 Camera2 API 的相机引擎实现（华为/HONOR 设备运行时选用）。
 *
 * 由 [com.banktool.loanphoto.di.CameraEngineModule] 通过 [com.banktool.loanphoto.util.DeviceDetector.isHuaweiDevice]
 * 在运行时判定：HUAWEI/HONOR 设备注入本引擎，其余设备注入 [CameraxCameraEngine]。
 *
 * 背景：华为 Mate70 的后置主摄是一个 logical multi-camera，其超广角镜头作为物理子相机
 * 隐藏在 logical camera 之下，CameraX 无法选择该物理子相机。本引擎直接使用 Camera2 API，
 * 通过 [android.hardware.camera2.CameraCharacteristics.getPhysicalCameraIds]（API 28+）
 * 选择焦距最短的物理子相机，或回退到独立的 logical camera id，以实现真正的超广角拍摄。
 *
 * 广角检测采用双策略以最大化 Mate70 兼容性（优先独立逻辑 ID，回退物理子相机）：
 * 1. 独立逻辑 ID 策略：扫描所有 logical camera id，若存在比主摄焦距更短的独立 logical camera，
 *    则 [toggleWideAngle] 时直接 openCamera 该 id（不走 setPhysicalCameraId）。该策略在 Mate70
 *    上更稳定，命中后即跳过策略 2。
 * 2. 物理子相机策略：当 logical camera 暴露 >=2 个 physicalCameraId 时，选取焦距最短者，
 *    通过 [OutputConfiguration.setPhysicalCameraId] 让 logical camera 从该物理子相机取流。
 *    仅当策略 1 未命中时回退使用（部分机型 setPhysicalCameraId 会话配置失败）。
 *
 * 线程模型：所有 Camera2 回调运行在后台 [bgHandler]；MutableStateFlow 线程安全，可在后台线程更新。
 */
@Singleton
class Camera2CameraEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : CameraEngine {

    private val _zoomInfo = MutableStateFlow(ZoomInfo())
    private val _cameraReady = MutableStateFlow(false)

    override val zoomInfo = _zoomInfo.asStateFlow()
    override val cameraReady = _cameraReady.asStateFlow()

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var textureView: TextureView? = null
    private var lifecycleOwner: LifecycleOwner? = null

    /** 后台 HandlerThread，提供所有 Camera2 回调所在线程。release() 会 quit，bind 时按需重启以支持重入。 */
    private var handlerThread: HandlerThread = HandlerThread("camera2-bg").apply { start() }
    private var bgHandler: Handler = Handler(handlerThread.looper)

    /** 主摄 logical camera id。 */
    private var logicalCameraId: String = ""

    /**
     * 广角相机 id。
     * - 物理子相机策略下：physicalCameraId（API 28+）。
     * - 独立逻辑 ID 策略下：另一个 logical camera id。
     * - null 表示不支持广角。
     */
    private var widePhysicalId: String? = null

    /** true 表示 [widePhysicalId] 是独立 logical camera id（需 openCamera 切换），false 表示是物理子相机 id。 */
    private var wideIsSeparateLogicalId: Boolean = false

    /** 当前是否正在从广角传感器取流。 */
    private var isWideActive: Boolean = false

    /** 闪光灯模式：0=OFF, 1=AUTO, 2=TORCH 常亮, 3=ON。 */
    private var flashMode: Int = 0

    /** 预览补光灯常亮开关（由 flashMode==2 或 enableTorch 维护）。 */
    @Volatile
    private var torchEnabled: Boolean = false

    private var currentZoom: Float = 1f
    private var minZoom: Float = 1f
    private var maxZoom: Float = 1f

    /** 当前打开的相机对应的 characteristics（用于 crop region 等计算）。 */
    private var currentCharacteristics: CameraCharacteristics? = null
    private var activeArraySize: Rect? = null
    private var jpegSize: Size? = null
    private var previewSize: Size? = null
    private var torchSupported: Boolean = false

    /** 待处理的拍照回调三元组：输出文件 / 成功回调 / 失败回调。 */
    @Volatile
    private var pendingCapture: Triple<File, (File) -> Unit, (Throwable) -> Unit>? = null
    @Volatile
    private var captureExecutor: Executor? = null

    @Volatile
    private var sessionRebuilding: Boolean = false

    /** 广角切换开启中标记：用于 onConfigureFailed 时回滚到普通会话，避免预览永久卡死。 */
    @Volatile
    private var pendingWideToggle: Boolean = false

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
            deviceOrientationDegrees = quantizeDegrees(degrees)
        }
    }

    /** 生命周期观察者：DESTROYED 时释放相机资源。 */
    private val lifecycleObserver = object : LifecycleEventObserver {
        override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
            if (event == Lifecycle.Event.ON_DESTROY) {
                Timber.d("Lifecycle ON_DESTROY，释放 Camera2 引擎")
                release()
            }
        }
    }

    // ===================== CameraEngine 接口实现 =====================

    @SuppressLint("MissingPermission")
    override fun bind(container: FrameLayout, lifecycleOwner: LifecycleOwner) {
        val texture = TextureView(container.context)
        container.removeAllViews()
        container.addView(texture)
        this.textureView = texture
        this.lifecycleOwner = lifecycleOwner
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        // 启用方向监听，捕获用户横持手机的真实方向（应用锁竖屏，display.rotation 失效）
        if (orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable()
        }
        ensureBgThread()

        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            discoverBackCamera(cm)
            if (logicalCameraId.isEmpty()) {
                Timber.e("未找到后置摄像头")
                _cameraReady.value = false
                return
            }

            texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    createSession()
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
            // SurfaceTexture 已可用则立即建会话
            if (texture.isAvailable) {
                createSession()
            }

            openCamera(logicalCameraId) {
                createSession()
            }
        } catch (e: Exception) {
            Timber.e(e, "Camera2CameraEngine.bind 失败")
            _cameraReady.value = false
        }
    }

    override fun captureToFile(
        outputFile: File,
        executor: Executor,
        onSaved: (File) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val session = captureSession
        val reader = imageReader
        if (session == null || reader == null) {
            executor.execute { onError(IllegalStateException("相机会话未就绪")) }
            return
        }
        try {
            pendingCapture = Triple(outputFile, onSaved, onError)
            captureExecutor = executor

            val request = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            request.addTarget(reader.surface)
            previewSurface?.let { request.addTarget(it) }
            request.set(CaptureRequest.JPEG_QUALITY, 100)
            request.set(CaptureRequest.JPEG_ORIENTATION, calculateJpegOrientation())
            applyZoomToRequest(request)
            // 闪光灯：0→OFF, 1→AUTO_FLASH, 2→SINGLE(torch 已开), 3→SINGLE
            when (flashMode) {
                0 -> {
                    request.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                    request.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                }
                1 -> {
                    request.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH)
                }
                2, 3 -> {
                    request.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                    request.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE)
                }
            }
            request.set(CaptureRequest.CONTROL_AE_LOCK, false)
            session.capture(request.build(), null, bgHandler)
        } catch (e: Exception) {
            Timber.e(e, "captureToFile 触发拍照失败")
            pendingCapture = null
            captureExecutor = null
            executor.execute { onError(e) }
        }
    }

    override fun setZoom(ratio: Float) {
        val clamped = ratio.coerceIn(minZoom, maxZoom)
        currentZoom = clamped
        _zoomInfo.value = _zoomInfo.value.copy(zoomRatio = clamped)
        try {
            startRepeating()
        } catch (e: Exception) {
            Timber.w(e, "setZoom 重建预览请求失败")
        }
    }

    override fun toggleWideAngle() {
        val wideId = widePhysicalId ?: return
        if (sessionRebuilding) {
            Timber.d("toggleWideAngle 跳过：会话正在重建")
            return
        }
        sessionRebuilding = true
        try {
            isWideActive = !isWideActive
            // 开启广角时标记 pendingWideToggle，供 onConfigureFailed 回滚；关闭时保持 false（成功/失败时已清）
            if (isWideActive) pendingWideToggle = true
            _zoomInfo.value = _zoomInfo.value.copy(isWideAngleActive = isWideActive)

            if (wideIsSeparateLogicalId) {
                // 独立 logical camera：关闭当前设备，openCamera 目标 id
                try { cameraDevice?.close() } catch (e: Exception) { Timber.w(e, "关闭当前相机设备失败") }
                cameraDevice = null
                captureSession?.let { runCatching { it.close() } }
                captureSession = null
                _cameraReady.value = false
                val targetId = if (isWideActive) wideId else logicalCameraId
                openCamera(targetId) { createSession() }
            } else {
                // 物理子相机：重建会话，createSession 会根据 isWideActive 设置 setPhysicalCameraId
                try { captureSession?.close() } catch (e: Exception) { Timber.w(e, "关闭当前会话失败") }
                captureSession = null
                _cameraReady.value = false
                createSession()
            }
        } catch (e: Exception) {
            Timber.e(e, "toggleWideAngle 失败")
            // 回滚状态
            isWideActive = !isWideActive
            pendingWideToggle = false
            _zoomInfo.value = _zoomInfo.value.copy(isWideAngleActive = isWideActive)
        } finally {
            sessionRebuilding = false
        }
    }

    override fun setFlashMode(mode: Int) {
        flashMode = mode
        torchEnabled = mode == 2
        try {
            startRepeating()
        } catch (e: Exception) {
            Timber.w(e, "setFlashMode 重建预览请求失败")
        }
    }

    override fun enableTorch(enable: Boolean) {
        torchEnabled = enable
        try {
            startRepeating()
        } catch (e: Exception) {
            Timber.w(e, "enableTorch 重建预览请求失败")
        }
    }

    override fun release() {
        // 禁用方向监听，避免离开相机界面后仍占用传感器
        orientationEventListener.disable()
        try {
            captureSession?.close()
        } catch (e: Exception) {
            Timber.w(e, "release 关闭 captureSession 失败")
        }
        try {
            imageReader?.close()
        } catch (e: Exception) {
            Timber.w(e, "release 关闭 imageReader 失败")
        }
        try {
            cameraDevice?.close()
        } catch (e: Exception) {
            Timber.w(e, "release 关闭 cameraDevice 失败")
        }
        captureSession = null
        imageReader = null
        cameraDevice = null
        previewSurface = null
        pendingCapture = null
        captureExecutor = null
        _cameraReady.value = false
        lifecycleOwner?.lifecycle?.removeObserver(lifecycleObserver)
        lifecycleOwner = null
        try {
            handlerThread.quitSafely()
        } catch (e: Exception) {
            Timber.w(e, "release 退出 HandlerThread 失败")
        }
    }

    // ===================== 内部实现 =====================

    /**
     * 确保后台 HandlerThread 存活：release() 后再次 bind 时重启线程并重建 [bgHandler]。
     * 引擎为 @Singleton，相机界面可重入，故需此兜底。
     */
    private fun ensureBgThread() {
        if (handlerThread.isAlive) return
        handlerThread = HandlerThread("camera2-bg").apply { start() }
        bgHandler = Handler(handlerThread.looper)
    }

    /**
     * 发现后置 logical camera，并执行广角双策略检测。
     */
    private fun discoverBackCamera(cm: CameraManager) {
        logicalCameraId = ""
        widePhysicalId = null
        wideIsSeparateLogicalId = false

        for (id in cm.cameraIdList) {
            val ch = cm.getCameraCharacteristics(id)
            val facing = ch.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                logicalCameraId = id
                loadCharacteristicsFor(cm, id, ch)
                break
            }
        }

        // 主摄焦距（用于独立逻辑 ID 回退比对）
        val mainMinFocal = currentCharacteristics
            ?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.minOrNull() ?: Float.MAX_VALUE

        // 策略 1：独立 logical camera id（任意 API，焦距比主摄更短）—— 优先策略，Mate70 上更稳定
        // 先尝试独立逻辑 id，命中即跳过物理子相机策略，避免 setPhysicalCameraId 在 Mate70 上 onConfigureFailed
        for (id in cm.cameraIdList) {
            if (id == logicalCameraId) continue
            try {
                val ch = cm.getCameraCharacteristics(id)
                val facing = ch.get(CameraCharacteristics.LENS_FACING)
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue
                val focals = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val minF = focals?.minOrNull() ?: continue
                if (minF < mainMinFocal) {
                    widePhysicalId = id
                    wideIsSeparateLogicalId = true
                    Timber.i("检测到独立逻辑广角相机: %s (focal=%.2f < main=%.2f)", id, minF, mainMinFocal)
                    break
                }
            } catch (e: Exception) {
                Timber.w(e, "扫描独立逻辑相机失败: %s", id)
            }
        }

        // 策略 2：物理子相机（API 28+）—— 仅当独立逻辑策略未命中时回退
        if (widePhysicalId == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val physicalIds = currentCharacteristics?.getPhysicalCameraIds()
            if (physicalIds != null && physicalIds.size >= 2) {
                var bestPhysicalId: String? = null
                var bestFocal = Float.MAX_VALUE
                for (pid in physicalIds) {
                    try {
                        val pch = cm.getCameraCharacteristics(pid)
                        val focals = pch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        val minF = focals?.minOrNull()
                        if (minF != null && minF < bestFocal) {
                            bestFocal = minF
                            bestPhysicalId = pid
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "读取物理子相机焦距失败: %s", pid)
                    }
                }
                if (bestPhysicalId == null) {
                    // 焦距读取失败：取第一个非默认物理子相机作为兜底
                    bestPhysicalId = physicalIds.firstOrNull()
                }
                if (bestPhysicalId != null) {
                    widePhysicalId = bestPhysicalId
                    wideIsSeparateLogicalId = false
                    Timber.i("检测到物理子相机广角: %s (focal=%.2f)", bestPhysicalId, bestFocal)
                }
            }
        }

        _zoomInfo.value = ZoomInfo(
            minZoomRatio = minZoom,
            maxZoomRatio = maxZoom,
            zoomRatio = 1f,
            hasWideAngle = widePhysicalId != null,
            isWideAngleActive = false,
        )
        Timber.i(
            "discoverBackCamera 逻辑=%s 广角=%s separateLogical=%b zoom=[%.2f,%.2f] torch=%b",
            logicalCameraId, widePhysicalId, wideIsSeparateLogicalId, minZoom, maxZoom, torchSupported,
        )
    }

    /**
     * 加载指定 cameraId 的 characteristics：activeArraySize / zoom 范围 / JPEG 与预览尺寸 / torch 支持。
     */
    private fun loadCharacteristicsFor(cm: CameraManager, cameraId: String, ch: CameraCharacteristics) {
        currentCharacteristics = ch
        activeArraySize = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map != null) {
            jpegSize = pickJpegSize(map.getOutputSizes(ImageFormat.JPEG))
            previewSize = pickPreviewSize(map.getOutputSizes(SurfaceTexture::class.java))
        }
        if (jpegSize == null) jpegSize = Size(1920, 1440)
        if (previewSize == null) previewSize = jpegSize

        // 缩放范围：API 30+ 用 CONTROL_ZOOM_RATIO_RANGE，否则退化为 [1f, 1f]
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val range = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (range != null) {
                minZoom = range.lower
                maxZoom = range.upper
            } else {
                minZoom = 1f
                maxZoom = 1f
            }
        } else {
            minZoom = 1f
            maxZoom = 1f
        }

        torchSupported = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
    }

    /** 选取最大 4:3 JPEG 输出尺寸（capture 用）。 */
    private fun pickJpegSize(sizes: Array<Size>?): Size? {
        if (sizes.isNullOrEmpty()) return null
        return sizes
            .filter { abs(it.width.toFloat() / it.height.toFloat() - 4f / 3f) < 0.02f }
            .maxByOrNull { it.width * it.height }
            ?: sizes.maxByOrNull { it.width * it.height }
    }

    /** 选取 4:3 预览尺寸（宽度 <= 1920），用于 SurfaceTexture 默认缓冲区。 */
    private fun pickPreviewSize(sizes: Array<Size>?): Size? {
        if (sizes.isNullOrEmpty()) return null
        return sizes
            .filter { it.width <= 1920 && abs(it.width.toFloat() / it.height.toFloat() - 4f / 3f) < 0.02f }
            .maxByOrNull { it.width * it.height }
            ?: sizes.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height }
            ?: sizes.maxByOrNull { it.width * it.height }
    }

    /**
     * 打开指定 cameraId，成功后在 [onOpened] 回调中（运行在 bgHandler）执行后续逻辑。
     * 同时刷新该 id 的 characteristics。
     */
    @SuppressLint("MissingPermission")
    private fun openCamera(cameraId: String, onOpened: () -> Unit) {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            // 独立逻辑广角切换时刷新 characteristics（物理子相机策略不调用本方法，保持主摄 characteristics）
            if (cameraId != logicalCameraId) {
                val ch = cm.getCameraCharacteristics(cameraId)
                loadCharacteristicsFor(cm, cameraId, ch)
            }
            val stateCallback = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    onOpened()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Timber.e("CameraDevice onDisconnected: %s", cameraId)
                    // 身份守卫：切到广角 logical 相机时，旧设备的迟到 onDisconnected 不能覆盖新设备
                    if (cameraDevice === camera) {
                        cameraDevice = null
                        _cameraReady.value = false
                    }
                    runCatching { camera.close() }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Timber.e("CameraDevice onError id=%s error=%d", cameraId, error)
                    // 身份守卫：切到广角 logical 相机时，旧设备的迟到 onError 不能覆盖新设备
                    if (cameraDevice === camera) {
                        cameraDevice = null
                        _cameraReady.value = false
                    }
                    runCatching { camera.close() }
                }
            }
            cm.openCamera(cameraId, stateCallback, bgHandler)
        } catch (e: Exception) {
            Timber.e(e, "openCamera 失败: %s", cameraId)
            _cameraReady.value = false
        }
    }

    /**
     * 创建/重建 CaptureSession。
     *
     * - 物理 sub-camera 策略下，当 [isWideActive] 为 true 且 [wideIsSeparateLogicalId] 为 false 时，
     *   对所有 OutputConfiguration 设置 [OutputConfiguration.setPhysicalCameraId]，从广角物理子相机取流。
     * - API 28+ 使用 [SessionConfiguration]；API 26-27 退化为 [createCaptureSession]（不支持物理子相机）。
     */
    @SuppressLint("MissingPermission")
    private fun createSession() {
        val device = cameraDevice ?: return
        val texture = textureView ?: return
        val st = texture.surfaceTexture ?: return

        val usePhysicalSubCamera = isWideActive &&
            !wideIsSeparateLogicalId &&
            widePhysicalId != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

        // logical camera 的尺寸候选（非物理子相机分支沿用）
        val logicalJpegSize = jpegSize ?: Size(1920, 1440)
        val logicalPreviewSize = previewSize ?: jpegSize ?: Size(1920, 1440)
        var pvSize = logicalPreviewSize
        var jSize = logicalJpegSize

        // 物理 sub-camera 取流时，logical camera 的 jpegSize/previewSize 未必被物理传感器支持，
        // 需以物理子相机的 SCALER_STREAM_CONFIGURATION_MAP 为准，取两者公共支持的尺寸，避免 onConfigureFailed。
        if (usePhysicalSubCamera) {
            val wideId = widePhysicalId
            if (wideId != null) {
                try {
                    val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val pch = cm.getCameraCharacteristics(wideId)
                    val pMap = pch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val lMap = currentCharacteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    if (pMap != null && lMap != null) {
                        val physJpeg = pMap.getOutputSizes(ImageFormat.JPEG)?.toSet() ?: emptySet()
                        val physPreview = pMap.getOutputSizes(SurfaceTexture::class.java)?.toSet() ?: emptySet()
                        val logJpeg = lMap.getOutputSizes(ImageFormat.JPEG)?.toSet() ?: emptySet()
                        val logPreview = lMap.getOutputSizes(SurfaceTexture::class.java)?.toSet() ?: emptySet()

                        // JPEG：公共 4:3 最大 → 保守尺寸（需物理支持）→ 物理 4:3 最大 → 沿用 logical
                        val commonJpeg = physJpeg.intersect(logJpeg)
                        jSize = pickJpegSize(commonJpeg.toTypedArray())
                            ?: listOf(Size(1280, 960), Size(1920, 1080)).firstOrNull { physJpeg.contains(it) }
                            ?: pickJpegSize(physJpeg.toTypedArray())
                            ?: logicalJpegSize

                        // Preview：公共 4:3 最大 → 保守尺寸（需物理支持）→ 物理 4:3 最大 → 沿用 logical
                        val commonPreview = physPreview.intersect(logPreview)
                        pvSize = pickPreviewSize(commonPreview.toTypedArray())
                            ?: listOf(Size(1920, 1080), Size(1280, 960)).firstOrNull { physPreview.contains(it) }
                            ?: pickPreviewSize(physPreview.toTypedArray())
                            ?: logicalPreviewSize

                        Timber.i(
                            "物理子相机尺寸: jpeg=%s preview=%s (logical jpeg=%s preview=%s)",
                            jSize, pvSize, logicalJpegSize, logicalPreviewSize,
                        )
                    }
                } catch (e: Exception) {
                    Timber.w(e, "读取物理子相机尺寸失败，沿用 logical 尺寸: jpeg=%s preview=%s", jSize, pvSize)
                }
            }
        }

        try {
            st.setDefaultBufferSize(pvSize.width, pvSize.height)
            val preview = Surface(st)
            previewSurface = preview

            // 重建 ImageReader（尺寸可能因相机切换而变化）
            imageReader?.let { runCatching { it.close() } }
            imageReader = ImageReader.newInstance(jSize.width, jSize.height, ImageFormat.JPEG, 2)
            imageReader?.setOnImageAvailableListener({ reader -> handleImageAvailable(reader) }, bgHandler)

            val sessionStateCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    // 广角会话配置成功：清除待回滚标记
                    pendingWideToggle = false
                    captureSession = session
                    try {
                        startRepeating()
                        _cameraReady.value = true
                        // 恢复闪光/缩放状态
                        applyCurrentState()
                    } catch (e: Exception) {
                        Timber.e(e, "onConfigured startRepeating 失败")
                        _cameraReady.value = false
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Timber.e("CaptureSession onConfigureFailed")
                    captureSession = null
                    _cameraReady.value = false
                    if (pendingWideToggle) {
                        // 广角会话配置失败：回滚到主摄普通会话，避免预览永久卡死
                        pendingWideToggle = false
                        isWideActive = false
                        _zoomInfo.value = _zoomInfo.value.copy(isWideAngleActive = false)
                        Timber.w("广角会话 onConfigureFailed，回滚到普通会话")
                        // 若当前是物理子相机策略（设备未变），直接重建普通会话
                        // 若是独立 logical 策略，wideId 打开失败 → 重新打开主摄 logicalCameraId
                        // 防递归：pendingWideToggle 已置 false，若本次普通会话再失败不会二次回滚
                        if (wideIsSeparateLogicalId) {
                            runCatching { cameraDevice?.close() }
                            cameraDevice = null
                            // 重载主摄 characteristics：切广角时已被广角 logical 的尺寸/activeArray/zoom 覆盖，
                            // 若不重载，回滚后的普通会话会沿用广角尺寸，可能再次 onConfigureFailed。
                            runCatching {
                                val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                                val mainCh = cm.getCameraCharacteristics(logicalCameraId)
                                loadCharacteristicsFor(cm, logicalCameraId, mainCh)
                            }
                            openCamera(logicalCameraId) { createSession() }
                        } else {
                            createSession()
                        }
                    }
                }
            }

            val readerSurface = imageReader!!.surface

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val previewConfig = OutputConfiguration(preview)
                val readerConfig = OutputConfiguration(readerSurface)
                if (usePhysicalSubCamera) {
                    previewConfig.setPhysicalCameraId(widePhysicalId)
                    readerConfig.setPhysicalCameraId(widePhysicalId)
                }
                val configs = listOf(previewConfig, readerConfig)
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    configs,
                    { command -> bgHandler.post(command) },
                    sessionStateCallback,
                )
                device.createCaptureSession(sessionConfig)
            } else {
                // API 26-27：不支持物理子相机（广角检测在此 API 下也不会命中物理子相机路径）
                @Suppress("DEPRECATION")
                device.createCaptureSession(listOf(preview, readerSurface), sessionStateCallback, bgHandler)
            }
        } catch (e: Exception) {
            Timber.e(e, "createSession 失败")
            _cameraReady.value = false
        }
    }

    /** 启动/重建预览 repeating request。 */
    private fun startRepeating() {
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        val preview = previewSurface ?: return
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        request.addTarget(preview)
        applyZoomToRequest(request)
        // 预览补光：torch 开启则 FLASH_MODE_TORCH，否则 AE 自动 + 关闭闪光
        if (torchEnabled && torchSupported) {
            request.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            request.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
        } else {
            request.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            request.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        }
        session.setRepeatingRequest(request.build(), null, bgHandler)
    }

    /** 应用缩放到 CaptureRequest：API 30+ 用 CONTROL_ZOOM_RATIO，否则用 SCALER_CROP_REGION。 */
    private fun applyZoomToRequest(builder: CaptureRequest.Builder) {
        val zoom = currentZoom.coerceIn(minZoom, maxZoom)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        } else {
            val array = activeArraySize ?: return
            if (zoom <= 1f) {
                builder.set(CaptureRequest.SCALER_CROP_REGION, array)
                return
            }
            val centerX = array.width() / 2f
            val centerY = array.height() / 2f
            val cropW = (array.width() / zoom).toInt().coerceAtLeast(1)
            val cropH = (array.height() / zoom).toInt().coerceAtLeast(1)
            val left = (centerX - cropW / 2f).toInt().coerceIn(array.left, array.right - cropW)
            val top = (centerY - cropH / 2f).toInt().coerceIn(array.top, array.bottom - cropH)
            builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + cropW, top + cropH))
        }
    }

    /**
     * 计算 JPEG 方向：根据传感器方向与设备真实方向推算正确的 JPEG_ORIENTATION。
     *
     * Camera2 官方公式：jpegOrientation = (sensorOrientation + deviceOrientationCCW) % 360
     * 其中 deviceOrientationCCW 为设备从自然方向**逆时针**旋转的角度（与 [android.view.Display.getRotation] 同约定）。
     *
     * 但 [deviceOrientationDegrees] 来自 [OrientationEventListener]，为**顺时针 (CW)** 约定：
     * - OE 0 = 自然方向（顶朝上）
     * - OE 90 = 顺时针旋转 90°（顶朝右，右横持）
     * - OE 180 = 倒置
     * - OE 270 = 顺时针旋转 270°（顶朝左，左横持）
     *
     * CW 与 CCW 方向相反，需先转换：CCW = (360 - CW) % 360。
     * 代入官方公式：(sensorOrientation + (360 - deviceOrientationDegrees)) % 360
     * 等价于：(sensorOrientation - deviceOrientationDegrees + 360) % 360
     *
     * 应用锁定竖屏使 Display.getRotation 恒为 0，故改用 [deviceOrientationDegrees]。
     *
     * 各方向结果（sensorOrientation=90）：
     * - OE 0°（竖直）→ JPEG=(90-0+360)%360=90：像素旋转 90°，输出竖向正立
     * - OE 90°（右横持，顶朝右）→ JPEG=(90-90+360)%360=0：不旋转，输出横向正立（传感器已正立）
     * - OE 180°（倒置）→ JPEG=(90-180+360)%360=270：像素旋转 270°，输出竖向倒置矫正
     * - OE 270°（左横持，顶朝左）→ JPEG=(90-270+360)%360=180：像素旋转 180°，输出横向正立（传感器上下颠倒需翻转）
     *
     * 参考实现：Google Camera2Basic 官方示例
     * https://github.com/android/camera-samples/blob/main/Camera2Basic/.../CameraFragment.kt
     */
    private fun calculateJpegOrientation(): Int {
        val sensorOrientation = currentCharacteristics
            ?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        // deviceOrientationDegrees 来自 OE，为 CW 约定；Camera2 JPEG 公式需要 CCW。
        // 转换：CCW = (360 - CW) % 360；代入公式 (sensor + CCW) % 360 等价于 (sensor - CW + 360) % 360。
        return (sensorOrientation - deviceOrientationDegrees + 360) % 360
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

    /** 会话就绪后恢复当前 zoom / flash 状态到 UiState。 */
    private fun applyCurrentState() {
        _zoomInfo.value = _zoomInfo.value.copy(
            zoomRatio = currentZoom,
            minZoomRatio = minZoom,
            maxZoomRatio = maxZoom,
            isWideAngleActive = isWideActive,
            hasWideAngle = widePhysicalId != null,
        )
    }

    /**
     * ImageReader 回调：把 JPEG 帧写入待处理输出文件，并触发成功/失败回调。
     */
    private fun handleImageAvailable(reader: ImageReader) {
        val pending = pendingCapture ?: return
        val executor = captureExecutor
        var image: Image? = null
        try {
            image = reader.acquireLatestImage() ?: return
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val (file, onSaved, onError) = pending
            // 清除待处理状态，避免重复回调
            pendingCapture = null
            captureExecutor = null
            try {
                file.outputStream().use { it.write(bytes) }
                if (executor != null) {
                    executor.execute { onSaved(file) }
                } else {
                    onSaved(file)
                }
            } catch (e: Exception) {
                Timber.e(e, "写入拍照文件失败: %s", file.absolutePath)
                if (executor != null) executor.execute { onError(e) } else onError(e)
            }
        } catch (e: Exception) {
            Timber.e(e, "handleImageAvailable 处理图像失败")
            val (_, _, onError) = pending
            pendingCapture = null
            captureExecutor = null
            if (executor != null) executor.execute { onError(e) } else onError(e)
        } finally {
            image?.close()
        }
    }
}
