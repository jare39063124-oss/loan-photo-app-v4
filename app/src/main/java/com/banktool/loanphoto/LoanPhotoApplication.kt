package com.banktool.loanphoto

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.banktool.loanphoto.data.camera.CameraSessionRecovery
import com.banktool.loanphoto.data.security.SecurityChecker
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

/**
 * Application 主类
 * - Hilt 依赖注入入口
 * - WorkManager 配置
 * - Timber 日志
 * - 相机会话恢复（camera_session.json 检查）
 */
@HiltAndroidApp
class LoanPhotoApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var cameraSessionRecovery: CameraSessionRecovery

    @Inject
    lateinit var securityChecker: SecurityChecker

    companion object {
        /** 应用完整性校验是否失败（供 MainActivity 读取，决定是否展示 LockScreen）。 */
        @Volatile
        var securityFailed: Boolean = false

        /** 应用完整性校验失败原因（供 MainActivity 透传到 LockScreen）。 */
        var securityFailReason: String = ""
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Timber 日志（仅 Debug 模式）
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.i("LoanPhotoApplication onCreate, version=${BuildConfig.VERSION_NAME}")

        // 运行时抗逆向：启动时执行完整性校验（签名 / 调试器 / Frida）
        // 不在此处崩溃，仅记录标志供 MainActivity 读取并展示 LockScreen
        if (!securityChecker.verify(this)) {
            securityFailed = true
            securityFailReason = securityChecker.getFailReason()
            Timber.w("Security check failed at Application: %s", securityFailReason)
        }

        // Phase 2: 检查未完成的 camera_session.json 并恢复
        cameraSessionRecovery.checkAndRecover()
    }
}
