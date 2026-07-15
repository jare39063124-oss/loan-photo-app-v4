package com.banktool.loanphoto.data.watermark

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.banktool.loanphoto.data.camera.PhotoQuality
import com.banktool.loanphoto.data.camera.WatermarkConfig
import com.banktool.loanphoto.data.camera.WatermarkFontSize
import com.banktool.loanphoto.data.camera.WatermarkPosition
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 水印配置仓库。
 *
 * 使用 [preferencesDataStore] 持久化水印 8 项配置：
 * - enabled（启用水印，默认 true）
 * - fontSize（字号，默认 [WatermarkFontSize.MEDIUM]）
 * - position（位置，默认 [WatermarkPosition.BOTTOM_RIGHT]）
 * - opacity（不透明度，默认 0.7f）
 * - showDate / showSerial / showAddress / showLatlng（水印内容逐项显示开关，默认全 true）
 *
 * `segments` 字段不在持久化范围（由 [com.banktool.loanphoto.data.camera.WatermarkGenerator]
 * 在拍照时实时构建），读取配置时返回空列表，由调用方覆盖。
 *
 * 与命名规则 DataStore（`naming.preferences_pb`）相互独立，文件名 `watermark.preferences_pb`。
 * 所有 IO 操作在 [Dispatchers.IO] 上执行。
 */
private val Context.watermarkDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "watermark",
)

@Singleton
class WatermarkConfigRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * 暴露水印配置 [Flow]，供 Composable collectAsState 实时响应变更。
     *
     * DataStore 中未持久化的字段回退到 [WatermarkConfig] 的默认值；
     * 枚举转换失败时用 [runCatching] 安全回退，避免抛异常中断流。
     */
    fun configFlow(): Flow<WatermarkConfig> = context.watermarkDataStore.data.map { prefs ->
        WatermarkConfig(
            segments = emptyList(),
            enabled = prefs[KEY_ENABLED] ?: true,
            fontSize = prefs[KEY_FONT_SIZE]
                ?.let { runCatching { WatermarkFontSize.valueOf(it) }.getOrNull() }
                ?: WatermarkFontSize.MEDIUM,
            position = prefs[KEY_POSITION]
                ?.let { runCatching { WatermarkPosition.valueOf(it) }.getOrNull() }
                ?: WatermarkPosition.BOTTOM_RIGHT,
            opacity = prefs[KEY_OPACITY] ?: 0.7f,
            showDate = prefs[KEY_SHOW_DATE] ?: true,
            showSerial = prefs[KEY_SHOW_SERIAL] ?: true,
            showAddress = prefs[KEY_SHOW_ADDRESS] ?: true,
            showLatlng = prefs[KEY_SHOW_LATNG] ?: true,
        )
    }

    /** 启用 / 关闭水印。 */
    suspend fun setEnabled(v: Boolean) {
        withContext(Dispatchers.IO) {
            context.watermarkDataStore.edit { prefs -> prefs[KEY_ENABLED] = v }
        }
    }

    /** 设置水印字号。 */
    suspend fun setFontSize(v: WatermarkFontSize) {
        withContext(Dispatchers.IO) {
            context.watermarkDataStore.edit { prefs -> prefs[KEY_FONT_SIZE] = v.name }
        }
    }

    /** 设置水印位置。 */
    suspend fun setPosition(v: WatermarkPosition) {
        withContext(Dispatchers.IO) {
            context.watermarkDataStore.edit { prefs -> prefs[KEY_POSITION] = v.name }
        }
    }

    /** 设置水印不透明度。 */
    suspend fun setOpacity(v: Float) {
        withContext(Dispatchers.IO) {
            context.watermarkDataStore.edit { prefs -> prefs[KEY_OPACITY] = v }
        }
    }

    /** 设置是否显示拍摄日期段。 */
    suspend fun setShowDate(v: Boolean) {
        withContext(Dispatchers.IO) {
            context.watermarkDataStore.edit { prefs -> prefs[KEY_SHOW_DATE] = v }
        }
    }

    /** 设置是否显示序号段。 */
    suspend fun setShowSerial(v: Boolean) {
        withContext(Dispatchers.IO) {
            context.watermarkDataStore.edit { prefs -> prefs[KEY_SHOW_SERIAL] = v }
        }
    }

    /** 设置是否显示地址段。 */
    suspend fun setShowAddress(v: Boolean) {
        withContext(Dispatchers.IO) {
            context.watermarkDataStore.edit { prefs -> prefs[KEY_SHOW_ADDRESS] = v }
        }
    }

    /** 设置是否显示经纬度段。 */
    suspend fun setShowLatlng(v: Boolean) {
        withContext(Dispatchers.IO) {
            context.watermarkDataStore.edit { prefs -> prefs[KEY_SHOW_LATNG] = v }
        }
    }

    /**
     * 暴露照片质量 [Flow]，供 Composable collectAsState 实时响应变更。
     *
     * 持久化枚举名（HIGH/MEDIUM/LOW），缺失或解析失败时回退 [PhotoQuality.HIGH]。
     */
    fun getPhotoQuality(): Flow<PhotoQuality> = context.watermarkDataStore.data.map { prefs ->
        prefs[KEY_PHOTO_QUALITY]
            ?.let { runCatching { PhotoQuality.valueOf(it) }.getOrNull() }
            ?: PhotoQuality.HIGH
    }

    /** 设置照片质量等级。 */
    suspend fun setPhotoQuality(quality: PhotoQuality) {
        withContext(Dispatchers.IO) {
            context.watermarkDataStore.edit { prefs -> prefs[KEY_PHOTO_QUALITY] = quality.name }
        }
    }

    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("enabled")
        val KEY_FONT_SIZE = stringPreferencesKey("font_size")
        val KEY_POSITION = stringPreferencesKey("position")
        val KEY_OPACITY = floatPreferencesKey("opacity")
        val KEY_SHOW_DATE = booleanPreferencesKey("show_date")
        val KEY_SHOW_SERIAL = booleanPreferencesKey("show_serial")
        val KEY_SHOW_ADDRESS = booleanPreferencesKey("show_address")
        val KEY_SHOW_LATNG = booleanPreferencesKey("show_latlng")
        val KEY_PHOTO_QUALITY = stringPreferencesKey("photo_quality")
    }
}
