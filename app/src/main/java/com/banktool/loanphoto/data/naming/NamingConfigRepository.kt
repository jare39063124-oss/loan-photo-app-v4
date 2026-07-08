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
 * 使用 DataStore Preferences 持久化 4 段命名配置（段1~段4）。
 * 每段以 [NameSegment] 枚举名持久化，缺失时回退 [NameSegment.NONE]。
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
     * @param index 段索引（0..3，分别对应段1~段4）
     * @param segment 新的段类型
     */
    suspend fun setSegment(index: Int, segment: NameSegment) {
        require(index in 0..3) { "segment index must be 0..3, got $index" }
        val key = when (index) {
            0 -> KEY_SEGMENT_1
            1 -> KEY_SEGMENT_2
            2 -> KEY_SEGMENT_3
            else -> KEY_SEGMENT_4
        }
        withContext(Dispatchers.IO) {
            dataStore.edit { prefs ->
                prefs[key] = segment.name
            }
        }
    }

    private companion object {
        val KEY_SEGMENT_1 = stringPreferencesKey("naming_segment_1")
        val KEY_SEGMENT_2 = stringPreferencesKey("naming_segment_2")
        val KEY_SEGMENT_3 = stringPreferencesKey("naming_segment_3")
        val KEY_SEGMENT_4 = stringPreferencesKey("naming_segment_4")
    }
}
