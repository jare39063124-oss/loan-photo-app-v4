package com.banktool.loanphoto.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.banktool.loanphoto.domain.entity.CustomerRow
import com.banktool.loanphoto.domain.entity.PhotoType
import com.banktool.loanphoto.ui.settings.SettingsViewModel
import com.banktool.loanphoto.ui.theme.Accent
import com.banktool.loanphoto.ui.theme.AccentDark

/** 主题中未导出的纯白色，此处本地定义以避免主题文件改动。 */
private val White = Color(0xFFFFFFFF)

/**
 * 相机拍照界面。
 *
 * - 顶部: PhotoType 选择 Chip 组（5 种类型水平排列）
 * - 中部: CameraPreviewView（相机预览）
 * - 底部: 关闭按钮（左）/ 拍照按钮（中，大圆形）/ 查看已拍按钮（右）
 * - 拍照成功后不退出，自动准备下一张（连续拍摄模式）
 *
 * @param rows 由 CustomerListScreen 传入的选中行
 * @param excelUri 当前 Excel 文件 URI
 * @param onClose 关闭回调（返回客户列表）
 */
@Composable
fun CameraScreen(
    rows: List<CustomerRow>,
    excelUri: String,
    onClose: () -> Unit,
    viewModel: CameraViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 参考线配置（黄金分割线 / 中心标 / 水平仪），由 SettingsViewModel 持久化提供
    val guideLineConfig by settingsViewModel.guideLineConfig.collectAsStateWithLifecycle()
    // 设备真实方向（0/90/180/270），用于水平仪绘制
    val deviceOrientation by viewModel.deviceOrientation.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // 初始化（仅一次）
    LaunchedEffect(rows, excelUri) {
        if (rows.isNotEmpty()) {
            viewModel.initWithRows(rows, excelUri)
        }
    }

    // 位置预热：进入相机界面立即触发一次定位（命中缓存后拍照时直接复用）
    LaunchedEffect(Unit) {
        viewModel.prewarmLocation()
    }

    // 定位失败时弹出 Snackbar 提示
    LaunchedEffect(uiState.locationFailed) {
        if (uiState.locationFailed) {
            snackbarHostState.showSnackbar("定位失败，请检查位置权限或 GPS 开关")
        }
    }

    // 位置权限请求（在 CAMERA 之后顺带请求）；获得授权后立即预热
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it }) {
            viewModel.prewarmLocation()
        }
    }

    LaunchedEffect(Unit) {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (needed.isNotEmpty()) {
            locationPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    // Toast 消息处理
    LaunchedEffect(uiState.toastMessage) {
        val msg = uiState.toastMessage ?: return@LaunchedEffect
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        viewModel.toastConsumed()
    }

    // 退出时清理
    LaunchedEffect(onClose) {
        // 不在这里调用 onExitCamera；onClose 触发时由调用方在销毁前调用
    }

    CameraPermission {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // 相机预览
            CameraPreviewView(
                engine = viewModel.cameraEngine,
                onZoomChange = { ratio -> viewModel.onZoomChange(ratio) },
                modifier = Modifier.fillMaxSize(),
            )

            // 参考线叠加层（黄金分割线 / 中心标 / 水平仪），绘制于预览之上、交互控件之下
            CameraGuideLines(
                goldenRatioGrid = guideLineConfig.goldenRatioGrid,
                centerMark = guideLineConfig.centerMark,
                levelGauge = guideLineConfig.levelGauge,
                deviceOrientation = deviceOrientation,
                modifier = Modifier.fillMaxSize(),
            )

            // 顶部: PhotoType Chips
            PhotoTypeSelector(
                current = uiState.currentPhotoType,
                onSelect = viewModel::onPhotoTypeChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 8.dp, start = 8.dp, end = 8.dp),
            )

            // 顶部右侧: 客户名 + 已拍数
            CustomerInfoPill(
                primaryName = uiState.primaryRow?.borrower ?: "",
                multiCount = uiState.multiSelectRows.size,
                sessionCount = uiState.photosThisSession,
                totalCount = uiState.totalPhotos,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 96.dp, start = 12.dp),
            )

            // 底部: 缩放控制 + 控制条（Column 垂直排列）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp, start = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 缩放控制条（仅在设备支持缩放或广角时显示）
                val showZoomControls = uiState.maxZoomRatio > 1.0f || uiState.hasWideAngle
                if (showZoomControls) {
                    ZoomControlBar(
                        zoomRatio = uiState.zoomRatio,
                        maxZoomRatio = uiState.maxZoomRatio,
                        minZoomRatio = uiState.minZoomRatio,
                        hasWideAngle = uiState.hasWideAngle,
                        isWideAngleActive = uiState.isWideAngleActive,
                        onZoomChange = viewModel::setZoom,
                        onToggleWideAngle = viewModel::toggleWideAngle,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // 闪光灯控制条（仅在相机预览就绪时显示）
                if (uiState.cameraReady) {
                    FlashControlBar(
                        flashMode = uiState.flashMode,
                        onFlashModeChange = viewModel::setFlashMode,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }

                BottomControlBar(
                    isCapturing = uiState.isCapturing,
                    sessionCount = uiState.photosThisSession,
                    onClose = {
                        viewModel.onExitCamera()
                        onClose()
                    },
                    onCapture = { viewModel.takePhoto() },
                    onShowGallery = { viewModel.openGallery() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Snackbar：定位失败提示（悬浮于控制条上方，含缩放条+闪光灯条时抬高避免重叠）
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 225.dp, start = 16.dp, end = 16.dp),
            )

            // 拍照中遮罩
            if (uiState.isCapturing) {
                CapturingOverlay()
            }

            // 画廊
            if (uiState.showGallery) {
                PhotoGallerySheet(
                    thumbnails = uiState.capturedThumbnails,
                    totalCount = uiState.totalPhotos,
                    onDismiss = viewModel::dismissGallery,
                    customerName = uiState.primaryRow?.borrower ?: "",
                )
            }
        }
    }
}

/**
 * 顶部拍照类型选择条。
 */
@Composable
private fun PhotoTypeSelector(
    current: PhotoType,
    onSelect: (PhotoType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color(0xCC1F1F1F),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(PhotoType.entries) { type ->
                FilterChip(
                    selected = type == current,
                    onClick = { onSelect(type) },
                    label = {
                        Text(
                            text = type.displayName,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            fontSize = 16.sp,
                            fontWeight = if (type == current) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Accent,
                        selectedLabelColor = Color.White,
                        containerColor = Color(0x33FFFFFF),
                        labelColor = Color.White,
                    ),
                )
            }
        }
    }
}

/**
 * 客户信息小卡片：显示主客户名 + 多选数 + 本次已拍/累计。
 */
@Composable
private fun CustomerInfoPill(
    primaryName: String,
    multiCount: Int,
    sessionCount: Int,
    totalCount: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color(0xCC1F1F1F),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(
                text = primaryName.ifBlank { "未知客户" },
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(2.dp))
            val summary = buildString {
                append("本次 $sessionCount 张")
                if (totalCount > 0) append(" / 累计 $totalCount")
                if (multiCount > 0) append(" (多选 ${multiCount + 1})")
            }
            Text(
                text = summary,
                color = Color(0xFFE0E0E0),
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * 缩放控制条：广角切换按钮 + 缩放滑块 + 倍率显示。
 *
 * - [minZoomRatio] < 1.0 时显示广角按钮（点击在 0.5x / 1x 间切换）
 * - [maxZoomRatio] > 1.0 时显示滑块（范围 1.0 ~ maxZoomRatio）
 * - 滑块值会被 clamp 到 [1.0, maxZoomRatio]；广角态（zoom < 1.0）时滑块显示在 1.0 位置
 * - 广角按钮为长方形（72x36dp、6dp 圆角），与缩放滑杆在 Column 内垂直堆叠，
 *   spacedBy 8.dp，整体水平居中；缩放滑杆 Row 内 Slider 用 weight(1f) 自适应剩余空间，
 *   倍率文字固定宽度 44dp，确保窄屏不溢出
 * - 两者均不满足时整个 [ZoomControlBar] 不应被调用（由父 Composable 判断）
 */
@Composable
private fun ZoomControlBar(
    zoomRatio: Float,
    maxZoomRatio: Float,
    minZoomRatio: Float,
    hasWideAngle: Boolean,
    isWideAngleActive: Boolean,
    onZoomChange: (Float) -> Unit,
    onToggleWideAngle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasZoom = maxZoomRatio > 1.0f

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 广角切换按钮（仅当设备支持广角时显示）。长方形 72x36dp、6dp 圆角，文案「广角:开/关」
        if (hasWideAngle) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (isWideAngleActive) Accent else Color(0xCC404040),
                modifier = Modifier.height(36.dp).width(72.dp),
            ) {
                IconButton(
                    onClick = onToggleWideAngle,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Text(
                        text = if (isWideAngleActive) "广角:开" else "广角:关",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }

        // 缩放滑杆（仅当设备支持变焦时显示）。内层 Row：Slider weight(1f) 自适应 + 倍率文字
        if (hasZoom) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Slider(
                    value = zoomRatio.coerceIn(1.0f, maxZoomRatio),
                    onValueChange = onZoomChange,
                    valueRange = 1.0f..maxZoomRatio,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "%.1fx".format(zoomRatio),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.width(44.dp),
                )
            }
        }
    }
}

/**
 * 闪光灯控制条：4 个图标按钮水平排列（关闭 / 自动 / 常亮 / 开启）。
 *
 * - 0 关闭：Icons.Filled.FlashOff
 * - 1 自动：Icons.Filled.FlashAuto
 * - 2 常亮：Icons.Filled.FlashlightOn（持续补光，调用 enableTorch）
 * - 3 开启：Icons.Filled.FlashOn（仅拍照瞬间闪光）
 *
 * 选中态高亮 [Accent] 背景 + 白色图标，未选中灰色背景。
 */
@Composable
private fun FlashControlBar(
    flashMode: Int,
    onFlashModeChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlashModeButton(
            icon = Icons.Filled.FlashOff,
            contentDescription = "闪光灯关闭",
            isSelected = flashMode == 0,
            onClick = { onFlashModeChange(0) },
        )
        FlashModeButton(
            icon = Icons.Filled.FlashAuto,
            contentDescription = "闪光灯自动",
            isSelected = flashMode == 1,
            onClick = { onFlashModeChange(1) },
        )
        FlashModeButton(
            icon = Icons.Filled.FlashlightOn,
            contentDescription = "闪光灯常亮",
            isSelected = flashMode == 2,
            onClick = { onFlashModeChange(2) },
        )
        FlashModeButton(
            icon = Icons.Filled.FlashOn,
            contentDescription = "闪光灯开启",
            isSelected = flashMode == 3,
            onClick = { onFlashModeChange(3) },
        )
    }
}

