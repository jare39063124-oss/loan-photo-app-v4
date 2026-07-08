package com.banktool.loanphoto.ui.license

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.banktool.loanphoto.ui.theme.Accent
import com.banktool.loanphoto.ui.theme.Bg
import com.banktool.loanphoto.ui.theme.Card as CardColor
import com.banktool.loanphoto.ui.theme.Error
import com.banktool.loanphoto.ui.theme.Text as TextColor
import com.banktool.loanphoto.ui.theme.TextSecondary

/**
 * 锁定状态枚举。
 *
 * - [UNAUTHORIZED]：设备未授权（Android ID 未匹配到授权列表，且未到期）
 * - [EXPIRED]：体验已到期
 */
enum class LockState {
    UNAUTHORIZED,
    EXPIRED,
}

/**
 * 全屏锁定页。
 *
 * 在 [com.banktool.loanphoto.MainActivity] 中当 [com.banktool.loanphoto.data.license.LicenseChecker.isAuthorized]
 * 返回 false 时展示，阻止进入主界面。
 *
 * 布局：
 * - [Surface] 全屏 [Bg] 背景
 * - 居中锁定图标（64dp，[Error] 色）
 * - 根据 [lockState] 显示不同标题与提示：
 *   - [LockState.UNAUTHORIZED]：标题「设备未授权」+ 提示「请将以下设备识别码告知作者激活」
 *     + 设备识别码（大字号 [Accent]，点击复制）+ 设备信息 + 联系方式
 *   - [LockState.EXPIRED]：标题「体验已到期」+ 提示「请联系作者续费使用」+ 联系方式
 *
 * 使用 Fluent Design 浅色主题，文字使用系统默认字体（等价 Microsoft YaHei）。
 *
 * @param lockState 锁定状态
 * @param deviceId 设备识别码（Android ID），仅 [LockState.UNAUTHORIZED] 时展示
 * @param deviceInfo 设备信息（品牌 + 型号），仅 [LockState.UNAUTHORIZED] 时展示
 * @param extraMessage 额外提示（如应用完整性校验失败原因），非空时在 [UnauthorizedContent] 顶部展示
 */
@Composable
fun LockScreen(
    lockState: LockState,
    deviceId: String = "",
    deviceInfo: String = "",
    extraMessage: String? = null,
) {
    val context = LocalContext.current

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Bg,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 锁定图标
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = Error,
                modifier = Modifier.size(64.dp),
            )
            Spacer(modifier = Modifier.size(24.dp))

            when (lockState) {
                LockState.UNAUTHORIZED -> UnauthorizedContent(
                    context = context,
                    deviceId = deviceId,
                    deviceInfo = deviceInfo,
                    extraMessage = extraMessage,
                )
                LockState.EXPIRED -> ExpiredContent()
            }
        }
    }
}

/**
 * 设备未授权内容：标题 + 提示 + 设备识别码（可复制）+ 设备信息 + 联系方式。
 *
 * @param extraMessage 额外提示（如完整性校验失败原因），非空时在标题上方以 Error 色展示
 */
@Composable
private fun UnauthorizedContent(
    context: Context,
    deviceId: String,
    deviceInfo: String,
    extraMessage: String? = null,
) {
    // 额外提示（如应用完整性校验失败原因）
    if (!extraMessage.isNullOrBlank()) {
        Text(
            text = extraMessage,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Error,
            fontFamily = FontFamily.Default,
        )
        Spacer(modifier = Modifier.size(12.dp))
    }

    // 标题
    Text(
        text = "设备未授权",
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        color = TextColor,
        fontFamily = FontFamily.Default,
    )
    Spacer(modifier = Modifier.size(12.dp))

    // 提示
    Text(
        text = "请将以下设备识别码告知作者激活",
        fontSize = 14.sp,
        color = TextSecondary,
        fontFamily = FontFamily.Default,
    )
    Spacer(modifier = Modifier.size(20.dp))

    // 设备识别码卡片（大字号 Accent，点击复制）
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = CardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "设备识别码",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    fontFamily = FontFamily.Default,
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = deviceId.ifBlank { "unknown" },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Accent,
                    fontFamily = FontFamily.Default,
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = deviceInfo,
                    fontSize = 13.sp,
                    color = TextSecondary,
                    fontFamily = FontFamily.Default,
                )
            }
            IconButton(onClick = { copyToClipboard(context, deviceId) }) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "复制识别码",
                    tint = Accent,
                )
            }
        }
    }
    Spacer(modifier = Modifier.size(12.dp))

    // 重要说明警告卡片
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = CardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, Error),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(
                text = "重要说明",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Error,
                fontFamily = FontFamily.Default,
            )
            Spacer(modifier = Modifier.size(4.dp))
            Text(
                text = "手机恢复出厂设置后，设备识别码将改变，重新安装将导致 App 不可用。在使用有效期内联系作者，仅可获得一次重新激活机会。",
                fontSize = 12.sp,
                color = TextSecondary,
                fontFamily = FontFamily.Default,
            )
        }
    }
    Spacer(modifier = Modifier.size(24.dp))

    // 联系方式
    ContactInfo()
}

/**
 * 已到期内容：标题 + 提示 + 联系方式。
 */
@Composable
private fun ExpiredContent() {
    // 标题
    Text(
        text = "体验已到期",
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        color = TextColor,
        fontFamily = FontFamily.Default,
    )
    Spacer(modifier = Modifier.size(12.dp))

    // 提示
    Text(
        text = "请联系作者续费使用",
        fontSize = 14.sp,
        color = TextSecondary,
        fontFamily = FontFamily.Default,
    )
    Spacer(modifier = Modifier.size(24.dp))

    // 联系方式
    ContactInfo()
}

/**
 * 联系方式卡片。
 */
@Composable
private fun ContactInfo() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = CardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "联系方式",
                fontSize = 12.sp,
                color = TextSecondary,
                fontFamily = FontFamily.Default,
            )
            Spacer(modifier = Modifier.size(4.dp))
            Text(
                text = "15940454123（微信同）",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Accent,
                fontFamily = FontFamily.Default,
            )
        }
    }
}

/**
 * 复制文本到系统剪贴板并提示。
 */
private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("设备识别码", text))
    Toast.makeText(context, "已复制设备识别码", Toast.LENGTH_SHORT).show()
}
