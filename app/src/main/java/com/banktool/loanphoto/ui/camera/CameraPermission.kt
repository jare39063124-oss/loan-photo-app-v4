package com.banktool.loanphoto.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.banktool.loanphoto.ui.theme.Accent
import com.banktool.loanphoto.ui.theme.Error
import com.banktool.loanphoto.ui.theme.Text as TextColor
import com.banktool.loanphoto.ui.theme.TextSecondary

/**
 * 相机权限守卫 Composable。
 *
 * 行为：
 * - 进入时检查 CAMERA 权限；已授予则直接渲染 [content]
 * - 未授予时弹出系统权限请求（[ActivityResultContracts.RequestPermission]）
 * - 用户拒绝后展示提示文字 + 重试按钮
 * - 重试按钮再次发起权限请求
 *
 * @param content 权限授予后展示的内容（如相机预览）
 */
@Composable
fun CameraPermission(
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionRequested by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        permissionRequested = true
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasPermission) {
        content()
    } else {
        PermissionDeniedState(
            requested = permissionRequested,
            onRetry = { launcher.launch(Manifest.permission.CAMERA) },
        )
    }
}

@Composable
private fun PermissionDeniedState(
    requested: Boolean,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.CameraAlt,
                contentDescription = null,
                tint = if (requested) Error else TextSecondary,
                modifier = Modifier.size(64.dp),
            )
            Spacer(modifier = Modifier.size(16.dp))
            Text(
                text = if (requested) "相机权限被拒绝" else "需要相机权限",
                color = TextColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = "用于拍摄抵押物照片，请授予相机权限后继续",
                color = TextSecondary,
                fontSize = 13.sp,
            )
            Spacer(modifier = Modifier.size(20.dp))
            Button(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(text = "重新授权", color = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}
