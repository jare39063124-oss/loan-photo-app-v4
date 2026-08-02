package com.banktool.loanphoto.di

import com.banktool.loanphoto.BuildConfig
import com.banktool.loanphoto.data.api.DeepSeekApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1/"
    private const val HTTP_REFERER = "https://github.com/jare39063124-oss/loan-photo-app-v4"

    @Provides
    @Singleton
    fun provideMoshi(): Moshi {
        return Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
    }

    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader(
                        "Authorization",
                        "Bearer ${BuildConfig.OPENROUTER_API_KEY}"
                    )
                    .addHeader("Content-Type", "application/json")
                    // OpenRouter 推荐头：HTTP-Referer 用于应用识别，X-Title 在 OpenRouter 控制台展示
                    .addHeader("HTTP-Referer", HTTP_REFERER)
                    .addHeader("X-Title", "LoanPhotoApp")
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideDeepSeekApi(
        okHttpClient: OkHttpClient,
        moshi: Moshi
    ): DeepSeekApi {
        return Retrofit.Builder()
            .baseUrl(OPENROUTER_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(DeepSeekApi::class.java)
    }
}
