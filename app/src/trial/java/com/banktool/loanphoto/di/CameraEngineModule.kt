package com.banktool.loanphoto.di

import com.banktool.loanphoto.camera.engine.CameraEngine
import com.banktool.loanphoto.camera.engine.CameraxCameraEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CameraEngineModule {
    @Binds
    @Singleton
    abstract fun bindCameraEngine(impl: CameraxCameraEngine): CameraEngine
}
