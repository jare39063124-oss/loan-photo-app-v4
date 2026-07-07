package com.banktool.loanphoto.ui.camera

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.banktool.loanphoto.data.camera.LocationResult
import com.banktool.loanphoto.data.camera.LocationService
import com.banktool.loanphoto.data.camera.ThumbnailGenerator
import com.banktool.loanphoto.data.camera.WatermarkConfig
import com.banktool.loanphoto.data.camera.WatermarkFontSize
import com.banktool.loanphoto.data.camera.WatermarkGenerator
import com.banktool.loanphoto.data.camera.WatermarkPosition
import com.banktool.loanphoto.data.naming.NamingConfigRepository
import com.banktool.loanphoto.data.naming.NamingRuleGenerator
import com.banktool.loanphoto.domain.entity.CameraSession
import com.banktool.loanphoto.domain.entity.CustomerRow
import com.banktool.loanphoto.domain.entity.PhotoType
import com.banktool.loanphoto.domain.repository.CameraSessionRepository
import com.banktool.loanphoto.domain.repository.ProgressRepository
import com.banktool.loanphoto.util.ProgressKeyUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * 相机界面 UI 状态。
 */
data class CameraUiState(
    val currentPhotoType: PhotoType = PhotoType.DISTANT,
    val primaryRow: CustomerRow? = null,
    val multiSelectRows: List<CustomerRow> = emptyList(),
    val excelUri: String = "",
    val excelUriMd5: String = "",
    val photosThisSession: Int = 0,
    val totalPhotos: Int = 0,
    val isCapturing: Boolean = false,
    val lastSavedPath: String? = null,
    val capturedThumbnails: List<String> = emptyList(),
    val showGallery: Boolean = false,
    val toastMessage: String? = null,
    val cameraReady: Boolean = false,
    val locationFailed: Boolean = false,
)

/**
 * 相机 ViewModel。
 *
 * 职责：
 * 1. 持有 [ImageCapture] use case（@Volatile 供 Composable 引用）
 * 2. 维护当前 [PhotoType]、主 [CustomerRow]、多选 [CustomerRow] 列表
 * 3. 拍照触发：takePicture -> 落盘 -> 水印 -> 缩略图 -> markPhoto/markPhotoBatch
 * 4. 持久化 [CameraSession]（拍照前 save，完成或退出后 clear）
 * 5. 多选计数：主 key 走 [ProgressRepository.markPhoto]，其余走 [ProgressRepository.markPhotoBatch]
 *
 * 拍照流程详见 [takePhoto]。
 */
