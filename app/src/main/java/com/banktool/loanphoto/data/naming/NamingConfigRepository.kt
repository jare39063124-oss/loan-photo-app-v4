package com.banktool.loanphoto.data.naming

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 命名规则配置仓库。
 *
 * 使用 DataStore Preferences 持久化 4 段命名配置（段1~段4）及其 CUSTOM 自定义文本
 * （`naming_segment_1_custom`..`naming_segment_4_custom`）。
 * 每段以 [NameSegment] 枚举名持久化，缺失时回退 [NameSegment.NONE]；
 * custom key 缺失时回退空串。
 *
 * 所有 IO 操作在 [Dispatchers.IO] 上执行。
 */
@Singleton
class NamingConfigRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    /**
     * 暴露命名配置 [Flow]，供 Composable collectAsState 实时响应变更。
     *
     * DataStore 中未持久化的段（prefs[key] 为 null）回退到 [NamingConfig] 的默认值
     * （v4.0.3 起 `[DATE, BORROWER, ADDRESS, NONE]`），而非 [NameSegment.NONE]。
     * 已持久化的段（用户在设置页配置过）优先使用持久化值，保证向后兼容。
     */
    fun configFlow(): Flow<NamingConfig> = dataStore.data.map { prefs ->
        val default = NamingConfig()
        NamingConfig(
            segment1 = prefs[KEY_SEGMENT_1]?.let { NameSegment.fromName(it) } ?: default.segment1,
            segment2 = prefs[KEY_SEGMENT_2]?.let { NameSegment.fromName(it) } ?: default.segment2,
            segment3 = prefs[KEY_SEGMENT_3]?.let { NameSegment.fromName(it) } ?: default.segment3,
            segment4 = prefs[KEY_SEGMENT_4]?.let { NameSegment.fromName(it) } ?: default.segment4,
            customText1 = prefs[KEY_CUSTOM_1] ?: "",
            customText2 = prefs[KEY_CUSTOM_2] ?: "",
            customText3 = prefs[KEY_CUSTOM_3] ?: "",
            customText4 = prefs[KEY_CUSTOM_4] ?: "",
        )
    }

    /**
     * 一次性读取当前命名配置。
     */
    suspend fun getConfig(): NamingConfig = withContext(Dispatchers.IO) {
        configFlow().first()
    }

    /**
     * 设置某一段配置。
     *
     * 切换到非 [NameSegment.CUSTOM] 段时清空该段的 custom key
     * （避免残留文本在后续再切回 CUSTOM 时意外生效）。
     *
     * @param index 段索引（0..3，分别对应段1~段4）
     * @param segment 新的段类型
     */
    suspend fun setSegment(index: Int, segment: NameSegment) {
        require(index in 0..3) { "segment index must be 0..3, got $index" }
        val key = segmentKeyFor(index)
        val customKey = customKeyFor(index)
        withContext(Dispatchers.IO) {
            dataStore.edit { prefs ->
                prefs[key] = segment.name
                if (segment != NameSegment.CUSTOM) {
                    prefs.remove(customKey)
                }
            }
        }
    }

    /**
     * 设置某一段的 CUSTOM 自定义文本。
     *
     * @param index 段索引（0..3）
     * @param text 自定义文本（原样持久化，空白段在生成时被跳过）
     */
    suspend fun setCustomText(index: Int, text: String) {
        require(index in 0..3) { "segment index must be 0..3, got $index" }
        withContext(Dispatchers.IO) {
            dataStore.edit { prefs ->
                prefs[customKeyFor(index)] = text
            }
        }
    }

    private fun segmentKeyFor(index: Int): Preferences.Key<String> = when (index) {
        0 -> KEY_SEGMENT_1
        1 -> KEY_SEGMENT_2
        2 -> KEY_SEGMENT_3
        else -> KEY_SEGMENT_4
    }

    private fun customKeyFor(index: Int): Preferences.Key<String> = when (index) {
        0 -> KEY_CUSTOM_1
        1 -> KEY_CUSTOM_2
        2 -> KEY_CUSTOM_3
        else -> KEY_CUSTOM_4
    }

    private companion object {
        val KEY_SEGMENT_1 = stringPreferencesKey("naming_segment_1")
        val KEY_SEGMENT_2 = stringPreferencesKey("naming_segment_2")
        val KEY_SEGMENT_3 = stringPreferencesKey("naming_segment_3")
        val KEY_SEGMENT_4 = stringPreferencesKey("naming_segment_4")
        val KEY_CUSTOM_1 = stringPreferencesKey("naming_segment_1_custom")
        val KEY_CUSTOM_2 = stringPreferencesKey("naming_segment_2_custom")
        val KEY_CUSTOM_3 = stringPreferencesKey("naming_segment_3_custom")
        val KEY_CUSTOM_4 = stringPreferencesKey("naming_segment_4_custom")
    }
}
