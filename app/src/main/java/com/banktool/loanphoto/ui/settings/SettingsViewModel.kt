package com.banktool.loanphoto.ui.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.banktool.loanphoto.data.camera.PhotoQuality
import com.banktool.loanphoto.data.camera.WatermarkConfig
import com.banktool.loanphoto.data.camera.WatermarkFontSize
import com.banktool.loanphoto.data.camera.WatermarkPosition
import com.banktool.loanphoto.data.log.CrashLogger
import com.banktool.loanphoto.data.log.FileLoggingTree
import com.banktool.loanphoto.data.naming.NameSegment
import com.banktool.loanphoto.data.naming.NamingConfig
import com.banktool.loanphoto.data.naming.NamingConfigRepository
import com.banktool.loanphoto.data.naming.NamingRuleGenerator
import com.banktool.loanphoto.data.watermark.WatermarkConfigRepository
import com.banktool.loanphoto.domain.entity.CustomerRow
import com.banktool.loanphoto.domain.entity.PhotoType
import com.banktool.loanphoto.domain.entity.PhotoTypeConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.Calendar
import javax.inject.Inject

/**
 * 相机参考线配置。
 *
 * - [goldenRatioGrid]：黄金分割线（默认开启）
 * - [centerMark]：中心标（默认开启）
 *
 * 持久化到独立 DataStore（`camera_guide.preferences_pb`），与水印 / 命名配置互不影响。
 */
data class GuideLineConfig(
    val goldenRatioGrid: Boolean = true,
    val centerMark: Boolean = true,
)

/** 参考线配置 DataStore（独立于 watermark / naming，避免修改既有仓库）。 */
private val Context.guideLineDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "camera_guide",
)

/** 拍照类型配置 DataStore（独立持久化，清空缓存时不会被清除，保留用户自定义类型）。 */
private val Context.photoTypeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "photo_type_configs",
)

