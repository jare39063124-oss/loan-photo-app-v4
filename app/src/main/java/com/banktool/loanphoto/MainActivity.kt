package com.banktool.loanphoto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.banktool.loanphoto.nav.LoanPhotoNavHost
import com.banktool.loanphoto.ui.theme.LoanPhotoAppTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * 主 Activity
 * - Compose 入口
 * - Hilt 注入
 * - 竖屏锁定（在 AndroidManifest 中配置）
 * - adjustResize 键盘适配（在 AndroidManifest 中配置）
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LoanPhotoAppTheme {
                LoanPhotoNavHost()
            }
        }
    }
}
