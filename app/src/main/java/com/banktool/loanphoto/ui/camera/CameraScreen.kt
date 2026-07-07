package com.banktool.loanphoto.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.banktool.loanphoto.domain.entity.CustomerRow
import com.banktool.loanphoto.domain.entity.PhotoType
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
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 初始化（仅一次）
    LaunchedEffect(rows, excelUri) {
        if (rows.isNotEmpty()) {
            viewModel.initWithRows(rows, excelUri)
        }
    }

    // 位置权限请求（在 CAMERA 之后顺带请求）
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* 结果由 LocationService 内部兜底处理，这里不需要操作 */ }

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
                imageCapture = viewModel.imageCapture,
                onCameraReady = { viewModel.onCameraReady() },
                modifier = Modifier.fillMaxSize(),
            )

            // 顶部: PhotoType Chips
            PhotoTypeSelector(
                current = uiState.currentPhotoType,
                onSelect = viewModel::onPhotoTypeChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp, start = 8.dp, end = 8.dp),
            )

            // 顶部右侧: 客户名 + 已拍数
            CustomerInfoPill(
                primaryName = uiState.primaryRow?.borrower ?: "",
                multiCount = uiState.multiSelectRows.size,
                sessionCount = uiState.photosThisSession,
                totalCount = uiState.totalPhotos,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 72.dp, start = 12.dp),
            )

            // 底部: 控制条
            BottomControlBar(
                isCapturing = uiState.isCapturing,
                sessionCount = uiState.photosThisSession,
                onClose = {
                    viewModel.onExitCamera()
                    onClose()
                },
                onCapture = { viewModel.takePhoto() },
                onShowGallery = { viewModel.openGallery() },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp, start = 16.dp, end = 16.dp),
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
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(PhotoType.entries) { type ->
                FilterChip(
                    selected = type == current,
                    onClick = { onSelect(type) },
                    label = {
                        Text(
                            text = type.displayName,
                            fontSize = 13.sp,
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