/**
 * 单个闪光灯模式按钮。选中态 Accent 背景，未选中灰色背景。
 */
@Composable
private fun FlashModeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = if (isSelected) Accent else Color(0xCC404040),
        modifier = Modifier.size(44.dp),
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * 底部控制条：关闭 / 拍照 / 查看已拍。
 */
@Composable
private fun BottomControlBar(
    isCapturing: Boolean,
    sessionCount: Int,
    onClose: () -> Unit,
    onCapture: () -> Unit,
    onShowGallery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 关闭按钮
        CircleIconButton(
            icon = Icons.Filled.Close,
            contentDescription = "关闭相机",
            onClick = onClose,
            size = 52.dp,
            bgColor = Color(0xCC404040),
        )

        // 拍照按钮
        CaptureButton(
            isCapturing = isCapturing,
            onClick = onCapture,
        )

        // 查看已拍按钮
        CircleIconButton(
            icon = Icons.Filled.PhotoLibrary,
            contentDescription = "查看已拍",
            onClick = onShowGallery,
            size = 52.dp,
            bgColor = Color(0xCC404040),
            badge = sessionCount.takeIf { it > 0 },
        )
    }
}

/**
 * 大圆形拍照按钮（外环 + 内圆）。
 */
@Composable
private fun CaptureButton(
    isCapturing: Boolean,
    onClick: () -> Unit,
) {
    val outerSize = 78.dp
    val innerSize = 64.dp
    Box(
        modifier = Modifier
            .size(outerSize)
            .background(Color(0x55FFFFFF), CircleShape)
            .padding(7.dp)
            .background(if (isCapturing) AccentDark else Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (isCapturing) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier.size(28.dp),
            )
        } else {
            IconButton(
                onClick = onClick,
                modifier = Modifier.size(innerSize),
            ) {
                Icon(
                    imageVector = Icons.Filled.Camera,
                    contentDescription = "拍照",
                    tint = Accent,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
    }
}

/**
 * 圆形图标按钮（带可选角标）。
 */
@Composable
private fun CircleIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp,
    bgColor: Color,
    badge: Int? = null,
) {
    Box(contentAlignment = Alignment.TopEnd) {
        Surface(
            shape = CircleShape,
            color = bgColor,
            modifier = Modifier.size(size),
        ) {
            IconButton(onClick = onClick) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = Color.White,
                )
            }
        }
        if (badge != null && badge > 0) {
            Surface(
                shape = CircleShape,
                color = Accent,
                modifier = Modifier
                    .size(20.dp)
                    .padding(top = 0.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = if (badge > 99) "99+" else badge.toString(),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/**
 * 拍照中半透明遮罩。
 */
@Composable
private fun CapturingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x66000000)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xCC000000),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "正在保存...",
                    color = Color.White,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

/**
 * 相机参考线叠加层。
 *
 * 在相机预览之上绘制三种参考线（均由用户设置开关控制）：
 * 1. [goldenRatioGrid]：黄金分割线（水平 1/3、2/3 + 垂直 1/3、2/3 十字网格，半透明白色）
 * 2. [centerMark]：画面中心短十字标（半透明白色，较网格略亮）
 * 3. [levelGauge]：顶部水平仪（竖直持机时绿色高亮，倾斜时白色旋转显示）
 *
 * 所有线条使用半透明白色，不遮挡预览内容。Canvas 不拦截触摸事件。
 *
 * @param goldenRatioGrid 是否绘制黄金分割线
 * @param centerMark 是否绘制中心标
 * @param levelGauge 是否绘制水平仪
 * @param deviceOrientation 设备真实方向（0/90/180/270）
 * @param modifier 修饰符
 */
@Composable
private fun CameraGuideLines(
    goldenRatioGrid: Boolean,
    centerMark: Boolean,
    levelGauge: Boolean,
    deviceOrientation: Int,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        // ===== 黄金分割线 =====
        // 水平线 y = 1/3、2/3；垂直线 x = 1/3、2/3；半透明白色细线
        if (goldenRatioGrid) {
            val gridColor = Color.White.copy(alpha = 0.4f)
            val gridStrokeWidth = 1.2.dp.toPx()
            val xThird = size.width / 3f
            val yThird = size.height / 3f
            // 水平线
            drawLine(
                color = gridColor,
                start = Offset(0f, yThird),
                end = Offset(size.width, yThird),
                strokeWidth = gridStrokeWidth,
            )
            drawLine(
                color = gridColor,
                start = Offset(0f, yThird * 2f),
                end = Offset(size.width, yThird * 2f),
                strokeWidth = gridStrokeWidth,
            )
            // 垂直线
            drawLine(
                color = gridColor,
                start = Offset(xThird, 0f),
                end = Offset(xThird, size.height),
                strokeWidth = gridStrokeWidth,
            )
            drawLine(
                color = gridColor,
                start = Offset(xThird * 2f, 0f),
                end = Offset(xThird * 2f, size.height),
                strokeWidth = gridStrokeWidth,
            )
        }

        // ===== 中心标 =====
        // 画面中心短十字线（水平 + 垂直），总长约 20dp，半透明白色略亮于网格
        if (centerMark) {
            val markColor = Color.White.copy(alpha = 0.6f)
            val markStrokeWidth = 1.2.dp.toPx()
            val halfLen = 10.dp.toPx()
            val cx = size.width / 2f
            val cy = size.height / 2f
            // 水平短横线
            drawLine(
                color = markColor,
                start = Offset(cx - halfLen, cy),
                end = Offset(cx + halfLen, cy),
                strokeWidth = markStrokeWidth,
            )
            // 垂直短竖线
            drawLine(
                color = markColor,
                start = Offset(cx, cy - halfLen),
                end = Offset(cx, cy + halfLen),
                strokeWidth = markStrokeWidth,
            )
        }

        // ===== 水平仪 =====
        // 顶部中央水平条（长约 80dp、宽约 2dp）：
        // - deviceOrientation=0（竖直持机）：水平显示 + 绿色高亮（已水平）
        // - 其余方向：按 deviceOrientation 角度旋转 + 白色半透明（非水平）
        if (levelGauge) {
            val barLen = 80.dp.toPx()
            val barStrokeWidth = 2.dp.toPx()
            val cx = size.width / 2f
            val y = 30.dp.toPx()
            val isLevel = deviceOrientation == 0
            val barColor = if (isLevel) Color(0xFF4CAF50) else Color.White.copy(alpha = 0.4f)
            val angleDeg = deviceOrientation.toFloat()
            rotate(degrees = angleDeg, pivot = Offset(cx, y)) {
                drawLine(
                    color = barColor,
                    start = Offset(cx - barLen / 2f, y),
                    end = Offset(cx + barLen / 2f, y),
                    strokeWidth = barStrokeWidth,
                )
            }
        }
    }
}
