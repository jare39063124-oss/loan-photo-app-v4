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
import com.banktool.loanphoto.data.camera.WatermarkSegmentSetting
import com.banktool.loanphoto.data.camera.WatermarkSource
import com.banktool.loanphoto.data.camera.defaultWatermarkSegmentSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 水印配置仓库。
 *
 * 使用 [preferencesDataStore] 持久化水印配置：
 * - enabled（启用水印，默认 true）
 * - fontSize（字号，默认 [WatermarkFontSize.MEDIUM]）
 * - position（位置，默认 [WatermarkPosition.BOTTOM_RIGHT]）
 * - opacity（不透明度，默认 0.7f）
 * - segment_settings（4 槽段配置 JSON 数组，见 [serializeSegmentSettings]）
 * - showDate / showSerial / showAddress / showLatlng（旧水印内容逐项显示开关，仅作迁移来源，保留兼容）
 *
 * `segments` / `slotLines` 字段不在持久化范围（由 [com.banktool.loanphoto.data.camera.WatermarkGenerator]
 * 在拍照时实时构建），读取配置时返回空列表 / null，由调用方覆盖。
 *
 * 段配置迁移：`segment_settings` 缺失或解析失败时，由旧 `show_*` 布尔迁移
 * （槽1=DATE/OFF、槽2=SERIAL/OFF、槽3=ADDRESS/OFF、槽4=LATLNG/OFF），不回写旧键。
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
     *
     * 段配置（[WatermarkConfig.segmentSettings]）永不为 null：
     * `segment_settings` 存在且解析成功 → 解析值（归一化到 4 槽，不足补 OFF、超出截断）；
     * 缺失或解析失败 → 由旧 `show_*` 布尔迁移（不回写旧键）。
     */
    fun configFlow(): Flow<WatermarkConfig> = context.watermarkDataStore.data.map { prefs ->
        val persisted = prefs[KEY_SEGMENT_SETTINGS]?.let { parseSegmentSettings(it) }
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
            segmentSettings = persisted?.let { normalizeSegmentSettings(it) }
                ?: migrateLegacySegmentSettings(prefs),
        )
    }

    /**
     * 当前段配置快照（一次性读取）。
     */
    suspend fun getSegmentSettings(): List<WatermarkSegmentSetting> =
        configFlow().first().segmentSettings ?: defaultWatermarkSegmentSettings()

    /**
     * 设置某个槽位的段配置（写整个 JSON 数组）。
     *
     * 读取当前持久化值（缺失/损坏时按旧键迁移），替换 [index] 槽位后整体回写，
     * 保证其余槽位不被破坏。
     *
     * @param index 槽位索引（0..3）
     * @param setting 新的段配置
     */
    suspend fun setSegmentSetting(index: Int, setting: WatermarkSegmentSetting) {
        require(index in 0..3) { "segment index must be 0..3, got $index" }
        withContext(Dispatchers.IO) {
            context.watermarkDataStore.edit { prefs ->
                val current = prefs[KEY_SEGMENT_SETTINGS]?.let { parseSegmentSettings(it) }
                    ?.let { normalizeSegmentSettings(it) }
                    ?: migrateLegacySegmentSettings(prefs)
                val updated = current.toMutableList().also { it[index] = setting }
                prefs[KEY_SEGMENT_SETTINGS] = serializeSegmentSettings(updated)
            }
        }
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

    /**
     * 设置是否显示拍摄日期段（旧 4 开关路径，设置页已改用 [setSegmentSetting]，保留兼容）。
     */
    suspend fun setShowDate(v: Boolean) {
        withContext(Dispatchers.IO) {
            context.watermarkDataStore.edit { prefs -> prefs[KEY_SHOW_DATE] = v }
        }
    }

    /**
     * 设置是否显示序号段（旧 4 开关路径，设置页已改用 [setSegmentSetting]，保留兼容）。
     */
    suspend fun setShowSerial(v: Boolean) {
        withContext(Dispatchers.IO) {
            context.watermarkDataStore.edit { prefs -> prefs[KEY_SHOW_SERIAL] = v }
        }
    }

    /**
     * 设置是否显示地址段（旧 4 开关路径，设置页已改用 [setSegmentSetting]，保留兼容）。
     */
    suspend fun setShowAddress(v: Boolean) {
        withContext(Dispatchers.IO) {
            context.watermarkDataStore.edit { prefs -> prefs[KEY_SHOW_ADDRESS] = v }
        }
    }

    /**
     * 设置是否显示经纬度段（旧 4 开关路径，设置页已改用 [setSegmentSetting]，保留兼容）。
     */
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
        val KEY_SEGMENT_SETTINGS = stringPreferencesKey("segment_settings")

        /** 固定槽位数。 */
        const val SEGMENT_SLOT_COUNT = 4

        /**
         * 解析 `segment_settings` JSON 数组为段配置列表。
         *
         * null/空串/解析失败返回 null（调用方走迁移路径）；单项 source 枚举名无法识别时
         * 回退 [WatermarkSource.OFF]，customText 缺失回退空串。
         */
        fun parseSegmentSettings(json: String?): List<WatermarkSegmentSetting>? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    val source = runCatching { WatermarkSource.valueOf(obj.getString("source")) }
                        .getOrDefault(WatermarkSource.OFF)
                    WatermarkSegmentSetting(source, obj.optString("customText", ""))
                }
            }.getOrNull()
        }

        /** 序列化段配置为 JSON 数组 `[{"source":"DATE","customText":""},...]`。 */
        fun serializeSegmentSettings(settings: List<WatermarkSegmentSetting>): String {
            val arr = JSONArray()
            for (s in settings) {
                arr.put(
                    JSONObject()
                        .put("source", s.source.name)
                        .put("customText", s.customText),
                )
            }
            return arr.toString()
        }

        /** 归一化到 [SEGMENT_SLOT_COUNT] 槽：不足补 OFF，超出截断。 */
        fun normalizeSegmentSettings(
            settings: List<WatermarkSegmentSetting>,
        ): List<WatermarkSegmentSetting> =
            List(SEGMENT_SLOT_COUNT) { i ->
                settings.getOrNull(i) ?: WatermarkSegmentSetting(WatermarkSource.OFF)
            }

        /**
         * 由旧 `show_*` 布尔迁移段配置：
         * 槽1=DATE/OFF(showDate)、槽2=SERIAL/OFF(showSerial)、槽3=ADDRESS/OFF(showAddress)、
         * 槽4=LATLNG/OFF(showLatlng)。不回写旧键（首次在设置页修改段配置时才落盘新键）。
         */
        fun migrateLegacySegmentSettings(prefs: Preferences): List<WatermarkSegmentSetting> =
            listOf(
                if (prefs[KEY_SHOW_DATE] ?: true) WatermarkSource.DATE else WatermarkSource.OFF,
                if (prefs[KEY_SHOW_SERIAL] ?: true) WatermarkSource.SERIAL else WatermarkSource.OFF,
                if (prefs[KEY_SHOW_ADDRESS] ?: true) WatermarkSource.ADDRESS else WatermarkSource.OFF,
                if (prefs[KEY_SHOW_LATNG] ?: true) WatermarkSource.LATLNG else WatermarkSource.OFF,
            ).map { WatermarkSegmentSetting(it) }
    }
}
