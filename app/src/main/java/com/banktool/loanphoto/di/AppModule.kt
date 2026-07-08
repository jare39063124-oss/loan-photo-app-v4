package com.banktool.loanphoto.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.banktool.loanphoto.data.datasource.ExcelDataSource
import com.banktool.loanphoto.data.datasource.ExcelWriter
import com.banktool.loanphoto.data.datasource.ProgressFileDataSource
import com.banktool.loanphoto.data.repository.ProgressRepositoryImpl
import com.banktool.loanphoto.data.repository.SpecialLogRepositoryImpl
import com.banktool.loanphoto.data.repository.VisitNoteRepositoryImpl
import com.banktool.loanphoto.data.naming.NamingConfigRepository
import com.banktool.loanphoto.data.naming.NamingRuleGenerator
import com.banktool.loanphoto.data.repository.CameraSessionRepositoryImpl
import com.banktool.loanphoto.data.repository.ExcelDataIndexRepositoryImpl
import com.banktool.loanphoto.data.repository.ExcelRepositoryImpl
import com.banktool.loanphoto.domain.repository.CameraSessionRepository
import com.banktool.loanphoto.domain.repository.ExcelDataIndexRepository
import com.banktool.loanphoto.domain.repository.ExcelRepository
import com.banktool.loanphoto.domain.repository.ProgressRepository
import com.banktool.loanphoto.domain.repository.SpecialLogRepository
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
 * - 仓库实现: Excel / Progress / CameraSession / VisitNote / SpecialLog / ExcelDataIndex
 * - DataStore Preferences（命名规则配置持久化）
 *
 * [NamingConfigRepository] 与 [NamingRuleGenerator] 通过 @Inject constructor 自动注入，
 * 仅需在此提供 [DataStore] 实例。所有均 @Singleton，仓库绑定到 domain 层接口。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // ---- DataStore ----

    /**
     * 提供命名规则专用的 DataStore<Preferences>（文件名 `naming.preferences_pb`）。
     *
     * 与 [RecentFilesStorage] 使用的 `recent_excel_files` DataStore 相互独立，
     * 不会产生冲突。
     */
    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile("naming") },
        )

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
    fun provideExcelRepository(
        ds: ExcelDataSource,
        excelWriter: ExcelWriter,
    ): ExcelRepository = ExcelRepositoryImpl(ds, excelWriter)

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
    fun provideSpecialLogRepository(@ApplicationContext ctx: Context): SpecialLogRepository =
        SpecialLogRepositoryImpl(ctx)

    @Provides
    @Singleton
    fun provideExcelDataIndexRepository(@ApplicationContext ctx: Context): ExcelDataIndexRepository =
        ExcelDataIndexRepositoryImpl(ctx)
}
