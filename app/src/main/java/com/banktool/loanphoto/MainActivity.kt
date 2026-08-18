package com.banktool.loanphoto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.banktool.loanphoto.data.license.LicenseChecker
import com.banktool.loanphoto.data.security.SecurityChecker
import com.banktool.loanphoto.nav.LoanPhotoNavHost
import com.banktool.loanphoto.ui.license.LockScreen
import com.banktool.loanphoto.ui.license.LockState
import com.banktool.loanphoto.ui.theme.LoanPhotoAppTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 主 Activity
 * - Compose 入口
 * - Hilt 注入
 * - 竖屏锁定（在 AndroidManifest 中配置）
 * - adjustResize 键盘适配（在 AndroidManifest 中配置）
 * - 授权校验：LicenseChecker 恒授权，仅安全校验失败时展示 [LockScreen]，阻止进入主界面
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var licenseChecker: LicenseChecker

    @Inject
    lateinit var securityChecker: SecurityChecker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 安全校验：Application 阶段已校验过则跳过，否则再次校验
        // （securityFailed = true 时不再重复 verify，保留 Application 阶段的失败原因）
        val securityOk = if (LoanPhotoApplication.securityFailed) {
            false
        } else {
            securityChecker.verify(this)
        }

        // 授权校验（Settings.Secure.getString 是快速操作，可在主线程同步调用）
        val isAuthorized = securityOk && licenseChecker.isAuthorized(this)
        val lockState = if (!isAuthorized && licenseChecker.isExpired()) {
            LockState.EXPIRED
        } else {
            LockState.UNAUTHORIZED
        }
        val deviceId = licenseChecker.getDeviceId(this)
        val deviceInfo = licenseChecker.getDeviceInfo()

        // 安全校验失败时的额外提示
        val extraMessage = if (!securityOk) {
            "应用完整性校验失败：" +
                (LoanPhotoApplication.securityFailReason.ifBlank { securityChecker.getFailReason() })
        } else {
            null
        }

        setContent {
            LoanPhotoAppTheme {
                if (isAuthorized) {
                    LoanPhotoNavHost()
                } else {
                    LockScreen(
                        lockState = lockState,
                        deviceId = deviceId,
                        deviceInfo = deviceInfo,
                        extraMessage = extraMessage,
                    )
                }
            }
        }
    }
}