/**
 * 设置页 ViewModel。
 *
 * 职责：
 * 1. 暴露命名规则配置 [namingConfig]（DataStore 持久化，实时响应）
 * 2. 提供段设置入口 [setSegment]
 * 3. 生成实时预览文件名 [previewFileName]
 * 4. 暴露水印配置 [watermarkConfig]（DataStore 持久化，实时响应）
 * 5. 提供水印设置入口 [setWatermarkEnabled] / [setWatermarkFontSize] /
 *    [setWatermarkPosition] / [setWatermarkOpacity] /
 *    [setShowDate] / [setShowSerial] / [setShowAddress] / [setShowLatlng]
 * 6. 暴露相机参考线配置 [guideLineConfig]（DataStore 持久化，实时响应）
 * 7. 提供参考线开关入口 [onGoldenRatioGridChange] / [onCenterMarkChange]
 * 8. 暴露拍照类型配置 [photoTypeConfigs]（DataStore 持久化，实时响应，默认 5 种内置类型）
 * 9. 提供拍照类型管理入口 [onEditPhotoTypeName] / [onAddPhotoType] / [onDeletePhotoType]
 *
 * 注入 [NamingConfigRepository]、[NamingRuleGenerator] 与 [WatermarkConfigRepository]（均 @Singleton）。
 * 参考线配置直接通过应用级 [Context] 持久化到独立 DataStore（`camera_guide`）。
 * 拍照类型配置持久化到独立 DataStore（`photo_type_configs`），用 org.json 序列化为 JSON 字符串。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val namingConfigRepository: NamingConfigRepository,
    private val namingRuleGenerator: NamingRuleGenerator,
    private val watermarkConfigRepository: WatermarkConfigRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** 命名规则配置（初始值全 NONE，订阅 DataStore 后立即更新为持久化值）。 */
    val namingConfig: StateFlow<NamingConfig> = namingConfigRepository.configFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, NamingConfig())

    /** 水印配置（初始值 segments 为空，订阅 DataStore 后立即更新为持久化值）。 */
    val watermarkConfig: StateFlow<WatermarkConfig> = watermarkConfigRepository.configFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, WatermarkConfig(segments = emptyList()))

    /** 照片质量（初始值 HIGH，订阅 DataStore 后立即更新为持久化值）。 */
    val photoQuality: StateFlow<PhotoQuality> = watermarkConfigRepository.getPhotoQuality()
        .stateIn(viewModelScope, SharingStarted.Eagerly, PhotoQuality.HIGH)

    /** 相机参考线配置（初始值两项全开，订阅 DataStore 后立即更新为持久化值）。 */
    val guideLineConfig: StateFlow<GuideLineConfig> = context.guideLineDataStore.data
        .map { prefs ->
            GuideLineConfig(
                goldenRatioGrid = prefs[KEY_GOLDEN_RATIO_GRID] ?: true,
                centerMark = prefs[KEY_CENTER_MARK] ?: true,
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, GuideLineConfig())

    /** 拍照类型配置（初始值 5 种内置类型，订阅 DataStore 后立即更新为持久化值）。 */
    val photoTypeConfigs: StateFlow<List<PhotoTypeConfig>> = context.photoTypeDataStore.data
        .map { prefs -> deserializeConfigs(prefs[KEY_PHOTO_TYPE_CONFIGS]) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PhotoType.DEFAULT_CONFIGS)

    /** logEnabled 的可变后备，供 [toggleLog] 内部更新。 */
    private val _logEnabled = MutableStateFlow(
        context.getSharedPreferences(logPrefsName, Context.MODE_PRIVATE)
            .getBoolean(keyLogEnabled, false),
    )

    /** logFileExists 的可变后备，供 [refreshLogFileExists] 内部更新。 */
    private val _logFileExists = MutableStateFlow(FileLoggingTree.logFile(context).exists())

    /** 当前已 plant 的 [FileLoggingTree] 引用，便于关闭时 uproot。 */
    private var fileLoggingTree: FileLoggingTree? = null

    /** 日志诊断 - 是否启用文件日志（持久化到 SharedPreferences `log_prefs`）。 */
    val logEnabled: StateFlow<Boolean> = _logEnabled

    /** 日志诊断 - app.log 文件是否存在（开启日志并写入后会产生文件）。 */
    val logFileExists: StateFlow<Boolean> = _logFileExists

    /**
     * 设置某一段命名配置并持久化。
     *
     * @param index 段索引（0..3）
     * @param segment 新的段类型
     */
    fun setSegment(index: Int, segment: NameSegment) {
        viewModelScope.launch {
            namingConfigRepository.setSegment(index, segment)
        }
    }

    /** 启用 / 关闭水印。 */
    fun setWatermarkEnabled(v: Boolean) {
        viewModelScope.launch { watermarkConfigRepository.setEnabled(v) }
    }

    /** 设置水印字号。 */
    fun setWatermarkFontSize(v: WatermarkFontSize) {
        viewModelScope.launch { watermarkConfigRepository.setFontSize(v) }
    }

    /** 设置水印位置。 */
    fun setWatermarkPosition(v: WatermarkPosition) {
        viewModelScope.launch { watermarkConfigRepository.setPosition(v) }
    }

    /** 设置水印不透明度。 */
    fun setWatermarkOpacity(v: Float) {
        viewModelScope.launch { watermarkConfigRepository.setOpacity(v) }
    }

    /** 设置是否显示拍摄日期段。 */
    fun setShowDate(v: Boolean) {
        viewModelScope.launch { watermarkConfigRepository.setShowDate(v) }
    }

    /** 设置是否显示序号段。 */
    fun setShowSerial(v: Boolean) {
        viewModelScope.launch { watermarkConfigRepository.setShowSerial(v) }
    }

    /** 设置是否显示地址段。 */
    fun setShowAddress(v: Boolean) {
        viewModelScope.launch { watermarkConfigRepository.setShowAddress(v) }
    }

    /** 设置是否显示经纬度段。 */
    fun setShowLatlng(v: Boolean) {
        viewModelScope.launch { watermarkConfigRepository.setShowLatlng(v) }
    }

    /** 设置照片质量等级。 */
    fun setPhotoQuality(v: PhotoQuality) {
        viewModelScope.launch { watermarkConfigRepository.setPhotoQuality(v) }
    }

    /**
     * 开启 / 关闭文件日志与崩溃捕获。
     *
     * - 写入 SharedPreferences `log_prefs` 持久化开关状态
     * - 开启：plant [FileLoggingTree] 并安装 [CrashLogger]
     * - 关闭：uproot 已 plant 的 Tree 并卸载 [CrashLogger]
     * - 最后刷新 [logFileExists]
     *
     * @param enabled 是否启用日志
     */
    fun toggleLog(enabled: Boolean) {
        context.getSharedPreferences(logPrefsName, Context.MODE_PRIVATE)
            .edit().putBoolean(keyLogEnabled, enabled).apply()
        _logEnabled.value = enabled
        if (enabled) {
            fileLoggingTree = FileLoggingTree(context).also { Timber.plant(it) }
            CrashLogger.install(context)
        } else {
            fileLoggingTree?.let { Timber.uproot(it) }
            fileLoggingTree = null
            CrashLogger.uninstall()
        }
        refreshLogFileExists()
    }

    /** 返回日志文件（不存在返回 null）。 */
    fun getLogFile(): File? = FileLoggingTree.logFile(context).takeIf { it.exists() }

    /**
     * 读取 app.log 末尾 [maxLines] 行（默认 500）。
     *
     * 在 IO 线程执行，避免阻塞主线程；文件不存在时返回空串。
     * 注意 [File.readLines] 会全量读取，5MB 上限内可接受。
     *
     * @param maxLines 返回的最大行数
     * @return 末尾行拼接的字符串（`\n` 分隔），文件不存在时为 ""
     */
    suspend fun getLogTail(maxLines: Int = 500): String = withContext(Dispatchers.IO) {
        val file = FileLoggingTree.logFile(context)
        if (!file.exists()) return@withContext ""
        file.readLines().takeLast(maxLines).joinToString("\n")
    }

    /** 删除日志文件并刷新 [logFileExists]（在 IO 线程执行）。 */
    suspend fun clearLogFile() = withContext(Dispatchers.IO) {
        val file = FileLoggingTree.logFile(context)
        if (file.exists()) file.delete()
        refreshLogFileExists()
    }

    /** 刷新 [logFileExists] 状态（检查 app.log 是否存在）。 */
    private fun refreshLogFileExists() {
        _logFileExists.value = FileLoggingTree.logFile(context).exists()
    }

    /** 开启 / 关闭黄金分割线参考线。 */
    fun onGoldenRatioGridChange(v: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                context.guideLineDataStore.edit { prefs -> prefs[KEY_GOLDEN_RATIO_GRID] = v }
            }
        }
    }

    /** 开启 / 关闭中心标参考线。 */
    fun onCenterMarkChange(v: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                context.guideLineDataStore.edit { prefs -> prefs[KEY_CENTER_MARK] = v }
            }
        }
    }

    /**
     * 修改某个拍照类型的显示名（id 不变，保持配置稳定性）。
     *
     * 空白名会被忽略。新名会同时作为后续文件名片段与 progress.json key 使用；
     * 旧 progress.json 中已存的旧 displayName 记录不受影响（仍可被读取，只是不再匹配新名称）。
     */
    fun onEditPhotoTypeName(config: PhotoTypeConfig, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                context.photoTypeDataStore.edit { prefs ->
                    val current = deserializeConfigs(prefs[KEY_PHOTO_TYPE_CONFIGS])
                    val updated = current.map {
                        if (it.id == config.id) it.copy(displayName = trimmed) else it
                    }
                    prefs[KEY_PHOTO_TYPE_CONFIGS] = serializeConfigs(updated)
                }
            }
        }
    }

    /**
     * 新增一个拍照类型。
     *
     * 空白名会被忽略。id 使用 `"custom_${System.currentTimeMillis()}"` 保证唯一与稳定，
     * 便于后续编辑/删除定位。
     */
    fun onAddPhotoType(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                context.photoTypeDataStore.edit { prefs ->
                    val current = deserializeConfigs(prefs[KEY_PHOTO_TYPE_CONFIGS])
                    val newConfig = PhotoTypeConfig(
                        id = "custom_${System.currentTimeMillis()}",
                        displayName = trimmed,
                    )
                    prefs[KEY_PHOTO_TYPE_CONFIGS] = serializeConfigs(current + newConfig)
                }
            }
        }
    }

    /**
     * 删除一个拍照类型。
     *
     * 至少保留 1 个类型（删除后若列表为空则忽略本次操作）。已拍摄该类型的照片记录不受影响，
     * 仍保留在 progress.json 中。
     */
    fun onDeletePhotoType(config: PhotoTypeConfig) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                context.photoTypeDataStore.edit { prefs ->
                    val current = deserializeConfigs(prefs[KEY_PHOTO_TYPE_CONFIGS])
                    if (current.size <= 1) return@edit
                    prefs[KEY_PHOTO_TYPE_CONFIGS] = serializeConfigs(
                        current.filterNot { it.id == config.id },
                    )
                }
            }
        }
    }

    /**
     * 根据当前配置生成示例文件名（用于设置页实时预览）。
     *
     * 使用固定的 mock 客户数据（borrower="成都投资集团"、addrGeneral="和平区"、
     * addrDetail="XX街123号1430"）、[PhotoType.DISTANT]（远景）、sequence=1，
     * DATE 段取今日日期。
     */
    fun previewFileName(config: NamingConfig): String {
        val mockCustomer = CustomerRow(
            rowIndex = 0,
            serial = "1",
            borrower = "成都投资集团",
            addrGeneral = "和平区",
            addrDetail = "XX街123号1430",
            propertyType = "",
            remark = "",
            progressKey = "",
        )
        return namingRuleGenerator.generate(
            config = config,
            customerRow = mockCustomer,
            photoTypeDisplayName = PhotoType.DISTANT.displayName,
            sequence = 1,
            timestamp = todayNoonTimestamp(),
        )
    }

    /** 取今日正午时间戳（保证 yyyyMMdd 在设备本地时区下稳定显示为今日）。 */
    private fun todayNoonTimestamp(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 12)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private companion object {
        const val logPrefsName = "log_prefs"
        const val keyLogEnabled = "log_enabled"

        val KEY_GOLDEN_RATIO_GRID = booleanPreferencesKey("golden_ratio_grid")
        val KEY_CENTER_MARK = booleanPreferencesKey("center_mark")
        val KEY_PHOTO_TYPE_CONFIGS = stringPreferencesKey("photo_type_configs")

        /** 将配置列表序列化为 JSON 字符串（`[{"id":..,"displayName":..},...]`）。 */
        fun serializeConfigs(configs: List<PhotoTypeConfig>): String {
            val arr = JSONArray()
            for (c in configs) {
                val obj = JSONObject()
                obj.put("id", c.id)
                obj.put("displayName", c.displayName)
                arr.put(obj)
            }
            return arr.toString()
        }

        /**
         * 反序列化 JSON 字符串为配置列表。
         *
         * - null/空串/解析失败 → 返回 [PhotoType.DEFAULT_CONFIGS]（向后兼容首次安装与损坏数据）
         * - 解析结果为空数组 → 返回 [PhotoType.DEFAULT_CONFIGS]（兜底，避免无类型可用）
         */
        fun deserializeConfigs(json: String?): List<PhotoTypeConfig> {
            if (json.isNullOrBlank()) return PhotoType.DEFAULT_CONFIGS
            return runCatching {
                val arr = JSONArray(json)
                val result = ArrayList<PhotoTypeConfig>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    result.add(
                        PhotoTypeConfig(
                            id = obj.getString("id"),
                            displayName = obj.getString("displayName"),
                        ),
                    )
                }
                result.ifEmpty { PhotoType.DEFAULT_CONFIGS }
            }.getOrDefault(PhotoType.DEFAULT_CONFIGS)
        }
    }
}
