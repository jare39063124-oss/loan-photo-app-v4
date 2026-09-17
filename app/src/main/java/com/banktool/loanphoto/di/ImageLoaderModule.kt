package com.banktool.loanphoto.di

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Coil [ImageLoader] Hilt Module。
 *
 * 内存缓存约相当于 256 张缩略图，磁盘缓存 100MB，开启 crossfade，
 * 适用于缩略图列表与全屏预览的快速加载。
 */
@Module
@InstallIn(SingletonComponent::class)
object ImageLoaderModule {

    @Provides
    @Singleton
    fun provideImageLoader(@ApplicationContext ctx: Context): ImageLoader =
        ImageLoader.Builder(ctx)
            .memoryCache {
                MemoryCache.Builder(ctx)
                    // 等价于约 256 张缩略图（~200KB/张）
                    .maxSizeBytes(MAX_MEMORY_BYTES)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(ctx.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(MAX_DISK_BYTES)
                    .build()
            }
            .crossfade(true)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()

    // 256 张缩略图 ≈ 50MB（按 200KB/张估算）
    private const val MAX_MEMORY_BYTES = 50 * 1024 * 1024
    private const val MAX_DISK_BYTES: Long = 100L * 1024 * 1024 // 100 MB
}
