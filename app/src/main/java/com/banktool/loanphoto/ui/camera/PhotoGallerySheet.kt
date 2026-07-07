package com.banktool.loanphoto.ui.camera

import android.content.Intent
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.banktool.loanphoto.ui.theme.Accent
import com.banktool.loanphoto.ui.theme.Divider
import com.banktool.loanphoto.ui.theme.Text as TextColor
import com.banktool.loanphoto.ui.theme.TextSecondary
import java.io.File

/**
 * 已拍照片画廊（ModalBottomSheet）。
 *
 * v4.0.2：
 * - 标题「已拍 N 张 - {客户名}」
 * - 空状态文案「{客户名} 暂无照片（本次会话）」
 * - 缩略图加载失败显示 [Icons.Filled.BrokenImage] 占位
 * - 底部新增「在系统相册中查看」按钮
 *
 * @param thumbnails 缩略图绝对路径列表
 * @param totalCount 客户当前累计照片总数
 * @param onDismiss 关闭回调
 * @param customerName 客户名（用于标题与空状态文案，默认空字符串保持向后兼容）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoGallerySheet(
    thumbnails: List<String>,
    totalCount: Int,
    onDismiss: () -> Unit,
    customerName: String = "",
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var fullScreenPath by remember { mutableStateOf<String?>(null) }

    val displayName = customerName.ifBlank { "当前客户" }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "已拍 ${thumbnails.size} 张 - $displayName",
                        color = TextColor,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "本次会话 ${thumbnails.size} 张 / 客户累计 $totalCount 张",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = TextSecondary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (thumbnails.isEmpty()) {
                EmptyGallery(
                    customerName = displayName,
                    hasPhotosExpected = totalCount > 0,
                )
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(thumbnails.reversed()) { path ->
                        ThumbnailItem(
                            path = path,
                            onClick = { fullScreenPath = path },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 在系统相册中查看
            OutlinedButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
                    }
                    runCatching { context.startActivity(intent) }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.PhotoLibrary,
                    contentDescription = null,
                    tint = Accent,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("在系统相册中查看")
            }
        }
    }

    // 全屏预览
    fullScreenPath?.let { path ->
        FullScreenImage(path = path, onDismiss = { fullScreenPath = null })
    }
}

/**
 * 单个缩略图项。
 *
 * 加载失败时显示 [Icons.Filled.BrokenImage] 占位。
 */
@Composable
private fun ThumbnailItem(
    path: String,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(context)
            .data(File(path))
            .crossfade(true)
            .build(),
    )
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Divider)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when (painter.state) {
            is AsyncImagePainter.State.Error -> {
                Icon(
                    imageVector = Icons.Filled.BrokenImage,
                    contentDescription = "缩略图加载失败",
                    tint = TextSecondary,
                    modifier = Modifier.size(32.dp),
                )
            }
            is AsyncImagePainter.State.Loading,
            is AsyncImagePainter.State.Success,
            is AsyncImagePainter.State.Empty -> {
                Image(
                    painter = painter,
                    contentDescription = "缩略图",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * 空画廊占位。
 *
 * - [hasPhotosExpected]=true（应有照片但缩略图未加载）：显示 [Icons.Filled.BrokenImage] + 「照片加载失败」
 * - [hasPhotosExpected]=false：显示「{客户名} 暂无照片（本次会话）」
 */
@Composable
private fun EmptyGallery(customerName: String, hasPhotosExpected: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (hasPhotosExpected) {
                Icon(
                    imageVector = Icons.Filled.BrokenImage,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "照片加载失败",
                    color = TextSecondary,
                    fontSize = 14.sp,
                )
            } else {
                Text(
                    text = "$customerName 暂无照片（本次会话）",
                    color = TextSecondary,
                    fontSize = 14.sp,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "点击下方拍照按钮开始记录",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/**
 * 全屏图片预览（覆盖整屏，点击关闭）。
 */
@Composable
private fun FullScreenImage(
    path: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(File(path))
                .crossfade(true)
                .build(),
            contentDescription = "全屏预览",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "关闭预览",
                tint = Color.White,
            )
        }
    }
}
