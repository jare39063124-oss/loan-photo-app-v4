package com.banktool.loanphoto

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.banktool.loanphoto.data.camera.CameraSessionRecovery
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

        // Phase 2: 检查未完成的 camera_session.json 并恢复
        cameraSessionRecovery.checkAndRecover()
    }
}