@HiltViewModel
class CameraViewModel @Inject constructor(
    private val app: Application,
    private val progressRepository: ProgressRepository,
    private val cameraSessionRepository: CameraSessionRepository,
    private val locationService: LocationService,
    private val watermarkGenerator: WatermarkGenerator,
    private val thumbnailGenerator: ThumbnailGenerator,
    private val namingConfigRepository: NamingConfigRepository,
    private val namingRuleGenerator: NamingRuleGenerator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    /** ImageCapture 实例；由 Composable 持有引用以便绑定到 ProcessCameraProvider。 */
    val imageCapture: ImageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
        .build()

    /** 主线程 Executor（takePicture 回调）。 */
    val mainExecutor: Executor by lazy { ContextCompat.getMainExecutor(app) }

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.getDefault())

    /**
     * 初始化拍照上下文：注入选中行、Excel URI。
     *
     * - 第一个 row 为主行（拍照归属主 key）
     * - 其余行通过 [markPhotoBatch] 同步计数
     * - 立即 saveCameraSession 持久化（用于进程恢复）
     */
    fun initWithRows(rows: List<CustomerRow>, excelUri: String) {
        if (rows.isEmpty()) {
            _uiState.update { it.copy(toastMessage = "未选择客户行") }
            return
        }
        val primary = rows.first()
        val others = rows.drop(1)
        val md5 = ProgressKeyUtil.excelUriMd5(excelUri)
        _uiState.update {
            it.copy(
                primaryRow = primary,
                multiSelectRows = others,
                excelUri = excelUri,
                excelUriMd5 = md5,
                photosThisSession = 0,
                capturedThumbnails = emptyList(),
            )
        }
        // 初始 total = 主 key 当前照片数
        viewModelScope.launch {
            val existing = progressRepository.getProgress(primary.progressKey)?.photos?.size ?: 0
            _uiState.update { it.copy(totalPhotos = existing) }
        }
        saveCameraSession(launched = true, photoPath = null, mediaUri = null)
    }

    /** 切换拍照类型。 */
    fun onPhotoTypeChange(type: PhotoType) {
        _uiState.update { it.copy(currentPhotoType = type) }
    }

    /** 关闭画廊。 */
    fun dismissGallery() {
        _uiState.update { it.copy(showGallery = false) }
    }

    /** 打开画廊。 */
    fun openGallery() {
        _uiState.update { it.copy(showGallery = true) }
    }

    /** 消费 Toast 消息。 */
    fun toastConsumed() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    fun onCameraReady() {
        _uiState.update { it.copy(cameraReady = true) }
    }

    /**
     * 位置预热：在进入相机界面或获得位置权限后立即触发一次定位。
     *
     * - 成功：清零 [CameraUiState.locationFailed]，缓存将在 5 分钟内被复用
     * - 失败：置位 [CameraUiState.locationFailed]，触发 Snackbar 提示用户检查权限/GPS
     *
     * 不阻塞 UI，失败也不抛异常（仅记录日志）。
     */
    fun prewarmLocation() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    locationService.getCurrentLocation()
                } catch (e: Exception) {
                    Timber.w(e, "预热定位失败")
                    null
                }
            }
            if (result == null) {
                _uiState.update { it.copy(locationFailed = true) }
            } else {
                _uiState.update { it.copy(locationFailed = false) }
            }
        }
    }

    /**
     * 触发拍照。
     *
     * 流程：
     * 1. 校验 [ImageCapture] 与主 key 就绪
     * 2. 准备输出文件 `photos/<progressKey>/<fileName>.jpg`
     *    （文件名由 [NamingRuleGenerator] 按用户配置生成，全 NONE 时回退 IMG_<timestamp>.jpg）
     * 3. saveCameraSession（带 photo_path/photo_launch_time，防进程死亡）
     * 4. ImageCapture.takePicture(OutputFileOptions, mainExecutor, callback)
     * 5. 成功回调 [onImageSaved]：取位置 -> 水印 -> 缩略图 -> markPhoto -> Toast
     * 6. 失败回调 [onCaptureError]：Toast + Timber
     *
     * 因 [NamingConfigRepository.getConfig] 为 suspend，文件名生成在协程中完成，
     * 随后切回主线程调用 [captureToFile]。
     */
    fun takePhoto() {
        val primary = _uiState.value.primaryRow ?: run {
            _uiState.update { it.copy(toastMessage = "请先选择客户行") }
            return
        }
        if (_uiState.value.isCapturing) {
            Timber.d("正在拍照中，忽略重复请求")
            return
        }

        val currentPhotoType = _uiState.value.currentPhotoType
        val ctx: Context = app
        val photosDir = File(ctx.getExternalFilesDir(null), "photos/${primary.progressKey}")
        if (!photosDir.exists()) photosDir.mkdirs()

        _uiState.update { it.copy(isCapturing = true, lastSavedPath = null) }

        viewModelScope.launch {
            // 文件名生成（config 读取需在 IO 线程）
            val fileName = withContext(Dispatchers.IO) {
                val config = namingConfigRepository.getConfig()
                val sequence = calculateNextSequence(photosDir, currentPhotoType)
                namingRuleGenerator.generate(
                    config = config,
                    customerRow = primary,
                    photoType = currentPhotoType,
                    sequence = sequence,
                )
            }
            val outputFile = File(photosDir, fileName)

            // 持久化会话（防止拍照过程中被系统杀死）
            saveCameraSession(
                launched = true,
                photoPath = outputFile.absolutePath,
                mediaUri = null,
            )

            captureToFile(
                imageCapture = imageCapture,
                outputFile = outputFile,
                executor = mainExecutor,
                onImageSaved = { saved -> onImageSaved(saved, primary) },
                onError = { exc -> onCaptureError(exc) },
            )
        }
    }

    /**
     * 计算下一个序号：扫描 [photosDir] 下已存在的 .jpg 文件，
     * 统计文件名中包含 `-${photoType.displayName}-` 的数量 + 1。
     *
     * - 目录不存在或无匹配文件时返回 1
     * - 当 [NamingConfig.isAllNone] 时 NamingRuleGenerator 会回退 IMG_<timestamp>.jpg，
     *   此时序号不影响结果，但调用本方法无害
     */
    private fun calculateNextSequence(photosDir: File, photoType: PhotoType): Int {
        if (!photosDir.exists()) return 1
        val existing = photosDir.listFiles { f -> f.isFile && f.name.endsWith(".jpg", ignoreCase = true) }
            ?: return 1
        val count = existing.count { f ->
            f.name.contains("-${photoType.displayName}-", ignoreCase = true)
        }
        return count + 1
    }

    /**
     * 拍照成功回调（在主线程执行）。
     *
     * 步骤：
     * 1. 校验文件 size > 0
     * 2. 获取位置（LocationService）
     * 3. 生成水印 3 段内容（日期 / 地址 / 经纬度）
     * 4. 调用 WatermarkGenerator.drawAndSave 覆盖原文件
     * 5. 生成缩略图
     * 6. markPhoto（主 key） + markPhotoBatch（其他 keys）
     * 7. 显示 Toast "已拍摄 N 张"
     * 8. clearCameraSession
     */
    private fun onImageSaved(savedFile: File, primary: CustomerRow) {
        if (!savedFile.exists() || savedFile.length() <= 0) {
            _uiState.update {
                it.copy(
                    isCapturing = false,
                    toastMessage = "拍照失败：文件为空",
                )
            }
            clearCameraSessionSafely()
            return
        }

        viewModelScope.launch {
            try {
                val primaryKey = primary.progressKey
                val photoType = _uiState.value.currentPhotoType.displayName

                // 1. 位置（失败时返回 null，触发「定位失败」水印，不再兜底伪造坐标）
                val location: LocationResult? = withContext(Dispatchers.IO) {
                    try {
                        locationService.getCurrentLocation()
                    } catch (e: Exception) {
                        Timber.w(e, "定位失败")
                        null
                    }
                }
                // 同步更新定位失败状态（供 Snackbar 显示）
                _uiState.update { it.copy(locationFailed = location == null) }

                // 2. 水印内容（location 为 null 时内部自动渲染「定位失败」段）
                val segments = watermarkGenerator.buildSegments(
                    captureTimeMillis = System.currentTimeMillis(),
                    location = location,
                )
                val config = WatermarkConfig(
                    segments = segments,
                    position = WatermarkPosition.BOTTOM_RIGHT,
                    fontSize = WatermarkFontSize.MEDIUM,
                    opacity = 0.7f,
                    enabled = true,
                )

                // 3. 绘制水印并覆盖保存（同一路径）
                val watermarkedPath = withContext(Dispatchers.IO) {
                    watermarkGenerator.drawAndSave(
                        sourcePath = savedFile.absolutePath,
                        outputPath = savedFile.absolutePath,
                        location = location,
                        config = config,
                    )
                }
                if (watermarkedPath == null) {
                    Timber.w("水印生成失败，保留原图: %s", savedFile.absolutePath)
                }

                // 3.5 复制到 MediaStore（DCIM/LoanPhoto），使照片在系统相册可见
                // app 私有目录（getExternalFilesDir）不被 MediaScanner 索引，
                // 必须通过 MediaStore API 写入公共 DCIM 目录才能在相册可见。
                // 多选拍摄时物理文件只有一份，仅需复制一次。
                copyToMediaStore(savedFile)

                // 4. 缩略图
                val thumbPath = withContext(Dispatchers.IO) {
                    runCatching {
                        thumbnailGenerator.generateThumbnail(
                            sourcePath = savedFile.absolutePath,
                            progressKey = primaryKey,
                        )
                    }.getOrNull()
                }

                // 5. 进度持久化
                val otherKeys = _uiState.value.multiSelectRows.map { it.progressKey }
                progressRepository.markPhoto(
                    key = primaryKey,
                    photoPath = savedFile.absolutePath,
                    photoType = photoType,
                )
                if (otherKeys.isNotEmpty()) {
                    progressRepository.markPhotoBatch(
                        keys = otherKeys,
                        photoPath = savedFile.absolutePath,
                        photoType = photoType,
                    )
                }

                // 6. UI 更新
                val newSessionCount = _uiState.value.photosThisSession + 1
                val newTotal = _uiState.value.totalPhotos + 1
                _uiState.update {
                    it.copy(
                        isCapturing = false,
                        photosThisSession = newSessionCount,
                        totalPhotos = newTotal,
                        lastSavedPath = savedFile.absolutePath,
                        capturedThumbnails = (it.capturedThumbnails + listOfNotNull(thumbPath)),
                        toastMessage = "已拍摄 ${newSessionCount} 张",
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "拍照后处理失败")
                _uiState.update {
                    it.copy(
                        isCapturing = false,
                        toastMessage = "照片保存失败: ${e.message ?: "未知错误"}",
                    )
                }
            } finally {
                clearCameraSessionSafely()
            }
        }
    }

    /** 拍照失败回调。 */
    private fun onCaptureError(exc: ImageCaptureException) {
        Timber.e(exc, "拍照失败 code=%d", exc.imageCaptureError)
        _uiState.update {
            it.copy(
                isCapturing = false,
                toastMessage = "拍照失败: ${exc.message}",
            )
        }
        clearCameraSessionSafely()
    }

    /**
     * 退出相机界面时调用：清会话 + Toast 提示。
     */
    fun onExitCamera() {
        clearCameraSessionSafely()
        val count = _uiState.value.photosThisSession
        if (count > 0) {
            Toast.makeText(app, "本次共拍摄 $count 张", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 保存相机会话（11 字段，兼容 Kivy v3.22.24）。
     *
     * - [launched] 对应 camera_launched
     * - [photoPath] photo_path（拍照前写入，恢复时校验文件是否存在）
     * - [mediaUri] media_uri（CameraX OutputFileResults.savedUri，可能为 null）
     */
    private fun saveCameraSession(
        launched: Boolean,
        photoPath: String?,
        mediaUri: String?,
    ) {
        val primary = _uiState.value.primaryRow ?: return
        val others = _uiState.value.multiSelectRows
        val multiKeys = (listOf(primary.progressKey) + others.map { it.progressKey }).distinct()
        val multiRows = (listOf(primary.rowIndex) + others.map { it.rowIndex }).distinct()

        val session = CameraSession(
            cameraLaunched = launched,
            requestCode = REQUEST_CODE_CAMERA,
            rowIndex = primary.rowIndex,
            photoType = _uiState.value.currentPhotoType.displayName,
            multiSelectKeys = multiKeys,
            multiSelectRows = multiRows,
            key = primary.progressKey,
            photoPath = photoPath,
            mediaUri = mediaUri,
            photoLaunchTime = System.currentTimeMillis() / 1000.0,
            excelUriMd5 = _uiState.value.excelUriMd5,
            timestamp = timestampFormat.format(Date()),
        )
        viewModelScope.launch {
            runCatching { cameraSessionRepository.saveSession(session) }
                .onFailure { Timber.w(it, "saveCameraSession 失败") }
        }
    }

    /** 清除相机会话（吞掉异常，不阻塞 UI）。 */
    private fun clearCameraSessionSafely() {
        viewModelScope.launch {
            runCatching { cameraSessionRepository.clearSession() }
                .onFailure { Timber.w(it, "clearCameraSession 失败") }
        }
    }

    /**
     * 将照片复制到 MediaStore（DCIM/LoanPhoto），使其在系统相册立即可见。
     *
     * app 私有目录（getExternalFilesDir）下的文件不被 MediaScanner 索引，
     * 必须通过 MediaStore API 写入公共 DCIM 目录才能在相册中可见。
     * 原 app 私有目录的文件保留不变，供缩略图/进度追踪使用。
     *
     * Android 10+ (Q) 使用 RELATIVE_PATH + IS_PENDING 两阶段写入；
     * Android 9 及以下直接 insert（旧版无需 IS_PENDING）。
     */
    private suspend fun copyToMediaStore(sourceFile: File) = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = app.contentResolver
            val fileName = sourceFile.name
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/LoanPhoto")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val uri = resolver.insert(collection, values) ?: run {
                Timber.w("MediaStore insert 返回 null，跳过相册复制")
                return@runCatching
            }
            resolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { input -> input.copyTo(out) }
            } ?: run {
                Timber.w("无法打开 MediaStore OutputStream，跳过相册复制")
                return@runCatching
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val updateValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(uri, updateValues, null, null)
            }
            Timber.i("照片已复制到 MediaStore (相册可见): %s", uri)
        }.onFailure { Timber.w(it, "复制照片到 MediaStore 失败（不影响 app 内功能）") }
    }

    override fun onCleared() {
        super.onCleared()
        // 注意：ImageCapture 没有 unbindAll；ProcessCameraProvider 在 Lifecycle destroy
        // 时会自动 unbind。这里仅释放相机内部回调资源。
        // 如需主动 unbind，需通过 ProcessCameraProvider.getInstance(ctx).get().unbindAll()。
    }

    private companion object {
        const val REQUEST_CODE_CAMERA = 1001
    }
}
