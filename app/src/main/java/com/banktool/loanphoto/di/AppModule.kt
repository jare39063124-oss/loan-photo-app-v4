package com.banktool.loanphoto.di

import android.content.Context
import com.banktool.loanphoto.data.datasource.ExcelDataSource
import com.banktool.loanphoto.data.datasource.ExcelWriter
import com.banktool.loanphoto.data.datasource.ProgressFileDataSource
import com.banktool.loanphoto.data.repository.CameraSessionRepositoryImpl
import com.banktool.loanphoto.data.repository.ExcelDataIndexRepositoryImpl
import com.banktool.loanphoto.data.repository.ExcelRepositoryImpl
import com.banktool.loanphoto.data.repository.ProgressRepositoryImpl
import com.banktool.loanphoto.data.repository.VisitNoteRepositoryImpl
import com.banktool.loanphoto.domain.repository.CameraSessionRepository
import com.banktool.loanphoto.domain.repository.ExcelDataIndexRepository
import com.banktool.loanphoto.domain.repository.ExcelRepository
import com.banktool.loanphoto.domain.repository.ProgressRepository
import com.banktool.loanphoto.domain.repository.VisitNoteRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt 全局依赖注入 Module。
 *
 * 提供：
 * - 数据源: [ExcelDataSource]、[ProgressFileDataSource]
 * - 仓库实现: Excel / Progress / CameraSession / VisitNote / ExcelDataIndex
 *
 * 所有均 @Singleton，仓库绑定到 domain 层接口。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // ---- DataSource ----

    @Provides
    @Singleton
    fun provideExcelDataSource(@ApplicationContext ctx: Context): ExcelDataSource =
        ExcelDataSource(ctx)

    @Provides
    @Singleton
    fun provideProgressFileDataSource(@ApplicationContext ctx: Context): ProgressFileDataSource =
        ProgressFileDataSource(ctx)

    @Provides
    @Singleton
    fun provideExcelWriter(@ApplicationContext ctx: Context): ExcelWriter =
        ExcelWriter(ctx)

    // ---- Repository ----

    @Provides
    @Singleton
    fun provideExcelRepository(ds: ExcelDataSource): ExcelRepository =
        ExcelRepositoryImpl(ds)

    @Provides
    @Singleton
    fun provideProgressRepository(ds: ProgressFileDataSource): ProgressRepository =
        ProgressRepositoryImpl(ds)

    @Provides
    @Singleton
    fun provideCameraSessionRepository(@ApplicationContext ctx: Context): CameraSessionRepository =
        CameraSessionRepositoryImpl(ctx)

    @Provides
    @Singleton
    fun provideVisitNoteRepository(@ApplicationContext ctx: Context): VisitNoteRepository =
        VisitNoteRepositoryImpl(ctx)

    @Provides
    @Singleton
    fun provideExcelDataIndexRepository(@ApplicationContext ctx: Context): ExcelDataIndexRepository =
        ExcelDataIndexRepositoryImpl(ctx)
}
