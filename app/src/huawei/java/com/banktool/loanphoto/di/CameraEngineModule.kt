package com.banktool.loanphoto.di

import com.banktool.loanphoto.camera.engine.Camera2CameraEngine
import com.banktool.loanphoto.camera.engine.CameraEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * huawei flavor 专用的相机引擎 Hilt 模块。
 *
 * 将 [Camera2CameraEngine]（基于 Camera2，支持华为 Mate70 真超广角）绑定到共享的 [CameraEngine] 接口，
 * 供 [com.banktool.loanphoto.ui.camera.CameraViewModel] 注入。
 *
 * 注意：trial/full 源集需各自提供一个绑定 CameraX 引擎实现的同名模块，避免 main 中出现重复绑定。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CameraEngineModule {
    @Binds
    @Singleton
    abstract fun bindCameraEngine(impl: Camera2CameraEngine): CameraEngine
}
