package com.banktool.loanphoto.di

import android.content.Context
import com.banktool.loanphoto.camera.engine.Camera2CameraEngine
import com.banktool.loanphoto.camera.engine.CameraEngine
import com.banktool.loanphoto.camera.engine.CameraxCameraEngine
import com.banktool.loanphoto.util.DeviceDetector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 相机引擎 Hilt 绑定（main 源集，trial/full 共用）。
 *
 * 运行时按 [DeviceDetector.isHuaweiDevice] 选择实现：华为/荣耀 → Camera2CameraEngine
 * （支持物理子相机真广角），其他机型 → CameraxCameraEngine。
 */
@Module
@InstallIn(SingletonComponent::class)
object CameraEngineModule {
    @Provides
    @Singleton
    fun provideCameraEngine(@ApplicationContext ctx: Context): CameraEngine =
        if (DeviceDetector.isHuaweiDevice()) Camera2CameraEngine(ctx) else CameraxCameraEngine(ctx)
}
