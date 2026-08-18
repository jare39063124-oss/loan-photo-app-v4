package com.banktool.loanphoto.ui.customer.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.banktool.loanphoto.ui.theme.Accent
import com.banktool.loanphoto.ui.theme.Error
import com.banktool.loanphoto.ui.theme.TextSecondary
import java.net.URLEncoder

/**
 * 地址导航应用选择弹窗。
 *
 * 由条目地址点击触发，提供高德/百度两个入口：
 * - 已安装对应地图 App：直接拉起 Deep Link 导航（[Intent.FLAG_ACTIVITY_NEW_TASK]）
 * - 未安装：Toast「未安装XX地图」并尝试跳应用市场 `market://details?id=<包名>`（失败仅 Toast）
 *
 * @param address 完整导航地址（addrGeneral + addrDetail）
 * @param onDismiss 关闭弹窗
 */
@Composable
fun NavigationDialog(
    address: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "选择导航应用",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Accent,
            )
        },
        text = {
            Column {
                if (address.isNotBlank()) {
                    Text(
                        text = address,
                        fontSize = 13.sp,
                        color = TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                OutlinedButton(
                    onClick = {
                        launchNavigation(context, APP_AMAP, address)
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                ) {
                    Text(text = "高德地图", color = Accent)
                }
                OutlinedButton(
                    onClick = {
                        launchNavigation(context, APP_BAIDU, address)
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                ) {
                    Text(text = "百度地图", color = Accent)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", color = Error)
            }
        },
    )
}

/** 拉起地图导航：已安装则直接打开 Deep Link，未安装则 Toast 并尝试跳应用市场。 */
private fun launchNavigation(context: Context, app: String, address: String) {
    val encoded = URLEncoder.encode(address, "UTF-8").replace("+", "%20")
    val (uri, packageName, label) = when (app) {
        APP_AMAP -> Triple(
            "androidamap://keyword?keyword=$encoded&sourceApplication=banktool",
            PACKAGE_AMAP,
            "高德",
        )
        else -> Triple(
            "baidumap://map/geocoder?address=$encoded&src=com.banktool.loanphoto",
            PACKAGE_BAIDU,
            "百度",
        )
    }

    if (isAppInstalled(context, packageName)) {
        val launched = runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.isSuccess
        if (!launched) {
            Toast.makeText(context, "无法打开${label}地图", Toast.LENGTH_SHORT).show()
        }
    } else {
        Toast.makeText(context, "未安装${label}地图", Toast.LENGTH_SHORT).show()
        // 尝试跳应用市场下载页（失败仅保留上方 Toast，不额外提示）
        runCatching {
            val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(market)
        }
    }
}

private fun isAppInstalled(context: Context, packageName: String): Boolean =
    try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: Exception) {
        false
    }

private const val APP_AMAP = "amap"
private const val APP_BAIDU = "baidu"
private const val PACKAGE_AMAP = "com.autonavi.minimap"
private const val PACKAGE_BAIDU = "com.baidu.BaiduMap"
