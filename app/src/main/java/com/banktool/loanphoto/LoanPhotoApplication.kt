package com.banktool.loanphoto

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.banktool.loanphoto.data.camera.CameraSessionRecovery
import com.banktool.loanphoto.data.log.CrashLogger
import com.banktool.loanphoto.data.log.FileLoggingTree
import com.banktool.loanphoto.data.security.SecurityChecker
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

/** Application 入口：Hilt 装配、WorkManager 配置、日志初始化与相机会话恢复。 */
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
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // 文件日志 + 崩溃捕获：按设置页「启用日志记录」开关装配
        // 采用同步读取，保证杀进程重启后崩溃捕获在应用入口即就绪
        val logEnabled = getSharedPreferences("log_prefs", MODE_PRIVATE)
            .getBoolean("log_enabled", false)
        if (logEnabled) {
            Timber.plant(FileLoggingTree(this))
            CrashLogger.install(this)
            Timber.i("File logging & crash handler installed (log_enabled=true)")
        }

        Timber.i("LoanPhotoApplication onCreate, version=${BuildConfig.VERSION_NAME}")

        // 启动时执行完整性校验（签名 / 调试器 / Frida 检测）
        // 不在此处崩溃，仅记录标志供 MainActivity 读取并展示 LockScreen
        if (!securityChecker.verify(this)) {
            securityFailed = true
            securityFailReason = securityChecker.getFailReason()
            Timber.w("Security check failed at Application: %s", securityFailReason)
        }

        // 检查并恢复未完成的拍摄会话
        cameraSessionRecovery.checkAndRecover()
    }
}
